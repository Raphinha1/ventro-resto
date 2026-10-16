package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.InventoryReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InventoryReceiptLineRepository extends JpaRepository<InventoryReceiptLine, Long> {
    List<InventoryReceiptLine> findByReceiptIdOrderByLineNoAsc(Long receiptId);
}
