package com.tuapp.restaurante;

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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Negative URL tests use exclusively the disposable slice0 database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class SecurityHttpRegressionTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    @BeforeEach
    void syntheticFixtures() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        var tables = db.queryForList("select tablename from pg_tables where schemaname='public' " +
                "and tablename not in ('flyway_schema_history','deployment_identity','attendance_pin_config')", String.class);
        db.execute("truncate " + String.join(",", tables.stream().map(t -> "public.\"" + t + "\"").toList()) +
                " restart identity cascade");
        for (String role : List.of("ADMIN", "GERENTE", "EMPLEADO", "MESERO", "COCINERO", "SUPER_ADMIN")) {
            db.update("insert into usuario(username,password,nombre_completo,rol,activo) values (?,?,?,?,true)",
                    role.toLowerCase(), "synthetic-hash", "Synthetic " + role, "ROLE_" + role);
        }
        db.update("insert into configuracion_sistema(id,nombre_negocio,slogan,telefono,email,direccion,rfc,moneda," +
                "simbolo_moneda,porcentaje_iva,iva_incluido,permitir_descuentos,moneda_fecha,zona_horaria,mensaje_ticket," +
                "nivel_plan,mod_mesas,mod_reservas,mod_kds,mod_clientes,mod_caja,mod_historial,mod_reportes,mod_gastos," +
                "mod_asistencia,mod_ia,fecha_expiracion,limite_usuarios,limite_productos,limite_dispositivos," +
                "precio_personalizado,notas_maestras) values (1,'Synthetic business','Original slogan','0000000000'," +
                "'fixture@example.invalid','Fictional street','SYNTHETIC','MXN','$',7.50,true,true,'es-MX'," +
                "'America/Mexico_City','Synthetic ticket','PREMIUM',true,true,true,true,true,true,true,true,true,true," +
                "'2099-01-01',50,321,8,42.50,'Synthetic master note')");
    }

    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor actor(String role) {
        return user(role.toLowerCase()).roles(role);
    }
    private long id(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    private long count(String table) { return id("select count(*) from " + table); }
    private long supply(String name, String stock) {
        return id("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) " +
                "values (?,'unidad',5,?::numeric,0,true) returning id", name, stock);
    }
    private long reservation(String state, Long table) {
        return id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('Synthetic guest',?,2,?,'TELEFONO',?,?) returning id",
                LocalDateTime.now().plusMinutes(15), state, LocalDateTime.now(), table);
    }
    private String config() { return db.queryForObject("select row_to_json(c)::text from configuracion_sistema c where id=1", String.class); }
    private String userRow(long id) { return db.queryForObject("select row_to_json(u)::text from usuario u where id=?", String.class, id); }

    @ParameterizedTest
    @ValueSource(strings={"MESERO","EMPLEADO","COCINERO"})
    void lowerRolesCannotMutateCompositionOrProduce(String role) throws Exception {
        long source = supply("Synthetic source", "10"), destination = supply("Synthetic destination", "0");
        long line = id("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) values (?,?,1) returning id", destination, source);
        long extra = supply("Synthetic extra", "10");
        mvc.perform(post("/subrecetas/agregar").with(actor(role)).with(csrf())
                .param("insumoDestinoId", ""+destination).param("ingredienteId", ""+extra).param("cantidad", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/subrecetas/" + destination).with(actor(role))).andExpect(status().isForbidden());
        mvc.perform(post("/subrecetas/eliminar/"+line).with(actor(role)).with(csrf()).param("insumoDestinoId", ""+destination))
                .andExpect(status().isForbidden());
        mvc.perform(post("/subrecetas/producir").with(actor(role)).with(csrf()).param("insumoDestinoId", ""+destination)
                .param("cantidadAProducir", "1").param("requestId", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        assertThat(count("sub_receta")).isEqualTo(1);
        assertThat(count("production_request")).isZero();
        assertThat(count("movimiento_inventario")).isZero();
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,source)).isEqualByComparingTo("10");
    }

    @ParameterizedTest
    @ValueSource(strings={"ADMIN","GERENTE","SUPER_ADMIN"})
    void managersRetainCompositionProductionAndRetry(String role) throws Exception {
        long source=supply("Synthetic source","10"), destination=supply("Synthetic destination","0");
        mvc.perform(post("/subrecetas/agregar").with(actor(role)).with(csrf()).param("insumoDestinoId",""+destination)
                .param("ingredienteId",""+source).param("cantidad","2")).andExpect(flash().attributeExists("mensajeExito"));
        long line=id("select id from sub_receta where insumo_destino_id=?",destination);
        String key=UUID.randomUUID().toString();
        for (int i=0;i<2;i++) mvc.perform(post("/subrecetas/producir").with(actor(role)).with(csrf())
                .param("insumoDestinoId",""+destination).param("cantidadAProducir","1").param("requestId",key))
                .andExpect(flash().attributeExists("mensajeExito"));
        assertThat(count("production_request")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,source)).isEqualByComparingTo("8");
        mvc.perform(post("/subrecetas/eliminar/"+line).with(actor(role)).with(csrf()).param("insumoDestinoId",""+destination))
                .andExpect(flash().attributeExists("mensajeExito"));
        assertThat(count("sub_receta")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"MESERO","EMPLEADO"})
    void lowerRolesCanReadAndCreateButCannotModifyReservations(String role) throws Exception {
        mvc.perform(get("/reservas").with(actor(role))).andExpect(status().isOk());
        mvc.perform(get("/reservas/eventos").with(actor(role))).andExpect(status().isOk());
        mvc.perform(post("/reservas/guardar").with(actor(role)).with(csrf()).param("nombreCliente","Synthetic guest")
                .param("numeroPersonas","2").param("fechaHora",LocalDateTime.now().plusDays(1).toString()))
                .andExpect(flash().attributeExists("success"));
        long r=id("select id from reservas");
        String before=db.queryForObject("select row_to_json(r)::text from reservas r where id=?",String.class,r);
        mvc.perform(post("/reservas/guardar").with(actor(role)).with(csrf()).param("id",""+r)
                .param("nombreCliente","Forged edit").param("numeroPersonas","2").param("fechaHora",LocalDateTime.now().plusDays(2).toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/reservas/guardar").with(actor(role)).with(csrf()).param("nombreCliente","Forged confirmed")
                .param("estado","CONFIRMADA").param("numeroPersonas","2").param("fechaHora",LocalDateTime.now().plusDays(2).toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reservas/"+r+"/editar").with(actor(role))).andExpect(status().isForbidden());
        mvc.perform(post("/reservas/actualizar-horario").with(actor(role)).with(csrf()).param("id",""+r)
                .param("nuevaFecha",LocalDateTime.now().plusDays(3).toString())).andExpect(status().isForbidden());
        mvc.perform(post("/reservas/"+r+"/estado").with(actor(role)).with(csrf()).param("estado","CONFIRMADA")).andExpect(status().isForbidden());
        mvc.perform(post("/reservas/"+r+"/eliminar").with(actor(role)).with(csrf())).andExpect(status().isForbidden());
        assertThat(count("reservas")).isEqualTo(1);
        assertThat(db.queryForObject("select row_to_json(r)::text from reservas r where id=?",String.class,r)).isEqualTo(before);
        mvc.perform(get("/reservas").with(actor(role))).andExpect(content().string(org.hamcrest.Matchers.containsString("editable: false")));
    }

    @ParameterizedTest
    @ValueSource(strings={"MESERO","EMPLEADO"})
    void seatingConfirmedReservationsRemainsAllowedButUnlinkingDoesNot(String role) throws Exception {
        long table=id("insert into mesas(numero,capacidad,estado,activo) values(10,4,'LIBRE',true) returning id");
        long r=reservation("CONFIRMADA",table);
        mvc.perform(post("/mesas/"+table+"/estado").with(actor(role)).with(csrf()).param("estado","OCUPADA").param("reservaId",""+r))
                .andExpect(flash().attributeExists("mensajeExito"));
        assertThat(db.queryForObject("select estado from reservas where id=?",String.class,r)).isEqualTo("EN_CURSO");
        mvc.perform(post("/mesas/eliminar/"+table).with(actor(role)).with(csrf())).andExpect(status().isForbidden());
        assertThat(id("select mesa_id from reservas where id=?",r)).isEqualTo(table);
    }

    @ParameterizedTest
    @ValueSource(strings={"ADMIN","GERENTE","SUPER_ADMIN"})
    void managersRetainReservationEditsAndTransitions(String role) throws Exception {
        long r=reservation("PENDIENTE",null);
        mvc.perform(get("/reservas/"+r+"/editar").with(actor(role))).andExpect(status().isOk());
        mvc.perform(post("/reservas/guardar").with(actor(role)).with(csrf()).param("id",""+r).param("nombreCliente","Edited fictitious guest")
                .param("numeroPersonas","3").param("fechaHora",LocalDateTime.now().plusDays(2).toString())).andExpect(flash().attributeExists("success"));
        mvc.perform(post("/reservas/actualizar-horario").with(actor(role)).with(csrf()).param("id",""+r)
                .param("nuevaFecha",LocalDateTime.now().plusDays(3).toString())).andExpect(jsonPath("$.status").value("success"));
        mvc.perform(post("/reservas/"+r+"/estado").with(actor(role)).with(csrf()).param("estado","CONFIRMADA")).andExpect(flash().attributeExists("success"));
        mvc.perform(post("/reservas/"+r+"/eliminar").with(actor(role)).with(csrf())).andExpect(flash().attributeExists("success"));
        assertThat(count("reservas")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"SUPER_ADMIN","ROLE_SUPER_ADMIN"})
    void adminCannotAssignOrModifyProtectedRolesInEitherRepresentation(String role) throws Exception {
        long target=id("insert into usuario(username,password,nombre_completo,rol,activo) values('protected','synthetic-hash','Protected fixture',?,true) returning id",role);
        String before=userRow(target);
        mvc.perform(post("/usuarios/guardar").with(actor("ADMIN")).with(csrf()).param("username","forged-super")
                .param("password","synthetic-pass").param("nombreCompleto","Synthetic").param("rol",role)).andExpect(status().isForbidden());
        mvc.perform(post("/usuarios/actualizar").with(actor("ADMIN")).with(csrf()).param("id",""+target)
                .param("username","renamed").param("nombreCompleto","Forged").param("rol","ROLE_MESERO")).andExpect(status().isForbidden());
        mvc.perform(post("/usuarios/actualizar").with(actor("ADMIN")).with(csrf()).param("id",""+id("select id from usuario where username='mesero'"))
                .param("username","mesero").param("nombreCompleto","Forged").param("rol",role)).andExpect(status().isForbidden());
        mvc.perform(post("/usuarios/eliminar/"+target).with(actor("ADMIN")).with(csrf())).andExpect(status().isForbidden());
        assertThat(count("usuario")).isEqualTo(7);
        assertThat(userRow(target)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"ADMIN","ROLE_ADMIN","MESERO","ROLE_MESERO"})
    void ordinaryRoleCreationUsesCanonicalRoleAndNewEntity(String role) throws Exception {
        mvc.perform(post("/usuarios/guardar").with(actor("ADMIN")).with(csrf()).param("id","")
                .param("username","new-fixture").param("password","synthetic-pass").param("nombreCompleto","Synthetic fixture")
                .param("rol",role).param("pinDigest","forged-digest")).andExpect(flash().attributeExists("mensajeExito"));
        assertThat(count("usuario")).isEqualTo(7);
        assertThat(db.queryForObject("select rol from usuario where username='new-fixture'",String.class)).isEqualTo(role.startsWith("ROLE_")?role:"ROLE_"+role);
        assertThat(db.queryForObject("select password from usuario where username='new-fixture'",String.class)).startsWith("$2");
        assertThat(db.queryForObject("select pin_digest from usuario where username='new-fixture'",String.class)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings={"admin","ROLE_ROLE_ADMIN"," ROLE_SUPER_ADMIN","UNKNOWN"})
    void unsupportedRoleRepresentationsAreRejected(String role) throws Exception {
        mvc.perform(post("/usuarios/guardar").with(actor("ADMIN")).with(csrf()).param("username","invalid-role")
                .param("password","synthetic-pass").param("nombreCompleto","Synthetic fixture").param("rol",role))
                .andExpect(flash().attributeExists("mensajeError"));
        assertThat(count("usuario")).isEqualTo(6);
    }

    @Test
    void creationRejectsExistingAndArbitraryIdentifiersWithoutChangingAccounts() throws Exception {
        long target=id("select id from usuario where username='super_admin'");
        String before=userRow(target);
        for(String forged:List.of(""+target,"987654321")) mvc.perform(post("/usuarios/guardar").with(actor("ADMIN")).with(csrf())
                .param("id",forged).param("username","forged-new").param("password","synthetic-pass")
                .param("nombreCompleto","Forged fixture").param("rol","MESERO")).andExpect(status().isBadRequest());
        assertThat(userRow(target)).isEqualTo(before);
        assertThat(count("usuario")).isEqualTo(6);
    }

    @Test
    void superAdminCanManageProtectedAccountsAndLegacyRoleLoginRemainsValid() throws Exception {
        db.update("update usuario set rol='SUPER_ADMIN' where username='super_admin'");
        mvc.perform(post("/usuarios/guardar").with(actor("SUPER_ADMIN")).with(csrf()).param("username","new-super")
                .param("password","synthetic-pass").param("nombreCompleto","Synthetic root").param("rol","SUPER_ADMIN"))
                .andExpect(flash().attributeExists("mensajeExito"));
        long target=id("select id from usuario where username='new-super'");
        mvc.perform(post("/usuarios/actualizar").with(actor("SUPER_ADMIN")).with(csrf()).param("id",""+target)
                .param("username","new-super").param("nombreCompleto","Changed fixture").param("rol","SUPER_ADMIN").param("activo","true"))
                .andExpect(flash().attributeExists("mensajeExito"));
        mvc.perform(post("/usuarios/eliminar/"+target).with(actor("SUPER_ADMIN")).with(csrf())).andExpect(flash().attributeExists("mensajeExito"));
        assertThat(db.queryForObject("select activo from usuario where id=?",Boolean.class,target)).isFalse();
    }

    @Test
    void adminConfigurationCannotOverwriteMasterFieldsAndOmittedFieldsSurvive() throws Exception {
        String before=config();
        mvc.perform(post("/configuracion/guardar").with(actor("ADMIN")).with(csrf()).param("nombreNegocio","Forged")
                .param("nivelPlan","LITE").param("limiteUsuarios","999").param("limiteProductos","999")
                .param("limiteDispositivos","999").param("fechaExpiracion","").param("precioPersonalizado","0")
                .param("notasMaestras","Forged").param("modIa","false")).andExpect(status().isForbidden());
        assertThat(config()).isEqualTo(before);
        mvc.perform(post("/configuracion/guardar").with(actor("ADMIN")).with(csrf()).param("nombreNegocio","Edited fixture")
                .param("_ivaIncluido","on").param("id","99").param("email","forged@example.invalid")
                .param("zonaHoraria","UTC")).andExpect(flash().attributeExists("mensajeExito"));
        assertThat(id("select limite_usuarios from configuracion_sistema where id=1")).isEqualTo(50);
        assertThat(config()).contains("Edited fixture","Original slogan","fixture@example.invalid","Synthetic master note","2099-01-01","321","42.50");
        assertThat(config()).doesNotContain("forged@example.invalid");
        assertThat(db.queryForObject("select iva_incluido from configuracion_sistema where id=1",Boolean.class)).isFalse();
        assertThat(count("configuracion_sistema")).isEqualTo(1);
    }

    @Test
    void superAdminMasterUpdatePreservesOperationalFieldsAndOmittedMasterFields() throws Exception {
        mvc.perform(post("/configuracion/guardar").with(actor("SUPER_ADMIN")).with(csrf()).param("nombreNegocio","Forged hidden name")
                .param("nivelPlan","CUSTOM").param("limiteUsuarios","40").param("_modIa","on"))
                .andExpect(flash().attributeExists("mensajeExito"));
        assertThat(config()).contains("Synthetic business","Original slogan","fixture@example.invalid","Synthetic ticket","CUSTOM","Synthetic master note");
        assertThat(id("select limite_usuarios from configuracion_sistema where id=1")).isEqualTo(40);
        assertThat(db.queryForObject("select mod_ia from configuracion_sistema where id=1",Boolean.class)).isFalse();
        assertThat(db.queryForObject("select mod_mesas from configuracion_sistema where id=1",Boolean.class)).isTrue();
    }

    @Test
    void csrfRemainsRequiredAndCookCannotCreateReservations() throws Exception {
        mvc.perform(post("/subrecetas/agregar").with(actor("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/usuarios/guardar").with(actor("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/configuracion/guardar").with(actor("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/reservas/guardar").with(actor("MESERO"))).andExpect(status().isForbidden());
        mvc.perform(get("/reservas").with(actor("COCINERO"))).andExpect(status().isForbidden());
        mvc.perform(post("/reservas/guardar").with(actor("COCINERO")).with(csrf())).andExpect(status().isForbidden());
    }
}
