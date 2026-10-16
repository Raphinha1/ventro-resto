package com.tuapp.restaurante.inventory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Huella del comando normalizado; no incluye saldo ni catálogo actuales. */
final class ReceiptReversalFingerprint {
    static final String VERSION="receipt-reversal-command-v1";
    private ReceiptReversalFingerprint() {}
    static String calculate(ReceiptReversalService.Command command) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{VERSION,command.requestId().toString(),command.receiptId().toString(),
                    command.actorId().toString(),command.motivo()}) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible",ex); }
    }
}
