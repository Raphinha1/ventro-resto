package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Insumo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface InsumoRepository extends JpaRepository<Insumo, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Insumo i where i.id = :id")
    Optional<Insumo> lockById(@Param("id") Long id);

    /** Lista todos los insumos que no han sido marcados como borrados (activo=true). */
    @EntityGraph(attributePaths = {"proveedor", "categoria"})
    java.util.List<Insumo> findByActivoTrueOrderByIdAsc();

    /** Cuenta insumos activos que están por debajo del nivel de stock mínimo. */
    long countByActivoTrueAndStockActualLessThanEqual(java.math.BigDecimal stockMinimo);

    // Método solicitado para comparar con un valor fijo
    List<Insumo> findByStockActualLessThanEqual(BigDecimal stockMinimo);

    // Método óptimo para comparar interno entre las columnas
    @Query("SELECT i FROM Insumo i WHERE i.stockActual <= i.stockMinimo AND i.stockMinimo > 0 AND i.activo = true")
    List<Insumo> findInsumosConStockCritico();
}
