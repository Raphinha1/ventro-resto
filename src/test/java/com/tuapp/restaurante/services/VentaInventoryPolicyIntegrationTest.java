package com.tuapp.restaurante.services;

import com.tuapp.restaurante.inventory.BaseUnit;
import com.tuapp.restaurante.inventory.InventoryActivationService;
import com.tuapp.restaurante.inventory.InventoryReconciliationService;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier;
import com.tuapp.restaurante.models.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Venta 5A.5c: únicamente PostgreSQL sintético descartable de slice0-test.sh. */
@SpringBootTest
@ActiveProfiles("slice0")
class VentaInventoryPolicyIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired VentaService ventas;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryActivationService activation;
    @Autowired ProduccionService production;
    @Autowired PlatformTransactionManager transactionManager;

    private long actor;
    private long shift;

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate table public.usuario, public.insumo, public.producto, " +
                "public.corte_caja, public.pos_sale_request, public.production_request restart identity cascade");
        actor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('venta_5a','synthetic','Cajero sintético','ROLE_ADMIN',true) returning id", Long.class);
        shift = db.queryForObject("insert into corte_caja(fecha_apertura,monto_inicial,estado," +
                "total_efectivo,total_tarjeta,total_ventas,total_retiros,usuario_id) " +
                "values (timezone('America/Monterrey', now()),100,'ABIERTO',0,0,0,0,?) returning id",
                Long.class, actor);
    }

    private long ingredient(String name, String stock) {
        return db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                "stock_actual,stock_minimo,activo) values (?,'texto heredado',5,?::numeric,0,true) returning id",
                Long.class, name, stock);
    }

    private long reviewedIngredient(String name, String stock, BaseUnit unit, boolean active) {
        long id = ingredient(name, stock);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),
                id, unit, new LegacyInventoryClassifier.Evidence(unit, null), actor,
                "revisión sintética", "conteo sintético"));
        if (active) {
            // Solo fixture local: la transición operativa pertenece a 5A.5f.
            db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?", id);
        }
        return id;
    }

    private long product(String name, String price, long ingredient, String recipeQuantity) {
        long id = db.queryForObject("insert into producto(nombre,precio_venta,puntos_otorgados," +
                "puntos_precio,activo,es_reventa) values (?,?::numeric,2,0,true,false) returning id",
                Long.class, name, price);
        db.update("insert into receta(producto_id,insumo_id,cantidad) values (?,?,?::numeric)",
                id, ingredient, recipeQuantity);
        return id;
    }

    private Venta sale(List<Long> products, List<Integer> quantities, UUID key) {
        return ventas.procesarVentasMultiples(products, quantities,
                Collections.nCopies(products.size(), ""), actor, "EFECTIVO",
                new BigDecimal("100"), BigDecimal.ZERO, null, null,
                Collections.nCopies(products.size(), false), key.toString());
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, id);
    }

    private long count(String table) {
        return db.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void pendingAndReviewedSalesKeepLegacyMovementsAndPrecision() {
        long pending = ingredient("Pendiente", "10");
        long reviewed = reviewedIngredient("Reconciliado", "10", BaseUnit.UNIT, false);
        long p1 = product("Uno", "10", pending, "1.25");
        long p2 = product("Dos", "10", reviewed, "2.00");

        Venta sale = sale(List.of(p1, p2), List.of(2, 1), UUID.randomUUID());
        assertThat(sale.getCorteCaja().getId()).isEqualTo(shift);
        assertThat(stock(pending)).isEqualByComparingTo("7.50");
        assertThat(stock(reviewed)).isEqualByComparingTo("8.00");
        assertThat(db.queryForList("select tipo_movimiento from movimiento_inventario order by id", String.class))
                .containsExactly("SALIDA_VENTA", "SALIDA_VENTA");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where operation_id is not null",
                Long.class)).isZero();
    }

    @Test
    void comparableLegacyAndActiveSalesHaveSameStockAndSaleResult() {
        long legacy = ingredient("Legacy", "10");
        long active = reviewedIngredient("Activo", "10", BaseUnit.UNIT, true);
        long legacyProduct = product("Platillo legacy", "10", legacy, "1.25");
        long activeProduct = product("Platillo activo", "10", active, "1.25");

        Venta legacySale = sale(List.of(legacyProduct), List.of(2), UUID.randomUUID());
        Venta activeSale = sale(List.of(activeProduct), List.of(2), UUID.randomUUID());

        assertThat(activeSale.getTotal()).isEqualByComparingTo(legacySale.getTotal());
        assertThat(stock(active)).isEqualByComparingTo(stock(legacy));
        assertThat(stock(active)).isEqualByComparingTo("7.50");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                Long.class, legacy)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                Long.class, active)).isEqualTo(1);
        assertThat(db.queryForObject("select resulting_stock from movimiento_inventario where insumo_id=?",
                BigDecimal.class, active)).isEqualByComparingTo(stock(legacy));
    }

    @Test
    void activeSaleUsesOneCausalMovementAndStableSaleReference() {
        long active = reviewedIngredient("Activo", "10", BaseUnit.GRAM, true);
        long product = product("Platillo", "10", active, "1.2345");
        long client = db.queryForObject("insert into clientes(nombre,tipo,puntos,saldo_credito," +
                "limite_credito,total_gastado,fecha_registro,activo) " +
                "values ('Cliente sintético','VIP',0,0,100,0,now(),true) returning id", Long.class);
        UUID key = UUID.randomUUID();

        Venta sale = ventas.procesarVentasMultiples(List.of(product), List.of(2), List.of(""),
                actor, "MIXTO", new BigDecimal("8"), new BigDecimal("12"), null, client,
                List.of(false), key.toString());
        assertThat(stock(active)).isEqualByComparingTo("7.531000");
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        var movement = db.queryForMap("select tipo_movimiento,cantidad,operation_id,causal_origin," +
                "causal_reference,resulting_stock from movimiento_inventario where insumo_id=?", active);
        assertThat(movement).containsEntry("tipo_movimiento", "SALIDA_VENTA")
                .containsEntry("causal_origin", "VENTA_POS")
                .containsEntry("causal_reference", "VENTA:" + sale.getId());
        assertThat((BigDecimal) movement.get("cantidad")).isEqualByComparingTo("2.469000");
        assertThat((BigDecimal) movement.get("resulting_stock")).isEqualByComparingTo("7.531000");
        assertThat(movement.get("operation_id")).isEqualTo(operationId(key, active));
        assertThat(db.queryForObject("select venta_id from pos_sale_request where idempotency_key=?",
                Long.class, key)).isEqualTo(sale.getId());
        assertThat(sale.getCorteCaja().getId()).isEqualTo(shift);
        assertThat(sale.getTotal()).isEqualByComparingTo("20");
        assertThat(sale.getMontoEfectivo()).isEqualByComparingTo("8");
        assertThat(sale.getMontoTarjeta()).isEqualByComparingTo("12");
        assertThat(db.queryForObject("select puntos from clientes where id=?", Integer.class, client))
                .isEqualTo(4);
    }

    @Test
    void mixedSaleKeepsOneWriterPerState() {
        long legacy = ingredient("Legacy", "10");
        long active = reviewedIngredient("Activo", "5", BaseUnit.GRAM, true);
        long p1 = product("Legacy", "10", legacy, "2.00");
        long p2 = product("Activo", "10", active, "1.2345");

        Venta sale = sale(List.of(p1, p2), List.of(1, 1), UUID.randomUUID());
        assertThat(sale.getTotal()).isEqualByComparingTo("20");
        assertThat(stock(legacy)).isEqualByComparingTo("8");
        assertThat(stock(active)).isEqualByComparingTo("3.7655");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario " +
                "where insumo_id=? and operation_id is null", Long.class, legacy)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario " +
                "where insumo_id=? and operation_id is not null", Long.class, active)).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void repeatedRecipesAggregateActiveConsumptionOnceAndRetryDoesNotRepeatIt() {
        long active = reviewedIngredient("Compartido", "10", BaseUnit.UNIT, true);
        long p1 = product("Uno", "10", active, "1.25");
        long p2 = product("Dos", "10", active, "0.75");
        UUID key = UUID.randomUUID();

        Venta first = sale(List.of(p1, p2), List.of(2, 3), key);
        Venta retry = sale(List.of(p1, p2), List.of(2, 3), key);
        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(stock(active)).isEqualByComparingTo("5.25");
        assertThat(db.queryForObject("select cantidad from movimiento_inventario where insumo_id=?",
                BigDecimal.class, active)).isEqualByComparingTo("4.75");
        assertThat(count("ventas")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        assertThat(count("pos_sale_request")).isEqualTo(1);
    }

    @Test
    void activeShortageRejectsEntireMixedSale() {
        long legacy = ingredient("Legacy", "10");
        long active = reviewedIngredient("Activo", "1", BaseUnit.UNIT, true);
        long p1 = product("Uno", "10", legacy, "2");
        long p2 = product("Dos", "10", active, "2");

        assertThatThrownBy(() -> sale(List.of(p1, p2), List.of(1, 1), UUID.randomUUID()))
                .hasMessageContaining("Stock insuficiente");
        assertNoSaleEffects(legacy, "10", active, "1");
    }

    @Test
    void legacyShortageRejectsEntireMixedSale() {
        long active = reviewedIngredient("Activo", "10", BaseUnit.UNIT, true);
        long legacy = ingredient("Legacy", "1");
        long p1 = product("Uno", "10", active, "2");
        long p2 = product("Dos", "10", legacy, "2");

        assertThatThrownBy(() -> sale(List.of(p1, p2), List.of(1, 1), UUID.randomUUID()))
                .hasMessageContaining("Stock insuficiente");
        assertNoSaleEffects(active, "10", legacy, "1");
    }

    @Test
    void laterFailureInSameTransactionRollsBackSaleAndBothInventories() {
        long legacy = ingredient("Legacy", "10");
        long active = reviewedIngredient("Activo", "10", BaseUnit.UNIT, true);
        long p1 = product("Uno", "10", legacy, "2");
        long p2 = product("Dos", "10", active, "1.25");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> {
            sale(List.of(p1, p2), List.of(1, 1), UUID.randomUUID());
            assertThat(count("movimiento_inventario")).isEqualTo(2);
            throw new IllegalStateException("fallo sintético posterior al inventario");
        })).hasMessageContaining("fallo sintético posterior");
        assertNoSaleEffects(legacy, "10", active, "10");
    }

    @Test
    void legacyRecipeStillRejectsMoreThanTwoDecimals() {
        long legacy = ingredient("Legacy", "10");
        long product = product("Inválido", "10", legacy, "1.2345");
        assertThatThrownBy(() -> sale(List.of(product), List.of(1), UUID.randomUUID()))
                .hasMessageContaining("dos decimales");
        assertNoSaleEffects(legacy, "10", legacy, "10");
    }

    @Test
    void actualActivationThenConcurrentSaleAndProductionCannotLoseOrOverdrawStock() throws Exception {
        long source = reviewedIngredient("Última unidad", "1", BaseUnit.UNIT, false);
        long destination = reviewedIngredient("Destino activo", "0", BaseUnit.UNIT, false);
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(), source, actor));
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(), destination, actor));
        long product = product("Disputa", "25", source, "1");
        db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) values (?,?,1)",
                destination, source);
        boolean sold;
        boolean produced;
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var sale = pool.submit(() -> {
                start.await();
                try { sale(List.of(product), List.of(1), UUID.randomUUID()); return true; }
                catch (IllegalArgumentException ex) {
                    assertThat(ex).hasMessageContaining("Stock insuficiente"); return false;
                }
            });
            var manufacturing = pool.submit(() -> {
                start.await();
                try { com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> production.producirInsumo(destination, BigDecimal.ONE, actor, UUID.randomUUID())); return true; }
                catch (IllegalArgumentException ex) {
                    assertThat(ex).hasMessageContaining("Stock insuficiente"); return false;
                }
            });
            start.countDown();
            sold = sale.get(20, java.util.concurrent.TimeUnit.SECONDS);
            produced = manufacturing.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(sold ^ produced).isTrue();
        assertThat(stock(source)).isEqualByComparingTo("0");
        assertThat(stock(destination)).isEqualByComparingTo(produced ? "1" : "0");
        assertThat(count("ventas")).isEqualTo(sold ? 1 : 0);
        assertThat(count("production_request")).isEqualTo(produced ? 1 : 0);
        assertThat(count("movimiento_inventario")).isEqualTo(produced ? 2 : 1);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where operation_id is null",
                Long.class)).isZero();
    }

    private void assertNoSaleEffects(long a, String aStock, long b, String bStock) {
        assertThat(stock(a)).isEqualByComparingTo(aStock);
        assertThat(stock(b)).isEqualByComparingTo(bStock);
        assertThat(count("ventas")).isZero();
        assertThat(count("movimiento_inventario")).isZero();
        assertThat(count("pos_sale_request")).isZero();
    }

    private UUID operationId(UUID key, long ingredientId) {
        return UUID.nameUUIDFromBytes(("VENTA_POS|" + key + "|" + ingredientId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
