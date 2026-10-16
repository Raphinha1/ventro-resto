package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Gasto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GastoRepository extends JpaRepository<Gasto, Long> {
    @Query("select coalesce(sum(g.monto), 0) from Gasto g where g.activo = true")
    java.math.BigDecimal sumMontoActivos();
    List<Gasto> findByActivoTrue();
    List<Gasto> findByActivoTrueOrderByFechaDesc();

    List<Gasto> findByActivoTrueAndFechaBetweenOrderByFechaDesc(LocalDateTime start, LocalDateTime end);
}
