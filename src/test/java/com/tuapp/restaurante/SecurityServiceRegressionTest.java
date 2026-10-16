package com.tuapp.restaurante;

import com.tuapp.restaurante.dto.CreateUsuarioRequest;
import com.tuapp.restaurante.dto.ConfiguracionUpdate;
import com.tuapp.restaurante.models.Reserva;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.services.*;
import com.tuapp.restaurante.security.ApplicationRole;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class SecurityServiceRegressionTest {
    @Autowired JdbcTemplate db;
    @Autowired SubRecetaService compositions;
    @Autowired ProduccionService production;
    @Autowired ReservaService reservations;
    @Autowired UsuarioService users;
    @Autowired ConfiguracionService configuration;

    @BeforeEach
    void fixtures() {
        SecurityContextHolder.clearContext();
        // Same guarded synthetic data as the HTTP suite, no real records.
        var fixture = new SecurityHttpRegressionTest();
        fixture.db = db;
        fixture.syntheticFixtures();
    }
    private long actor(String role) { return db.queryForObject("select id from usuario where username=?",Long.class,role.toLowerCase()); }
    private void as(String role, Runnable body) { SyntheticSecurity.runAs(db,actor(role),body); }
    private void denied(Runnable body) { assertThatThrownBy(body::run).isInstanceOf(AccessDeniedException.class); }

    @ParameterizedTest
    @ValueSource(strings={"MESERO","EMPLEADO","COCINERO"})
    void serviceAuthorizationRejectsDirectCompositionAndProductionEvenOnRetry(String role) throws Exception {
        UUID request=UUID.randomUUID();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("-1|1|"+actor("ADMIN")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("insert into production_request(request_id,request_hash) values (?,?)",request,hash);
        as(role,()-> {
            denied(()->compositions.agregar(-1L,-2L,BigDecimal.ONE));
            denied(()->compositions.quitar(-1L,-2L));
            denied(()->production.producirInsumo(-1L,BigDecimal.ONE,actor("ADMIN")));
            denied(()->production.producirInsumo(-1L,BigDecimal.ONE,actor("ADMIN"),request));
        });
        assertThat(db.queryForObject("select request_hash from production_request where request_id=?",String.class,request)).isEqualTo(hash);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"MESERO","EMPLEADO","COCINERO"})
    void serviceAuthorizationRejectsDirectReservationMutations(String role) {
        as(role,()-> {
            Reserva existing=new Reserva();existing.setId(987L);
            denied(()->reservations.saveReserva(existing));
            denied(()->reservations.actualizarHorario(987L,LocalDateTime.now()));
            denied(()->reservations.cambiarEstado(987L,"CONFIRMADA"));
            denied(()->reservations.deleteReserva(987L));
        });
    }

    @Test
    void missingInactiveOrStaleAuthenticationAndForgedActorFailBeforeProductionWrites() {
        denied(()->production.producirInsumo(-1L,BigDecimal.ONE,actor("ADMIN"),UUID.randomUUID()));
        as("ADMIN",()->denied(()->production.producirInsumo(-1L,BigDecimal.ONE,actor("GERENTE"),UUID.randomUUID())));
        as("ADMIN",()-> {
            db.update("update usuario set activo=false where username='admin'");
            denied(()->production.producirInsumo(-1L,BigDecimal.ONE,actor("ADMIN"),UUID.randomUUID()));
        });
        db.update("update usuario set activo=true where username='admin'");
        as("ADMIN",()-> {
            db.update("update usuario set rol='ROLE_MESERO' where username='admin'");
            denied(()->production.producirInsumo(-1L,BigDecimal.ONE,actor("ADMIN"),UUID.randomUUID()));
        });
        assertThat(db.queryForObject("select count(*) from production_request",Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"SUPER_ADMIN","ROLE_SUPER_ADMIN"})
    void protectedRoleGuardsAlsoApplyToDirectUserServiceCalls(String protectedRole) {
        long target=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('protected','synthetic','Synthetic protected',?,true) returning id",Long.class,protectedRole);
        var input=new CreateUsuarioRequest();input.setUsername("forged");input.setPassword("synthetic");
        input.setNombreCompleto("Synthetic");input.setRol(protectedRole);
        as("ADMIN",()-> {
            denied(()->users.registrarUsuario(input,null));
            var edited=new Usuario(target,"protected","","Changed fixture","MESERO");
            denied(()->users.actualizar(edited,null,null,false));
            denied(()->users.eliminar(target));
        });
        assertThat(db.queryForObject("select rol from usuario where id=?",String.class,target)).isEqualTo(protectedRole);
        assertThat(db.queryForObject("select activo from usuario where id=?",Boolean.class,target)).isTrue();
    }

    @Test
    void directConfigurationServiceRejectsForbiddenActorsAndMasterFields() {
        var input=new ConfiguracionUpdate();input.setLimiteUsuarios(999);
        String before=db.queryForObject("select row_to_json(c)::text from configuracion_sistema c where id=1",String.class);
        as("ADMIN",()->denied(()->configuration.guardar(input)));
        as("GERENTE",()->denied(()->configuration.guardar(new ConfiguracionUpdate())));
        denied(()->configuration.guardar(input));
        assertThat(db.queryForObject("select row_to_json(c)::text from configuracion_sistema c where id=1",String.class)).isEqualTo(before);
    }

    @Test
    void roleParsingHasClosedCanonicalRepresentationsAndLegacyLoginWorks() {
        for(var role:ApplicationRole.values()) {
            assertThat(ApplicationRole.parse(role.name())).isEqualTo(role);
            assertThat(ApplicationRole.parse(role.authority())).isEqualTo(role);
            db.update("update usuario set rol=? where username=?",role.name(),role.name().toLowerCase());
            assertThat(users.loadUserByUsername(role.name().toLowerCase()).getAuthorities())
                    .extracting(a->a.getAuthority()).containsExactly(role.authority());
        }
        for(String invalid:List.of("admin","ROLE_ROLE_ADMIN"," ADMIN","ADMIN ","ROOT","")) {
            assertThatThrownBy(()->ApplicationRole.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->ApplicationRole.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
