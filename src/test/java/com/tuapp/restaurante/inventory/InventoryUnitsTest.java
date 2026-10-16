package com.tuapp.restaurante.inventory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryUnitsTest {
    @Test
    void unidadesBaseCanonicas() {
        assertThat(BaseUnit.GRAM.family()).isEqualTo(UnitFamily.MASS);
        assertThat(BaseUnit.GRAM.symbol()).isEqualTo("g");
        assertThat(BaseUnit.MILLILITER.family()).isEqualTo(UnitFamily.VOLUME);
        assertThat(BaseUnit.MILLILITER.symbol()).isEqualTo("ml");
        assertThat(BaseUnit.UNIT.family()).isEqualTo(UnitFamily.COUNT);
        assertThat(BaseUnit.UNIT.symbol()).isEqualTo("unidad");
    }

    @Test
    void conversionesExplicitasExactas() {
        assertQuantity("1250.000000", BaseUnit.GRAM,
                new UnitConversion("kg", UnitFamily.MASS, BaseUnit.GRAM, decimal("1000")).convert(decimal("1.25")));
        assertQuantity("1.250000", BaseUnit.GRAM,
                new UnitConversion("g", UnitFamily.MASS, BaseUnit.GRAM, BigDecimal.ONE).convert(decimal("1.25")));
        assertQuantity("750.000000", BaseUnit.MILLILITER,
                new UnitConversion("L", UnitFamily.VOLUME, BaseUnit.MILLILITER, decimal("1000")).convert(decimal("0.75")));
        assertQuantity("0.750000", BaseUnit.MILLILITER,
                new UnitConversion("ml", UnitFamily.VOLUME, BaseUnit.MILLILITER, BigDecimal.ONE).convert(decimal("0.75")));
        assertQuantity("72.000000", BaseUnit.UNIT,
                new UnitConversion("caja de 24", UnitFamily.COUNT, BaseUnit.UNIT, decimal("24")).convert(decimal("3")));
    }

    @Test
    void aceptaFraccionesExactasYLaCantidadRealCapturada() {
        UnitConversion conversion = new UnitConversion("caja de 24", UnitFamily.COUNT, BaseUnit.UNIT, decimal("24"));
        assertQuantity("3.000000", BaseUnit.UNIT, conversion.convert(decimal("0.125")));
        assertQuantity("0.000001", BaseUnit.GRAM,
                new UnitConversion("presentación medida", UnitFamily.MASS, BaseUnit.GRAM, decimal("2"))
                        .convert(decimal("0.0000005")));
        // La presentación variable puede aportar su medición real sin asumir un factor de caja.
        assertQuantity("7.500000", BaseUnit.UNIT, new BaseQuantity(decimal("7.5"), BaseUnit.UNIT));
    }

    @Test
    void rechazaFamiliasIncompatiblesSinInferirPorTexto() {
        assertThatThrownBy(() -> new UnitConversion("kg", UnitFamily.MASS, BaseUnit.MILLILITER, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("misma familia");
        assertThatThrownBy(() -> new UnitConversion("L", UnitFamily.VOLUME, BaseUnit.UNIT, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("misma familia");
    }

    @Test
    void factorDebeSerPositivoExactoYDentroDeNumeric2412() {
        for (String invalid : new String[] { "0", "-1", "0.0000000000001", "1000000000000" }) {
            assertThatThrownBy(() -> new UnitConversion("kg", UnitFamily.MASS, BaseUnit.GRAM, decimal(invalid)))
                    .as("factor %s", invalid).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new UnitConversion("kg", UnitFamily.MASS, BaseUnit.GRAM, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnitConversion(" ", UnitFamily.MASS, BaseUnit.GRAM, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        UnitConversion max = new UnitConversion("kg", UnitFamily.MASS, BaseUnit.GRAM,
                decimal("999999999999.999999999999"));
        assertThat(max.factor()).isEqualByComparingTo("999999999999.999999999999");
        assertThat(max.factor().scale()).isEqualTo(12);
    }

    @Test
    void cantidadOperativaDebeSerPositiva() {
        UnitConversion conversion = new UnitConversion("unidad", UnitFamily.COUNT, BaseUnit.UNIT, BigDecimal.ONE);
        for (String invalid : new String[] { "0", "-0.1" }) {
            assertThatThrownBy(() -> conversion.convert(decimal(invalid)))
                    .as("cantidad %s", invalid).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> conversion.convert(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BaseQuantity(decimal("-1"), BaseUnit.UNIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertQuantity("0.000000", BaseUnit.UNIT, new BaseQuantity(BigDecimal.ZERO, BaseUnit.UNIT));
    }

    @Test
    void rechazaResultadosQueExigenRedondeo() {
        UnitConversion conversion = new UnitConversion("presentación", UnitFamily.MASS, BaseUnit.GRAM, decimal("0.5"));
        assertThatThrownBy(() -> conversion.convert(decimal("0.333333")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escala 6");
        assertThatThrownBy(() -> new BaseQuantity(decimal("0.0000001"), BaseUnit.GRAM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escala 6");
    }

    @Test
    void rechazaValoresFueraDeNumeric196() {
        assertQuantity("9999999999999.999999", BaseUnit.GRAM,
                new BaseQuantity(decimal("9999999999999.999999"), BaseUnit.GRAM));
        assertThatThrownBy(() -> new BaseQuantity(decimal("10000000000000"), BaseUnit.GRAM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NUMERIC(19,6)");
        UnitConversion conversion = new UnitConversion("kg", UnitFamily.MASS, BaseUnit.GRAM, decimal("1000"));
        assertThatThrownBy(() -> conversion.convert(decimal("10000000000")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NUMERIC(19,6)");
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void assertQuantity(String value, BaseUnit unit, BaseQuantity quantity) {
        assertThat(quantity.value()).isEqualTo(new BigDecimal(value));
        assertThat(quantity.unit()).isEqualTo(unit);
    }
}
