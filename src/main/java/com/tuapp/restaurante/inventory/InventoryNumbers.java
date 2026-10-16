package com.tuapp.restaurante.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Política numérica aprobada para cantidades base y factores de Slice 5A. */
final class InventoryNumbers {
    private static final int BASE_PRECISION = 19;
    private static final int BASE_SCALE = 6;
    private static final int FACTOR_PRECISION = 24;
    private static final int FACTOR_SCALE = 12;

    private InventoryNumbers() {}

    static BigDecimal base(BigDecimal value) {
        BigDecimal normalized = normalize(value, BASE_SCALE, BASE_PRECISION, "La cantidad base");
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("La cantidad base no puede ser negativa");
        }
        return normalized;
    }

    static BigDecimal factor(BigDecimal value) {
        positive(value, "El factor");
        return normalize(value, FACTOR_SCALE, FACTOR_PRECISION, "El factor");
    }

    static BigDecimal converted(BigDecimal quantity, BigDecimal factor) {
        positive(quantity, "La cantidad operativa");
        return base(quantity.multiply(factor));
    }

    private static void positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(label + " debe ser mayor que cero");
        }
    }

    private static BigDecimal normalize(BigDecimal value, int scale, int precision, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " debe especificarse");
        }
        final BigDecimal normalized;
        try {
            normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(label + " no se representa exactamente con escala " + scale, ex);
        }
        if (normalized.precision() > precision) {
            throw new IllegalArgumentException(label + " excede NUMERIC(" + precision + "," + scale + ")");
        }
        return normalized;
    }
}
