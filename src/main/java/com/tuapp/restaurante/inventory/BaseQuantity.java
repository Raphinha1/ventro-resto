package com.tuapp.restaurante.inventory;

import java.math.BigDecimal;
import java.util.Objects;

/** Saldo o cantidad no negativa en la unidad base canónica. */
public record BaseQuantity(BigDecimal value, BaseUnit unit) {
    public BaseQuantity {
        unit = Objects.requireNonNull(unit, "La unidad base es obligatoria");
        value = InventoryNumbers.base(value);
    }
}
