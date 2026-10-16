package com.tuapp.restaurante.repositories;

import java.math.BigDecimal;

public interface ProductPerformanceProjection {
    Long getProductoId();
    String getNombre();
    BigDecimal getPrecio();
    Long getCantidad();
    BigDecimal getCosto();
}
