package com.tuapp.restaurante.inventory;

import java.math.BigDecimal;
import java.util.Objects;

/** La presentación se identifica explícitamente; su texto nunca determina la familia ni el factor. */
public record UnitConversion(String sourcePresentation, UnitFamily sourceFamily,
                             BaseUnit baseUnit, BigDecimal factor) {
    public UnitConversion {
        if (sourcePresentation == null || sourcePresentation.isBlank()) {
            throw new IllegalArgumentException("La presentación de origen debe especificarse");
        }
        sourceFamily = Objects.requireNonNull(sourceFamily, "La familia de origen es obligatoria");
        baseUnit = Objects.requireNonNull(baseUnit, "La unidad base es obligatoria");
        if (sourceFamily != baseUnit.family()) {
            throw new IllegalArgumentException("La conversión debe permanecer dentro de la misma familia");
        }
        factor = InventoryNumbers.factor(factor);
    }

    public BaseQuantity convert(BigDecimal sourceQuantity) {
        return new BaseQuantity(InventoryNumbers.converted(sourceQuantity, factor), baseUnit);
    }
}
