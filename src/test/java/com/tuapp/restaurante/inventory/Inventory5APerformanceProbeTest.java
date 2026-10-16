package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.services.ProduccionService;
import com.tuapp.restaurante.services.VentaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sonda opt-in de servicios, no benchmark HTTP ni gate de latencia de producción. */
@SpringBootTest
@ActiveProfiles("slice0")
@EnabledIfSystemProperty(named = "ventro.inventory.performance", matches = "true")
class Inventory5APerformanceProbeTest {
    @Autowired JdbcTemplate db;
    @Autowired VentaService sales;
    @Autowired ProduccionService production;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryActivationService activation;
    @Autowired LegacyInventoryClassifier classifier;
    @Autowired InventoryChangeApplier applier;
    @Autowired PlatformTransactionManager transactions;
    private long actor;

    private long supply(String stock) {
        return db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                "stock_actual,stock_minimo,activo) values ('Sonda sintética','unidad',5,?::numeric,0,true) " +
                "returning id", Long.class, stock);
    }

    private void activate(long id) {
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(), id,
                BaseUnit.UNIT, new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT, null),
                actor, "sonda sintética", "conteo sintético"));
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(), id, actor));
    }

    private void measure(String label, Runnable operation) {
        for (int i = 0; i < 3; i++) operation.run();
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            operation.run();
            samples.add((System.nanoTime() - start) / 1_000_000.0);
        }
        db.execute("select pg_stat_statements_reset()");
        operation.run();
        Long sql = db.queryForObject("select coalesce(sum(calls),0)::bigint from pg_stat_statements " +
                "where dbid=(select oid from pg_database where datname=current_database()) " +
                "and query not ilike '%pg_stat_statements%'", Long.class);
        Collections.sort(samples);
        System.out.printf(java.util.Locale.ROOT, "PROBE %s n=20 p50=%.2fms p95=%.2fms SQL=%d%n",
                label, (samples.get(9) + samples.get(10)) / 2, samples.get(18), sql);
    }

    @Test
    void sampleServiceFlows() throws Exception {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("create extension if not exists pg_stat_statements");
        for (int size : new int[] {30, 60}) {
            for (boolean active : new boolean[] {false, true}) {
                db.execute("truncate usuario, insumo, producto, corte_caja, pos_sale_request, " +
                        "production_request restart identity cascade");
                actor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                        "values ('sonda','synthetic','Sonda','ROLE_ADMIN',true) returning id", Long.class);
                db.update("insert into corte_caja(fecha_apertura,monto_inicial,estado,total_efectivo," +
                        "total_tarjeta,total_ventas,total_retiros,usuario_id) " +
                        "values (localtimestamp,0,'ABIERTO',0,0,0,0,?)", actor);
                long first = 0;
                long product = 0;
                for (int i = 0; i < size; i++) {
                    long id = supply("100000");
                    if (active) activate(id);
                    long p = db.queryForObject("insert into producto(nombre,precio_venta,puntos_otorgados," +
                            "puntos_precio,activo,es_reventa) values ('Sonda',25,0,0,true,false) returning id", Long.class);
                    db.update("insert into receta(producto_id,insumo_id,cantidad) values (?,?,1)", p, id);
                    if (i == 0) { first = id; product = p; }
                }
                long destination = supply("0");
                if (active) activate(destination);
                db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) values (?,?,1)",
                        destination, first);
                long productId = product;
                String prefix = size + "/" + (active ? "ACTIVO" : "LEGACY");
                measure(prefix + "/venta", () -> sales.procesarVentasMultiples(List.of(productId),
                        List.of(1), List.of(""), actor, "EFECTIVO", new BigDecimal("25"),
                        BigDecimal.ZERO, null, null, List.of(false), UUID.randomUUID().toString()));
                measure(prefix + "/produccion", () -> com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> production.producirInsumo(destination,
                        BigDecimal.ONE, actor, UUID.randomUUID())));
                if (active) {
                    long id = first;
                    measure(prefix + "/applier", () -> applier.apply(new InventoryChangeApplier.Command(
                            UUID.randomUUID(), id, InventoryChangeApplier.Type.SALIDA, BigDecimal.ONE,
                            BaseUnit.UNIT, actor, "SONDA", "SONDA:1", "consumo sintético")));
                }
            }
        }
        for (int count : new int[] {501, 5001}) {
            long id = supply(Integer.toString(count));
            db.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha) " +
                    "select ?,?,'ENTRADA',1,timestamp '2025-01-02' from generate_series(1,?)", id, actor, count);
            var evidence = new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT,
                    new LegacyInventoryClassifier.StartingPoint(LocalDateTime.of(2025,1,1,0,0), BigDecimal.ZERO));
            measure(count + "/clasificacion", () -> assertThat(classifier.classify(id, evidence).classification())
                    .isEqualTo(LegacyInventoryClassifier.Classification.RECONCILIABLE));
            var tx = new TransactionTemplate(transactions);
            measure(count + "/aprobacion+activacion+rollback", () -> tx.executeWithoutResult(status -> {
                reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(), id,
                        BaseUnit.UNIT, evidence, actor, "sonda sintética", null));
                activation.activate(new InventoryActivationService.Command(UUID.randomUUID(), id, actor));
                status.setRollbackOnly();
            }));
            assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class, id))
                    .isEqualTo("PENDIENTE");
        }
        String proc = Files.readString(Path.of("/proc", Long.toString(ProcessHandle.current().pid()), "status"));
        proc.lines().filter(line -> line.startsWith("VmRSS:") || line.startsWith("Threads:"))
                .forEach(line -> System.out.println("PROBE JVM_TEST " + line));
        System.out.println("PROBE DB_connections=" + db.queryForObject(
                "select count(*) from pg_stat_activity where datname=current_database()", Long.class));
    }
}
