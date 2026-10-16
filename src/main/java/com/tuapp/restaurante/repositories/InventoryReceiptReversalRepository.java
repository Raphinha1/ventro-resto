package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.InventoryReceiptReversal;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface InventoryReceiptReversalRepository extends JpaRepository<InventoryReceiptReversal,Long> {
    @EntityGraph(attributePaths="lines")
    Optional<InventoryReceiptReversal> findByRequestId(UUID requestId);
    boolean existsByReceiptId(Long receiptId);
}
