package com.tuapp.restaurante.repositories;

import java.math.BigDecimal;

public interface WeekdaySalesProjection {
    Integer getDiaSemana();
    BigDecimal getTotal();
    Long getCuenta();
}
