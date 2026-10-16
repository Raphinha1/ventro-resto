package com.tuapp.restaurante.services;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Reproduce el typmod NUMERIC(10,2) heredado solo para escritores legacy. */
final class LegacyInventoryPrecision {
    private static final BigDecimal MAX_ABSOLUTE = new BigDecimal("99999999.99");

    private LegacyInventoryPrecision() {}

    static BigDecimal forStorage(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Valor de inventario legacy obligatorio.");
        }
        // PostgreSQL numeric redondea empates alejándose de cero antes de comprobar el rango.
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        if (rounded.abs().compareTo(MAX_ABSOLUTE) > 0) {
            throw new IllegalArgumentException("Valor de inventario fuera de NUMERIC(10,2).");
        }
        return rounded;
    }
}
