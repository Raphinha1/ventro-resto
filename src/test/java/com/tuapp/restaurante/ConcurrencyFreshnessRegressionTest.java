package com.tuapp.restaurante;

import com.tuapp.restaurante.controllers.ConfiguracionController;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import com.tuapp.restaurante.repositories.ReservaRepository;
import com.tuapp.restaurante.services.UsuarioService;
import com.tuapp.restaurante.services.ReservaService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real PostgreSQL transactions; latches and pg_blocking_pids establish the schedule. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "spring.jpa.open-in-view=true"})
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class ConcurrencyFreshnessRegressionTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired UsuarioService users;
    @Autowired ReservaService reservations;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean ConfiguracionController configController;
    @SpyBean UsuarioRepository userRepository;
    @SpyBean ReservaRepository reservationRepository;
    @PersistenceContext EntityManager entities;
    @LocalServerPort int port;

    private void fixtures() {
        var fixture = new SecurityHttpRegressionTest();
        fixture.db = db;
        fixture.syntheticFixtures();
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(15, TimeUnit.SECONDS)).as("coordinated transaction reached boundary").isTrue();
    }

    private void as(String role, Runnable action) {
        long actor = db.queryForObject("select id from usuario where username=?", Long.class, role.toLowerCase());
        SyntheticSecurity.runAs(db, actor, action);
    }

    private record Session(HttpClient client, String token) {}
    private Session login(String username) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String page = client.send(HttpRequest.newBuilder(URI.create(base()+"/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String token = httpCsrf(page);
        var result = httpPost(client, "/login", Map.of("username", username, "password", "synthetic-http-pass", "_csrf", token));
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("location").orElse("")).doesNotContain("error");
        page = client.send(HttpRequest.newBuilder(URI.create(base()+"/configuracion")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return new Session(client, httpCsrf(page));
    }
    private String httpCsrf(String page) {
        var inputs = Pattern.compile("<input[^>]+>").matcher(page);
        while (inputs.find()) {
            if (inputs.group().contains("name=\"_csrf\"")) {
                var value = Pattern.compile("value=\"([^\"]+)\"").matcher(inputs.group());
                assertThat(value.find()).isTrue();
                return value.group(1);
            }
        }
        throw new AssertionError("CSRF input missing");
    }
    private String base() { return "http://127.0.0.1:"+port; }
    private HttpResponse<String> httpPost(HttpClient client, String path, Map<String,String> params) throws Exception {
        String form = params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                +"="+URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(URI.create(base()+path))
                .timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void realHttpOsivPreloadCannotLoseConcurrentConfiguration(boolean masterIsStale) throws Exception {
        fixtures();
        db.update("update usuario set password=?", passwords.encode("synthetic-http-pass"));
        Session admin = login("admin"), root = login("super_admin");
        Session stale = masterIsStale ? root : admin;
        Session winner = masterIsStale ? admin : root;
        String pausedActor = masterIsStale ? "super_admin" : "admin";
        var preloaded = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (SecurityContextHolder.getContext().getAuthentication().getName().equals(pausedActor)) {
                // ControllerAdvice has already run on this actual servlet request.
                var cached = entities.find(com.tuapp.restaurante.models.ConfiguracionSistema.class, 1L);
                assertThat(entities.contains(cached)).isTrue();
                assertThat(cached.getLimiteUsuarios()).isEqualTo(50);
                assertThat(cached.getNombreNegocio()).isEqualTo("Synthetic business");
                preloaded.countDown();
                await(resume);
            }
            return invocation.callRealMethod();
        }).when(configController).guardar(any(), any(), any());
        Map<String,String> adminFields = Map.of("_csrf",admin.token(),"nombreNegocio","Synthetic HTTP operational");
        Map<String,String> rootFields = Map.of("_csrf",root.token(),"limiteUsuarios","77");
        try (var pool = Executors.newFixedThreadPool(1)) {
            var pending = pool.submit(() -> httpPost(stale.client(), "/configuracion/guardar",
                    masterIsStale ? rootFields : adminFields));
            try {
                await(preloaded);
                assertThat(httpPost(winner.client(),"/configuracion/guardar",masterIsStale ? adminFields : rootFields)
                        .statusCode()).isEqualTo(302);
                resume.countDown();
                assertThat(pending.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(302);
                assertThat(db.queryForObject("select limite_usuarios from configuracion_sistema where id=1",Integer.class)).isEqualTo(77);
                assertThat(db.queryForObject("select nombre_negocio from configuracion_sistema where id=1",String.class))
                        .isEqualTo("Synthetic HTTP operational");
            } finally { resume.countDown(); }
        } finally { reset(configController); }
    }

    @ParameterizedTest
    @ValueSource(strings={"update","deactivate","role"})
    void selfTargetPromotedBeforeLockCannotBeChangedByStaleAdmin(String operation) throws Exception {
        fixtures();
        long target = db.queryForObject("select id from usuario where username='admin'", Long.class);
        var beforeLock = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (SecurityContextHolder.getContext().getAuthentication().getName().equals("admin")) {
                assertThat(entities.find(Usuario.class,target).getRol()).isEqualTo("ROLE_ADMIN");
                beforeLock.countDown();
                await(resume);
            }
            return Optional.ofNullable(entities.find(Usuario.class,target,LockModeType.PESSIMISTIC_WRITE));
        }).when(userRepository).findByIdForUpdate(target);
        try (var pool = Executors.newFixedThreadPool(1)) {
            var attempted = pool.submit(() -> {
                var request = operation.equals("deactivate") ? post("/usuarios/eliminar/"+target) :
                        post("/usuarios/actualizar").param("id",""+target).param("username","admin")
                        .param("nombreCompleto","Forbidden concurrent edit").param("activo","true")
                        .param("rol",operation.equals("role") ? "GERENTE" : "ADMIN")
                        .param("nuevaPassword","synthetic-forbidden-password");
                return mvc.perform(request.with(user("admin").roles("ADMIN")).with(csrf())).andReturn();
            });
            try {
                await(beforeLock);
                var promoted = new Usuario(target,"admin","","Synthetic promoted account","SUPER_ADMIN");
                promoted.setActivo(true);
                as("SUPER_ADMIN", () -> users.actualizar(promoted,null,null,false));
                String protectedRow = db.queryForObject("select row_to_json(u)::text from usuario u where id=?",String.class,target);
                resume.countDown();
                assertThat(attempted.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
                assertThat(db.queryForObject("select row_to_json(u)::text from usuario u where id=?",String.class,target)).isEqualTo(protectedRow);
            } finally { resume.countDown(); }
        } finally { reset(userRepository); }
    }

    @ParameterizedTest
    @ValueSource(strings={"update","deactivate","role"})
    void ordinarySelfManagementRemainsAllowed(String operation) throws Exception {
        fixtures();
        long target = db.queryForObject("select id from usuario where username='admin'", Long.class);
        var request = operation.equals("deactivate") ? post("/usuarios/eliminar/"+target) :
                post("/usuarios/actualizar").param("id",""+target).param("username","admin")
                .param("nombreCompleto","Synthetic legitimate edit").param("activo","true")
                .param("rol",operation.equals("role") ? "GERENTE" : "ADMIN");
        var response = mvc.perform(request.with(user("admin").roles("ADMIN")).with(csrf())).andReturn();
        assertThat(response.getFlashMap().containsKey("mensajeExito")).isTrue();
        assertThat(db.queryForObject("select activo from usuario where id=?",Boolean.class,target))
                .isEqualTo(!operation.equals("deactivate"));
        assertThat(db.queryForObject("select rol from usuario where id=?",String.class,target))
                .isEqualTo(operation.equals("role") ? "ROLE_GERENTE" : "ROLE_ADMIN");
    }

    private long table(int number) {
        return db.queryForObject("insert into mesas(numero,capacidad,estado,activo) values(?,4,'LIBRE',true) returning id",Long.class,number);
    }
    private long reservation(long table, String state) {
        return db.queryForObject("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) "
                +"values('Synthetic guest',?,2,?,'TELEFONO',?,?) returning id",Long.class,
                LocalDateTime.now().plusMinutes(15),state,LocalDateTime.now(),table);
    }
    private com.tuapp.restaurante.models.Reserva moved(long id, long table) {
        var input = new com.tuapp.restaurante.models.Reserva();
        var ref = new com.tuapp.restaurante.models.Mesa(); ref.setId(table);
        input.setId(id); input.setMesa(ref); input.setNombreCliente("Synthetic moved guest");
        input.setFechaHora(LocalDateTime.now().plusDays(1)); input.setNumeroPersonas(2);
        return input;
    }

    @Test
    void changedAssociationIsRejectedWithoutOverwritingTheWinner() throws Exception {
        fixtures();
        long first = table(90), second = table(91), id = reservation(first,"PENDIENTE");
        var snapshotRead = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            Long result = db.queryForObject("select mesa_id from reservas where id=?",Long.class,id);
            if (SecurityContextHolder.getContext().getAuthentication().getName().equals("admin")) {
                snapshotRead.countDown(); await(resume);
            }
            return result;
        }).when(reservationRepository).findMesaId(id);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale = pool.submit(() -> as("ADMIN", () -> reservations.actualizarHorario(id,LocalDateTime.now().plusDays(3))));
            try {
                await(snapshotRead);
                as("SUPER_ADMIN", () -> reservations.saveReserva(moved(id,second)));
                String winner = db.queryForObject("select row_to_json(r)::text from reservas r where id=?",String.class,id);
                resume.countDown();
                assertThatThrownBy(() -> stale.get(15,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalArgumentException.class);
                assertThat(db.queryForObject("select row_to_json(r)::text from reservas r where id=?",String.class,id)).isEqualTo(winner);
            } finally { resume.countDown(); }
        } finally { reset(reservationRepository); }
    }

    @Test
    void oppositeTableMovesCompleteWithoutDeadlock() throws Exception {
        fixtures();
        long first = table(92), second = table(93);
        long left = reservation(first,"PENDIENTE"), right = reservation(second,"PENDIENTE");
        var snapshotsRead = new CountDownLatch(2);
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            Long result = db.queryForObject("select mesa_id from reservas where id=?",Long.class,id);
            snapshotsRead.countDown(); await(snapshotsRead);
            return result;
        }).when(reservationRepository).findMesaId(anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> as("ADMIN", () -> reservations.saveReserva(moved(left,second))));
            var b = pool.submit(() -> as("SUPER_ADMIN", () -> reservations.saveReserva(moved(right,first))));
            a.get(15,TimeUnit.SECONDS); b.get(15,TimeUnit.SECONDS);
            assertThat(db.queryForObject("select mesa_id from reservas where id=?",Long.class,left)).isEqualTo(second);
            assertThat(db.queryForObject("select mesa_id from reservas where id=?",Long.class,right)).isEqualTo(first);
        } finally { reset(reservationRepository); }
    }

    @Test
    void blockedTableDoesNotSerializeAnotherTable() throws Exception {
        fixtures();
        long first = table(94), second = table(95);
        long left = reservation(first,"PENDIENTE"), right = reservation(second,"PENDIENTE");
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var mutationStarted = new CountDownLatch(1); var pid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                db.queryForObject("select id from mesas where id=? for update",Long.class,first);
                locked.countDown();
                try { await(release); } catch (InterruptedException ex) { throw new IllegalStateException(ex); }
            }));
            try {
                await(locked);
                var blocked = pool.submit(() -> as("ADMIN", () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    pid.set(db.queryForObject("select pg_backend_pid()",Integer.class)); mutationStarted.countDown();
                    reservations.actualizarHorario(left,LocalDateTime.now().plusDays(2));
                })));
                await(mutationStarted); waitForBlock(pid.get());
                as("SUPER_ADMIN", () -> reservations.actualizarHorario(right,LocalDateTime.now().plusDays(2)));
                assertThat(blocked.isDone()).isFalse();
                release.countDown(); holder.get(15,TimeUnit.SECONDS); blocked.get(15,TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    @Test
    void seatingAndConcurrentCancellationUseTheSameLockOrder() throws Exception {
        fixtures();
        long table = table(96), id = reservation(table,"CONFIRMADA");
        var seatedRead = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var mutationStarted = new CountDownLatch(1); var pid = new AtomicInteger();
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            var rows = entities.createQuery("select r from Reserva r where r.fechaHora >= :now and r.estado in ('PENDIENTE','CONFIRMADA') order by r.fechaHora",
                    com.tuapp.restaurante.models.Reserva.class).setParameter("now",invocation.getArgument(0)).getResultList();
            if (SecurityContextHolder.getContext().getAuthentication().getName().equals("mesero") && once.compareAndSet(false,true)) {
                seatedRead.countDown(); await(resume);
            }
            return rows;
        }).when(reservationRepository).findProximasActivas(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var seating = pool.submit(() -> mvc.perform(post("/mesas/"+table+"/estado").with(user("mesero").roles("MESERO"))
                    .with(csrf()).param("estado","OCUPADA").param("reservaId",""+id)).andReturn());
            try {
                await(seatedRead);
                var cancellation = pool.submit(() -> as("ADMIN", () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    pid.set(db.queryForObject("select pg_backend_pid()",Integer.class)); mutationStarted.countDown();
                    reservations.cambiarEstado(id,"CANCELADA");
                })));
                await(mutationStarted); waitForBlock(pid.get()); resume.countDown();
                assertThat(seating.get(15,TimeUnit.SECONDS).getFlashMap().containsKey("mensajeExito")).isTrue();
                cancellation.get(15,TimeUnit.SECONDS);
                assertThat(db.queryForObject("select estado from reservas where id=?",String.class,id)).isEqualTo("CANCELADA");
                assertThat(db.queryForObject("select estado from mesas where id=?",String.class,table)).isEqualTo("OCUPADA");
            } finally { resume.countDown(); }
        } finally { reset(reservationRepository); }
    }

    private void waitForBlock(int pid) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(db.queryForObject("select cardinality(pg_blocking_pids(?)) > 0",Boolean.class,pid))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected PostgreSQL lock wait not observed");
    }

    @ParameterizedTest
    @ValueSource(strings={"schedule","state","delete","edit"})
    void tableDeletionAndExistingReservationMutationHaveNoDeadlock(String operation) throws Exception {
        fixtures();
        long table = db.queryForObject("insert into mesas(numero,capacidad,estado,activo) values(88,4,'LIBRE',true) returning id",Long.class);
        long reservation = db.queryForObject("insert into reservas(nombre_cliente,fecha_hora,numero_personas,estado,origen,fecha_creacion,mesa_id) "
                +"values('Synthetic concurrent guest',?,2,'PENDIENTE','TELEFONO',?,?) returning id",Long.class,
                LocalDateTime.now().plusDays(1),LocalDateTime.now(),table);
        long deadlocks = db.queryForObject("select deadlocks from pg_stat_database where datname=current_database()",Long.class);
        var tableLocked = new CountDownLatch(1);
        var unlink = new CountDownLatch(1);
        var mutationStarted = new CountDownLatch(1);
        var pid = new AtomicInteger();
        doAnswer(invocation -> {
            tableLocked.countDown();
            await(unlink);
            return true;
        }).when(reservationRepository).existsByMesaId(table);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> mvc.perform(post("/mesas/eliminar/"+table)
                    .with(user("admin").roles("ADMIN")).with(csrf())).andReturn());
            try {
                await(tableLocked);
                var mutation = pool.submit(() -> as("ADMIN", () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                    mutationStarted.countDown();
                    switch (operation) {
                        case "schedule" -> reservations.actualizarHorario(reservation,LocalDateTime.now().plusDays(2));
                        case "state" -> reservations.cambiarEstado(reservation,"CANCELADA");
                        case "delete" -> reservations.deleteReserva(reservation);
                        case "edit" -> {
                            var input = new com.tuapp.restaurante.models.Reserva();
                            var ref = new com.tuapp.restaurante.models.Mesa(); ref.setId(table); input.setMesa(ref);
                            input.setId(reservation); input.setNombreCliente("Synthetic concurrent edit");
                            input.setFechaHora(LocalDateTime.now().plusDays(2)); input.setNumeroPersonas(3);
                            reservations.saveReserva(input);
                        }
                        default -> throw new AssertionError(operation);
                    }
                })));
                await(mutationStarted);
                waitForBlock(pid.get());
                unlink.countDown();
                assertThat(deletion.get(15,TimeUnit.SECONDS).getFlashMap().containsKey("mensajeExito")).isTrue();
                // A changed association may be rejected for explicit retry; no partial update is allowed.
                try { mutation.get(15,TimeUnit.SECONDS); }
                catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
                    assertThat(ex.getCause().getMessage()).contains("reserva");
                }
                assertThat(db.queryForObject("select count(*) from mesas where id=?",Long.class,table)).isZero();
                assertThat(db.queryForObject("select count(*) from reservas where mesa_id=?",Long.class,table)).isZero();
                assertThat(db.queryForObject("select deadlocks from pg_stat_database where datname=current_database()",Long.class)).isEqualTo(deadlocks);
            } finally { unlink.countDown(); }
        } finally { reset(reservationRepository); }
    }
}
