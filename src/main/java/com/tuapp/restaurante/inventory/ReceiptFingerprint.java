package com.tuapp.restaurante.inventory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 del comando normalizado; campos UTF-8 delimitados por longitud. */
final class ReceiptFingerprint {
    static final String VERSION = "receipt-command-v1";
    private ReceiptFingerprint() {}

    static String calculate(ReceiptService.Command command) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, VERSION);
            add(digest, command.requestId().toString());
            add(digest, command.actorId().toString());
            add(digest, command.currency());
            add(digest, command.supplierId() == null ? null : command.supplierId().toString());
            add(digest, command.externalDocument());
            add(digest, Integer.toString(command.lines().size()));
            for (var line : command.lines()) {
                add(digest, Integer.toString(line.lineNo()));
                add(digest, line.insumoId().toString());
                add(digest, line.presentationId().toString());
                add(digest, number(line.purchased()));
                add(digest, number(line.receivedBase()));
                add(digest, number(line.cost()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static String number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }
}
