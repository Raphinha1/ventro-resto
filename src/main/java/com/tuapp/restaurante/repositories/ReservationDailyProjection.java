package com.tuapp.restaurante.repositories;

import java.time.LocalDate;

public interface ReservationDailyProjection {
    LocalDate getDia();
    Long getComensales();
}
