package com.tuapp.restaurante.services;

import com.tuapp.restaurante.inventory.BaseUnit;
import com.tuapp.restaurante.inventory.InventoryReconciliationService;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Producción 5A.5d: únicamente PostgreSQL sintético descartable de slice0-test.sh. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class ProduccionInventoryPolicyIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ProduccionService production;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired PlatformTransactionManager transactionManager;

    private long actor;

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate table public.production_request, public.usuario, public.insumo " +
                "restart identity cascade");
        actor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('production_5a','synthetic','Producción sintética','ROLE_ADMIN',true) returning id",
                Long.class);
    }

    private long ingredient(String name, String stock, String cost) {
        return db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                "stock_actual,stock_minimo,activo) values (?,'unidad heredada',?::numeric," +
                "?::numeric,0,true) returning id", Long.class, name, cost, stock);
    }

    private long reviewed(String name, String stock, String cost, boolean active) {
        long id = ingredient(name, stock, cost);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),
                id, BaseUnit.UNIT, new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT, null),
                actor, "revisión sintética", "conteo sintético"));
        if (active) {
            // Solo fixture descartable; la activación real sigue fuera de 5A.5d.
            db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?", id);
        }
        return id;
    }

    private void recipe(long destination, long ingredient, String amountPerUnit) {
        db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) " +
                "values (?,?,?::numeric)", destination, ingredient, amountPerUnit);
    }

    private void produce(long destination, String amount, UUID requestId) {
        com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> production.producirInsumo(destination, new BigDecimal(amount), actor, requestId));
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, id);
    }

    private BigDecimal cost(long id) {
        return db.queryForObject("select costo_unitario from insumo where id=?", BigDecimal.class, id);
    }

    private long count(String table) {
        return db.queryForObject("select count(*) from " + table, Long.class);
    }

    private UUID operationId(UUID requestId, long ingredientId, String direction) {
        return UUID.nameUUIDFromBytes(("PRODUCCION|" + requestId + "|" + ingredientId +
                "|" + direction).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pendingAndReviewedKeepLegacyStocksMovementsAndCost() {
        long pending = ingredient("Pendiente", "10", "2.50");
        long reviewed = reviewed("Reconciliado", "10", "1.20", false);
        long destination = ingredient("Destino", "1", "0.50");
        recipe(destination, pending, "1.25");
        recipe(destination, reviewed, "2.00");

        com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> production.producirInsumo(destination, new BigDecimal("2"), actor));

        assertThat(stock(pending)).isEqualByComparingTo("7.50");
        assertThat(stock(reviewed)).isEqualByComparingTo("6.00");
        assertThat(stock(destination)).isEqualByComparingTo("3.00");
        assertThat(cost(destination)).isEqualByComparingTo("5.53");
        assertThat(db.queryForList("select tipo_movimiento from movimiento_inventario order by id",
                String.class)).containsExactly("SALIDA_PRODUCCION", "SALIDA_PRODUCCION", "ENTRADA_PRODUCCION");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where operation_id is not null",
                Long.class)).isZero();
        assertThat(count("production_request")).isZero();
    }

    @Test
    void comparableLegacyAndActiveProductionsHaveSameStockAndCost() {
        long legacySource = ingredient("Legacy origen", "10", "5");
        long legacyDestination = ingredient("Legacy destino", "0", "1");
        long activeSource = reviewed("Activo origen", "10", "5", true);
        long activeDestination = reviewed("Activo destino", "0", "1", true);
        recipe(legacyDestination, legacySource, "1.25");
        recipe(activeDestination, activeSource, "1.25");

        produce(legacyDestination, "2", UUID.randomUUID());
        produce(activeDestination, "2", UUID.randomUUID());

        assertThat(stock(activeSource)).isEqualByComparingTo(stock(legacySource));
        assertThat(stock(activeDestination)).isEqualByComparingTo(stock(legacyDestination));
        assertThat(stock(activeSource)).isEqualByComparingTo("7.50");
        assertThat(stock(activeDestination)).isEqualByComparingTo("2");
        assertThat(cost(activeDestination)).isEqualByComparingTo(cost(legacyDestination));
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id in (?,?)",
                Long.class, legacySource, legacyDestination)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id in (?,?)",
                Long.class, activeSource, activeDestination)).isEqualTo(2);
    }

    @Test
    void activeSourceAndDestinationUseOnlyCausalMovements() {
        long source = reviewed("Activo origen", "10", "2.50", true);
        long destination = reviewed("Activo destino", "1", "0.50", true);
        recipe(destination, source, "1.2345");
        UUID requestId = UUID.randomUUID();

        produce(destination, "2", requestId);

        assertThat(stock(source)).isEqualByComparingTo("7.531000");
        assertThat(stock(destination)).isEqualByComparingTo("3.000000");
        assertThat(cost(destination)).isEqualByComparingTo("3.09");
        assertThat(db.queryForList("select tipo_movimiento from movimiento_inventario order by id",
                String.class)).containsExactly("SALIDA_PRODUCCION", "ENTRADA_PRODUCCION");
        assertThat(db.queryForObject("select operation_id from movimiento_inventario where insumo_id=?",
                UUID.class, source)).isEqualTo(operationId(requestId, source, "SALIDA"));
        assertThat(db.queryForObject("select operation_id from movimiento_inventario where insumo_id=?",
                UUID.class, destination)).isEqualTo(operationId(requestId, destination, "ENTRADA"));
        assertThat(db.queryForList("select causal_reference from movimiento_inventario order by id",
                String.class)).containsExactly("PRODUCCION:" + requestId, "PRODUCCION:" + requestId);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where operation_id is null",
                Long.class)).isZero();
        assertThat(count("production_request")).isEqualTo(1);
    }

    @Test
    void mixedSourcesAndDestinationRemainAtomicAndUseOneWriterPerState() {
        long activeSource = reviewed("Origen activo", "10", "3.00", true);
        long legacySource = ingredient("Origen legacy", "10", "1.00");
        long destination = reviewed("Destino reconciliado", "0", "0", false);
        recipe(destination, activeSource, "1.25");
        recipe(destination, legacySource, "2.00");
        UUID requestId = UUID.randomUUID();

        produce(destination, "1", requestId);

        assertThat(stock(activeSource)).isEqualByComparingTo("8.75");
        assertThat(stock(legacySource)).isEqualByComparingTo("8.00");
        assertThat(stock(destination)).isEqualByComparingTo("1.00");
        assertThat(cost(destination)).isEqualByComparingTo("5.75");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario " +
                "where insumo_id=? and operation_id is not null", Long.class, activeSource)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario " +
                "where insumo_id in (?,?) and operation_id is null", Long.class,
                legacySource, destination)).isEqualTo(2);
        assertThat(count("movimiento_inventario")).isEqualTo(3);
    }

    @Test
    void repeatedActiveIngredientAggregatesOneExitAndPreservesRawCost() {
        long source = reviewed("Compartido", "10", "2.00", true);
        long destination = ingredient("Destino", "0", "0");
        recipe(destination, source, "1.25");
        recipe(destination, source, "0.75");
        UUID requestId = UUID.randomUUID();

        produce(destination, "2", requestId);

        assertThat(stock(source)).isEqualByComparingTo("6.00");
        assertThat(stock(destination)).isEqualByComparingTo("2.00");
        assertThat(cost(destination)).isEqualByComparingTo("4.00");
        assertThat(db.queryForObject("select cantidad from movimiento_inventario where insumo_id=?",
                BigDecimal.class, source)).isEqualByComparingTo("4.00");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                Long.class, source)).isEqualTo(1);
    }

    @Test
    void retryOfMixedOperationDoesNotRepeatLegacyOrActiveEffects() {
        long source = reviewed("Origen activo", "10", "2", true);
        long destination = ingredient("Destino legacy", "0", "0");
        recipe(destination, source, "1.00");
        UUID requestId = UUID.randomUUID();

        produce(destination, "2", requestId);
        produce(destination, "2.0000", requestId);
        assertThat(stock(source)).isEqualByComparingTo("8");
        assertThat(stock(destination)).isEqualByComparingTo("2");
        assertThat(count("movimiento_inventario")).isEqualTo(2);
        assertThat(count("production_request")).isEqualTo(1);
        assertThatThrownBy(() -> produce(destination, "3", requestId))
                .hasMessageContaining("otra producción");
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void activeDestinationWithLegacySourceUsesApplierOnlyForEntry() {
        long source = ingredient("Origen legacy", "10", "2.00");
        long destination = reviewed("Destino activo", "0", "0", true);
        recipe(destination, source, "1.00");

        produce(destination, "2", UUID.randomUUID());

        assertThat(stock(source)).isEqualByComparingTo("8");
        assertThat(stock(destination)).isEqualByComparingTo("2");
        assertThat(db.queryForObject("select operation_id from movimiento_inventario where insumo_id=?",
                UUID.class, source)).isNull();
        assertThat(db.queryForObject("select operation_id from movimiento_inventario where insumo_id=?",
                UUID.class, destination)).isNotNull();
        assertThat(cost(destination)).isEqualByComparingTo("2.00");
    }

    @Test
    void insufficientActiveOrLegacyStockRollsBackEverything() {
        long active = reviewed("Activo", "1", "2", true);
        long legacy = ingredient("Legacy", "1", "2");
        long destination = ingredient("Destino", "0", "0");
        recipe(destination, active, "2");
        recipe(destination, legacy, "1");
        UUID first = UUID.randomUUID();

        assertThatThrownBy(() -> produce(destination, "1", first))
                .hasMessageContaining("Stock insuficiente");
        assertNoEffects(active, "1", legacy, "1", destination);

        db.update("update insumo set stock_actual=3 where id=?", active);
        db.update("update sub_receta set cantidad=2 where insumo_destino_id=? and ingrediente_id=?",
                destination, legacy);
        assertThatThrownBy(() -> produce(destination, "1", UUID.randomUUID()))
                .hasMessageContaining("Stock insuficiente");
        assertNoEffects(active, "3", legacy, "1", destination);
    }

    @Test
    void laterFailureRollsBackMixedStockMovementsCostAndRequest() {
        long active = reviewed("Activo", "10", "2.50", true);
        long legacy = ingredient("Legacy", "10", "1.00");
        long destination = ingredient("Destino", "0", "0.50");
        recipe(destination, active, "1");
        recipe(destination, legacy, "1");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> {
            produce(destination, "2", UUID.randomUUID());
            assertThat(count("movimiento_inventario")).isEqualTo(3);
            throw new IllegalStateException("fallo sintético posterior al inventario");
        })).hasMessageContaining("fallo sintético posterior");
        assertNoEffects(active, "10", legacy, "10", destination);
        assertThat(cost(destination)).isEqualByComparingTo("0.50");
    }

    @Test
    void simultaneousProductionsCannotOverdrawActiveIngredient() throws Exception {
        long active = reviewed("Disputado", "1", "2", true);
        long destination = ingredient("Destino", "0", "0");
        recipe(destination, active, "1");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> attemptAfter(start, destination));
            var second = pool.submit(() -> attemptAfter(start, destination));
            start.countDown();
            assertThat((first.get(20, TimeUnit.SECONDS) ? 1 : 0) +
                    (second.get(20, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(stock(active)).isZero();
        assertThat(stock(destination)).isEqualByComparingTo("1");
        assertThat(count("production_request")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void simultaneousRetryWithSameRequestIdCompletesOnlyOnce() throws Exception {
        long active = reviewed("Origen activo", "10", "2", true);
        long destination = ingredient("Destino legacy", "0", "0");
        recipe(destination, active, "1");
        UUID requestId = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> {
                start.await();
                produce(destination, "1", requestId);
                return true;
            });
            var second = pool.submit(() -> {
                start.await();
                produce(destination, "1", requestId);
                return true;
            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(stock(active)).isEqualByComparingTo("9");
        assertThat(stock(destination)).isEqualByComparingTo("1");
        assertThat(count("production_request")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void productionRequestCannotBeUpdatedOrDeletedAndStillAcceptsRetry() {
        long source = ingredient("Origen", "10", "2");
        long destination = ingredient("Destino", "0", "0");
        recipe(destination, source, "1");
        UUID requestId = UUID.randomUUID();
        produce(destination, "1", requestId);
        String originalHash = db.queryForObject(
                "select request_hash from production_request where request_id=?", String.class, requestId);

        assertThatThrownBy(() -> db.update("update production_request set request_hash=repeat('0',64) " +
                "where request_id=?", requestId)).hasMessageContaining("production_request is immutable");
        assertThatThrownBy(() -> db.update("delete from production_request where request_id=?", requestId))
                .hasMessageContaining("production_request is immutable");

        assertThat(db.queryForObject("select request_hash from production_request where request_id=?",
                String.class, requestId)).isEqualTo(originalHash);
        produce(destination, "1", requestId);
        assertThat(stock(source)).isEqualByComparingTo("9");
        assertThat(stock(destination)).isEqualByComparingTo("1");
        assertThat(count("production_request")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void productionPostRequiresRequestIdAndRetainsItUntilSuccess() throws Exception {
        long source = ingredient("Origen", "10", "2");
        long destination = ingredient("Destino", "0", "0");
        recipe(destination, source, "1");
        var page = mvc.perform(get("/subrecetas/" + destination)
                        .with(user("production_5a").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        UUID requestId = (UUID) page.getModelAndView().getModel().get("productionRequestId");

        mvc.perform(post("/subrecetas/producir").with(user("production_5a").roles("ADMIN"))
                        .with(csrf()).header("X-Requested-With", "XMLHttpRequest")
                        .param("insumoDestinoId", Long.toString(destination))
                        .param("cantidadAProducir", "1"))
                .andExpect(status().isBadRequest());
        assertThat(count("production_request")).isZero();
        assertThat(count("movimiento_inventario")).isZero();

        var rejected = mvc.perform(post("/subrecetas/producir").with(user("production_5a").roles("ADMIN"))
                        .with(csrf()).header("X-Requested-With", "XMLHttpRequest")
                        .param("insumoDestinoId", Long.toString(destination))
                        .param("cantidadAProducir", "11").param("requestId", requestId.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(rejected.getModelAndView().getModel().get("productionRequestId")).isEqualTo(requestId);
        assertThat(rejected.getResponse().getContentAsString()).contains(requestId.toString(), "Error en producción");
        assertThat(count("production_request")).isZero();
        assertThat(stock(source)).isEqualByComparingTo("10");

        var accepted = mvc.perform(post("/subrecetas/producir").with(user("production_5a").roles("ADMIN"))
                        .with(csrf()).header("X-Requested-With", "XMLHttpRequest")
                        .param("insumoDestinoId", Long.toString(destination))
                        .param("cantidadAProducir", "1").param("requestId", requestId.toString()))
                .andExpect(status().isOk()).andReturn();
        UUID nextRequestId = (UUID) accepted.getModelAndView().getModel().get("productionRequestId");
        assertThat(nextRequestId).isNotEqualTo(requestId);
        assertThat(accepted.getResponse().getContentAsString()).contains(nextRequestId.toString());

        mvc.perform(post("/subrecetas/producir").with(user("production_5a").roles("ADMIN"))
                        .with(csrf()).param("insumoDestinoId", Long.toString(destination))
                        .param("cantidadAProducir", "1").param("requestId", requestId.toString()))
                .andExpect(redirectedUrl("/insumos"));
        assertThat(stock(source)).isEqualByComparingTo("9");
        assertThat(stock(destination)).isEqualByComparingTo("1");

        mvc.perform(post("/subrecetas/producir").with(user("production_5a").roles("ADMIN"))
                        .with(csrf()).param("insumoDestinoId", Long.toString(destination))
                        .param("cantidadAProducir", "1").param("requestId", nextRequestId.toString()))
                .andExpect(redirectedUrl("/insumos"));
        assertThat(stock(source)).isEqualByComparingTo("8");
        assertThat(stock(destination)).isEqualByComparingTo("2");
        assertThat(count("production_request")).isEqualTo(2);
        assertThat(count("movimiento_inventario")).isEqualTo(4);
    }

    @Test
    void productionFormProvidesStableRequestIdToPost() throws Exception {
        long active = reviewed("Origen activo", "10", "2", true);
        long destination = ingredient("Destino", "0", "0");
        recipe(destination, active, "1");
        var page = mvc.perform(get("/subrecetas/" + destination)
                        .with(user("production_5a").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        UUID requestId = (UUID) page.getModelAndView().getModel().get("productionRequestId");
        assertThat(requestId).isNotNull();
        assertThat(page.getResponse().getContentAsString()).contains("inputProductionRequestId", requestId.toString());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/subrecetas/producir").with(user("production_5a").roles("ADMIN"))
                            .with(csrf()).param("insumoDestinoId", Long.toString(destination))
                            .param("cantidadAProducir", "1").param("requestId", requestId.toString()))
                    .andExpect(redirectedUrl("/insumos"));
        }
        assertThat(stock(active)).isEqualByComparingTo("9");
        assertThat(stock(destination)).isEqualByComparingTo("1");
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    private boolean attemptAfter(CountDownLatch start, long destination) throws InterruptedException {
        start.await();
        try {
            produce(destination, "1", UUID.randomUUID());
            return true;
        } catch (IllegalArgumentException ex) {
            assertThat(ex).hasMessageContaining("Stock insuficiente");
            return false;
        }
    }

    private void assertNoEffects(long active, String activeStock, long legacy, String legacyStock,
                                 long destination) {
        assertThat(stock(active)).isEqualByComparingTo(activeStock);
        assertThat(stock(legacy)).isEqualByComparingTo(legacyStock);
        assertThat(stock(destination)).isZero();
        assertThat(count("movimiento_inventario")).isZero();
        assertThat(count("production_request")).isZero();
    }
}
