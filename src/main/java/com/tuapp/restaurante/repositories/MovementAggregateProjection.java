package com.tuapp.restaurante.repositories;

import java.math.BigDecimal;

public interface MovementAggregateProjection {
    Long getInsumoId();
    BigDecimal getCantidad();
    Long getCuenta();
}
