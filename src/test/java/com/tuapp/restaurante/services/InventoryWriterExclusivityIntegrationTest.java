package com.tuapp.restaurante.services;

import com.tuapp.restaurante.inventory.BaseUnit;
import com.tuapp.restaurante.inventory.InventoryReconciliationService;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier;
import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.models.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Frontera de los escritores manuales sobre PostgreSQL descartable. */
@SpringBootTest
@ActiveProfiles("slice0")
class InventoryWriterExclusivityIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired MovimientoInventarioService movements;
    @Autowired InsumoService supplies;
    @Autowired InventoryReconciliationService reconciliation;

    private long actorId;

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate table public.usuario, public.insumo restart identity cascade");
        actorId = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('writer_5a','synthetic','Operador sintético','ROLE_ADMIN',true) returning id",
                Long.class);
    }

    private long ingredient(String stock) {
        return db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                "stock_actual,stock_minimo,activo) values ('Sintético','unidad heredada',5,?::numeric,0,true) " +
                "returning id", Long.class, stock);
    }

    private long reviewedIngredient(boolean active) {
        long id = ingredient("10");
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),
                id, BaseUnit.UNIT, new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT, null),
                actorId, "revisión sintética", "conteo sintético"));
        if (active) {
            // Fixture local: la activación operativa corresponde a 5A.5f.
            db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?", id);
        }
        return id;
    }

    private MovimientoInventario movement(long id, String type, String amount) {
        Insumo supply = new Insumo();
        supply.setId(id);
        Usuario actor = new Usuario();
        actor.setId(actorId);
        MovimientoInventario movement = new MovimientoInventario();
        movement.setInsumo(supply);
        movement.setUsuario(actor);
        movement.setTipoMovimiento(type);
        movement.setCantidad(new BigDecimal(amount));
        return movement;
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, id);
    }

    private long movementCount(long id) {
        return db.queryForObject("select count(*) from movimiento_inventario where insumo_id=?",
                Long.class, id);
    }

    @Test
    void everyManualMovementTypeRejectsActiveIngredientWithoutEffects() {
        long id = reviewedIngredient(true);
        for (String type : new String[] {"ENTRADA", "SALIDA", "MERMA", "AJUSTE"}) {
            assertThatThrownBy(() -> movements.registrarMovimiento(movement(id, type, "1.00")))
                    .hasMessageContaining("ACTIVO_5A");
        }
        assertThat(stock(id)).isEqualByComparingTo("10");
        assertThat(movementCount(id)).isZero();
    }

    @Test
    void pendingAndReviewedIngredientsKeepManualLegacyBehavior() {
        long pending = ingredient("10");
        long reviewed = reviewedIngredient(false);
        for (long id : new long[] {pending, reviewed}) {
            movements.registrarMovimiento(movement(id, "ENTRADA", "2.00"));
            movements.registrarMovimiento(movement(id, "SALIDA", "1.00"));
            movements.registrarMovimiento(movement(id, "AJUSTE", "-0.50"));
            movements.registrarMovimiento(movement(id, "MERMA", "0.25"));
            assertThat(stock(id)).isEqualByComparingTo("10.25");
            assertThat(movementCount(id)).isEqualTo(4);
            assertThat(db.queryForObject("select count(*) from movimiento_inventario " +
                    "where insumo_id=? and operation_id is not null", Long.class, id)).isZero();
        }
    }

    @Test
    void manualWriterCannotUpdateHistoryOrForgeCausalMovement() {
        long id = ingredient("10");
        MovimientoInventario original = movements.registrarMovimiento(movement(id, "ENTRADA", "1"));
        MovimientoInventario changed = movement(id, "ENTRADA", "1");
        changed.setId(original.getId());
        assertThatThrownBy(() -> movements.registrarMovimiento(changed))
                .hasMessageContaining("movimientos legacy nuevos");
        MovimientoInventario forged = movement(id, "ENTRADA", "1");
        forged.setOperationId(UUID.randomUUID());
        assertThatThrownBy(() -> movements.registrarMovimiento(forged))
                .hasMessageContaining("movimientos legacy nuevos");
        assertThat(stock(id)).isEqualByComparingTo("11");
        assertThat(movementCount(id)).isEqualTo(1);
    }

    @Test
    void editingActiveIngredientDoesNotWriteStaleFormStock() {
        long id = reviewedIngredient(true);
        Insumo submitted = new Insumo();
        submitted.setId(id);
        submitted.setNombre("Nombre editado");
        submitted.setUnidadMedida("unidad heredada");
        submitted.setCostoUnitario(new BigDecimal("5"));
        submitted.setStockMinimo(BigDecimal.ZERO);
        submitted.setStockActual(new BigDecimal("999"));
        supplies.guardar(submitted);
        assertThat(stock(id)).isEqualByComparingTo("10");
        assertThat(movementCount(id)).isZero();
    }

    @Test
    void legacyResultingStockKeepsNumeric10RangeAfterV9() {
        assertThatThrownBy(() -> db.queryForObject("select 100000000::numeric(10,2)", BigDecimal.class))
                .isNotNull();
        for (long id : new long[] {ingredient("10"), reviewedIngredient(false)}) {
            db.update("update insumo set stock_actual=99999999.98 where id=?", id);
            movements.registrarMovimiento(movement(id, "ENTRADA", "0.01"));
            for (String type : new String[] {"ENTRADA", "AJUSTE"}) {
                assertThatThrownBy(() -> movements.registrarMovimiento(movement(id, type, "0.01")))
                        .hasMessageContaining("NUMERIC(10,2)");
            }
            assertThat(stock(id)).isEqualByComparingTo("99999999.99");
            assertThat(movementCount(id)).isEqualTo(1);
        }
    }
}
