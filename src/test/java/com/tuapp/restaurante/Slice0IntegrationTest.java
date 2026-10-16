package com.tuapp.restaurante;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import com.tuapp.restaurante.services.VentaService;
import com.tuapp.restaurante.services.UsuarioService;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier;
import com.tuapp.restaurante.inventory.BaseUnit;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Classification;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Evidence;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.StartingPoint;
import com.tuapp.restaurante.inventory.InventoryReconciliationService;
import com.tuapp.restaurante.inventory.InventoryReconciliationService.Command;

import java.nio.file.Files;
import java.nio.file.Path;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Contratos del comportamiento heredado. Solo funciona contra la BD descartable de slice0-test.sh. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class Slice0IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    @Autowired Flyway flyway;
    @Autowired VentaService ventaService;
    @Autowired com.tuapp.restaurante.services.ReservaService reservaService;
    @Autowired UsuarioService usuarioService;
    @Autowired Environment environment;
    @Autowired DataSource dataSource;
    @Autowired LegacyInventoryClassifier legacyInventoryClassifier;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired InventoryReconciliationService inventoryReconciliationService;

    @BeforeEach
    void datosSinteticosNuevos() {
        String database = db.queryForObject("select current_database()", String.class);
        assertThat(database).isEqualTo("ventro_slice0");
        List<String> tables = db.queryForList(
                "select tablename from pg_tables where schemaname='public' " +
                "and tablename not in ('flyway_schema_history','deployment_identity','pos_sale_request'," +
                "'attendance_pin_config','production_request') order by tablename", String.class);
        assertThat(tables).hasSize(27).contains("inventory_receipt_reversal", "inventory_receipt_reversal_line");
        db.execute("TRUNCATE TABLE pos_sale_request");
        db.execute("TRUNCATE TABLE production_request");
        db.execute("TRUNCATE TABLE " + tables.stream().map(t -> "public.\"" + t + "\"")
                .reduce((a, b) -> a + ", " + b).orElseThrow() + " RESTART IDENTITY CASCADE");
        for (String role : List.of("ADMIN", "GERENTE", "MESERO", "COCINERO")) {
            db.update("insert into usuario(username,password,nombre_completo,rol,activo) values (?,?,?,?,true)",
                    role.toLowerCase(), encoder.encode("synthetic-pass"), "Prueba " + role, "ROLE_" + role);
        }
        // Los contratos de venta existentes se ejecutan con turno; los casos sin turno lo retiran explícitamente.
        db.update("insert into corte_caja(fecha_apertura,monto_inicial,estado,total_efectivo,total_tarjeta," +
                "total_ventas,total_retiros,usuario_id) select timezone('America/Monterrey', now())," +
                "100,'ABIERTO',0,0,0,0,id " +
                "from usuario where username='admin'");
    }

    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private long id(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    private long count(String table) { return db.queryForObject("select count(*) from " + table, Long.class); }
    private BigDecimal money(String sql, Object... args) { return db.queryForObject(sql, BigDecimal.class, args); }
    private void amount(String expected, BigDecimal actual) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private long ingredient(String name, String stock) {
        return id("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) " +
                "values (?,'unidad',5,?::numeric,0,true) returning id", name, stock);
    }

    private long historicalMovement(long insumoId, String type, String quantity, LocalDateTime at) {
        return id("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha) " +
                "values (?,(select id from usuario where username='admin'),?,?::numeric,?) returning id",
                insumoId, type, quantity, at);
    }

    private static final LocalDateTime HISTORICAL_AT = LocalDateTime.of(2025, 1, 2, 12, 0);
    private static final Evidence PROVEN_UNIT = new Evidence(BaseUnit.UNIT, null);

    private Command approval(UUID requestId, long insumoId, BaseUnit unit, Evidence evidence,
                             String provenance) {
        return new Command(requestId, insumoId, unit, evidence,
                id("select id from usuario where username='admin'"), provenance, "fixture sintético");
    }

    private long product(String name, String price, int points, int cost, long ingredient) {
        long p = id("insert into producto(nombre,precio_venta,puntos_otorgados,puntos_precio,activo,es_reventa) " +
                "values (?,?::numeric,?,?,true,false) returning id", name, price, points, cost);
        db.update("insert into receta(producto_id,insumo_id,cantidad) values (?,?,2)", p, ingredient);
        return p;
    }

    private MvcResult sale(long product, int qty, String method, String cash, String card,
                           Long client, Long table, boolean redeemed) throws Exception {
        return saleWithKey(product, qty, method, cash, card, client, table, redeemed, UUID.randomUUID().toString());
    }

    private MvcResult saleWithKey(long product, int qty, String method, String cash, String card,
                           Long client, Long table, boolean redeemed, String key) throws Exception {
        var request = post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(product))
                .param("cantidadVendida", Integer.toString(qty))
                .param("notas", "sin sal")
                .param("metodoPago", method)
                .param("montoEfectivo", cash)
                .param("montoTarjeta", card)
                .param("idempotencyKey", key)
                .param("esCanjeado", Boolean.toString(redeemed));
        if (client != null) request.param("clienteId", client.toString());
        if (table != null) request.param("mesaId", table.toString());
        return mvc.perform(request).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(table == null ? "/pos" : "/pos?mesaId=" + table)).andReturn();
    }

    private void assignPin(String username, String role, String pin) {
        long userId = id("select id from usuario where username=?", username);
        Usuario edited = new Usuario(userId, username, "", "Prueba " + role, "ROLE_" + role);
        edited.setActivo(true);
        SyntheticSecurity.runAs(db, id("select id from usuario where username='admin'"), () -> usuarioService.actualizar(edited, null, pin, false));
    }

    private MvcResult clock(String pin, String address) throws Exception {
        return clock(pin, address, UUID.randomUUID().toString());
    }

    private MvcResult clock(String pin, String address, String requestId) throws Exception {
        return mvc.perform(post("/asistencia/reloj").with(csrf())
                .with(request -> { request.setRemoteAddr(address); return request; })
                .param("pin", pin).param("requestId", requestId))
                .andExpect(redirectedUrl("/asistencia/reloj")).andReturn();
    }

    private String renderedClockRequestId() throws Exception {
        MvcResult page = mvc.perform(get("/asistencia/reloj")).andExpect(status().isOk()).andReturn();
        String key = (String) page.getModelAndView().getModel().get("requestId");
        assertThat(key).isNotNull();
        assertThat(page.getResponse().getContentAsString()).contains("name=\"requestId\"").contains(key);
        return key;
    }

    @Test
    void inertHtmlNotesAndNamesRemainDataAcrossPosKdsAndReservationEvents() throws Exception {
        String marker = "<b data-xss='fixture'>Synthetic & guest</b>";
        long ingredient = ingredient("Synthetic ingredient", "10");
        long product = product(marker, "10", 0, 0, ingredient);
        mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(product)).param("cantidadVendida", "1")
                .param("notas", marker).param("metodoPago", "EFECTIVO").param("montoEfectivo", "10")
                .param("montoTarjeta", "0").param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(redirectedUrl("/pos"));
        mvc.perform(get("/kds/api/datos").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos[0].notas").value(marker))
                .andExpect(jsonPath("$.pedidos[0].productoNombre").value(marker));
        mvc.perform(post("/reservas/guardar").with(user("mesero").roles("MESERO")).with(csrf())
                .param("nombreCliente", marker).param("numeroPersonas", "2")
                .param("fechaHora", LocalDateTime.now().plusDays(1).toString()))
                .andExpect(flash().attributeExists("success"));
        long client = id("insert into clientes(nombre,activo,puntos,fecha_registro,limite_credito,saldo_credito,tipo,total_gastado) values (?,true,0,now(),0,0,'NORMAL',0) returning id", marker);
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("cliente.id", Long.toString(client)).param("numeroPersonas", "2")
                .param("fechaHora", LocalDateTime.now().plusDays(2).toString()))
                .andExpect(flash().attributeExists("success"));
        mvc.perform(get("/reservas/eventos").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value(marker + " (2 pers)"))
                .andExpect(jsonPath("$[1].title").value(marker + " (2 pers)"));
    }

    @Test
    void asistenciaPinProtegidoEntradaSalidaEHistorial() throws Exception {
        assignPin("mesero", "MESERO", "135790");
        assertThat(renderedClockRequestId()).isNotEqualTo(renderedClockRequestId());
        long employee = id("select id from usuario where username='mesero'");
        String digest = db.queryForObject("select pin_digest from usuario where id=?", String.class, employee);
        assertThat(digest).matches("[0-9a-f]{64}").doesNotContain("135790");
        assertThat(db.queryForObject("select count(*) from information_schema.columns " +
                "where table_name='usuario' and column_name='pin'", Long.class)).isZero();
        String usersHtml = mvc.perform(get("/usuarios").with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(usersHtml).doesNotContain("135790", digest).contains("data-pin-configurado=\"true\"");

        String entryMessage = clock("135790", "192.0.2.11").getFlashMap().get("mensajeExito").toString();
        assertThat(entryMessage).matches(".*Entrada registrada a las \\d{2}:\\d{2}:\\d{2}")
                .doesNotContain(".");
        LocalDateTime storedEntry = db.queryForObject("select hora_entrada from asistencia where usuario_id=?",
                LocalDateTime.class, employee);
        assertThat(storedEntry.getNano()).isPositive();
        assertThat(count("asistencia")).isEqualTo(1);
        MvcResult openReport = mvc.perform(get("/asistencia/reporte").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("reporteActualizadoEn"))
                .andExpect(model().attribute("asistencias", org.hamcrest.Matchers.hasSize(1)))
                .andReturn();
        assertThat(openReport.getResponse().getContentAsString())
                .contains("En turno", "Trabajando", "Actualización automática cada 30 segundos",
                        "window.location.reload(), 30000");
        String exitMessage = clock("135790", "192.0.2.11").getFlashMap().get("mensajeExito").toString();
        assertThat(exitMessage).matches(".*Salida registrada a las \\d{2}:\\d{2}:\\d{2}")
                .doesNotContain(".");
        assertThat(db.queryForObject("select count(*) from asistencia where usuario_id=? and hora_salida is null",
                Long.class, employee)).isZero();
        assertThat(db.queryForObject("select horas_trabajadas from asistencia where usuario_id=?",
                Double.class, employee)).isNotNull();
        MvcResult closedReport = mvc.perform(get("/asistencia/reporte").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("asistencias", org.hamcrest.Matchers.hasSize(1)))
                .andReturn();
        String storedExitTime = db.queryForObject("select to_char(hora_salida, 'HH24:MI:SS') from asistencia " +
                "where usuario_id=?", String.class, employee);
        assertThat(closedReport.getResponse().getContentAsString()).contains("Completado", storedExitTime);

        // Un campo vacío conserva la asignación; revocación explícita la elimina.
        Usuario edited = new Usuario(employee, "mesero", "", "Prueba MESERO", "ROLE_MESERO");
        edited.setActivo(true);
        SyntheticSecurity.runAs(db, id("select id from usuario where username='admin'"), () -> usuarioService.actualizar(edited, "", "", false));
        assertThat(db.queryForObject("select pin_digest from usuario where id=?", String.class, employee))
                .isEqualTo(digest);
        SyntheticSecurity.runAs(db, id("select id from usuario where username='admin'"), () -> usuarioService.actualizar(edited, "", "", true));
        assertThat(db.queryForObject("select pin_digest from usuario where id=?", String.class, employee)).isNull();
    }

    @Test
    void asistenciaRechazaPinIncorrectoInactivoYDuplicado() throws Exception {
        assignPin("mesero", "MESERO", "246802");
        assertThat(clock("000000", "192.0.2.12").getFlashMap().get("mensajeError").toString())
                .contains("PIN incorrecto");
        assertThat(db.queryForObject("select attempt_count from attendance_pin_attempt", Integer.class)).isEqualTo(1);
        assertThat(count("asistencia")).isZero();
        assertThatThrownBy(() -> assignPin("cocinero", "COCINERO", "246802"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PIN ya está asignado");
        assertThat(db.queryForObject("select pin_digest from usuario where username='cocinero'", String.class)).isNull();
        String digest = db.queryForObject("select pin_digest from usuario where username='mesero'", String.class);
        assertThatThrownBy(() -> db.update("update usuario set pin_digest=? where username='cocinero'", digest))
                .hasMessageContaining("usuario_pin_digest_unique");
        db.update("update usuario set activo=false where username='mesero'");
        assertThat(clock("246802", "192.0.2.13").getFlashMap().get("mensajeError").toString())
                .contains("usuario inactivo");
        assertThat(db.queryForObject("select count(*) from attendance_pin_attempt " +
                "where attempt_count=1", Long.class)).isEqualTo(2L);
        assertThat(count("asistencia")).isZero();
    }

    @Test
    void relojPublicoLimitaIntentosEnServidorYReabreVentana() throws Exception {
        assignPin("mesero", "MESERO", "101010");
        for (int attempt = 0; attempt < 8; attempt++) {
            assertThat(clock("999999", "192.0.2.14").getFlashMap().get("mensajeError").toString())
                    .contains("PIN incorrecto");
        }
        assertThat(clock("101010", "192.0.2.14").getFlashMap().get("mensajeExito").toString())
                .contains("Entrada registrada");
        assertThat(db.queryForObject("select attempt_count from attendance_pin_attempt", Integer.class)).isEqualTo(8);
        assertThat(clock("999999", "192.0.2.14").getFlashMap().get("mensajeError").toString())
                .contains("PIN incorrecto");
        assertThat(clock("999999", "192.0.2.14").getFlashMap().get("mensajeError").toString())
                .contains("PIN incorrecto");
        assertThat(db.queryForObject("select attempt_count from attendance_pin_attempt", Integer.class)).isEqualTo(10);
        assertThat(clock("101010", "192.0.2.14").getFlashMap().get("mensajeError").toString())
                .contains("Demasiados intentos");
        assertThat(count("asistencia")).isEqualTo(1);
        db.update("update attendance_pin_attempt set window_started_at=now()-interval '11 minutes'");
        assertThat(clock("101010", "192.0.2.14").getFlashMap().get("mensajeExito").toString())
                .contains("Salida registrada");
        assertThat(db.queryForObject("select attempt_count from attendance_pin_attempt", Integer.class)).isZero();
    }

    @Test
    void fichajesValidosDeKioscoCompartidoNoConsumenPresupuesto() throws Exception {
        assignPin("mesero", "MESERO", "101011");
        for (int mark = 0; mark < 12; mark++) {
            assertThat(clock("101011", "192.0.2.15").getFlashMap().get("mensajeExito")).isNotNull();
        }
        assertThat(count("asistencia")).isEqualTo(6);
        assertThat(db.queryForObject("select attempt_count from attendance_pin_attempt", Integer.class)).isZero();
    }

    @Test
    void asistenciaIdempotenteConcurrenteYRestriccionSqlImpidenDosEntradasAbiertas() throws Exception {
        assignPin("mesero", "MESERO", "112233");
        long employee = id("select id from usuario where username='mesero'");
        String requestId = renderedClockRequestId();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<MvcResult> first = pool.submit(() -> { start.await(); return clock("112233", "192.0.2.16", requestId); });
            Future<MvcResult> second = pool.submit(() -> { start.await(); return clock("112233", "192.0.2.16", requestId); });
            start.countDown();
            String firstMessage = first.get(10, TimeUnit.SECONDS).getFlashMap().get("mensajeExito").toString();
            String secondMessage = second.get(10, TimeUnit.SECONDS).getFlashMap().get("mensajeExito").toString();
            assertThat(firstMessage).contains("Entrada registrada").isEqualTo(secondMessage);
            assertThat(clock("112233", "192.0.2.16", requestId).getFlashMap().get("mensajeExito"))
                    .isEqualTo(firstMessage);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("asistencia")).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from asistencia where usuario_id=? and hora_salida is null",
                Long.class, employee)).isEqualTo(1);
        assertThat(count("attendance_mark_request")).isEqualTo(1);
        assertThatThrownBy(() -> db.update("insert into asistencia(hora_entrada,usuario_id) values (now(),?)", employee))
                .hasMessageContaining("asistencia_una_abierta_por_usuario");
        String exitId = renderedClockRequestId();
        assertThat(exitId).isNotEqualTo(requestId);
        assertThat(clock("112233", "192.0.2.16", exitId).getFlashMap().get("mensajeExito").toString())
                .contains("Salida registrada");
        assertThat(count("asistencia")).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from asistencia where usuario_id=? and hora_salida is null",
                Long.class, employee)).isZero();
    }

    @Test
    void migracionV4InvalidaPinHeredadoConservaHistorialYRechazaDuplicadosAbiertos() throws Exception {
        String name = "ventro_attendance_" + UUID.randomUUID().toString().replace("-", "");
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL().replaceFirst("/[^/]+$", "/" + name);
        }
        db.execute("create database " + name);
        DriverManagerDataSource isolated = new DriverManagerDataSource(url,
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        JdbcTemplate local = new JdbcTemplate(isolated);
        try {
            Flyway toV3 = Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("3")).load();
            assertThat(toV3.migrate().migrationsExecuted).isEqualTo(3);
            long employee = local.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo,pin) " +
                    "values ('empleado','synthetic','Empleado','ROLE_MESERO',true,'1234') returning id", Long.class);
            local.update("insert into asistencia(usuario_id,hora_entrada,hora_salida,horas_trabajadas) " +
                    "values (?,now()-interval '2 days',now()-interval '2 days'+interval '8 hours',8)", employee);
            local.update("insert into asistencia(usuario_id,hora_entrada) values (?,now()-interval '1 hour')", employee);
            local.update("insert into asistencia(usuario_id,hora_entrada) values (?,now()-interval '30 minutes')", employee);
            Flyway all = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").load();
            assertThatThrownBy(all::migrate).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("asistencias abiertas duplicadas");
            assertThat(local.queryForObject("select count(*) from asistencia", Long.class)).isEqualTo(3);
            assertThat(local.queryForObject("select pin from usuario where id=?", String.class, employee))
                    .isEqualTo("1234");
            // Reconciliación explícita solo de este fixture, sin inventar una salida en la migración.
            local.update("update asistencia set hora_salida=hora_entrada + interval '5 minutes', " +
                    "horas_trabajadas=5.0/60 where usuario_id=? and hora_entrada < " +
                    "(select max(hora_entrada) from asistencia where usuario_id=? and hora_salida is null) " +
                    "and hora_salida is null", employee, employee);
            assertThat(all.migrate().migrationsExecuted).isEqualTo(10);
            assertThat(local.queryForObject("select count(*) from asistencia", Long.class)).isEqualTo(3);
            assertThat(local.queryForObject("select horas_trabajadas from asistencia " +
                    "where usuario_id=? order by hora_entrada limit 1", Double.class, employee)).isEqualTo(8.0);
            assertThat(local.queryForObject("select count(*) from information_schema.columns " +
                    "where table_name='usuario' and column_name='pin'", Long.class)).isZero();
            assertThat(local.queryForObject("select pin_digest from usuario where id=?", String.class, employee)).isNull();
            assertThat(all.migrate().migrationsExecuted).isZero();
        } finally {
            db.execute("drop database " + name + " with (force)");
        }
    }

    @Test
    void flywayCreoEsquemaYRepetirNoCambiaNada() {
        assertThat(db.queryForList("select version from flyway_schema_history where success=true order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select count(*) from flyway_schema_history", Long.class)).isEqualTo(13);
    }

    @Test
    void migracionV7ExpandeFixtureV6SinInferirNiAlterarHistoria() throws Exception {
        String name = "ventro_inventory_" + UUID.randomUUID().toString().replace("-", "");
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL().replaceFirst("/[^/]+$", "/" + name);
        }
        db.execute("create database " + name);
        DriverManagerDataSource isolated = new DriverManagerDataSource(url,
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        JdbcTemplate local = new JdbcTemplate(isolated);
        try {
            Flyway toV6 = Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("6")).load();
            assertThat(toV6.migrate().migrationsExecuted).isEqualTo(6);
            long userId = local.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                    "values ('synthetic','synthetic','Fixture','ROLE_ADMIN',true) returning id", Long.class);
            long insumoId = local.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                    "stock_actual,stock_minimo,activo) values ('Caja heredada','caja',9.75,11.25,0,true) returning id",
                    Long.class);
            long productoId = local.queryForObject("insert into producto(nombre,precio_venta,puntos_otorgados," +
                    "puntos_precio,activo,es_reventa) values ('Platillo heredado',20,0,0,true,false) returning id",
                    Long.class);
            long recetaId = local.queryForObject("insert into receta(producto_id,insumo_id,cantidad) " +
                    "values (?,?,0.1250) returning id", Long.class, productoId, insumoId);
            long movimientoId = local.queryForObject("insert into movimiento_inventario(insumo_id,usuario_id," +
                    "tipo_movimiento,cantidad,fecha) values (?,?,'ENTRADA',2.50,now()) returning id",
                    Long.class, insumoId, userId);
            long turnoId = local.queryForObject("insert into corte_caja(fecha_apertura,monto_inicial,estado," +
                    "total_efectivo,total_tarjeta,total_ventas,total_retiros,usuario_id) " +
                    "values (now(),0,'ABIERTO',0,0,0,0,?) returning id", Long.class, userId);
            long ventaId = local.queryForObject("insert into ventas(fecha,total,metodo_pago,monto_efectivo," +
                    "monto_tarjeta,cambio,usuario_id,corte_caja_id) values (now(),20,'EFECTIVO',20,0,0,?,?) " +
                    "returning id", Long.class, userId, turnoId);

            Flyway latest = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(local.queryForObject("select estado_inventario_5a from insumo where id=?", String.class, insumoId))
                    .isEqualTo("PENDIENTE");
            assertThat(local.queryForMap("select unidad_medida,unidad_base_familia,unidad_base_codigo," +
                    "reconciliacion_actual_id from insumo where id=?", insumoId))
                    .containsEntry("unidad_medida", "caja")
                    .containsEntry("unidad_base_familia", null)
                    .containsEntry("unidad_base_codigo", null)
                    .containsEntry("reconciliacion_actual_id", null);
            assertThat(local.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, insumoId))
                    .isEqualByComparingTo("11.25");
            assertThat(local.queryForObject("select cantidad from receta where id=?", BigDecimal.class, recetaId))
                    .isEqualByComparingTo("0.1250");
            assertThat(local.queryForObject("select cantidad from movimiento_inventario where id=?",
                    BigDecimal.class, movimientoId)).isEqualByComparingTo("2.50");
            assertThat(local.queryForObject("select total from ventas where id=?", BigDecimal.class, ventaId))
                    .isEqualByComparingTo("20.00");
            assertThat(local.queryForObject("select count(*) from inventario_reconciliacion", Long.class)).isZero();
            assertThat(latest.migrate().migrationsExecuted).isZero();
        } finally {
            db.execute("drop database " + name + " with (force)");
        }
    }

    @Test
    void migracionV8ConservaAnclaRevisadaV7SinInventarUnidadHistorica() throws Exception {
        String name = "ventro_review_" + UUID.randomUUID().toString().replace("-", "");
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL().replaceFirst("/[^/]+$", "/" + name);
        }
        db.execute("create database " + name);
        DriverManagerDataSource isolated = new DriverManagerDataSource(url,
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        JdbcTemplate local = new JdbcTemplate(isolated);
        try {
            Flyway toV7 = Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("7")).load();
            assertThat(toV7.migrate().migrationsExecuted).isEqualTo(7);
            long reviewer = local.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                    "values ('synthetic','synthetic','Revisor V7','ROLE_ADMIN',true) returning id", Long.class);
            long insumoId = local.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                    "stock_actual,stock_minimo,activo) values ('Ancla V7','caja',9.75,11.25,0,true) " +
                    "returning id", Long.class);
            long movementId = local.queryForObject("insert into movimiento_inventario(insumo_id,usuario_id," +
                    "tipo_movimiento,cantidad,fecha) values (?,?,'ENTRADA',2.50,?) returning id",
                    Long.class, insumoId, reviewer, HISTORICAL_AT);
            long anchorId = local.queryForObject("insert into inventario_reconciliacion(insumo_id,request_id," +
                    "saldo_observado,suma_movimientos_conocidos,delta_no_explicado,corte_en," +
                    "version_calculo,clasificacion,revisado_en,revisado_por_usuario_id,procedencia) " +
                    "values (?, ?, 11.25, 2.50, 8.75, now(), 'legacy-inventory-v1', " +
                    "'ORIGEN_NO_VERIFICADO', now(), ?, 'revisión sintética V7') returning id",
                    Long.class, insumoId, UUID.randomUUID(), reviewer);
            local.update("update insumo set unidad_base_familia='COUNT',unidad_base_codigo='unidad'," +
                    "reconciliacion_actual_id=?,estado_inventario_5a='RECONCILIADO' where id=?",
                    anchorId, insumoId);

            Flyway latest = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
            assertThat(local.queryForMap("select unidad_historica_familia,unidad_historica_codigo," +
                    "punto_inicial_en,punto_inicial_saldo,huella_comando from inventario_reconciliacion where id=?",
                    anchorId)).containsEntry("unidad_historica_familia", null)
                    .containsEntry("unidad_historica_codigo", null)
                    .containsEntry("punto_inicial_en", null)
                    .containsEntry("punto_inicial_saldo", null)
                    .containsEntry("huella_comando", null);
            assertThat(local.queryForObject("select reconciliacion_actual_id from insumo where id=?",
                    Long.class, insumoId)).isEqualTo(anchorId);
            assertThat(local.queryForObject("select stock_actual from insumo where id=?",
                    BigDecimal.class, insumoId)).isEqualByComparingTo("11.25");
            assertThat(local.queryForObject("select cantidad from movimiento_inventario where id=?",
                    BigDecimal.class, movementId)).isEqualByComparingTo("2.50");
            var isolatedActivation = new com.tuapp.restaurante.inventory.InventoryActivationService(
                    local,
                    new com.tuapp.restaurante.inventory.LegacyInventoryClassifier(
                            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(isolated)),
                    new com.tuapp.restaurante.inventory.InventoryPermissionService(local));
            assertThatThrownBy(() -> isolatedActivation.activate(
                    new com.tuapp.restaurante.inventory.InventoryActivationService.Command(
                            UUID.randomUUID(), insumoId, reviewer)))
                    .hasMessageContaining("evidencia V8 completas");
            assertThat(local.queryForObject("select count(*) from inventory_activation", Long.class)).isZero();
            // Una fila V7 activa no se invalida por actualizar otros datos ni
            // por repetir su valor de unidad sin cambiar la promoción.
            local.update("update insumo set stock_actual=stock_actual," +
                    "unidad_base_codigo=unidad_base_codigo where id=?", insumoId);
            assertThat(latest.migrate().migrationsExecuted).isZero();
        } finally {
            db.execute("drop database " + name + " with (force)");
        }
    }

    @Test
    void v8SoloPromueveConAnclaRevisadaCompletaYUnidadIgual() {
        long insumoId = ingredient("Promoción protegida", "7");
        long otherId = ingredient("Otro insumo protegido", "3");
        long reviewer = id("select id from usuario where username='admin'");
        db.update("update insumo set unidad_base_familia='COUNT',unidad_base_codigo='unidad' where id=?",
                insumoId);
        long anchorId = id("insert into inventario_reconciliacion(insumo_id,request_id," +
                        "saldo_observado,suma_movimientos_conocidos,delta_no_explicado,corte_en," +
                        "version_calculo,clasificacion,revisado_en,revisado_por_usuario_id,procedencia) " +
                        "values (?, ?, 7, 0, 7, ?,'legacy-inventory-v1','ORIGEN_NO_VERIFICADO'," +
                        "now(),?,'fixture V8') returning id", insumoId, UUID.randomUUID(),
                OffsetDateTime.parse("2026-01-01T00:00:00Z"), reviewer);
        String promote = "update insumo set reconciliacion_actual_id=?,estado_inventario_5a=? where id=?";
        String hash = "a".repeat(64);

        db.update("update inventario_reconciliacion set huella_comando=? where id=?", hash, anchorId);
        assertThatThrownBy(() -> db.update(promote, anchorId, "RECONCILIADO", insumoId))
                .hasMessageContaining("evidencia V8 completa");
        assertThatThrownBy(() -> db.update(promote, anchorId, "ACTIVO_5A", insumoId))
                .hasMessageContaining("evidencia V8 completa");

        db.update("update inventario_reconciliacion set unidad_historica_familia='MASS'," +
                "unidad_historica_codigo='g' where id=?", anchorId);
        assertThatThrownBy(() -> db.update(promote, anchorId, "RECONCILIADO", insumoId))
                .hasMessageContaining("unidad histórica distinta");

        db.update("update inventario_reconciliacion set unidad_historica_familia='COUNT'," +
                "unidad_historica_codigo='unidad',huella_comando=null where id=?", anchorId);
        assertThatThrownBy(() -> db.update(promote, anchorId, "RECONCILIADO", insumoId))
                .hasMessageContaining("evidencia V8 completa");

        db.update("update inventario_reconciliacion set huella_comando=? where id=?", hash, anchorId);
        assertThatThrownBy(() -> db.update("update insumo set unidad_base_familia='COUNT'," +
                "unidad_base_codigo='unidad',reconciliacion_actual_id=? where id=?", anchorId, otherId))
                .hasMessageContaining("fk_insumo_reconciliacion_actual");
        db.update(promote, anchorId, "RECONCILIADO", insumoId);
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class,
                insumoId)).isEqualTo("RECONCILIADO");
        long incompleteAnchor = id("insert into inventario_reconciliacion(insumo_id,request_id," +
                        "saldo_observado,suma_movimientos_conocidos,delta_no_explicado,corte_en," +
                        "version_calculo,clasificacion,revisado_en,revisado_por_usuario_id,procedencia) " +
                        "values (?, ?, 7, 0, 7, ?,'legacy-inventory-v1','ORIGEN_NO_VERIFICADO'," +
                        "now(),?,'otra ancla') returning id", insumoId, UUID.randomUUID(),
                OffsetDateTime.parse("2026-01-01T00:00:01Z"), reviewer);
        assertThatThrownBy(() -> db.update("update insumo set reconciliacion_actual_id=? where id=?",
                incompleteAnchor, insumoId)).hasMessageContaining("evidencia V8 completa");
        assertThatThrownBy(() -> db.update("update insumo set unidad_base_familia='MASS'," +
                "unidad_base_codigo='g' where id=?", insumoId))
                .hasMessageContaining("unidad histórica distinta");
        assertThatThrownBy(() -> db.update("update inventario_reconciliacion set huella_comando=null " +
                "where id=?", anchorId)).hasMessageContaining("ancla activa");
        assertThatThrownBy(() -> db.update("update inventario_reconciliacion set " +
                "unidad_historica_familia='MASS',unidad_historica_codigo='g' where id=?", anchorId))
                .hasMessageContaining("ancla activa");
        assertThat(db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class,
                insumoId)).isEqualByComparingTo("7");
        assertThat(count("movimiento_inventario")).isZero();
    }

    @Test
    void expansionV7ValidaEstadosUnidadesAnclaEIdempotenciaSinCambiarStock() throws Exception {
        long insumoId = ingredient("Insumo sin unidad inferida", "11.25");
        long otherId = ingredient("Otro insumo", "3");
        long reviewer = id("select id from usuario where username='admin'");
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class, insumoId))
                .isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("select unidad_base_codigo from insumo where id=?", String.class, insumoId))
                .isNull();
        for (String sql : List.of(
                "update insumo set estado_inventario_5a='DESCONOCIDO' where id=?",
                "update insumo set unidad_base_familia='MASS' where id=?",
                "update insumo set unidad_base_codigo='g' where id=?",
                "update insumo set unidad_base_familia='MASS',unidad_base_codigo='ml' where id=?",
                "update insumo set estado_inventario_5a='RECONCILIADO' where id=?",
                "update insumo set estado_inventario_5a='ACTIVO_5A' where id=?")) {
            assertThatThrownBy(() -> db.update(sql, insumoId)).as(sql).isInstanceOf(Exception.class);
        }
        db.update("update insumo set unidad_base_familia='COUNT',unidad_base_codigo='unidad' where id=?", insumoId);
        assertThatThrownBy(() -> db.update("update insumo set estado_inventario_5a='RECONCILIADO' " +
                "where id=?", insumoId)).isInstanceOf(Exception.class);

        UUID request = UUID.randomUUID();
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        String insert = "insert into inventario_reconciliacion(insumo_id,request_id,saldo_observado," +
                "suma_movimientos_conocidos,delta_no_explicado,corte_en,version_calculo,clasificacion," +
                "revisado_en,revisado_por_usuario_id,procedencia) values (?,?,?,?,?,?,?,?," +
                "now(),?,'fixture sintético') returning id";
        long anchorId = id("insert into inventario_reconciliacion(insumo_id,request_id,saldo_observado," +
                        "suma_movimientos_conocidos,delta_no_explicado,corte_en,version_calculo,clasificacion," +
                        "procedencia) values (?,?,?,?,?,?,?,?, 'fixture sintético') returning id",
                insumoId, request, new BigDecimal("11.25"), new BigDecimal("2.50"),
                new BigDecimal("8.75"), cutoff, "v1", "ORIGEN_NO_VERIFICADO");
        assertThat(db.queryForMap("select revisado_en,revisado_por_usuario_id from " +
                "inventario_reconciliacion where id=?", anchorId))
                .containsEntry("revisado_en", null)
                .containsEntry("revisado_por_usuario_id", null);
        for (String state : List.of("RECONCILIADO", "ACTIVO_5A")) {
            assertThatThrownBy(() -> db.update("update insumo set reconciliacion_actual_id=?," +
                    "estado_inventario_5a=? where id=?", anchorId, state, insumoId))
                    .hasMessageContaining("requiere ancla revisada");
        }
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class,
                insumoId)).isEqualTo("PENDIENTE");
        assertThatThrownBy(() -> id(insert, insumoId, UUID.randomUUID(), new BigDecimal("11.25"),
                new BigDecimal("2.50"), new BigDecimal("8.00"), cutoff.plusSeconds(2), "v1",
                "ORIGEN_NO_VERIFICADO", reviewer)).hasMessageContaining("inventario_reconciliacion_delta_check");
        assertThatThrownBy(() -> id(insert, insumoId, UUID.randomUUID(), new BigDecimal("11.25"),
                new BigDecimal("2.50"), new BigDecimal("8.75"), cutoff.plusSeconds(3), "v1",
                "ORIGEN_NO_VERIFICADO", null)).hasMessageContaining("inventario_reconciliacion_revision_check");
        assertThatThrownBy(() -> id(insert, insumoId, request, new BigDecimal("11.25"),
                new BigDecimal("2.50"), new BigDecimal("8.75"), cutoff.plusSeconds(1), "v1",
                "ORIGEN_NO_VERIFICADO", reviewer)).hasMessageContaining("inventario_reconciliacion_request_unique");
        assertThatThrownBy(() -> id(insert, insumoId, UUID.randomUUID(), new BigDecimal("11.25"),
                new BigDecimal("2.50"), new BigDecimal("8.75"), cutoff, "v1",
                "ORIGEN_NO_VERIFICADO", reviewer)).hasMessageContaining("inventario_reconciliacion_corte_unique");
        assertThatThrownBy(() -> db.update("update insumo set unidad_base_familia='COUNT'," +
                "unidad_base_codigo='unidad',reconciliacion_actual_id=? where id=?", anchorId, otherId))
                .hasMessageContaining("fk_insumo_reconciliacion_actual");
        db.update("update inventario_reconciliacion set revisado_en=now()," +
                "revisado_por_usuario_id=? where id=?", reviewer, anchorId);
        db.update("update inventario_reconciliacion set unidad_historica_familia='COUNT'," +
                "unidad_historica_codigo='unidad',huella_comando=? where id=?",
                "b".repeat(64), anchorId);
        db.update("update insumo set reconciliacion_actual_id=?,estado_inventario_5a='RECONCILIADO' " +
                "where id=?", anchorId, insumoId);
        assertThatThrownBy(() -> db.update("update inventario_reconciliacion set revisado_en=null," +
                "revisado_por_usuario_id=null where id=?", anchorId))
                .hasMessageContaining("No se puede retirar la revisión");
        db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?", insumoId);
        assertThat(db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, insumoId))
                .isEqualByComparingTo("11.25");
        assertThat(count("movimiento_inventario")).isZero();
        assertThat(count("inventario_reconciliacion")).isEqualTo(1);

        mvc.perform(post("/insumos/guardar").with(admin()).with(csrf())
                        .param("nombre", "Intento de activación")
                        .param("unidadMedida", "texto heredado")
                        .param("costoUnitario", "2")
                        .param("stockActual", "4")
                        .param("stockMinimo", "0")
                        .param("estadoInventario5a", "ACTIVO_5A")
                        .param("unidadBaseFamilia", "MASS")
                        .param("unidadBaseCodigo", "g"))
                .andExpect(status().is3xxRedirection());
        long attempted = id("select id from insumo where nombre='Intento de activación'");
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class, attempted))
                .isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("select unidad_base_familia from insumo where id=?", String.class, attempted))
                .isNull();
    }

    @Test
    void clasificadorHeredadoNoInfiereOrigenNiUnidad() {
        long empty = ingredient("Sin historia y sin stock", "0");
        long observed = ingredient("Saldo observado sin historia", "5");
        long withEntry = ingredient("Entrada de unidad no comprobada", "7");
        long entryId = historicalMovement(withEntry, "ENTRADA", "5", HISTORICAL_AT);

        var zero = legacyInventoryClassifier.classify(empty, Evidence.none());
        assertThat(zero.classification()).isEqualTo(Classification.ORIGEN_NO_VERIFICADO);
        assertThat(zero.knownMovementSum()).isEqualByComparingTo("0");
        assertThat(zero.unexplainedDelta()).isEqualByComparingTo("0");
        assertThat(zero.includedMovementIds()).isEmpty();
        assertThat(zero.excludedMovementIds()).isEmpty();

        var positive = legacyInventoryClassifier.classify(observed, Evidence.none());
        assertThat(positive.classification()).isEqualTo(Classification.ORIGEN_NO_VERIFICADO);
        assertThat(positive.unexplainedDelta()).isEqualByComparingTo("5");

        var unknownUnit = legacyInventoryClassifier.classify(withEntry, Evidence.none());
        assertThat(unknownUnit.classification()).isEqualTo(Classification.REQUIERE_INTERVENCION);
        assertThat(unknownUnit.includedMovementIds()).isEmpty();
        assertThat(unknownUnit.excludedMovementIds()).containsExactly(entryId);
        assertThat(unknownUnit.knownMovementSum()).isEqualByComparingTo("0");

        var proven = legacyInventoryClassifier.classify(withEntry, PROVEN_UNIT);
        assertThat(proven.classification()).isEqualTo(Classification.ORIGEN_NO_VERIFICADO);
        assertThat(proven.includedMovementIds()).containsExactly(entryId);
        assertThat(proven.excludedMovementIds()).isEmpty();
        assertThat(proven.knownMovementSum()).isEqualByComparingTo("5");
        assertThat(proven.unexplainedDelta()).isEqualByComparingTo("2");
        assertThat(proven.historicalUnit()).isEqualTo(BaseUnit.UNIT);
        assertThat(proven.cutoff()).isNotNull();
        assertThat(legacyInventoryClassifier.classify(withEntry, PROVEN_UNIT))
                .usingRecursiveComparison().ignoringFields("cutoff").isEqualTo(proven);
    }

    @Test
    void clasificadorHeredadoSumaTiposFirmadosYDetectaEfectosDesconocidos() {
        long manual = ingredient("Entrada y salida", "10");
        long entry = historicalMovement(manual, "ENTRADA", "15", HISTORICAL_AT);
        long exit = historicalMovement(manual, "SALIDA", "5", HISTORICAL_AT.plusMinutes(1));
        long sale = ingredient("Salida por venta", "1");
        long saleExit = historicalMovement(sale, "SALIDA_VENTA", "3", HISTORICAL_AT);
        long production = ingredient("Producción", "6");
        long productionEntry = historicalMovement(production, "ENTRADA_PRODUCCION", "8", HISTORICAL_AT);
        long productionExit = historicalMovement(production, "SALIDA_PRODUCCION", "2", HISTORICAL_AT.plusMinutes(1));
        long adjustments = ingredient("Ajustes firmados", "1");
        long plus = historicalMovement(adjustments, "AJUSTE", "3", HISTORICAL_AT);
        long minus = historicalMovement(adjustments, "AJUSTE", "-2", HISTORICAL_AT.plusMinutes(1));
        long waste = ingredient("Merma", "1");
        long wasteExit = historicalMovement(waste, "MERMA", "2", HISTORICAL_AT);
        long negativeDelta = ingredient("Delta matemático negativo", "1");
        historicalMovement(negativeDelta, "ENTRADA", "5", HISTORICAL_AT);

        var results = legacyInventoryClassifier.classifyBatch(
                List.of(manual, sale, production, adjustments, waste, negativeDelta),
                java.util.Map.of(manual, PROVEN_UNIT, sale, PROVEN_UNIT, production, PROVEN_UNIT,
                        adjustments, PROVEN_UNIT, waste, PROVEN_UNIT, negativeDelta, PROVEN_UNIT));
        assertThat(results).hasSize(6);
        assertThat(results.stream().map(result -> result.cutoff()).distinct()).hasSize(1);
        assertThat(results.get(0).includedMovementIds()).containsExactly(entry, exit);
        assertThat(results.get(0).knownMovementSum()).isEqualByComparingTo("10");
        assertThat(results.get(1).includedMovementIds()).containsExactly(saleExit);
        assertThat(results.get(1).knownMovementSum()).isEqualByComparingTo("-3");
        assertThat(results.get(2).includedMovementIds()).containsExactly(productionEntry, productionExit);
        assertThat(results.get(2).knownMovementSum()).isEqualByComparingTo("6");
        assertThat(results.get(3).includedMovementIds()).containsExactly(plus, minus);
        assertThat(results.get(3).knownMovementSum()).isEqualByComparingTo("1");
        assertThat(results.get(4).includedMovementIds()).containsExactly(wasteExit);
        assertThat(results.get(4).knownMovementSum()).isEqualByComparingTo("-2");
        assertThat(results.get(5).unexplainedDelta()).isEqualByComparingTo("-4");
        assertThat(results).allSatisfy(result ->
                assertThat(result.classification()).isEqualTo(Classification.ORIGEN_NO_VERIFICADO));

        long bad = ingredient("Efectos ilegibles", "7");
        long known = historicalMovement(bad, "ENTRADA", "2", HISTORICAL_AT);
        long unknown = historicalMovement(bad, "TIPO_DESCONOCIDO", "1", HISTORICAL_AT.plusMinutes(1));
        long zero = historicalMovement(bad, "SALIDA", "0", HISTORICAL_AT.plusMinutes(2));
        long wrongSign = historicalMovement(bad, "ENTRADA", "-1", HISTORICAL_AT.plusMinutes(3));
        var intervention = legacyInventoryClassifier.classify(bad, PROVEN_UNIT);
        assertThat(intervention.classification()).isEqualTo(Classification.REQUIERE_INTERVENCION);
        assertThat(intervention.includedMovementIds()).containsExactly(known);
        assertThat(intervention.excludedMovementIds()).containsExactly(unknown, zero, wrongSign);
        assertThat(intervention.knownMovementSum()).isEqualByComparingTo("2");
        assertThat(intervention.unexplainedDelta()).isEqualByComparingTo("5");
    }

    @Test
    void clasificadorHeredadoComparaPuntoFiableSinPersistirNiModificarHistoria() {
        long insumoId = ingredient("Con punto fiable", "7");
        long before = historicalMovement(insumoId, "ENTRADA", "100", HISTORICAL_AT.minusDays(2));
        long after = historicalMovement(insumoId, "ENTRADA", "4", HISTORICAL_AT);
        var start = new StartingPoint(HISTORICAL_AT.minusDays(1), new BigDecimal("3"));
        var reliable = new Evidence(BaseUnit.UNIT, start);
        var reconciled = legacyInventoryClassifier.classify(insumoId, reliable);
        assertThat(reconciled.classification()).isEqualTo(Classification.RECONCILIABLE);
        assertThat(reconciled.includedMovementIds()).containsExactly(after);
        assertThat(reconciled.includedMovementIds()).doesNotContain(before);
        assertThat(reconciled.knownMovementSum()).isEqualByComparingTo("4");
        assertThat(reconciled.unexplainedDelta()).isEqualByComparingTo("3");
        assertThat(legacyInventoryClassifier.classify(insumoId, reliable))
                .usingRecursiveComparison().ignoringFields("cutoff").isEqualTo(reconciled);

        var discrepancy = legacyInventoryClassifier.classify(insumoId,
                new Evidence(BaseUnit.UNIT,
                        new StartingPoint(HISTORICAL_AT.minusDays(1), new BigDecimal("2"))));
        assertThat(discrepancy.classification()).isEqualTo(Classification.DISCREPANCIA);
        assertThat(discrepancy.unexplainedDelta()).isEqualByComparingTo("3");
        assertThat(db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, insumoId))
                .isEqualByComparingTo("7");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?", Long.class,
                insumoId)).isEqualTo(2);
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class,
                insumoId)).isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("select reconciliacion_actual_id from insumo where id=?", Long.class,
                insumoId)).isNull();
        assertThat(count("inventario_reconciliacion")).isZero();

        assertThatThrownBy(() -> db.update("update insumo set stock_actual=-1 where id=?", insumoId))
                .hasMessageContaining("insumo_stock_actual_check");
        assertThat(legacyInventoryClassifier.classify(insumoId, reliable))
                .usingRecursiveComparison().ignoringFields("cutoff").isEqualTo(reconciled);
    }

    @Test
    void clasificadorHeredadoPaginaMasDeQuinientosMovimientosEnUnSoloSnapshot() {
        long insumoId = ingredient("Movimientos paginados", "501");
        List<Long> movementIds = db.queryForList("insert into movimiento_inventario(" +
                "insumo_id,usuario_id,tipo_movimiento,cantidad,fecha) " +
                "select ?, (select id from usuario where username='admin'), 'ENTRADA', 1, " +
                "timestamp '2025-01-02 12:00:00' from generate_series(1,501) returning id",
                Long.class, insumoId);
        assertThat(movementIds).hasSize(501);
        var evidence = new Evidence(BaseUnit.UNIT,
                new StartingPoint(HISTORICAL_AT.minusDays(1), BigDecimal.ZERO));
        TransactionTemplate sameSnapshot = new TransactionTemplate(transactionManager);
        sameSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        sameSnapshot.setReadOnly(true);
        var result = sameSnapshot.execute(status -> {
            var first = legacyInventoryClassifier.classify(insumoId, evidence);
            assertThat(legacyInventoryClassifier.classify(insumoId, evidence)).isEqualTo(first);
            return first;
        });
        assertThat(result).isNotNull();
        assertThat(result.classification()).isEqualTo(Classification.RECONCILIABLE);
        assertThat(result.historicalUnit()).isEqualTo(BaseUnit.UNIT);
        assertThat(result.includedMovementCount()).isEqualTo(501);
        assertThat(result.includedMovementIds()).containsExactlyElementsOf(movementIds);
        assertThat(result.excludedMovementCount()).isZero();
        assertThat(result.knownMovementSum()).isEqualByComparingTo("501");
        assertThat(result.unexplainedDelta()).isEqualByComparingTo("0");
        assertThat(db.queryForObject("select ?::timestamptz", java.time.OffsetDateTime.class,
                result.cutoff()).toInstant()).isEqualTo(result.cutoff().toInstant());
        assertThat(db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, insumoId))
                .isEqualByComparingTo("501");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?", Long.class,
                insumoId)).isEqualTo(501);
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class,
                insumoId)).isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("select reconciliacion_actual_id from insumo where id=?", Long.class,
                insumoId)).isNull();
        assertThat(count("inventario_reconciliacion")).isZero();
    }

    @Test
    void revisionExplicitaApruebaOrigenNoVerificadoConAnclaExactaEIdempotente() {
        long insumoId = ingredient("Ancla sin origen", "7");
        historicalMovement(insumoId, "ENTRADA", "5", HISTORICAL_AT);
        UUID requestId = UUID.randomUUID();
        Command command = approval(requestId, insumoId, BaseUnit.UNIT, PROVEN_UNIT,
                "Acta de revisión sintética");
        var approved = inventoryReconciliationService.approve(command);

        assertThat(approved.classification()).isEqualTo(Classification.ORIGEN_NO_VERIFICADO);
        assertThat(approved.observedStock()).isEqualByComparingTo("7");
        assertThat(approved.knownMovementSum()).isEqualByComparingTo("5");
        assertThat(approved.unexplainedDelta()).isEqualByComparingTo("2");
        assertThat(approved.cutoff()).isNotNull();
        assertThat(approved.reviewedAt()).isNotNull();
        assertThat(approved.algorithmVersion()).isEqualTo(LegacyInventoryClassifier.ALGORITHM_VERSION);
        assertThat(approved.historicalUnit()).isEqualTo(BaseUnit.UNIT);
        assertThat(inventoryReconciliationService.approve(command)).isEqualTo(approved);
        assertThatThrownBy(() -> inventoryReconciliationService.approve(
                approval(requestId, insumoId, BaseUnit.UNIT, PROVEN_UNIT, "Otra procedencia")))
                .hasMessageContaining("requestId reutilizado");
        assertThatThrownBy(() -> inventoryReconciliationService.approve(
                approval(UUID.randomUUID(), insumoId, BaseUnit.UNIT, PROVEN_UNIT, "Otra revisión")))
                .hasMessageContaining("PENDIENTE");

        var row = db.queryForMap("select estado_inventario_5a,unidad_base_familia,unidad_base_codigo," +
                "reconciliacion_actual_id,stock_actual,unidad_medida,costo_unitario " +
                "from insumo where id=?", insumoId);
        assertThat(row).containsEntry("estado_inventario_5a", "RECONCILIADO")
                .containsEntry("unidad_base_familia", "COUNT")
                .containsEntry("unidad_base_codigo", "unidad")
                .containsEntry("reconciliacion_actual_id", approved.id())
                .containsEntry("unidad_medida", "unidad");
        assertThat((BigDecimal) row.get("stock_actual")).isEqualByComparingTo("7");
        assertThat((BigDecimal) row.get("costo_unitario")).isEqualByComparingTo("5");
        assertThat(db.queryForObject("select corte_en from inventario_reconciliacion where id=?",
                OffsetDateTime.class, approved.id()).toInstant()).isEqualTo(approved.cutoff().toInstant());
        assertThat(db.queryForObject("select length(huella_comando) from inventario_reconciliacion " +
                "where id=?", Integer.class, approved.id())).isEqualTo(64);
        assertThat(db.queryForObject("select creado_por_usuario_id from inventario_reconciliacion " +
                "where id=?", Long.class, approved.id())).isEqualTo(approved.reviewerId());
        assertThat(count("inventario_reconciliacion")).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                Long.class, insumoId)).isEqualTo(1);
    }

    @Test
    void revisionSoloAceptaReconciliableYUnidadHistoricaIgual() {
        long good = ingredient("Ancla cuadrada", "7");
        historicalMovement(good, "ENTRADA", "4", HISTORICAL_AT);
        Evidence reliable = new Evidence(BaseUnit.GRAM,
                new StartingPoint(HISTORICAL_AT.minusDays(1), new BigDecimal("3")));
        assertThatThrownBy(() -> inventoryReconciliationService.approve(
                approval(UUID.randomUUID(), good, BaseUnit.UNIT, reliable, "Revisión")))
                .hasMessageContaining("unidad aprobada difiere");
        var approved = inventoryReconciliationService.approve(
                approval(UUID.randomUUID(), good, BaseUnit.GRAM, reliable, "Revisión"));
        assertThat(approved.classification()).isEqualTo(Classification.RECONCILIABLE);
        assertThat(approved.historicalUnit()).isEqualTo(BaseUnit.GRAM);
        assertThat(approved.startingAt()).isEqualTo(HISTORICAL_AT.minusDays(1));
        assertThat(approved.startingBalance()).isEqualByComparingTo("3");
        assertThat(db.queryForObject("select unidad_base_codigo from insumo where id=?", String.class, good))
                .isEqualTo("g");

        long unknown = ingredient("Tipo ilegible", "7");
        historicalMovement(unknown, "DESCONOCIDO", "1", HISTORICAL_AT);
        assertThatThrownBy(() -> inventoryReconciliationService.approve(
                approval(UUID.randomUUID(), unknown, BaseUnit.UNIT, PROVEN_UNIT, "Revisión")))
                .hasMessageContaining("REQUIERE_INTERVENCION");
        long mismatch = ingredient("Saldo discrepante", "7");
        historicalMovement(mismatch, "ENTRADA", "4", HISTORICAL_AT);
        Evidence wrongStart = new Evidence(BaseUnit.UNIT,
                new StartingPoint(HISTORICAL_AT.minusDays(1), new BigDecimal("2")));
        assertThatThrownBy(() -> inventoryReconciliationService.approve(
                approval(UUID.randomUUID(), mismatch, BaseUnit.UNIT, wrongStart, "Revisión")))
                .hasMessageContaining("DISCREPANCIA");
        assertThat(db.queryForList("select estado_inventario_5a from insumo where id in (?,?) order by id",
                String.class, unknown, mismatch)).containsExactly("PENDIENTE", "PENDIENTE");
        assertThat(db.queryForObject("select count(*) from inventario_reconciliacion where " +
                "insumo_id in (?,?)", Long.class, unknown, mismatch)).isZero();
        assertThat(count("movimiento_inventario")).isEqualTo(3);
    }

    @Test
    void dosRevisionesConcurrentesDelMismoInsumoSoloApruebanUna() throws Exception {
        long insumoId = ingredient("Aprobación disputada", "2");
        Command first = approval(UUID.randomUUID(), insumoId, BaseUnit.UNIT, PROVEN_UNIT, "Uno");
        Command second = approval(UUID.randomUUID(), insumoId, BaseUnit.UNIT, PROVEN_UNIT, "Dos");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var calls = List.of(first, second).stream().map(command -> executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    inventoryReconciliationService.approve(command);
                    return true;
                } catch (IllegalStateException ex) {
                    return false;
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(calls.get(0).get(10, TimeUnit.SECONDS) ^ calls.get(1).get(10, TimeUnit.SECONDS))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(db.queryForObject("select count(*) from inventario_reconciliacion where insumo_id=?",
                Long.class, insumoId)).isEqualTo(1);
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class,
                insumoId)).isEqualTo("RECONCILIADO");
        assertThat(db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class,
                insumoId)).isEqualByComparingTo("2");
    }

    @Test
    void dosReintentosConcurrentesDelMismoComandoDevuelvenLaMismaAncla() throws Exception {
        long insumoId = ingredient("Reintento simultáneo", "2");
        Command command = approval(UUID.randomUUID(), insumoId, BaseUnit.UNIT,
                PROVEN_UNIT, "Revisión única");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var calls = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                go.await();
                return inventoryReconciliationService.approve(command);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(calls.get(0).get(10, TimeUnit.SECONDS))
                    .isEqualTo(calls.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(db.queryForObject("select count(*) from inventario_reconciliacion where insumo_id=?",
                Long.class, insumoId)).isEqualTo(1);
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class,
                insumoId)).isEqualTo("RECONCILIADO");
    }

    @Test
    void aprobacionEsperaEscriturasConcurrentesYRecalculaStockYMovimientos() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        TransactionTemplate writer = new TransactionTemplate(transactionManager);
        try {
            long stockChanged = ingredient("Stock cambiado", "2");
            Command stockCommand = approval(UUID.randomUUID(), stockChanged, BaseUnit.UNIT,
                    PROVEN_UNIT, "Revisión concurrente");
            java.util.concurrent.atomic.AtomicReference<Future<InventoryReconciliationService.ApprovedAnchor>>
                    stockFuture = new java.util.concurrent.atomic.AtomicReference<>();
            writer.executeWithoutResult(status -> {
                db.queryForObject("select id from insumo where id=? for update", Long.class, stockChanged);
                stockFuture.set(executor.submit(() -> inventoryReconciliationService.approve(stockCommand)));
                try { Thread.sleep(150); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                assertThat(stockFuture.get().isDone()).isFalse();
                db.update("update insumo set stock_actual=3 where id=?", stockChanged);
            });
            var stockAnchor = stockFuture.get().get(10, TimeUnit.SECONDS);
            assertThat(stockAnchor.observedStock()).isEqualByComparingTo("3");
            assertThat(stockAnchor.knownMovementSum()).isEqualByComparingTo("0");
            assertThat(stockAnchor.unexplainedDelta()).isEqualByComparingTo("3");

            long movementChanged = ingredient("Movimiento cambiado", "2");
            Command movementCommand = approval(UUID.randomUUID(), movementChanged, BaseUnit.UNIT,
                    PROVEN_UNIT, "Revisión concurrente");
            java.util.concurrent.atomic.AtomicReference<Future<InventoryReconciliationService.ApprovedAnchor>>
                    movementFuture = new java.util.concurrent.atomic.AtomicReference<>();
            writer.executeWithoutResult(status -> {
                db.queryForObject("select id from insumo where id=? for update", Long.class, movementChanged);
                movementFuture.set(executor.submit(() ->
                        inventoryReconciliationService.approve(movementCommand)));
                try { Thread.sleep(150); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                assertThat(movementFuture.get().isDone()).isFalse();
                db.update("update insumo set stock_actual=3 where id=?", movementChanged);
                historicalMovement(movementChanged, "ENTRADA", "1", HISTORICAL_AT);
            });
            var movementAnchor = movementFuture.get().get(10, TimeUnit.SECONDS);
            assertThat(movementAnchor.observedStock()).isEqualByComparingTo("3");
            assertThat(movementAnchor.knownMovementSum()).isEqualByComparingTo("1");
            assertThat(movementAnchor.unexplainedDelta()).isEqualByComparingTo("2");
            assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                    Long.class, movementChanged)).isEqualTo(1);

            long correctedHistory = ingredient("Historial corregido", "2");
            long oldMovement = historicalMovement(correctedHistory, "ENTRADA", "1", HISTORICAL_AT);
            Command correctedCommand = approval(UUID.randomUUID(), correctedHistory, BaseUnit.UNIT,
                    PROVEN_UNIT, "Revisión tras corrección");
            java.util.concurrent.atomic.AtomicReference<Future<InventoryReconciliationService.ApprovedAnchor>>
                    correctedFuture = new java.util.concurrent.atomic.AtomicReference<>();
            writer.executeWithoutResult(status -> {
                db.queryForObject("select id from movimiento_inventario where id=? for update",
                        Long.class, oldMovement);
                correctedFuture.set(executor.submit(() ->
                        inventoryReconciliationService.approve(correctedCommand)));
                try { Thread.sleep(150); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                assertThat(correctedFuture.get().isDone()).isFalse();
                db.update("update movimiento_inventario set cantidad=2 where id=?", oldMovement);
            });
            var correctedAnchor = correctedFuture.get().get(10, TimeUnit.SECONDS);
            assertThat(correctedAnchor.observedStock()).isEqualByComparingTo("2");
            assertThat(correctedAnchor.knownMovementSum()).isEqualByComparingTo("2");
            assertThat(correctedAnchor.unexplainedDelta()).isEqualByComparingTo("0");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void migracionV6ConservaVentaHistoricaSinAtribuirYRechazaNuevasHuerfanas() throws Exception {
        String name = "ventro_cash_" + UUID.randomUUID().toString().replace("-", "");
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL().replaceFirst("/[^/]+$", "/" + name);
        }
        db.execute("create database " + name);
        DriverManagerDataSource isolated = new DriverManagerDataSource(url,
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        JdbcTemplate local = new JdbcTemplate(isolated);
        try {
            Flyway toV5 = Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("5")).load();
            assertThat(toV5.migrate().migrationsExecuted).isEqualTo(5);
            long user = local.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                    "values ('historico','synthetic','Histórico','ROLE_ADMIN',true) returning id", Long.class);
            long legacy = local.queryForObject("insert into ventas(fecha,total,metodo_pago,monto_efectivo," +
                    "monto_tarjeta,cambio,usuario_id) values (now()-interval '1 day',20,'EFECTIVO',20,0,0,?) " +
                    "returning id", Long.class, user);
            long oldShift = local.queryForObject("insert into corte_caja(fecha_apertura,monto_inicial," +
                    "estado,total_efectivo,total_tarjeta,total_ventas,total_retiros,usuario_id) " +
                    "values (now()-interval '1 day',0,'ABIERTO',0,0,0,0,?) returning id", Long.class, user);
            Flyway toV6 = Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("6")).load();
            assertThatThrownBy(toV6::migrate).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("Cierre los turnos de caja abiertos");
            assertThat(local.queryForObject("select count(*) from information_schema.columns " +
                    "where table_name='ventas' and column_name='corte_caja_id'", Long.class)).isZero();
            // Cierre sintético con el esquema anterior; no se atribuye la venta por fecha.
            local.update("update corte_caja set estado='CERRADO', fecha_cierre=now(), " +
                    "total_efectivo=20,total_ventas=20 where id=?", oldShift);
            assertThat(toV6.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(local.queryForObject("select corte_caja_id from ventas where id=?", Long.class, legacy))
                    .isNull();
            assertThat(local.queryForObject("select legacy_sin_turno from ventas where id=?", Boolean.class, legacy))
                    .isTrue();
            assertThat(local.queryForObject("select sum(total) from ventas", BigDecimal.class))
                    .isEqualByComparingTo("20");
            assertThatThrownBy(() -> local.update("insert into ventas(fecha,total,metodo_pago,monto_efectivo," +
                    "monto_tarjeta,cambio,usuario_id) values (now(),20,'EFECTIVO',20,0,0,?)", user))
                    .hasMessageContaining("venta_monetaria_turno_check");
            Flyway latest = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(latest.migrate().migrationsExecuted).isZero();
        } finally {
            db.execute("drop database " + name + " with (force)");
        }
    }

    @Test
    void migracionInterrumpidaRevierteDdlYPermiteReintento() {
        db.execute("drop schema if exists slice1_abort cascade");
        db.execute("create schema slice1_abort");
        try {
            Flyway failing = Flyway.configure().dataSource(db.getDataSource())
                    .schemas("slice1_abort").defaultSchema("slice1_abort")
                    .locations("classpath:db/slice1-abort").load();
            assertThatThrownBy(failing::migrate).isInstanceOf(FlywayException.class);
            assertThat(db.queryForObject("select to_regclass('slice1_abort.partial')", String.class)).isNull();

            Flyway retry = Flyway.configure().dataSource(db.getDataSource())
                    .schemas("slice1_abort").defaultSchema("slice1_abort")
                    .locations("classpath:db/slice1-retry").load();
            assertThat(retry.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(db.queryForObject("select to_regclass('slice1_abort.partial')", String.class))
                    .isEqualTo("slice1_abort.partial");
        } finally {
            db.execute("drop schema if exists slice1_abort cascade");
        }
    }

    @Test
    void autenticacionRealRolesYCsrf() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/login").param("username", "admin").param("password", "synthetic-pass"))
                .andExpect(status().isForbidden());
        MvcResult login = mvc.perform(post("/login").with(csrf()).param("username", "admin")
                .param("password", "synthetic-pass"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dashboard"))
                .andReturn();
        mvc.perform(get("/dashboard").session((org.springframework.mock.web.MockHttpSession)
                login.getRequest().getSession(false))).andExpect(status().isOk());
        mvc.perform(post("/login").with(csrf()).param("username", "admin")
                .param("password", "wrong")).andExpect(redirectedUrl("/login?error=true"));
        db.update("update usuario set activo=false where username='gerente'");
        mvc.perform(post("/login").with(csrf()).param("username", "gerente")
                .param("password", "synthetic-pass"))
                .andExpect(redirectedUrl("/login?error=true"));
        db.update("update usuario set activo=true where username='gerente'");
        mvc.perform(get("/dashboard").with(user("gerente").roles("GERENTE"))).andExpect(status().isOk());
        mvc.perform(get("/insumos").with(user("mesero").roles("MESERO")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/pos").with(user("mesero").roles("MESERO"))).andExpect(status().isOk());
        mvc.perform(get("/kds/api/datos").with(user("cocinero").roles("COCINERO")))
                .andExpect(status().isOk());
        mvc.perform(get("/pos").with(user("cocinero").roles("COCINERO")))
                .andExpect(status().isForbidden());
        // Ruta IA heredada: cae en anyRequest().authenticated(), accesible al mesero.
        mvc.perform(get("/ia/compras/api").with(user("mesero").roles("MESERO")))
                .andExpect(status().isOk());
    }

    @Test
    void altaUsuarioConErroresDeBindingNoDevuelve500NiPersiste() throws Exception {
        mvc.perform(post("/usuarios/guardar").with(admin()).with(csrf())
                        .param("username", "x").param("password", "")
                        .param("nombreCompleto", "").param("rol", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/usuarios"))
                .andExpect(flash().attributeExists("mensajeError"));
        assertThat(count("usuario")).isEqualTo(4);
        assertThat(db.queryForObject("select count(*) from usuario where username='x'", Long.class)).isZero();
    }

    @Test
    void altaValidaYEdicionConNuevaPasswordConservanSuFlujo() throws Exception {
        mvc.perform(post("/usuarios/guardar").with(admin()).with(csrf())
                        .param("username", "usuario-regresion").param("password", "initial-pass")
                        .param("nombreCompleto", "Usuario Regresión").param("rol", "ROLE_EMPLEADO"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/usuarios"))
                .andExpect(flash().attributeExists("mensajeExito"));
        long userId = id("select id from usuario where username='usuario-regresion'");
        assertThat(encoder.matches("initial-pass", db.queryForObject(
                "select password from usuario where id=?", String.class, userId))).isTrue();

        mvc.perform(post("/usuarios/actualizar").with(admin()).with(csrf())
                        .param("id", Long.toString(userId)).param("username", "usuario-regresion")
                        .param("nombreCompleto", "Usuario Regresión").param("rol", "ROLE_EMPLEADO")
                        .param("activo", "true").param("nuevaPassword", "updated-pass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/usuarios"))
                .andExpect(flash().attributeExists("mensajeExito"));
        assertThat(encoder.matches("updated-pass", db.queryForObject(
                "select password from usuario where id=?", String.class, userId))).isTrue();
    }

    @Test
    void desactivacionRevocaSesionYRememberMeNoEmiteCookie() throws Exception {
        MvcResult login = mvc.perform(post("/login").with(csrf())
                        .param("username", "admin").param("password", "synthetic-pass")
                        .param("remember-me", "on"))
                .andExpect(redirectedUrl("/dashboard")).andReturn();
        assertThat(login.getResponse().getCookies()).noneMatch(c -> c.getName().equals("remember-me"));
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/dashboard").session(session)).andExpect(status().isOk());
        db.update("update usuario set activo=false where username='admin'");
        mvc.perform(get("/dashboard").session(session))
                .andExpect(redirectedUrl("/login?expired=true"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/dashboard").cookie(new jakarta.servlet.http.Cookie("remember-me", "antiguo")))
                .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/login").with(csrf()).param("username", "admin")
                .param("password", "synthetic-pass"))
                .andExpect(redirectedUrl("/login?error=true"));
    }

    @Test
    void logoutYRolesActuales() throws Exception {
        MvcResult login = mvc.perform(post("/login").with(csrf())
                        .param("username", "mesero").param("password", "synthetic-pass"))
                .andExpect(redirectedUrl("/pos")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/pos").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout=true"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/usuarios").with(user("gerente").roles("GERENTE")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/usuarios").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/kds").with(user("mesero").roles("MESERO")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/kds").with(user("cocinero").roles("COCINERO")))
                .andExpect(status().isOk());
    }

    @Test
    void fotosSeEscribenYLeenEnRaizDelDespliegue() throws Exception {
        Path root = Path.of(environment.getRequiredProperty("app.upload-dir"));
        MvcResult saved = mvc.perform(multipart("/productos/guardar")
                        .file(new MockMultipartFile("foto", "foto.png", "image/png", new byte[]{1, 2, 3}))
                        .with(admin()).with(csrf())
                        .header("X-Tenant-ID", "otro-restaurante")
                        .header("Host", "otro.invalid")
                        .param("nombre", "Foto sintética").param("precioVenta", "12"))
                .andExpect(redirectedUrl("/productos")).andReturn();
        assertThat(saved.getFlashMap().get("mensajeError")).isNull();
        String url = db.queryForObject("select imagen_url from producto where nombre='Foto sintética'", String.class);
        assertThat(url).startsWith("/uploads/");
        Path file = root.resolve(url.substring("/uploads/".length()));
        try {
            assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
            mvc.perform(get(url).header("X-Tenant-ID", "otro-restaurante")
                    .header("Host", "otro.invalid")).andExpect(status().isOk())
                    .andExpect(content().bytes(new byte[]{1, 2, 3}));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void altasCatalogoRecetaYValidaciones() throws Exception {
        mvc.perform(post("/categorias/menu/guardar").with(admin()).with(csrf())
                .param("nombre", "Bebidas sintéticas"))
                .andExpect(redirectedUrl("/categorias/menu"));
        mvc.perform(post("/categorias/insumos/guardar").with(admin()).with(csrf())
                .param("nombre", "Ingredientes sintéticos"))
                .andExpect(redirectedUrl("/categorias/insumos"));
        long menu = id("select id from categoria where nombre='Bebidas sintéticas'");
        long inputs = id("select id from categoria where nombre='Ingredientes sintéticos'");
        assertThat(db.queryForObject("select tipo from categoria where id=?", String.class, menu)).isEqualTo("MENU");
        assertThat(db.queryForObject("select tipo from categoria where id=?", String.class, inputs)).isEqualTo("INSUMO");
        mvc.perform(post("/categorias/menu/guardar").with(admin()).with(csrf()).param("nombre", ""))
                .andExpect(status().isOk());
        assertThat(count("categoria")).isEqualTo(2);

        mvc.perform(post("/insumos/guardar").with(admin()).with(csrf())
                .param("nombre", "Agua sintética").param("unidadMedida", "L")
                .param("costoUnitario", "5").param("stockActual", "20")
                .param("stockMinimo", "0").param("categoria.id", Long.toString(inputs)))
                .andExpect(redirectedUrl("/insumos"));
        long i = id("select id from insumo where nombre='Agua sintética'");
        mvc.perform(post("/productos/guardar").with(admin()).with(csrf())
                .param("nombre", "Té sintético").param("precioVenta", "30")
                .param("puntosOtorgados", "2").param("puntosPrecio", "10")
                .param("categoria.id", Long.toString(menu)))
                .andExpect(redirectedUrl("/productos"));
        long p = id("select id from producto where nombre='Té sintético'");
        mvc.perform(post("/recetas/agregar").with(admin()).with(csrf())
                .param("producto.id", Long.toString(p)).param("insumo.id", Long.toString(i))
                .param("cantidad", "1.5"))
                .andExpect(redirectedUrl("/recetas/producto/" + p));
        amount("1.5", money("select cantidad from receta where producto_id=?", p));
        mvc.perform(post("/recetas/agregar").with(admin()).with(csrf())
                .param("producto.id", Long.toString(p)).param("insumo.id", Long.toString(i))
                .param("cantidad", "0"))
                .andExpect(redirectedUrl("/recetas/producto/" + p));
        assertThat(count("receta")).isEqualTo(1);
    }

    @Test
    void ventaEfectivoStockMovimientoKdsYRollback() throws Exception {
        long i = ingredient("Harina", "10");
        long p = product("Pan", "25", 0, 0, i);
        MvcResult ok = sale(p, 2, "EFECTIVO", "60", "0", null, null, false);
        assertThat(ok.getFlashMap().get("mensajeExito")).isNotNull();
        long v = id("select id from ventas");
        amount("50", money("select total from ventas where id=?", v));
        amount("10", money("select cambio from ventas where id=?", v));
        amount("6", money("select stock_actual from insumo where id=?", i));
        amount("4", money("select cantidad from movimiento_inventario where insumo_id=?", i));
        assertThat(db.queryForObject("select tipo_movimiento from movimiento_inventario", String.class))
                .isEqualTo("SALIDA_VENTA");
        assertThat(db.queryForObject("select estado from venta_detalles", String.class)).isEqualTo("PENDIENTE");
        mvc.perform(get("/kds/api/datos").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pendientes").value(1))
                .andExpect(jsonPath("$.pedidos[0].productoNombre").value("Pan"));

        MvcResult error = sale(p, 4, "EFECTIVO", "100", "0", null, null, false);
        assertThat(error.getFlashMap().get("mensajeError").toString()).contains("Stock insuficiente");
        assertThat(count("ventas")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        amount("6", money("select stock_actual from insumo where id=?", i));
    }

    @Test
    void ventaConNotaVaciaPersisteYReintentoNoDuplicaEfectos() throws Exception {
        long i = ingredient("Pan sin nota", "10");
        long p = product("Pan", "25", 3, 0, i);
        long c = id("insert into clientes(nombre,tipo,puntos,saldo_credito,limite_credito,total_gastado,fecha_registro,activo) " +
                "values ('Cliente B01','VIP',0,0,100,0,now(),true) returning id");
        String key = UUID.randomUUID().toString();
        var request = post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("notas", "").param("metodoPago", "EFECTIVO")
                .param("montoEfectivo", "30").param("montoTarjeta", "0")
                .param("clienteId", Long.toString(c)).param("esCanjeado", "false")
                .param("idempotencyKey", key);
        MvcResult first = mvc.perform(request).andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(first.getFlashMap().get("mensajeExito")).isNotNull();
        assertThat(first.getFlashMap().get("ventaAceptadaKey")).isEqualTo(key);
        MvcResult retry = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("notas", "").param("metodoPago", "EFECTIVO")
                .param("montoEfectivo", "30").param("montoTarjeta", "0")
                .param("clienteId", Long.toString(c)).param("esCanjeado", "false")
                .param("idempotencyKey", key))
                .andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(retry.getFlashMap().get("ultimoTicketId")).isEqualTo(first.getFlashMap().get("ultimoTicketId"));
        assertThat(db.queryForObject("select notas from venta_detalles", String.class)).isEmpty();
        assertThat(count("ventas")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        assertThat(count("pos_sale_request")).isEqualTo(1);
        amount("8", money("select stock_actual from insumo where id=?", i));
        amount("30", money("select monto_efectivo from ventas"));
        amount("5", money("select cambio from ventas"));
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isEqualTo(3);
    }

    @Test
    void variasLineasConNotasVaciasCombinadasYCaracteresEspeciales() throws Exception {
        long i = ingredient("Insumo notas", "20");
        long p1 = product("Primero", "10", 0, 0, i);
        long p2 = product("Segundo", "15", 0, 0, i);
        String special = "Sin <picante> & \"extra\" 'limón'";
        MvcResult sale = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p1), Long.toString(p2), Long.toString(p1))
                .param("cantidadVendida", "1", "1", "1")
                .param("notas", "", special, "")
                .param("metodoPago", "TARJETA").param("montoEfectivo", "0")
                .param("montoTarjeta", "35")
                .param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(sale.getFlashMap().get("mensajeExito")).isNotNull();
        assertThat(db.queryForList("select notas from venta_detalles order by id", String.class))
                .containsExactly("", special, "");
        assertThat(count("venta_detalles")).isEqualTo(3);
        assertThat(count("movimiento_inventario")).isEqualTo(3);
        amount("14", money("select stock_actual from insumo where id=?", i));
        amount("35", money("select total from ventas"));
    }

    @Test
    void splitRechazadoNoAfectaPrimeraVentaYReintentoCorregidoCobraUnaVez() throws Exception {
        long i = ingredient("Insumo split", "20");
        long p = product("Split", "20", 0, 0, i);
        String firstKey = UUID.randomUUID().toString();
        String rejectedKey = UUID.randomUUID().toString();
        MvcResult first = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("notas", "primera").param("metodoPago", "TARJETA")
                .param("montoEfectivo", "0").param("montoTarjeta", "20")
                .param("idempotencyKey", firstKey)).andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(first.getFlashMap().get("mensajeExito")).isNotNull();
        MvcResult rejected = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("notas", "").param("metodoPago", "EFECTIVO")
                .param("montoEfectivo", "10").param("montoTarjeta", "0")
                .param("idempotencyKey", rejectedKey)).andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(rejected.getFlashMap().get("mensajeError").toString()).contains("insuficiente");
        assertThat(rejected.getFlashMap().get("ventaRechazadaKey")).isEqualTo(rejectedKey);
        assertThat(count("ventas")).isEqualTo(1);
        assertThat(count("pos_sale_request")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        amount("18", money("select stock_actual from insumo where id=?", i));

        String correctedKey = UUID.randomUUID().toString();
        MvcResult second = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("notas", "").param("metodoPago", "EFECTIVO")
                .param("montoEfectivo", "20").param("montoTarjeta", "0")
                .param("idempotencyKey", correctedKey)).andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(second.getFlashMap().get("mensajeExito")).isNotNull();
        assertThat(count("ventas")).isEqualTo(2);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
        amount("16", money("select stock_actual from insumo where id=?", i));
    }

    @Test
    void pagosTarjetaMixtoYRechazoDePagoInsuficiente() throws Exception {
        long i = ingredient("Leche", "30");
        long p = product("Café", "20", 0, 0, i);
        sale(p, 1, "TARJETA", "0", "20", null, null, false);
        sale(p, 1, "MIXTO", "7", "13", null, null, false);
        assertThat(db.queryForList("select metodo_pago from ventas order by id", String.class))
                .containsExactly("TARJETA", "MIXTO");
        amount("7", money("select monto_efectivo from ventas where metodo_pago='MIXTO'"));
        amount("13", money("select monto_tarjeta from ventas where metodo_pago='MIXTO'"));
        amount("0", money("select cambio from ventas where metodo_pago='MIXTO'"));
        MvcResult error = sale(p, 1, "EFECTIVO", "0", "0", null, null, false);
        assertThat(error.getFlashMap().get("mensajeError").toString()).contains("insuficiente");
        assertThat(count("ventas")).isEqualTo(2);
        amount("26", money("select stock_actual from insumo where id=?", i));
    }

    @Test
    void ventaRechazaEntradasInvalidasSinEfectosParciales() throws Exception {
        long i = ingredient("Harina", "4");
        long p = product("Pan", "20", 2, 0, i);
        for (MvcResult error : List.of(
                sale(p, -1, "EFECTIVO", "20", "0", null, null, false),
                sale(p, 1, "EFECTIVO", "19", "0", null, null, false),
                sale(p, 1, "TARJETA", "0", "19", null, null, false),
                sale(p, 1, "MIXTO", "5", "10", null, null, false),
                sale(p, 1, "OTRO", "20", "0", null, null, false),
                sale(p, 1, "EFECTIVO", "-1", "0", null, null, false),
                sale(p, 1, "EFECTIVO", "20.001", "0", null, null, false),
                sale(999999, 1, "EFECTIVO", "20", "0", null, null, false),
                sale(p, 3, "EFECTIVO", "60", "0", null, null, false))) {
            assertThat(error.getFlashMap().get("mensajeError")).isNotNull();
        }
        assertThat(count("ventas")).isZero();
        assertThat(count("venta_detalles")).isZero();
        assertThat(count("movimiento_inventario")).isZero();
        assertThat(count("pos_sale_request")).isZero();
        amount("4", money("select stock_actual from insumo where id=?", i));

        mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("idempotencyKey", UUID.randomUUID().toString())
                .param("productoId", Long.toString(p), Long.toString(p))
                .param("cantidadVendida", "1", "2")
                .param("metodoPago", "EFECTIVO").param("montoEfectivo", "60"))
                .andExpect(redirectedUrl("/pos"));
        assertThat(count("ventas")).isZero();
        amount("4", money("select stock_actual from insumo where id=?", i));

        MvcResult noKey = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("montoEfectivo", "20"))
                .andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(noKey.getFlashMap().get("mensajeError").toString()).contains("idempotencia");
        assertThat(count("ventas")).isZero();

        MvcResult forgedTotal = mvc.perform(post("/pos/venta-multiple").with(admin()).with(csrf())
                .param("idempotencyKey", UUID.randomUUID().toString())
                .param("productoId", Long.toString(p)).param("cantidadVendida", "1")
                .param("precioUnidad", "0.01").param("total", "0.01")
                .param("metodoPago", "EFECTIVO").param("montoEfectivo", "20"))
                .andExpect(redirectedUrl("/pos")).andReturn();
        assertThat(forgedTotal.getFlashMap().get("mensajeExito")).isNotNull();
        amount("20", money("select total from ventas"));
        amount("2", money("select stock_actual from insumo where id=?", i));
    }

    @Test
    void reintentoIdempotenteNoDuplicaVentaStockNiPuntos() throws Exception {
        long i = ingredient("Arroz", "10");
        long p = product("Tazón", "20", 3, 0, i);
        long c = id("insert into clientes(nombre,tipo,puntos,saldo_credito,limite_credito,total_gastado,fecha_registro,activo) " +
                "values ('C','VIP',0,0,100,0,now(),true) returning id");
        String key = UUID.randomUUID().toString();
        MvcResult first = saleWithKey(p, 1, "EFECTIVO", "25", "0", c, null, false, key);
        MvcResult retry = saleWithKey(p, 1, "EFECTIVO", "25", "0", c, null, false, key);
        assertThat(first.getFlashMap().get("ultimoTicketId")).isEqualTo(retry.getFlashMap().get("ultimoTicketId"));
        assertThat(count("ventas")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        assertThat(count("pos_sale_request")).isEqualTo(1);
        amount("8", money("select stock_actual from insumo where id=?", i));
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isEqualTo(3);
        MvcResult changed = saleWithKey(p, 1, "EFECTIVO", "20", "0", c, null, false, key);
        assertThat(changed.getFlashMap().get("mensajeError").toString()).contains("idempotencia");
        assertThat(count("ventas")).isEqualTo(1);
    }

    @Test
    void ventasConcurrentesNoSobrevendenInsumo() throws Exception {
        long i = ingredient("Última porción", "2");
        long p = product("Platillo", "20", 0, 0, i);
        long userId = id("select id from usuario where username='admin'");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int n = 0; n < 2; n++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        ventaService.procesarVentasMultiples(List.of(p), List.of(1), List.of(""), userId,
                                "EFECTIVO", new BigDecimal("20"), BigDecimal.ZERO, null, null,
                                List.of(false), UUID.randomUUID().toString());
                        return true;
                    } catch (IllegalArgumentException ex) {
                        assertThat(ex.getMessage()).contains("Stock insuficiente");
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(calls.get(0).get(15, TimeUnit.SECONDS), calls.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("ventas")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        assertThat(count("pos_sale_request")).isEqualTo(1);
        amount("0", money("select stock_actual from insumo where id=?", i));
    }

    @Test
    void edicionDeInsumoConFormularioObsoletoNoReponeStock() throws Exception {
        long i = ingredient("Leche", "10");
        long p = product("Vaso", "20", 0, 0, i);
        sale(p, 1, "EFECTIVO", "20", "0", null, null, false);
        amount("8", money("select stock_actual from insumo where id=?", i));
        mvc.perform(post("/insumos/guardar").with(admin()).with(csrf())
                .param("id", Long.toString(i)).param("nombre", "Leche nueva")
                .param("unidadMedida", "unidad").param("costoUnitario", "5")
                .param("stockActual", "10").param("stockMinimo", "0"))
                .andExpect(redirectedUrl("/insumos"));
        amount("8", money("select stock_actual from insumo where id=?", i));
        assertThat(db.queryForObject("select nombre from insumo where id=?", String.class, i)).isEqualTo("Leche nueva");
    }

    @Test
    void puntosYCreditoRechazanSaldosInvalidos() throws Exception {
        long i = ingredient("Fruta", "10");
        long p = product("Jugo", "15", 0, 5, i);
        long c = id("insert into clientes(nombre,tipo,puntos,saldo_credito,limite_credito,total_gastado,fecha_registro,activo) " +
                "values ('C','VIP',3,0,100,0,now(),true) returning id");
        MvcResult noPoints = sale(p, 1, "EFECTIVO", "0", "0", c, null, true);
        assertThat(noPoints.getFlashMap().get("mensajeError").toString()).contains("puntos suficientes");
        assertThat(count("ventas")).isZero();
        amount("10", money("select stock_actual from insumo where id=?", i));
        for (String points : List.of("-4", "2147483647")) {
            mvc.perform(post("/clientes/" + c + "/puntos").with(admin()).with(csrf()).param("puntos", points))
                    .andExpect(redirectedUrl("/clientes"));
            assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isEqualTo(3);
        }
        for (String amount : List.of("-1", "0", "100.001", "101")) {
            mvc.perform(post("/clientes/" + c + "/credito/consumir").with(admin()).with(csrf()).param("monto", amount))
                    .andExpect(redirectedUrl("/clientes"));
            amount("0", money("select saldo_credito from clientes where id=?", c));
        }
        mvc.perform(post("/clientes/" + c + "/credito/consumir").with(admin()).with(csrf()).param("monto", "30"))
                .andExpect(redirectedUrl("/clientes"));
        amount("30", money("select saldo_credito from clientes where id=?", c));
        mvc.perform(post("/clientes/" + c + "/credito/abonar").with(admin()).with(csrf()).param("abono", "31"))
                .andExpect(redirectedUrl("/clientes"));
        amount("30", money("select saldo_credito from clientes where id=?", c));
        mvc.perform(post("/clientes/" + c + "/credito/abonar").with(admin()).with(csrf()).param("abono", "20"))
                .andExpect(redirectedUrl("/clientes"));
        amount("10", money("select saldo_credito from clientes where id=?", c));
    }

    @Test
    void splitActualSonDosVentasYUnaMesaSeEnsuciaEnLaPrimera() throws Exception {
        long i = ingredient("Arroz", "20");
        long p = product("Bowl", "40", 0, 0, i);
        long mesa = id("insert into mesas(numero,capacidad,estado,activo) values (1,4,'OCUPADA',true) returning id");
        sale(p, 1, "EFECTIVO", "40", "0", null, mesa, false);
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("SUCIA");
        sale(p, 1, "TARJETA", "0", "40", null, mesa, false);
        assertThat(count("ventas")).isEqualTo(2);
        assertThat(count("venta_detalles")).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from ventas where mesa_id=?", Long.class, mesa)).isEqualTo(2);
        amount("16", money("select stock_actual from insumo where id=?", i));
    }

    @Test
    void liberarMesaRequiereDecisionYDescartarConfirmaContexto() throws Exception {
        long mesa = id("insert into mesas(numero,capacidad,estado,activo) values (1,4,'OCUPADA',true) returning id");
        String mapa = mvc.perform(get("/mesas").with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapa).contains("/js/pos-cart-context.js", "data-mesa-id=\"" + mesa + "\"",
                "name=\"decisionLiberacion\"");
        String pos = mvc.perform(get("/pos").with(admin()).param("mesaId", Long.toString(mesa)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(pos).contains("/js/pos-cart-context.js", "name=\"mesaId\"");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "LIBRE").param("decisionLiberacion", "SIN_CARRITO"))
                .andExpect(redirectedUrl("/mesas"))
                .andExpect(flash().attribute("mensajeError",
                        "Transición de mesa no permitida: OCUPADA → LIBRE."));
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "NO_EXISTE"))
                .andExpect(redirectedUrl("/mesas"))
                .andExpect(flash().attribute("mensajeError",
                        "Transición de mesa no permitida: OCUPADA → NO_EXISTE."));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("OCUPADA");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "SUCIA"))
                .andExpect(redirectedUrl("/mesas"))
                .andExpect(flash().attributeExists("mensajeError"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("OCUPADA");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "SUCIA").param("decisionLiberacion", "DESCARTAR"))
                .andExpect(redirectedUrl("/mesas"))
                .andExpect(flash().attribute("carritoDescartadoMesaId", mesa));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("SUCIA");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "LIBRE"))
                .andExpect(flash().attributeExists("mensajeError"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("SUCIA");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "LIBRE").param("decisionLiberacion", "SIN_CARRITO"))
                .andExpect(redirectedUrl("/mesas"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("LIBRE");
        db.update("update mesas set estado='OCUPADA' where id=?", mesa);
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "SUCIA").param("decisionLiberacion", "SIN_CARRITO"))
                .andExpect(redirectedUrl("/mesas"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("SUCIA");

        long reservada = id("insert into mesas(numero,capacidad,estado,activo) values (2,4,'RESERVADA',true) returning id");
        mvc.perform(post("/mesas/" + reservada + "/estado").with(admin()).with(csrf())
                .param("estado", "OCUPADA"))
                .andExpect(redirectedUrl("/mesas"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, reservada)).isEqualTo("OCUPADA");
        db.update("update mesas set estado='RESERVADA' where id=?", reservada);
        mvc.perform(post("/mesas/" + reservada + "/estado").with(admin()).with(csrf())
                .param("estado", "LIBRE"))
                .andExpect(flash().attributeExists("mensajeError"));
        // Un estado RESERVADA heredado sin reserva confirmada activa se muestra libre;
        // el formulario de estado no debe cancelar una reserva inexistente.
        assertMesaMapa(reservada, "LIBRE", 1L, 0L);
    }

    @Test
    void puntosCanjeYCliente() throws Exception {
        long i = ingredient("Fruta", "30");
        long p = product("Jugo", "15", 3, 10, i);
        mvc.perform(post("/clientes/guardar").with(admin()).with(csrf())
                .param("nombre", "Cliente sintético").param("tipo", "VIP")
                .param("puntos", "20").param("limiteCredito", "100"))
                .andExpect(redirectedUrl("/clientes"));
        long c = id("select id from clientes where nombre='Cliente sintético'");
        sale(p, 1, "EFECTIVO", "15", "0", c, null, false);
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isEqualTo(23);
        amount("15", money("select total_gastado from clientes where id=?", c));
        sale(p, 1, "EFECTIVO", "0", "0", c, null, true);
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isEqualTo(13);
        amount("0", money("select total from ventas where id=2"));
        assertThat(db.queryForObject("select canjeado from venta_detalles where venta_id=2", Boolean.class)).isTrue();
        MvcResult missing = sale(p, 1, "EFECTIVO", "0", "0", null, null, true);
        assertThat(missing.getFlashMap().get("mensajeError").toString()).contains("cliente");
        assertThat(count("ventas")).isEqualTo(2);
        mvc.perform(post("/clientes/" + c + "/puntos").with(admin()).with(csrf()).param("puntos", "5"))
                .andExpect(redirectedUrl("/clientes"));
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isEqualTo(18);
    }

    @Test
    void kdsAceptaEstadosArbitrariosHeredados() throws Exception {
        long i = ingredient("Hielo", "10");
        long p = product("Granizado", "20", 0, 0, i);
        sale(p, 1, "EFECTIVO", "20", "0", null, null, false);
        long detail = id("select id from venta_detalles");
        mvc.perform(post("/kds/actualizar-estado").with(admin()).with(csrf())
                .param("id", Long.toString(detail)).param("estado", "PREPARANDO"))
                .andExpect(redirectedUrl("/kds"));
        mvc.perform(get("/kds/api/datos").with(admin()))
                .andExpect(jsonPath("$.stats.preparando").value(1));
        mvc.perform(post("/kds/actualizar-estado").with(admin()).with(csrf())
                .param("id", Long.toString(detail)).param("estado", "CUALQUIERA"))
                .andExpect(redirectedUrl("/kds"));
        assertThat(db.queryForObject("select estado from venta_detalles", String.class)).isEqualTo("CUALQUIERA");
        mvc.perform(get("/kds/api/datos").with(admin()))
                .andExpect(jsonPath("$.stats.pendientes").value(0))
                .andExpect(jsonPath("$.stats.preparando").value(0));
    }

    @Test
    void mesasReservasConflictoYEstados() throws Exception {
        mvc.perform(post("/mesas/guardar").with(admin()).with(csrf())
                .param("numero", "7").param("capacidad", "4").param("zona", "Terraza"))
                .andExpect(redirectedUrl("/mesas"));
        long table = id("select id from mesas where numero=7");
        mvc.perform(post("/mesas/guardar").with(admin()).with(csrf())
                .param("numero", "7").param("capacidad", "4"))
                .andExpect(redirectedUrl("/mesas"));
        assertThat(count("mesas")).isEqualTo(1);
        String at = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0).toString();
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("nombreCliente", "Invitado sintético").param("fechaHora", at)
                .param("numeroPersonas", "2").param("mesa.id", Long.toString(table)))
                .andExpect(redirectedUrl("/reservas"));
        assertThat(count("reservas")).isEqualTo(1);
        long booking = id("select id from reservas");
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("nombreCliente", "Otro invitado").param("fechaHora", at)
                .param("numeroPersonas", "2").param("mesa.id", Long.toString(table)))
                .andExpect(redirectedUrl("/reservas"));
        // Las pendientes no bloquean la disponibilidad ni entre sí.
        assertThat(count("reservas")).isEqualTo(2);
        mvc.perform(post("/reservas/" + booking + "/estado").with(admin()).with(csrf())
                .param("estado", "CONFIRMADA")).andExpect(redirectedUrl("/reservas"));
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("nombreCliente", "Tercer invitado").param("fechaHora", at)
                .param("numeroPersonas", "2").param("mesa.id", Long.toString(table)))
                .andExpect(redirectedUrl("/reservas"));
        assertThat(count("reservas")).isEqualTo(3);
        mvc.perform(post("/reservas/" + booking + "/estado").with(admin()).with(csrf())
                .param("estado", "EN_CURSO")).andExpect(redirectedUrl("/reservas"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, table))
                .isEqualTo("OCUPADA");
        mvc.perform(get("/reservas/eventos").with(admin()))
                .andExpect(jsonPath("$[?(@.id == " + booking + ")].extendedProps.estado")
                        .value(org.hamcrest.Matchers.hasItem("EN_CURSO")));
    }

    @Test
    void disponibilidadMesaUsaMismaReglaEnResumenYTarjeta() throws Exception {
        long mesa = id("insert into mesas(numero,capacidad,estado,activo) values (21,4,'LIBRE',true) returning id");
        LocalDateTime ahora = LocalDateTime.now();
        long pendiente = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('Pendiente',?,2,'PENDIENTE','TELEFONO',?,?) returning id",
                ahora.plusMinutes(10), ahora, mesa);
        assertMesaMapa(mesa, "LIBRE", 1L, 0L);
        long confirmada = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('Futura',?,2,'CONFIRMADA','TELEFONO',?,?) returning id",
                ahora.plusMinutes(90), ahora, mesa);
        assertMesaMapa(mesa, "LIBRE", 1L, 0L);
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "OCUPADA")).andExpect(flash().attributeExists("mensajeExito"));
        assertMesaMapa(mesa, "OCUPADA", 0L, 0L);
        assertThat(db.queryForObject("select estado from reservas where id=?", String.class, confirmada))
                .isEqualTo("CONFIRMADA");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "SUCIA").param("decisionLiberacion", "SIN_CARRITO"));
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "LIBRE").param("decisionLiberacion", "SIN_CARRITO"));
        mvc.perform(post("/reservas/actualizar-horario").with(admin()).with(csrf())
                .param("id", Long.toString(confirmada)).param("nuevaFecha", ahora.plusMinutes(20).toString()))
                .andExpect(jsonPath("$.status").value("success"));
        assertMesaMapa(mesa, "RESERVADA", 0L, 1L);
        mvc.perform(post("/reservas/actualizar-horario").with(admin()).with(csrf())
                .param("id", Long.toString(confirmada)).param("nuevaFecha", ahora.plusMinutes(90).toString()))
                .andExpect(jsonPath("$.status").value("success"));
        assertMesaMapa(mesa, "LIBRE", 1L, 0L);
        mvc.perform(post("/reservas/actualizar-horario").with(admin()).with(csrf())
                .param("id", Long.toString(confirmada)).param("nuevaFecha", ahora.plusMinutes(20).toString()))
                .andExpect(jsonPath("$.status").value("success"));
        assertMesaMapa(mesa, "RESERVADA", 0L, 1L);
        db.update("update mesas set estado='OCUPADA' where id=?", mesa);
        assertMesaMapa(mesa, "OCUPADA", 0L, 0L);
        db.update("update mesas set estado='LIBRE' where id=?", mesa);
        mvc.perform(post("/reservas/" + confirmada + "/estado").with(admin()).with(csrf())
                .param("estado", "CANCELADA")).andExpect(redirectedUrl("/reservas"));
        assertMesaMapa(mesa, "LIBRE", 1L, 0L);
        assertThat(db.queryForObject("select estado from reservas where id=?", String.class, pendiente)).isEqualTo("PENDIENTE");
    }

    private void assertMesaMapa(long mesa, String estado, long libres, long reservadas) throws Exception {
        String html = mvc.perform(get("/mesas").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("libres", libres))
                .andExpect(model().attribute("reservadas", reservadas))
                .andReturn().getResponse().getContentAsString();
        int inicio = html.indexOf("id=\"mesa-" + mesa + "\"");
        assertThat(inicio).isGreaterThanOrEqualTo(0);
        String tarjeta = html.substring(inicio, html.indexOf("<!-- Número grande -->", inicio));
        assertThat(tarjeta).contains("badge-" + estado.toLowerCase(), ">" + estado + "</div>");
    }

    @Test
    void editarReservaValidaConflictosYRecalculaDisponibilidad() throws Exception {
        long mesa = id("insert into mesas(numero,capacidad,estado,activo) values (22,4,'LIBRE',true) returning id");
        long otra = id("insert into mesas(numero,capacidad,estado,activo) values (23,4,'LIBRE',true) returning id");
        LocalDateTime fecha = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        long fija = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('Fija',?,2,'CONFIRMADA','TELEFONO',?,?) returning id", fecha, LocalDateTime.now(), mesa);
        long editable = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('Editable',?,2,'CONFIRMADA','TELEFONO',?,?) returning id", fecha.plusHours(3), LocalDateTime.now(), otra);
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("id", Long.toString(editable)).param("nombreCliente", "Editable")
                .param("fechaHora", fecha.toString()).param("numeroPersonas", "2")
                .param("estado", "CONFIRMADA").param("mesa.id", Long.toString(mesa)))
                .andExpect(redirectedUrl("/reservas"))
                .andExpect(flash().attributeExists("error"));
        assertThat(id("select mesa_id from reservas where id=?", editable)).isEqualTo(otra);
        mvc.perform(post("/reservas/actualizar-horario").with(admin()).with(csrf())
                .param("id", Long.toString(editable)).param("nuevaFecha", fecha.toString()))
                .andExpect(jsonPath("$.status").value("success"));
        mvc.perform(post("/reservas/" + fija + "/estado").with(admin()).with(csrf())
                .param("estado", "CANCELADA")).andExpect(redirectedUrl("/reservas"));
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("id", Long.toString(editable)).param("nombreCliente", "Editable")
                .param("fechaHora", fecha.toString()).param("numeroPersonas", "2")
                .param("estado", "CONFIRMADA").param("mesa.id", Long.toString(mesa)))
                .andExpect(redirectedUrl("/reservas"))
                .andExpect(flash().attributeExists("success"));
        assertThat(id("select mesa_id from reservas where id=?", editable)).isEqualTo(mesa);
    }

    @Test
    void transicionesMesaYReservaRechazanEstadosInvalidos() throws Exception {
        long mesa = id("insert into mesas(numero,capacidad,estado,activo) values (24,4,'LIBRE',true) returning id");
        long reserva = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('Invitado',?,2,'PENDIENTE','TELEFONO',?,?) returning id",
                LocalDateTime.now().plusMinutes(15), LocalDateTime.now(), mesa);
        mvc.perform(post("/reservas/" + reserva + "/estado").with(admin()).with(csrf())
                .param("estado", "EN_CURSO")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/reservas/" + reserva + "/estado").with(admin()).with(csrf())
                .param("estado", "ARBITRARIO")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/reservas/" + reserva + "/estado").with(admin()).with(csrf())
                .param("estado", "CONFIRMADA")).andExpect(flash().attributeExists("success"));
        assertMesaMapa(mesa, "RESERVADA", 0L, 1L);
        assertThat(mvc.perform(get("/mesas").with(admin())).andReturn().getResponse().getContentAsString())
                .contains("name=\"reservaId\" value=\"" + reserva + "\"");
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "LIBRE")).andExpect(flash().attributeExists("mensajeError"));
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "OCUPADA")).andExpect(flash().attributeExists("mensajeError"));
        mvc.perform(post("/mesas/" + mesa + "/estado").with(admin()).with(csrf())
                .param("estado", "OCUPADA").param("reservaId", Long.toString(reserva)))
                .andExpect(flash().attributeExists("mensajeExito"));
        assertThat(db.queryForObject("select estado from reservas where id=?", String.class, reserva)).isEqualTo("EN_CURSO");
        mvc.perform(post("/reservas/" + reserva + "/estado").with(admin()).with(csrf())
                .param("estado", "CONFIRMADA")).andExpect(flash().attributeExists("error"));
        long otra = id("insert into mesas(numero,capacidad,estado,activo) values (25,4,'LIBRE',true) returning id");
        mvc.perform(post("/reservas/guardar").with(admin()).with(csrf())
                .param("id", Long.toString(reserva)).param("nombreCliente", "Invitado")
                .param("fechaHora", LocalDateTime.now().plusMinutes(15).toString())
                .param("numeroPersonas", "2").param("estado", "EN_CURSO")
                .param("mesa.id", Long.toString(otra)))
                .andExpect(flash().attributeExists("error"));
        assertThat(id("select mesa_id from reservas where id=?", reserva)).isEqualTo(mesa);
        mvc.perform(post("/mesas/guardar").with(admin()).with(csrf())
                .param("id", Long.toString(mesa)).param("numero", "24")
                .param("capacidad", "4").param("estado", "LIBRE"))
                .andExpect(flash().attributeExists("mensajeError"));
        assertThat(db.queryForObject("select estado from mesas where id=?", String.class, mesa)).isEqualTo("OCUPADA");
    }

    @Test
    void confirmacionesConcurrentesDeLaMismaMesaNoCreanConflicto() throws Exception {
        long mesa = id("insert into mesas(numero,capacidad,estado,activo) values (26,4,'LIBRE',true) returning id");
        LocalDateTime fecha = LocalDateTime.now().plusDays(1);
        long a = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('A',?,2,'PENDIENTE','TELEFONO',?,?) returning id", fecha, LocalDateTime.now(), mesa);
        long b = id("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) " +
                "values ('B',?,2,'PENDIENTE','TELEFONO',?,?) returning id", fecha, LocalDateTime.now(), mesa);
        var pool = Executors.newFixedThreadPool(2);
        var inicio = new CountDownLatch(1);
        try {
            var primera = pool.submit(() -> {
                inicio.await();
                try { SyntheticSecurity.runAs(db, id("select id from usuario where username='admin'"), () -> reservaService.cambiarEstado(a, "CONFIRMADA")); return true; }
                catch (IllegalArgumentException e) { return false; }
            });
            var segunda = pool.submit(() -> {
                inicio.await();
                try { SyntheticSecurity.runAs(db, id("select id from usuario where username='admin'"), () -> reservaService.cambiarEstado(b, "CONFIRMADA")); return true; }
                catch (IllegalArgumentException e) { return false; }
            });
            inicio.countDown();
            assertThat(List.of(primera.get(10, TimeUnit.SECONDS), segunda.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(db.queryForObject("select count(*) from reservas where mesa_id=? and estado='CONFIRMADA'",
                    Long.class, mesa)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cajaAperturaCierreDescuentaCambio() throws Exception {
        db.update("delete from corte_caja");
        mvc.perform(post("/caja/abrir").with(admin()).with(csrf()).param("montoInicial", "-1"))
                .andExpect(redirectedUrl("/caja"));
        assertThat(count("corte_caja")).isZero();
        mvc.perform(post("/caja/abrir").with(admin()).with(csrf()).param("montoInicial", "100"))
                .andExpect(redirectedUrl("/caja"));
        mvc.perform(post("/caja/abrir").with(admin()).with(csrf()).param("montoInicial", "100"))
                .andExpect(redirectedUrl("/caja"));
        assertThat(count("corte_caja")).isEqualTo(1);
        long i = ingredient("Azúcar", "10");
        long p = product("Dulce", "20", 0, 0, i);
        sale(p, 1, "EFECTIVO", "25", "0", null, null, false);
        long cashbox = id("select id from corte_caja");
        mvc.perform(post("/caja/cerrar/" + cashbox).with(admin()).with(csrf())
                .param("montoContado", "-1")).andExpect(redirectedUrl("/caja"));
        assertThat(db.queryForObject("select estado from corte_caja", String.class)).isEqualTo("ABIERTO");
        mvc.perform(post("/caja/cerrar/" + cashbox).with(admin()).with(csrf())
                .param("montoContado", "120")).andExpect(redirectedUrl("/caja"));
        assertThat(db.queryForObject("select estado from corte_caja", String.class)).isEqualTo("CERRADO");
        amount("20", money("select total_efectivo from corte_caja"));
        amount("0", money("select diferencia from corte_caja"));
        mvc.perform(post("/caja/cerrar/" + cashbox).with(admin()).with(csrf())
                .param("montoContado", "999")).andExpect(redirectedUrl("/caja"));
        amount("120", money("select monto_contado from corte_caja"));
    }

    @Test
    void cobrosEfectivoTarjetaMixtoQuedanEnTurnoYElCorteSoloSumaSusVentas() throws Exception {
        long turno = id("select id from corte_caja where estado='ABIERTO'");
        long i = ingredient("Caja", "30");
        long p = product("Cobro", "20", 0, 0, i);
        for (String[] payment : List.of(new String[]{"EFECTIVO", "25", "0"},
                new String[]{"TARJETA", "0", "20"}, new String[]{"MIXTO", "8", "12"})) {
            assertThat(sale(p, 1, payment[0], payment[1], payment[2], null, null, false)
                    .getFlashMap().get("mensajeExito")).isNotNull();
        }
        assertThat(db.queryForList("select corte_caja_id from ventas order by id", Long.class))
                .containsExactly(turno, turno, turno);
        assertThat(db.queryForList("select legacy_sin_turno from ventas order by id", Boolean.class))
                .containsExactly(false, false, false);
        mvc.perform(get("/caja/cerrar/" + turno).with(admin())).andExpect(status().isOk())
                .andExpect(model().attribute("totalVentas", new BigDecimal("60.00")));
        mvc.perform(post("/caja/cerrar/" + turno).with(admin()).with(csrf())
                .param("montoContado", "128")).andExpect(redirectedUrl("/caja"));
        amount("28", money("select total_efectivo from corte_caja where id=?", turno));
        amount("32", money("select total_tarjeta from corte_caja where id=?", turno));
        amount("60", money("select total_ventas from corte_caja where id=?", turno));
    }

    @Test
    void historialPosEnlazaAlTicketDeLaVenta() throws Exception {
        long i = ingredient("Ticket historial", "10");
        long p = product("Ticket historial", "20", 0, 0, i);
        sale(p, 1, "EFECTIVO", "20", "0", null, null, false);
        long saleId = id("select id from ventas");

        mvc.perform(get("/pos/historial").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/pos/ticket/" + saleId + "\"")));
        mvc.perform(get("/pos/ticket/" + saleId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("venta", org.hamcrest.Matchers.hasProperty("id", org.hamcrest.Matchers.is(saleId))));
    }

    @Test
    void panelMuestraTotalProvisionalAutorizadoDelTurnoAbierto() throws Exception {
        long turno = id("select id from corte_caja where estado='ABIERTO'");
        long i = ingredient("Panel caja", "30");
        long p = product("Venta panel", "20", 0, 0, i);
        sale(p, 1, "EFECTIVO", "25", "0", null, null, false);
        sale(p, 1, "MIXTO", "8", "12", null, null, false);

        mvc.perform(get("/caja").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalVentasActivo", new BigDecimal("40.00")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("$40.00")));
        assertThat(db.queryForObject("select total_ventas from corte_caja where id=?", BigDecimal.class, turno))
                .isEqualByComparingTo("0.00");
        mvc.perform(get("/caja/cerrar/" + turno).with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalVentas", new BigDecimal("40.00")));
    }

    @Test
    void canjeSinCobroConservaContadorVisibleSinAtribucionMonetaria() throws Exception {
        long turno = id("select id from corte_caja where estado='ABIERTO'");
        long i = ingredient("Canje", "10");
        long p = product("Canje", "20", 0, 5, i);
        long c = id("insert into clientes(nombre,tipo,puntos,saldo_credito,limite_credito,total_gastado,fecha_registro,activo) " +
                "values ('Canje','NORMAL',5,0,0,0,now(),true) returning id");
        assertThat(sale(p, 1, "EFECTIVO", "0", "0", c, null, true)
                .getFlashMap().get("mensajeExito")).isNotNull();
        assertThat(db.queryForObject("select corte_caja_id from ventas", Long.class)).isNull();
        assertThat(db.queryForObject("select count(*) from ventas where total=0 and corte_caja_id is null", Long.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from ventas where total=0 and corte_caja_id is null " +
                "and fecha between (select fecha_apertura from corte_caja where id=?) and now()", Long.class, turno))
                .isEqualTo(1);
        mvc.perform(get("/caja/cerrar/" + turno).with(admin())).andExpect(status().isOk())
                .andExpect(model().attribute("totalVentas", BigDecimal.ZERO))
                .andExpect(model().attribute("ticketsTurno", 1L));
    }

    @Test
    void sinTurnoNoHayCobroNiEfectosYNoSeReasignaAlSiguiente() throws Exception {
        db.update("delete from corte_caja");
        long i = ingredient("Sin turno", "10");
        long p = product("Cobro", "20", 3, 0, i);
        long c = id("insert into clientes(nombre,tipo,puntos,saldo_credito,limite_credito,total_gastado,fecha_registro,activo) " +
                "values ('Cliente caja','NORMAL',0,0,0,0,now(),true) returning id");
        MvcResult rejected = sale(p, 1, "EFECTIVO", "20", "0", c, null, false);
        assertThat(rejected.getFlashMap().get("mensajeError").toString()).contains("Abre un turno de caja");
        assertThat(count("ventas")).isZero();
        assertThat(count("movimiento_inventario")).isZero();
        assertThat(count("pos_sale_request")).isZero();
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, c)).isZero();
        amount("10", money("select stock_actual from insumo where id=?", i));
        mvc.perform(post("/caja/abrir").with(admin()).with(csrf()).param("montoInicial", "0"))
                .andExpect(redirectedUrl("/caja"));
        assertThat(count("ventas")).isZero();
        assertThat(sale(p, 1, "EFECTIVO", "20", "0", c, null, false)
                .getFlashMap().get("mensajeExito")).isNotNull();
        assertThat(db.queryForObject("select corte_caja_id from ventas", Long.class))
                .isEqualTo(id("select id from corte_caja"));
    }

    @Test
    void turnosConsecutivosEIdempotenciaNoMezclanVentas() throws Exception {
        long a = id("select id from corte_caja where estado='ABIERTO'");
        long i = ingredient("Turnos", "20");
        long p = product("Cobro", "20", 0, 0, i);
        String key = UUID.randomUUID().toString();
        MvcResult first = saleWithKey(p, 1, "EFECTIVO", "25", "0", null, null, false, key);
        assertThat(first.getFlashMap().get("mensajeExito")).isNotNull();
        mvc.perform(post("/caja/cerrar/" + a).with(admin()).with(csrf())
                .param("montoContado", "120")).andExpect(redirectedUrl("/caja"));
        assertThat(saleWithKey(p, 1, "EFECTIVO", "25", "0", null, null, false, key)
                .getFlashMap().get("ultimoTicketId")).isEqualTo(first.getFlashMap().get("ultimoTicketId"));
        MvcResult between = sale(p, 1, "TARJETA", "0", "20", null, null, false);
        assertThat(between.getFlashMap().get("mensajeError").toString()).contains("Abre un turno");
        mvc.perform(post("/caja/abrir").with(admin()).with(csrf()).param("montoInicial", "0"))
                .andExpect(redirectedUrl("/caja"));
        long b = id("select id from corte_caja where estado='ABIERTO'");
        assertThat(b).isNotEqualTo(a);
        assertThat(sale(p, 1, "TARJETA", "0", "20", null, null, false)
                .getFlashMap().get("mensajeExito")).isNotNull();
        assertThat(db.queryForList("select corte_caja_id from ventas order by id", Long.class))
                .containsExactly(a, b);
        assertThat(count("pos_sale_request")).isEqualTo(2);
        mvc.perform(post("/caja/cerrar/" + b).with(admin()).with(csrf())
                .param("montoContado", "0")).andExpect(redirectedUrl("/caja"));
        amount("20", money("select total_ventas from corte_caja where id=?", a));
        amount("20", money("select total_ventas from corte_caja where id=?", b));
    }

    @Test
    void cobroYcierreConcurrentesNuncaDejanVentaHuerfana() throws Exception {
        long turno = id("select id from corte_caja where estado='ABIERTO'");
        long i = ingredient("Carrera", "10");
        long p = product("Cobro", "20", 0, 0, i);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> charge = pool.submit(() -> {
                start.await();
                return sale(p, 1, "EFECTIVO", "20", "0", null, null, false);
            });
            Future<MvcResult> close = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/caja/cerrar/" + turno).with(admin()).with(csrf())
                        .param("montoContado", "100")).andExpect(redirectedUrl("/caja")).andReturn();
            });
            start.countDown();
            MvcResult chargeResult = charge.get(15, TimeUnit.SECONDS);
            assertThat(close.get(15, TimeUnit.SECONDS).getFlashMap().get("mensajeExito")).isNotNull();
            boolean accepted = chargeResult.getFlashMap().get("mensajeExito") != null;
            assertThat(count("ventas")).isEqualTo(accepted ? 1 : 0);
            assertThat(db.queryForObject("select count(*) from ventas where corte_caja_id=?", Long.class, turno))
                    .isEqualTo(accepted ? 1 : 0);
            amount(accepted ? "20" : "0", money("select total_ventas from corte_caja where id=?", turno));
            assertThat(db.queryForObject("select estado from corte_caja where id=?", String.class, turno))
                    .isEqualTo("CERRADO");
            if (!accepted) assertThat(chargeResult.getFlashMap().get("mensajeError").toString())
                    .contains("Abre un turno de caja");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mermaValidaErroresYPronosticoBasico() throws Exception {
        long i = ingredient("Tomate", "20");
        long p = product("Ensalada", "30", 0, 0, i);
        sale(p, 1, "EFECTIVO", "30", "0", null, null, false);
        mvc.perform(post("/movimientos/merma/guardar").with(admin()).with(csrf())
                .param("insumo.id", Long.toString(i)).param("cantidad", "3")
                .param("comentario", "Caducidad sintética"))
                .andExpect(status().isOk());
        amount("15", money("select stock_actual from insumo where id=?", i));
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where tipo_movimiento='MERMA'", Long.class))
                .isEqualTo(1);
        mvc.perform(post("/movimientos/merma/guardar").with(admin()).with(csrf())
                .param("insumo.id", Long.toString(i)).param("cantidad", "2"))
                .andExpect(status().isOk());
        assertThat(count("movimiento_inventario")).isEqualTo(2);
        mvc.perform(post("/movimientos/merma/guardar").with(admin()).with(csrf())
                .param("insumo.id", Long.toString(i)).param("cantidad", "100")
                .param("comentario", "Prueba de exceso"))
                .andExpect(status().isOk());
        amount("15", money("select stock_actual from insumo where id=?", i));
        mvc.perform(get("/ia/compras/api").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.recomendaciones[0].nombreInsumo").value("Tomate"))
                .andExpect(jsonPath("$.estadisticas").exists());
    }
}
