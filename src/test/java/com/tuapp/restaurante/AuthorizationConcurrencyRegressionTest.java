package com.tuapp.restaurante;

import com.tuapp.restaurante.repositories.ReservaRepository;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import com.tuapp.restaurante.repositories.ConfiguracionRepository;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.dto.ConfiguracionUpdate;
import com.tuapp.restaurante.services.UsuarioService;
import com.tuapp.restaurante.services.ConfiguracionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Freeze the authorization check to exercise the create/unlink race deterministically. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class AuthorizationConcurrencyRegressionTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @SpyBean ReservaRepository reservations;
    @SpyBean UsuarioRepository userRepository;
    @SpyBean ConfiguracionRepository configRepository;
    @Autowired UsuarioService users;
    @Autowired ConfiguracionService configuration;
    @PersistenceContext EntityManager entities;

    private void fixtures() {
        var fixture=new SecurityHttpRegressionTest();fixture.db=db;fixture.syntheticFixtures();
    }
    private void as(String role, Runnable operation) {
        long actor=db.queryForObject("select id from usuario where username=?",Long.class,role.toLowerCase());
        SyntheticSecurity.runAs(db,actor,operation);
    }
    private <T> T pauseAdmin(Supplier<T> read, CountDownLatch checked, CountDownLatch resume) throws Exception {
        T result=read.get();
        if (SecurityContextHolder.getContext().getAuthentication().getName().equals("admin")) {
            checked.countDown();
            if (!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic test timeout");
        }
        return result;
    }

    @Test
    void tableLockSerializesCreationWithAuthorizationBeforeUnlinking() throws Exception {
        fixtures();
        long table=db.queryForObject("insert into mesas(numero,capacidad,estado,activo) values(55,4,'LIBRE',true) returning id",Long.class);
        var checked=new CountDownLatch(1);
        var resume=new CountDownLatch(1);
        var creationStarted=new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean result=Boolean.TRUE.equals(db.queryForObject(
                    "select exists(select 1 from reservas where mesa_id=?)",Boolean.class,table));
            checked.countDown();
            if (!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic test timeout");
            return result;
        }).when(reservations).existsByMesaId(table);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deleting=pool.submit(()->mvc.perform(post("/mesas/eliminar/"+table)
                    .with(user("empleado").roles("EMPLEADO")).with(csrf())).andReturn());
            try {
                assertThat(checked.await(5,TimeUnit.SECONDS)).isTrue();
                var creating=pool.submit(()-> {
                    creationStarted.countDown();
                    return mvc.perform(post("/reservas/guardar").with(user("mesero").roles("MESERO")).with(csrf())
                            .param("mesa.id",""+table).param("nombreCliente","Synthetic concurrent guest")
                            .param("numeroPersonas","2").param("fechaHora",LocalDateTime.now().plusDays(1).toString())).andReturn();
                });
                assertThat(creationStarted.await(5,TimeUnit.SECONDS)).isTrue();
                // With the old exists-before-lock order, creation finishes here;
                // deletion would then unlink that committed reservation as EMPLEADO.
                assertThatThrownBy(()->creating.get(2,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                resume.countDown();
                assertThat(deleting.get(5,TimeUnit.SECONDS).getFlashMap().containsKey("mensajeExito")).isTrue();
                assertThat(creating.get(5,TimeUnit.SECONDS).getFlashMap().containsKey("error")).isTrue();
                assertThat(db.queryForObject("select count(*) from reservas",Long.class)).isZero();
            } finally {
                resume.countDown();
            }
        } finally { reset(reservations); }
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void configurationWritesCannotOverwriteConcurrentMasterChanges(boolean existing) throws Exception {
        fixtures();
        if (!existing) db.update("delete from configuracion_sistema");
        var checked=new CountDownLatch(1);var resume=new CountDownLatch(1);
        doAnswer(invocation -> pauseAdmin(()->Optional.ofNullable(entities.find(ConfiguracionSistema.class,1L)),checked,resume))
                .when(configRepository).findById(1L);
        var operational=new ConfiguracionUpdate();operational.setNombreNegocio("Synthetic administrative update");
        var master=new ConfiguracionUpdate();master.setLimiteUsuarios(77);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var admin=pool.submit(()->as("ADMIN",()->configuration.guardar(operational)));
            try {
                assertThat(checked.await(5,TimeUnit.SECONDS)).isTrue();
                var root=pool.submit(()->as("SUPER_ADMIN",()->configuration.guardar(master)));
                assertThatThrownBy(()->root.get(2,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                resume.countDown();admin.get(5,TimeUnit.SECONDS);root.get(5,TimeUnit.SECONDS);
                assertThat(db.queryForObject("select limite_usuarios from configuracion_sistema where id=1",Integer.class)).isEqualTo(77);
                assertThat(db.queryForObject("select nombre_negocio from configuracion_sistema where id=1",String.class)).isEqualTo("Synthetic administrative update");
            } finally { resume.countDown(); }
        } finally { reset(configRepository); }
    }

    @Test
    void userRoleDecisionIsSerializedWithConcurrentPromotion() throws Exception {
        fixtures();
        long target=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values('race-target','synthetic','Synthetic target','ROLE_MESERO',true) returning id",Long.class);
        var checked=new CountDownLatch(1);var resume=new CountDownLatch(1);
        doAnswer(invocation -> pauseAdmin(()->Optional.ofNullable(entities.find(Usuario.class,target,LockModeType.PESSIMISTIC_WRITE)),checked,resume))
                .when(userRepository).findByIdForUpdate(target);
        // This alternate hook makes the regression fail if the locking lookup is removed.
        doAnswer(invocation -> pauseAdmin(()->Optional.ofNullable(entities.find(Usuario.class,target)),checked,resume))
                .when(userRepository).findById(target);
        var administrative=new Usuario(target,"race-target","","Synthetic admin edit","MESERO");
        var privileged=new Usuario(target,"race-target","","Synthetic root edit","SUPER_ADMIN");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var admin=pool.submit(()->as("ADMIN",()->users.actualizar(administrative,null,null,false)));
            try {
                assertThat(checked.await(5,TimeUnit.SECONDS)).isTrue();
                var root=pool.submit(()->as("SUPER_ADMIN",()->users.actualizar(privileged,null,null,false)));
                assertThatThrownBy(()->root.get(2,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                resume.countDown();admin.get(5,TimeUnit.SECONDS);root.get(5,TimeUnit.SECONDS);
                assertThat(db.queryForObject("select rol from usuario where id=?",String.class,target)).isEqualTo("ROLE_SUPER_ADMIN");
                assertThat(db.queryForObject("select nombre_completo from usuario where id=?",String.class,target)).isEqualTo("Synthetic root edit");
            } finally { resume.countDown(); }
        } finally { reset(userRepository); }
    }
}
