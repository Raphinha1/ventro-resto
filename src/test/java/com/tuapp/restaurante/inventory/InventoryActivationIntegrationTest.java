package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.inventory.InventoryActivationService.Activation;
import com.tuapp.restaurante.inventory.InventoryActivationService.Command;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Evidence;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.StartingPoint;
import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.services.MovimientoInventarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Activación únicamente sobre PostgreSQL descartable y datos sintéticos. */
@SpringBootTest
@ActiveProfiles("slice0")
class InventoryActivationIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.parse("2026-01-01T00:00:00");
    private static final LocalDateTime MOVEMENT_AT = START.plusDays(1);

    @Autowired JdbcTemplate db;
    @Autowired InventoryActivationService activation;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryChangeApplier applier;
    @Autowired MovimientoInventarioService manualMovements;
    @Autowired PlatformTransactionManager transactionManager;

    private long admin;

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate table public.usuario, public.insumo restart identity cascade");
        admin = user("activation_admin", "ROLE_ADMIN", true);
    }

    private long user(String username, String role, boolean active) {
        return db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                        "values (?,'synthetic','Actor sintético',?,?) returning id",
                Long.class, username, role, active);
    }

    private long ingredient(String stock) {
        return db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                        "stock_actual,stock_minimo,activo) values ('Sintético','texto heredado',5," +
                        "?::numeric,0,true) returning id", Long.class, stock);
    }

    private long origin(String stock) {
        long id = ingredient(stock);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(), id,
                BaseUnit.UNIT, new Evidence(BaseUnit.UNIT, null), admin,
                "conteo sintético revisado", "acta sintética de inventario inicial"));
        return id;
    }

    private long exact() {
        long id = ingredient("7");
        db.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento," +
                "cantidad,fecha) values (?,?,'ENTRADA',4,?)", id, admin, MOVEMENT_AT);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(), id,
                BaseUnit.UNIT, new Evidence(BaseUnit.UNIT,
                new StartingPoint(START, new BigDecimal("3"))), admin,
                "punto inicial sintético revisado", null));
        return id;
    }

    private Command command(long id) {
        return new Command(UUID.randomUUID(), id, admin);
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, id);
    }

    private long movements(long id) {
        return db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                Long.class, id);
    }

    private String status(long id) {
        return db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class, id);
    }

    @Test
    void originActivationAndRetryPreserveDataThenRouteWritesToApplier() {
        long id = origin("10");
        Command command = command(id);
        Activation first = activation.activate(command);
        assertThat(activation.activate(command)).isEqualTo(first);
        assertThat(first.anchorId()).isEqualTo(db.queryForObject(
                "select reconciliacion_actual_id from insumo where id=?", Long.class, id));
        assertThat(status(id)).isEqualTo("ACTIVO_5A");
        assertThat(stock(id)).isEqualByComparingTo("10");
        assertThat(db.queryForObject("select costo_unitario from insumo where id=?",
                BigDecimal.class, id)).isEqualByComparingTo("5");
        assertThat(db.queryForObject("select unidad_medida from insumo where id=?",
                String.class, id)).isEqualTo("texto heredado");
        assertThat(movements(id)).isZero();
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isEqualTo(1);

        Insumo supply = new Insumo();
        supply.setId(id);
        Usuario actor = new Usuario();
        actor.setId(admin);
        MovimientoInventario legacy = new MovimientoInventario();
        legacy.setInsumo(supply);
        legacy.setUsuario(actor);
        legacy.setTipoMovimiento("ENTRADA");
        legacy.setCantidad(BigDecimal.ONE);
        assertThatThrownBy(() -> manualMovements.registrarMovimiento(legacy))
                .hasMessageContaining("ACTIVO_5A");

        var result = applier.apply(new InventoryChangeApplier.Command(UUID.randomUUID(), id,
                InventoryChangeApplier.Type.SALIDA, new BigDecimal("2"), BaseUnit.UNIT,
                admin, "PRUEBA_ACTIVACION", "INSUMO:" + id, "consumo sintético"));
        assertThat(result.resultingStock()).isEqualByComparingTo("8");
        assertThat(stock(id)).isEqualByComparingTo("8");
        assertThat(movements(id)).isEqualTo(1);
        assertThat(activation.activate(command)).isEqualTo(first);
    }

    @Test
    void reconciliableAnchorStillActivatesAfterConsistentMovement() {
        long id = exact();
        assertThat(movements(id)).isEqualTo(1);
        db.update("update insumo set stock_actual=9 where id=?", id);
        db.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento," +
                "cantidad,fecha) values (?,?,'ENTRADA',2,?)", id, admin, MOVEMENT_AT.plusDays(1));
        activation.activate(command(id));
        assertThat(status(id)).isEqualTo("ACTIVO_5A");
        assertThat(stock(id)).isEqualByComparingTo("9");
        assertThat(movements(id)).isEqualTo(2);
    }

    @Test
    void rejectsReusedRequestWithAnotherIngredientOrActorAndIncorrectState() {
        long first = origin("10");
        long second = origin("10");
        long otherAdmin = user("other_admin", "ROLE_ADMIN", true);
        Command command = command(first);
        activation.activate(command);
        assertThatThrownBy(() -> activation.activate(new Command(command.requestId(), second, admin)))
                .hasMessageContaining("requestId reutilizado");
        assertThatThrownBy(() -> activation.activate(new Command(command.requestId(), first, otherAdmin)))
                .hasMessageContaining("requestId reutilizado");
        assertThatThrownBy(() -> activation.activate(command(first)))
                .hasMessageContaining("RECONCILIADO");
        assertThatThrownBy(() -> activation.activate(command(ingredient("2"))))
                .hasMessageContaining("RECONCILIADO");
        assertThat(status(second)).isEqualTo("RECONCILIADO");
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isEqualTo(1);
    }

    @Test
    void onlyActiveAdminHasActivationPermission() {
        long id = origin("3");
        long manager = user("activation_manager", "ROLE_GERENTE", true);
        long inactive = user("activation_inactive", "ROLE_ADMIN", false);
        assertThatThrownBy(() -> activation.activate(new Command(UUID.randomUUID(), id, manager)))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("INVENTARIO_ACTIVAR_5A");
        assertThatThrownBy(() -> activation.activate(new Command(UUID.randomUUID(), id, inactive)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(status(id)).isEqualTo("RECONCILIADO");
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isZero();
    }

    @Test
    void approvalRequiresActiveReviewerPermissionEvenOnRetry() {
        long id = ingredient("3");
        for (long reviewer : new long[] {user("review_manager", "ROLE_GERENTE", true),
                user("review_inactive", "ROLE_ADMIN", false)}) {
            assertThatThrownBy(() -> reconciliation.approve(new InventoryReconciliationService.Command(
                    UUID.randomUUID(), id, BaseUnit.UNIT, new Evidence(BaseUnit.UNIT, null),
                    reviewer, "procedencia sintética", "evidencia sintética")))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("INVENTARIO_RECONCILIAR");
        }
        assertThat(status(id)).isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("select count(*) from inventario_reconciliacion", Long.class)).isZero();
        var command = new InventoryReconciliationService.Command(UUID.randomUUID(), id,
                BaseUnit.UNIT, new Evidence(BaseUnit.UNIT, null), admin, "procedencia", "evidencia");
        reconciliation.approve(command);
        db.update("update usuario set activo=false where id=?", admin);
        assertThatThrownBy(() -> reconciliation.approve(command)).isInstanceOf(AccessDeniedException.class);
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(movements(id)).isZero();
    }

    @Test
    void originApprovalRequiresNonBlankEvidenceWithoutPartialEffects() {
        long id = ingredient("3");
        for (String evidence : new String[] {null, "", " \t\n"}) {
            assertThatThrownBy(() -> reconciliation.approve(new InventoryReconciliationService.Command(
                    UUID.randomUUID(), id, BaseUnit.UNIT, new Evidence(BaseUnit.UNIT, null),
                    admin, "procedencia", evidence))).hasMessageContaining("evidencia no vacía");
        }
        assertThat(status(id)).isEqualTo("PENDIENTE");
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(movements(id)).isZero();
        assertThat(db.queryForObject("select count(*) from inventario_reconciliacion", Long.class)).isZero();
    }

    @Test
    void activationParticipatesInOuterRollbackAndSameRequestCanRetry() {
        long id = origin("3");
        Command command = command(id);
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            activation.activate(command);
            throw new IllegalStateException("fallo posterior sintético");
        })).hasMessageContaining("fallo posterior sintético");
        assertThat(status(id)).isEqualTo("RECONCILIADO");
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(movements(id)).isZero();
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isZero();
        Activation result = activation.activate(command);
        assertThat(activation.activate(command)).isEqualTo(result);
        assertThat(status(id)).isEqualTo("ACTIVO_5A");
    }

    @Test
    void originDeltaChangeOrUnknownMovementBlocksActivation() {
        long changed = origin("10");
        db.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento," +
                "cantidad,fecha) values (?,?,'ENTRADA',1,?)", changed, admin, MOVEMENT_AT);
        assertThatThrownBy(() -> activation.activate(command(changed)))
                .hasMessageContaining("origen no verificado cambió");
        long unknown = origin("10");
        db.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento," +
                "cantidad,fecha) values (?,?,'DESCONOCIDO',1,?)", unknown, admin, MOVEMENT_AT);
        assertThatThrownBy(() -> activation.activate(command(unknown)))
                .hasMessageContaining("origen no verificado cambió");
        assertThat(status(changed)).isEqualTo("RECONCILIADO");
        assertThat(status(unknown)).isEqualTo("RECONCILIADO");
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isZero();
    }

    @Test
    void unchangedOriginDeltaAllowsNewInterpretableMovement() {
        long id = origin("10");
        db.update("update insumo set stock_actual=12 where id=?", id);
        db.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento," +
                "cantidad,fecha) values (?,?,'ENTRADA',2,?)", id, admin, MOVEMENT_AT);
        activation.activate(command(id));
        assertThat(status(id)).isEqualTo("ACTIVO_5A");
        assertThat(stock(id)).isEqualByComparingTo("12");
        assertThat(movements(id)).isEqualTo(1);
    }

    @Test
    void changedReconciliableHistoryBlocksActivation() {
        long id = exact();
        db.update("update movimiento_inventario set cantidad=3 where insumo_id=?", id);
        assertThatThrownBy(() -> activation.activate(command(id)))
                .hasMessageContaining("dejó de ser RECONCILIABLE");
        assertThat(status(id)).isEqualTo("RECONCILIADO");
        assertThat(stock(id)).isEqualByComparingTo("7");
        assertThat(movements(id)).isEqualTo(1);
    }

    @Test
    void changedHashedApprovalCannotActivateEvenWithWellFormedFingerprint() {
        long id = origin("5");
        long anchor = db.queryForObject("select reconciliacion_actual_id from insumo where id=?",
                Long.class, id);
        // Representa una alteración anterior a la protección V11: el ancla deja
        // de estar seleccionada durante la edición, conservando su huella antigua.
        db.update("update insumo set estado_inventario_5a='PENDIENTE'," +
                "reconciliacion_actual_id=null where id=?", id);
        db.update("update inventario_reconciliacion set procedencia='procedencia alterada' where id=?", anchor);
        db.update("update insumo set estado_inventario_5a='RECONCILIADO'," +
                "reconciliacion_actual_id=? where id=?", anchor, id);
        assertThatThrownBy(() -> activation.activate(command(id)))
                .hasMessageContaining("huella no coincide");
        assertThat(status(id)).isEqualTo("RECONCILIADO");
        assertThat(stock(id)).isEqualByComparingTo("5");
        assertThat(movements(id)).isZero();
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isZero();
    }

    @Test
    void databaseFreezesCurrentApprovalIncludingClassificationAndDelta() {
        long id = exact();
        long anchor = db.queryForObject("select reconciliacion_actual_id from insumo where id=?",
                Long.class, id);
        for (String change : java.util.List.of(
                "clasificacion='ORIGEN_NO_VERIFICADO'",
                "delta_no_explicado=4,saldo_observado=8",
                "version_calculo='alterada'",
                "punto_inicial_en=punto_inicial_en+interval '1 second'",
                "punto_inicial_saldo=4",
                "procedencia='alterada'",
                "evidencia='alterada'",
                "revisado_por_usuario_id=" + user("reviewer_other", "ROLE_ADMIN", true))) {
            assertThatThrownBy(() -> db.update("update inventario_reconciliacion set " + change +
                    " where id=?", anchor)).hasMessageContaining("aprobación de un ancla actual");
        }
        // No-op sigue permitido; la protección no impide el flujo válido ni retry.
        db.update("update inventario_reconciliacion set evidencia=evidencia where id=?", anchor);
        Command command = command(id);
        Activation first = activation.activate(command);
        assertThat(activation.activate(command)).isEqualTo(first);
        assertThat(stock(id)).isEqualByComparingTo("7");
        assertThat(movements(id)).isEqualTo(1);
        assertThatThrownBy(() -> db.update("update inventario_reconciliacion set " +
                "evidencia='alterada después de activar' where id=?", anchor))
                .hasMessageContaining("aprobación de un ancla actual");
    }

    @Test
    void fingerprintRoundTripsPostgresTimestampPrecisionAndApprovalRetry() {
        // Vector del serializador V8 original: conserva huellas históricas
        // cuyo timestamp ya era representable exactamente en PostgreSQL.
        assertThat(InventoryReconciliationFingerprint.calculate(17L, BaseUnit.UNIT,
                new Evidence(BaseUnit.UNIT, new StartingPoint(START.withNano(123456000),
                        new BigDecimal("3"))), 23L, "procedencia V8", null))
                .isEqualTo("d04f28ff414c6f116f08de4714db146a2953fd349eaf19565353455e29c94756");
        for (int nanos : new int[]{123456000, 123456789, 999999999}) {
            long id = ingredient("7");
            var command = new InventoryReconciliationService.Command(UUID.randomUUID(), id,
                    BaseUnit.UNIT, new Evidence(BaseUnit.UNIT,
                    new StartingPoint(START.withNano(nanos), new BigDecimal("7"))),
                    admin, "punto inicial sintético", "evidencia sintética");
            var approved = reconciliation.approve(command);
            assertThat(reconciliation.approve(command)).isEqualTo(approved);
            LocalDateTime rounded = START.withNano(nanos).plusNanos(500);
            assertThat(approved.startingAt())
                    .isEqualTo(rounded.withNano(rounded.getNano() / 1000 * 1000));
            activation.activate(command(id));
            assertThat(status(id)).isEqualTo("ACTIVO_5A");
            assertThat(stock(id)).isEqualByComparingTo("7");
            assertThat(movements(id)).isZero();
        }
    }

    @Test
    void databaseKeepsForeignAnchorUnitAndFingerprintProtected() {
        long id = origin("5");
        long other = origin("5");
        long otherAnchor = db.queryForObject("select reconciliacion_actual_id from insumo where id=?",
                Long.class, other);
        long ownAnchor = db.queryForObject("select reconciliacion_actual_id from insumo where id=?",
                Long.class, id);
        assertThatThrownBy(() -> db.update("update insumo set reconciliacion_actual_id=? where id=?",
                otherAnchor, id)).hasMessageContaining("ancla revisada del mismo insumo");
        assertThatThrownBy(() -> db.update("update insumo set unidad_base_familia='MASS'," +
                "unidad_base_codigo='g' where id=?", id)).hasMessageContaining("unidad histórica distinta");
        assertThatThrownBy(() -> db.update("update inventario_reconciliacion " +
                "set huella_comando=repeat('0',64) where id=?", ownAnchor))
                .hasMessageContaining("evidencia V8");
        activation.activate(command(id));
        assertThat(status(id)).isEqualTo("ACTIVO_5A");
    }

    @Test
    void unreviewedAnchorCannotCreateAnActivatableState() {
        long id = ingredient("5");
        long unreviewed = db.queryForObject("insert into inventario_reconciliacion(" +
                        "insumo_id,request_id,saldo_observado,suma_movimientos_conocidos," +
                        "delta_no_explicado,corte_en,version_calculo,clasificacion) " +
                        "values (?,?,5,0,5,now(),'legacy-inventory-v1','ORIGEN_NO_VERIFICADO') " +
                        "returning id", Long.class, id, UUID.randomUUID());
        assertThatThrownBy(() -> db.update("update insumo set unidad_base_familia='COUNT'," +
                "unidad_base_codigo='unidad',reconciliacion_actual_id=?," +
                "estado_inventario_5a='RECONCILIADO' where id=?", unreviewed, id))
                .hasMessageContaining("ancla revisada");
        assertThatThrownBy(() -> activation.activate(command(id)))
                .hasMessageContaining("RECONCILIADO");
        assertThat(status(id)).isEqualTo("PENDIENTE");
        assertThat(stock(id)).isEqualByComparingTo("5");
        assertThat(movements(id)).isZero();
    }

    @Test
    void activationReceiptIsImmutableAndRetryStillWorks() {
        long id = origin("1");
        Command command = command(id);
        Activation first = activation.activate(command);
        assertThatThrownBy(() -> db.update("update inventory_activation set actor_usuario_id=? " +
                "where request_id=?", admin, command.requestId()))
                .hasMessageContaining("inventory_activation is immutable");
        assertThatThrownBy(() -> db.update("delete from inventory_activation where request_id=?",
                command.requestId())).hasMessageContaining("inventory_activation is immutable");
        assertThat(activation.activate(command)).isEqualTo(first);
    }

    @Test
    void simultaneousRetryProducesOneActivation() throws Exception {
        long id = origin("2");
        Command command = command(id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Activation> a = pool.submit(() -> { start.await(); return activation.activate(command); });
            Future<Activation> b = pool.submit(() -> { start.await(); return activation.activate(command); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(status(id)).isEqualTo("ACTIVO_5A");
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isEqualTo(1);
        assertThat(movements(id)).isZero();
    }

    @Test
    void activationWaitsForWriterAndRevalidatesCommittedStock() throws Exception {
        long id = origin("2");
        TransactionTemplate writer = new TransactionTemplate(transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            writer.executeWithoutResult(status -> {
                db.queryForObject("select id from insumo where id=? for update", Long.class, id);
                Future<?> pending = pool.submit(() -> activation.activate(command(id)));
                try {
                    Thread.sleep(150);
                    assertThat(pending.isDone()).isFalse();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                db.update("update insumo set stock_actual=3 where id=?", id);
                // El hilo termina después del commit de este bloque.
                pendingAfterCommit = pending;
            });
            assertThatThrownBy(() -> pendingAfterCommit.get(20, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("El origen no verificado cambió desde la aprobación");
        }
        assertThat(status(id)).isEqualTo("RECONCILIADO");
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(db.queryForObject("select count(*) from inventory_activation", Long.class)).isZero();
    }

    private Future<?> pendingAfterCommit;
}
