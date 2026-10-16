package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.MovimientoInventario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MovimientoInventarioRepository extends JpaRepository<MovimientoInventario, Long> {
    Optional<MovimientoInventario> findByOperationId(UUID operationId);
    List<MovimientoInventario> findByOperationIdIn(List<UUID> operationIds);
    List<MovimientoInventario> findByInsumoIdOrderByFechaDesc(Long insumoId);
    
    @EntityGraph(attributePaths = {"insumo", "usuario"})
    List<MovimientoInventario> findTop10ByOrderByFechaDesc();

    List<MovimientoInventario> findByInsumoIdAndTipoMovimientoInAndFechaAfter(Long insumoId, List<String> tipos, java.time.LocalDateTime desde);

    @Query("select m.insumo.id as insumoId, sum(m.cantidad) as cantidad, count(m.id) as cuenta " +
            "from MovimientoInventario m where m.tipoMovimiento in :tipos and m.fecha > :desde " +
            "group by m.insumo.id")
    List<MovementAggregateProjection> resumirConsumo(@Param("tipos") List<String> tipos,
                                                     @Param("desde") java.time.LocalDateTime desde);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM MovimientoInventario m WHERE m.tipoMovimiento = 'MERMA' AND m.fecha >= :desde ORDER BY m.fecha DESC")
    List<MovimientoInventario> findMermasDesde(@org.springframework.data.repository.query.Param("desde") java.time.LocalDateTime desde);

    @Query("select m.insumo.id as insumoId, sum(m.cantidad) as cantidad, count(m.id) as cuenta " +
            "from MovimientoInventario m where m.tipoMovimiento = 'MERMA' and m.fecha >= :desde " +
            "group by m.insumo.id")
    List<MovementAggregateProjection> resumirMermas(@Param("desde") java.time.LocalDateTime desde);
}
