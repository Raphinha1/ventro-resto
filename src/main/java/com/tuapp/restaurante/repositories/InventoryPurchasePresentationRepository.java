package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.InventoryPurchasePresentation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface InventoryPurchasePresentationRepository extends JpaRepository<InventoryPurchasePresentation, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from InventoryPurchasePresentation p where p.id = :id")
    Optional<InventoryPurchasePresentation> lockById(@Param("id") Long id);

    List<InventoryPurchasePresentation> findByInsumoIdOrderByIdAsc(Long insumoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InventoryPurchasePresentation p where p.id = :id and p.insumo.id = :insumoId")
    Optional<InventoryPurchasePresentation> lockForManagement(@Param("id") Long id, @Param("insumoId") Long insumoId);

    List<InventoryPurchasePresentation> findByInsumoIdAndEstado(Long insumoId, InventoryPurchasePresentation.Status estado);
}
