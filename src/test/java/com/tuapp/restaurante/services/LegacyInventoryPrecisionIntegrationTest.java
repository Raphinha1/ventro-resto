package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Insumo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Paridad de las dos escrituras legacy con el typmod NUMERIC(10,2) de V8. */
@SpringBootTest
@ActiveProfiles("slice0")
class LegacyInventoryPrecisionIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired InsumoService insumos;
    @Autowired ProduccionService produccion;

    private long actor;

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate table public.usuario, public.insumo restart identity cascade");
        actor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('precision_test','synthetic','Prueba sintética','ROLE_ADMIN',true) returning id",
                Long.class);
    }

    private BigDecimal v8Numeric(String value) {
        return db.queryForObject("select ?::numeric(10,2)", BigDecimal.class, new BigDecimal(value));
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, id);
    }

    private long newSupply(String name, String initialStock) {
        Insumo supply = new Insumo();
        supply.setNombre(name);
        supply.setUnidadMedida("unidad heredada");
        supply.setCostoUnitario(new BigDecimal("2.00"));
        supply.setStockMinimo(BigDecimal.ZERO);
        supply.setStockActual(new BigDecimal(initialStock));
        return insumos.guardar(supply).getId();
    }

    @Test
    void legacyNumericPolicyMatchesPostgresAlsoForSignedTiesAndLimits() {
        for (String value : List.of("0.004", "0.005", "-1.234", "-1.235",
                "-99999999.994")) {
            assertThat(LegacyInventoryPrecision.forStorage(new BigDecimal(value)))
                    .isEqualByComparingTo(v8Numeric(value));
        }
        assertThatThrownBy(() -> v8Numeric("-99999999.995")).isNotNull();
        assertThatThrownBy(() -> LegacyInventoryPrecision.forStorage(
                new BigDecimal("-99999999.995"))).hasMessageContaining("NUMERIC(10,2)");
    }

    @Test
    void newSupplyMatchesV8RoundingAndRange() {
        long down = newSupply("Baja", "1.234");
        long up = newSupply("Sube", "1.235");
        long limit = newSupply("Límite", "99999999.994");
        assertThat(stock(down)).isEqualByComparingTo(v8Numeric("1.234"));
        assertThat(stock(up)).isEqualByComparingTo(v8Numeric("1.235"));
        assertThat(stock(limit)).isEqualByComparingTo(v8Numeric("99999999.994"));
        assertThat(stock(down)).isEqualByComparingTo("1.23");
        assertThat(stock(up)).isEqualByComparingTo("1.24");
        assertThat(stock(limit)).isEqualByComparingTo("99999999.99");

        assertThatThrownBy(() -> v8Numeric("99999999.995")).isNotNull();
        assertThatThrownBy(() -> newSupply("Rebasa", "99999999.995"))
                .hasMessageContaining("NUMERIC(10,2)");
        assertThat(db.queryForObject("select count(*) from insumo", Long.class)).isEqualTo(3);
    }

    @Test
    void productionMatchesV8RoundingForStockAndMovements() {
        for (String quantity : new String[] {"1.234", "1.235"}) {
            long ingredient = newSupply("Ingrediente " + quantity, "10.00");
            long destination = newSupply("Destino " + quantity, "0.00");
            db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) " +
                    "values (?,?,1.0000)", destination, ingredient);

            com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> produccion.producirInsumo(destination, new BigDecimal(quantity), actor));

            assertThat(stock(ingredient)).isEqualByComparingTo(v8Numeric(new BigDecimal("10.00")
                    .subtract(new BigDecimal(quantity)).toPlainString()));
            assertThat(stock(destination)).isEqualByComparingTo(v8Numeric(quantity));
            assertThat(db.queryForObject("select cantidad from movimiento_inventario " +
                    "where insumo_id=?", BigDecimal.class, ingredient))
                    .isEqualByComparingTo(v8Numeric(quantity));
            assertThat(db.queryForObject("select cantidad from movimiento_inventario " +
                    "where insumo_id=?", BigDecimal.class, destination))
                    .isEqualByComparingTo(v8Numeric(quantity));
        }
    }

    @Test
    void subRecipeFractionUsesV8StorageScaleWithoutChangingRawCostCalculation() {
        long ingredient = newSupply("Fraccionario", "10.00");
        long destination = newSupply("Elaborado", "0.00");
        db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) " +
                "values (?,?,0.0001)", destination, ingredient);

        com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> produccion.producirInsumo(destination, new BigDecimal("1.00"), actor));

        assertThat(stock(ingredient)).isEqualByComparingTo(v8Numeric("9.9999"));
        assertThat(db.queryForObject("select cantidad from movimiento_inventario " +
                "where insumo_id=?", BigDecimal.class, ingredient)).isEqualByComparingTo(v8Numeric("0.0001"));
        assertThat(stock(destination)).isEqualByComparingTo("1.00");
        assertThat(db.queryForObject("select costo_unitario from insumo where id=?",
                BigDecimal.class, destination)).isEqualByComparingTo("0.00");

        long secondIngredient = newSupply("Costo fraccionario", "10.00");
        long secondDestination = newSupply("Costo elaborado", "0.00");
        db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) " +
                "values (?,?,0.3333)", secondDestination, secondIngredient);
        com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> produccion.producirInsumo(secondDestination, BigDecimal.ONE, actor));
        assertThat(db.queryForObject("select cantidad from movimiento_inventario " +
                "where insumo_id=?", BigDecimal.class, secondIngredient)).isEqualByComparingTo("0.33");
        assertThat(db.queryForObject("select costo_unitario from insumo where id=?",
                BigDecimal.class, secondDestination)).isEqualByComparingTo("0.67");
    }
}
