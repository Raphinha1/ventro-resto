package com.tuapp.restaurante.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formatos exactos de persistencia 5B; no aplica cambios de inventario. */
public final class ReceiptNumbers {
    private ReceiptNumbers() {}

    public static BigDecimal quantity(BigDecimal value) {
        BigDecimal result = InventoryNumbers.base(value);
        if (result.signum() <= 0) throw new IllegalArgumentException("Cantidad de recepción debe ser positiva");
        return result;
    }

    public static BigDecimal factor(BigDecimal value) { return InventoryNumbers.factor(value); }

    public static BigDecimal cost(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("Costo real obligatorio y positivo");
        }
        final BigDecimal result;
        try { result = value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Costo real no representable exactamente con escala 2", ex);
        }
        if (result.precision() > 19) throw new IllegalArgumentException("Costo real excede NUMERIC(19,2)");
        return result;
    }
}
