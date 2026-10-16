package com.tuapp.restaurante.repositories;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailySalesProjection {
    LocalDate getDia();
    BigDecimal getTotal();
}
