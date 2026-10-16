package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.inventory.InventoryChangeApplier.Command;
import com.tuapp.restaurante.inventory.InventoryChangeApplier.Result;
import com.tuapp.restaurante.inventory.InventoryChangeApplier.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Se ejecuta únicamente contra el PostgreSQL descartable de slice0-test.sh. */
@SpringBootTest
@ActiveProfiles("slice0")
class InventoryChangeApplierIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired InventoryChangeApplier applier;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired PlatformTransactionManager transactionManager;

    private long actor;

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate table public.usuario, public.insumo restart identity cascade");
        actor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('inventory_test','synthetic','Inventario sintético','ROLE_ADMIN',true) returning id",
                Long.class);
    }

    private long ingredient(String stock, BaseUnit unit) {
        long id = db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                        "stock_actual,stock_minimo,activo) values ('Sintético','texto heredado',5,?::numeric,0,true) " +
                        "returning id", Long.class, stock);
        if (unit != null) {
            db.update("update insumo set unidad_base_familia=?,unidad_base_codigo=? where id=?",
                    unit.family().name(), unit.symbol(), id);
        }
        return id;
    }

    private long activeIngredient(String stock, BaseUnit unit) {
        long id = ingredient(stock, null);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),
                id, unit, new LegacyInventoryClassifier.Evidence(unit, null),
                actor, "revisión sintética", "conteo sintético"));
        // Fixture descartable: la activación funcional pertenece a 5A.5f.
        db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?", id);
        return id;
    }

    private Command command(UUID operationId, long insumoId, Type type, String quantity, BaseUnit unit) {
        return new Command(operationId, insumoId, type, new BigDecimal(quantity), unit,
                actor, "PRUEBA_5A", "fixture-" + insumoId, "causa sintética");
    }

    private Command command(long insumoId, Type type, String quantity, BaseUnit unit) {
        return command(UUID.randomUUID(), insumoId, type, quantity, unit);
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, id);
    }

    private long movementCount(long id) {
        return db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?", Long.class, id);
    }

    @Test
    void entryExitAndExactZeroAreCausalAndAtomic() {
        long id = activeIngredient("1.000000", BaseUnit.GRAM);
        Result entry = applier.apply(command(id, Type.ENTRADA, "0.123456", BaseUnit.GRAM));
        assertThat(entry.resultingStock()).isEqualByComparingTo("1.123456");
        assertThat(stock(id)).isEqualByComparingTo("1.123456");
        assertThat(db.queryForObject("select cantidad from movimiento_inventario where id=?",
                BigDecimal.class, entry.movementId())).isEqualByComparingTo("0.123456");
        Result exit = applier.apply(command(id, Type.SALIDA, "1.123456", BaseUnit.GRAM));
        assertThat(exit.resultingStock()).isEqualByComparingTo("0.000000");
        assertThat(stock(id)).isZero();
        assertThat(movementCount(id)).isEqualTo(2);
        assertThat(db.queryForList("select tipo_movimiento from movimiento_inventario " +
                "where insumo_id=? order by id", String.class, id)).containsExactly("ENTRADA", "SALIDA");
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?",
                String.class, id)).isEqualTo("ACTIVO_5A");
        assertThat(db.queryForObject("select reconciliacion_actual_id from insumo where id=?",
                Long.class, id)).isNotNull();
    }

    @Test
    void rejectsInsufficientStockInvalidAmountsAndRoundingWithoutWriting() {
        long id = activeIngredient("1.000000", BaseUnit.MILLILITER);
        assertThatThrownBy(() -> applier.apply(command(id, Type.SALIDA, "1.000001", BaseUnit.MILLILITER)))
                .hasMessageContaining("Stock insuficiente");
        assertThatThrownBy(() -> applier.apply(command(id, Type.ENTRADA, "0", BaseUnit.MILLILITER)))
                .hasMessageContaining("mayor que cero");
        assertThatThrownBy(() -> applier.apply(command(id, Type.SALIDA, "-1", BaseUnit.MILLILITER)))
                .hasMessageContaining("no puede ser negativa");
        assertThatThrownBy(() -> applier.apply(command(id, Type.ENTRADA, "0.0000001", BaseUnit.MILLILITER)))
                .hasMessageContaining("no se representa exactamente");
        assertThatThrownBy(() -> applier.apply(command(id, Type.ENTRADA, "1", BaseUnit.GRAM)))
                .hasMessageContaining("unidad");
        long full = activeIngredient("9999999999999.999999", BaseUnit.MILLILITER);
        assertThatThrownBy(() -> applier.apply(command(full, Type.ENTRADA, "0.000001", BaseUnit.MILLILITER)))
                .hasMessageContaining("NUMERIC(19,6)");
        assertThat(stock(id)).isEqualByComparingTo("1");
        assertThat(movementCount(id)).isZero();
        assertThat(stock(full)).isEqualByComparingTo("9999999999999.999999");
        assertThat(movementCount(full)).isZero();
    }

    @Test
    void pendingIngredientCannotUseOperationalApplier() {
        long id = ingredient("10.00", null);
        Command command = command(id, Type.SALIDA, "0.25", null);
        assertThatThrownBy(() -> applier.apply(command)).hasMessageContaining("LEGACY");
        assertThatThrownBy(() -> applier.applyAll(List.of(command))).hasMessageContaining("LEGACY");
        assertThat(stock(id)).isEqualByComparingTo("10.00");
        assertThat(db.queryForMap("select estado_inventario_5a,unidad_base_codigo,reconciliacion_actual_id " +
                "from insumo where id=?", id)).containsEntry("estado_inventario_5a", "PENDIENTE")
                .containsEntry("unidad_base_codigo", null).containsEntry("reconciliacion_actual_id", null);
        assertThat(movementCount(id)).isZero();
    }

    @Test
    void mixedBatchCannotPartiallyApplyToLegacyIngredient() {
        long active = activeIngredient("3", BaseUnit.UNIT);
        long pending = ingredient("2", null);
        assertThat(active).isLessThan(pending);
        assertThatThrownBy(() -> applier.applyAll(List.of(
                command(active, Type.SALIDA, "1", BaseUnit.UNIT),
                command(pending, Type.ENTRADA, "1", null))))
                .hasMessageContaining("LEGACY");
        assertThat(stock(active)).isEqualByComparingTo("3");
        assertThat(stock(pending)).isEqualByComparingTo("2");
        assertThat(movementCount(active)).isZero();
        assertThat(movementCount(pending)).isZero();
    }

    @Test
    void databaseRejectsNullAndUnknownInventoryStates() {
        long id = ingredient("2", null);
        assertThatThrownBy(() -> db.update("update insumo set estado_inventario_5a=null where id=?", id))
                .hasMessageContaining("estado_inventario_5a");
        assertThatThrownBy(() -> db.update("update insumo set estado_inventario_5a='DESCONOCIDO' where id=?", id))
                .hasMessageContaining("estado_inventario_5a");
        assertThat(stock(id)).isEqualByComparingTo("2");
        assertThat(movementCount(id)).isZero();
    }

    @Test
    void retryReturnsOriginalResultAndDifferentPayloadIsRejected() {
        long id = activeIngredient("10", BaseUnit.UNIT);
        UUID operationId = UUID.randomUUID();
        Command first = command(operationId, id, Type.SALIDA_VENTA, "2", BaseUnit.UNIT);
        Result initial = applier.apply(first);
        applier.apply(command(id, Type.ENTRADA, "1", BaseUnit.UNIT));
        assertThat(applier.apply(first)).isEqualTo(initial);
        assertThatThrownBy(() -> applier.apply(command(operationId, id, Type.SALIDA_VENTA,
                "3", BaseUnit.UNIT))).hasMessageContaining("operationId reutilizado");
        long other = activeIngredient("5", BaseUnit.UNIT);
        assertThatThrownBy(() -> applier.apply(command(operationId, other, Type.SALIDA_VENTA,
                "2", BaseUnit.UNIT))).hasMessageContaining("operationId reutilizado");
        assertThat(stock(id)).isEqualByComparingTo("9");
        assertThat(movementCount(id)).isEqualTo(2);
        assertThat(stock(other)).isEqualByComparingTo("5");
        assertThat(movementCount(other)).isZero();
        assertThat(db.queryForObject("select resulting_stock from movimiento_inventario where id=?",
                BigDecimal.class, initial.movementId())).isEqualByComparingTo("8");
    }

    @Test
    void concurrentRetryAppliesOnce() throws Exception {
        long id = activeIngredient("10", BaseUnit.UNIT);
        Command same = command(id, Type.SALIDA, "3", BaseUnit.UNIT);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Result> a = pool.submit(() -> { start.await(); return applier.apply(same); });
            Future<Result> b = pool.submit(() -> { start.await(); return applier.apply(same); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(stock(id)).isEqualByComparingTo("7");
        assertThat(movementCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctConsumptionsNeverOverdraw() throws Exception {
        long id = activeIngredient("5", BaseUnit.GRAM);
        Command first = command(id, Type.SALIDA, "4", BaseUnit.GRAM);
        Command second = command(id, Type.SALIDA, "4", BaseUnit.GRAM);
        int successes = concurrentSuccesses(first, second);
        assertThat(successes).isEqualTo(1);
        assertThat(stock(id)).isEqualByComparingTo("1");
        assertThat(movementCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctConsumptionsWithEnoughStockBothCommit() throws Exception {
        long id = activeIngredient("8", BaseUnit.GRAM);
        assertThat(concurrentSuccesses(command(id, Type.SALIDA, "4", BaseUnit.GRAM),
                command(id, Type.SALIDA, "4", BaseUnit.GRAM))).isEqualTo(2);
        assertThat(stock(id)).isZero();
        assertThat(movementCount(id)).isEqualTo(2);
    }

    private int concurrentSuccesses(Command first, Command second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> attemptAfter(start, first));
            Future<Boolean> b = pool.submit(() -> attemptAfter(start, second));
            start.countDown();
            return (a.get(20, TimeUnit.SECONDS) ? 1 : 0) + (b.get(20, TimeUnit.SECONDS) ? 1 : 0);
        }
    }

    private boolean attemptAfter(CountDownLatch start, Command command) throws InterruptedException {
        start.await();
        try { applier.apply(command); return true; }
        catch (IllegalArgumentException ex) {
            assertThat(ex).hasMessageContaining("Stock insuficiente");
            return false;
        }
    }

    @Test
    void outerRollbackRevertsMovementAndBalanceAndAllowsRetry() {
        long id = activeIngredient("4", BaseUnit.UNIT);
        Command command = command(id, Type.SALIDA, "2", BaseUnit.UNIT);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            applier.apply(command);
            throw new IllegalStateException("fallo exterior sintético");
        })).hasMessageContaining("fallo exterior sintético");
        assertThat(stock(id)).isEqualByComparingTo("4");
        assertThat(movementCount(id)).isZero();
        assertThat(applier.apply(command).resultingStock()).isEqualByComparingTo("2");
        assertThat(movementCount(id)).isEqualTo(1);
    }

    @Test
    void batchSortsLocksAndFailureRollsBackAllEffects() {
        long a = activeIngredient("3", BaseUnit.UNIT);
        long b = activeIngredient("1", BaseUnit.UNIT);
        List<Result> results = applier.applyAll(List.of(command(b, Type.ENTRADA, "2", BaseUnit.UNIT),
                command(a, Type.SALIDA, "1", BaseUnit.UNIT)));
        assertThat(results).extracting(Result::insumoId).containsExactly(b, a);
        assertThat(stock(a)).isEqualByComparingTo("2");
        assertThat(stock(b)).isEqualByComparingTo("3");
        assertThatThrownBy(() -> applier.applyAll(List.of(
                command(b, Type.SALIDA, "10", BaseUnit.UNIT),
                command(a, Type.SALIDA, "1", BaseUnit.UNIT))))
                .hasMessageContaining("Stock insuficiente");
        assertThat(stock(a)).isEqualByComparingTo("2");
        assertThat(stock(b)).isEqualByComparingTo("3");
        assertThat(movementCount(a)).isEqualTo(1);
        assertThat(movementCount(b)).isEqualTo(1);
    }

    @Test
    void reversedConcurrentBatchesForSameInsumoDoNotDeadlockOrDuplicate() throws Exception {
        long id = activeIngredient("10", BaseUnit.UNIT);
        Command a = command(id, Type.SALIDA, "2", BaseUnit.UNIT);
        Command b = command(id, Type.SALIDA, "3", BaseUnit.UNIT);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<List<Result>> first = pool.submit(() -> {
                start.await();
                return applier.applyAll(List.of(a, b));
            });
            Future<List<Result>> reversed = pool.submit(() -> {
                start.await();
                return applier.applyAll(List.of(b, a));
            });
            start.countDown();
            List<Result> normalResults = first.get(20, TimeUnit.SECONDS);
            List<Result> reversedResults = reversed.get(20, TimeUnit.SECONDS);
            assertThat(normalResults.get(0)).isEqualTo(reversedResults.get(1));
            assertThat(normalResults.get(1)).isEqualTo(reversedResults.get(0));
        }
        assertThat(stock(id)).isEqualByComparingTo("5");
        assertThat(movementCount(id)).isEqualTo(2);
    }

    @Test
    void reconciliationStateAndAnchorStayUntouched() {
        long id = ingredient("4", null);
        var anchor = reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),
                id, BaseUnit.UNIT, new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT, null),
                actor, "revisión sintética", "conteo sintético"));
        assertThatThrownBy(() -> applier.apply(command(id, Type.ENTRADA_PRODUCCION,
                "0.000001", BaseUnit.UNIT))).hasMessageContaining("LEGACY");
        assertThat(stock(id)).isEqualByComparingTo("4");
        assertThat(movementCount(id)).isZero();
        assertThat(db.queryForMap("select estado_inventario_5a,reconciliacion_actual_id from insumo where id=?",
                id)).containsEntry("estado_inventario_5a", "RECONCILIADO")
                .containsEntry("reconciliacion_actual_id", anchor.id());
        assertThat(db.queryForObject("select count(*) from inventario_reconciliacion where insumo_id=?",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void syntheticActiveFixtureCanApplyWithoutChangingItsStateOrAnchor() {
        long id = ingredient("2", null);
        var anchor = reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),
                id, BaseUnit.GRAM, new LegacyInventoryClassifier.Evidence(BaseUnit.GRAM, null),
                actor, "revisión sintética", "conteo sintético"));
        // Solo fixture SQL: 5A.5a no ofrece transición ni habilita insumos reales.
        db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?", id);
        applier.apply(command(id, Type.SALIDA_PRODUCCION, "0.000001", BaseUnit.GRAM));
        assertThat(stock(id)).isEqualByComparingTo("1.999999");
        assertThat(db.queryForMap("select estado_inventario_5a,reconciliacion_actual_id from insumo where id=?",
                id)).containsEntry("estado_inventario_5a", "ACTIVO_5A")
                .containsEntry("reconciliacion_actual_id", anchor.id());
    }

    @Test
    void databaseEnforcesCompleteCausalIdentityAndUniqueOperation() {
        long id = activeIngredient("2", BaseUnit.UNIT);
        UUID operationId = UUID.randomUUID();
        long movement = applier.apply(command(operationId, id, Type.ENTRADA, "1", BaseUnit.UNIT))
                .movementId();
        assertThatThrownBy(() -> db.update("insert into movimiento_inventario" +
                "(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha,operation_id) " +
                "values (?,?,'ENTRADA',1,now(),?)", id, actor, UUID.randomUUID()))
                .hasMessageContaining("movimiento_aplicador_completo_check");
        assertThatThrownBy(() -> db.update("insert into movimiento_inventario" +
                "(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha,operation_id," +
                "operation_fingerprint,causal_origin,causal_reference,resulting_stock) " +
                "select insumo_id,usuario_id,tipo_movimiento,cantidad,now(),operation_id," +
                "operation_fingerprint,causal_origin,causal_reference,resulting_stock " +
                "from movimiento_inventario where id=?", movement))
                .hasMessageContaining("movimiento_aplicador_operation_unique");
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(movementCount(id)).isEqualTo(1);
    }

    @Test
    void causalMovementRejectsMutationAndDeletionButRetryStillReturnsOriginalResult() {
        long id = activeIngredient("2", BaseUnit.UNIT);
        long otherIngredient = activeIngredient("1", BaseUnit.UNIT);
        long otherActor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('other_inventory_test','synthetic','Otro usuario sintético','ROLE_ADMIN',true) returning id",
                Long.class);
        Command command = command(id, Type.ENTRADA, "1", BaseUnit.UNIT);
        Result original = applier.apply(command);

        Map<String, Object> changes = Map.ofEntries(
                Map.entry("insumo_id", otherIngredient),
                Map.entry("usuario_id", otherActor),
                Map.entry("tipo_movimiento", "SALIDA"),
                Map.entry("cantidad", new BigDecimal("2")),
                Map.entry("operation_id", UUID.randomUUID()),
                Map.entry("operation_fingerprint", "b".repeat(64)),
                Map.entry("causal_origin", "OTRO_ORIGEN"),
                Map.entry("causal_reference", "otra-referencia"),
                Map.entry("resulting_stock", new BigDecimal("4")));
        for (var change : changes.entrySet()) {
            assertThatThrownBy(() -> db.update("update movimiento_inventario set " + change.getKey() +
                    "=? where id=?", change.getValue(), original.movementId()))
                    .as("No debe poder mutarse " + change.getKey())
                    .hasMessageContaining("movimiento causal inmutable");
        }
        assertThatThrownBy(() -> db.update("delete from movimiento_inventario where id=?",
                original.movementId())).hasMessageContaining("movimiento causal inmutable");
        assertThatThrownBy(() -> db.update("update movimiento_inventario set comentario=? where id=?",
                "comentario alterado", original.movementId()))
                .hasMessageContaining("movimiento causal inmutable");
        assertThatThrownBy(() -> db.update("update movimiento_inventario " +
                "set fecha=fecha + interval '1 second' where id=?", original.movementId()))
                .hasMessageContaining("movimiento causal inmutable");

        assertThat(applier.apply(command)).isEqualTo(original);
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(movementCount(id)).isEqualTo(1);
        assertThat(movementCount(otherIngredient)).isZero();
    }

    @Test
    void legacyMovementStillAllowsItsExistingUpdateAndDeleteBehavior() {
        long id = ingredient("2", null);
        long movement = db.queryForObject("insert into movimiento_inventario" +
                "(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha) " +
                "values (?,?,'ENTRADA',1,now()) returning id", Long.class, id, actor);
        assertThat(db.update("update movimiento_inventario set cantidad=2, comentario='legacy editado', " +
                "fecha=fecha + interval '1 second' where id=?", movement))
                .isEqualTo(1);
        assertThat(db.update("delete from movimiento_inventario where id=?", movement)).isEqualTo(1);
        assertThat(movementCount(id)).isZero();
    }

    @Test
    void legacyMovementCannotBeConvertedToCausalByUpdate() {
        long id = ingredient("2", null);
        long movement = db.queryForObject("insert into movimiento_inventario" +
                "(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha) " +
                "values (?,?,'ENTRADA',1,now()) returning id", Long.class, id, actor);
        assertThatThrownBy(() -> db.update("update movimiento_inventario set operation_id=?, " +
                "operation_fingerprint=?, causal_origin='PRUEBA_5A', " +
                "causal_reference='reclasificacion-sintetica', resulting_stock=3 where id=?",
                UUID.randomUUID(), "a".repeat(64), movement))
                .hasMessageContaining("no se puede convertir un movimiento legacy");
        assertThat(db.queryForObject("select operation_id from movimiento_inventario where id=?",
                UUID.class, movement)).isNull();
        assertThat(db.update("update movimiento_inventario set cantidad=2 where id=?", movement))
                .isEqualTo(1);
        assertThat(db.update("delete from movimiento_inventario where id=?", movement)).isEqualTo(1);
    }
}
