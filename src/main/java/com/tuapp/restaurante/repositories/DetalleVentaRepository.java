package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.DetalleVenta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

@Repository
public interface DetalleVentaRepository extends JpaRepository<DetalleVenta, Long> {
    @EntityGraph(attributePaths = {"venta", "venta.usuario", "producto"})
    java.util.List<DetalleVenta> findByEstadoInOrderByVenta_FechaAsc(java.util.List<String> estados);
}
