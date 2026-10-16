package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.InventoryReceipt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface InventoryReceiptRepository extends JpaRepository<InventoryReceipt, Long> {
    @EntityGraph(attributePaths = "lines")
    Optional<InventoryReceipt> findByRequestId(UUID requestId);
}
