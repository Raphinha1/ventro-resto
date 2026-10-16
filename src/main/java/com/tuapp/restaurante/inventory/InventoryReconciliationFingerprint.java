package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Evidence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** Serialización única de los datos del comando aprobados y persistidos en V8. */
final class InventoryReconciliationFingerprint {
    private InventoryReconciliationFingerprint() {}

    static String calculate(Long insumoId, BaseUnit approvedUnit, Evidence historicalEvidence,
                            Long reviewerId, String provenance, String evidenceText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, insumoId.toString());
            add(digest, approvedUnit.name());
            add(digest, historicalEvidence.historicalUnit() == null ? null :
                    historicalEvidence.historicalUnit().name());
            var start = historicalEvidence.trustedStart();
            add(digest, start == null ? null : persistentTime(start.at()).toString());
            add(digest, start == null ? null : start.balance().toPlainString());
            add(digest, reviewerId.toString());
            add(digest, provenance);
            add(digest, evidenceText);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    // Igual que timestamp(6)/pgjdbc: microsegundos, redondeados al más próximo.
    // No cambia el cálculo de inventario; normaliza únicamente la serialización.
    private static LocalDateTime persistentTime(LocalDateTime value) {
        LocalDateTime rounded = value.plusNanos(500);
        return rounded.withNano(rounded.getNano() / 1_000 * 1_000);
    }
}
