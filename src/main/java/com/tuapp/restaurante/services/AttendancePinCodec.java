package com.tuapp.restaurante.services;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AttendancePinCodec {
    private final byte[] secret;

    public AttendancePinCodec(@Value("${VENTRO_ATTENDANCE_PIN_PEPPER:}") String encodedSecret) {
        try {
            secret = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("VENTRO_ATTENDANCE_PIN_PEPPER debe ser Base64 de al menos 32 bytes.");
        }
        if (secret.length < 32) {
            throw new IllegalStateException("VENTRO_ATTENDANCE_PIN_PEPPER debe ser Base64 de al menos 32 bytes.");
        }
    }

    public String pinDigest(String pin) {
        if (pin == null || !pin.matches("[0-9]{4,6}")) {
            throw new IllegalArgumentException("El PIN debe contener entre 4 y 6 dígitos.");
        }
        return hmac("pin:" + pin);
    }

    public String sourceDigest(String remoteAddress) {
        return hmac("source:" + (remoteAddress == null ? "unknown" : remoteAddress));
    }

    public String secretCheck() {
        return hmac("attendance-pin-secret-check:v1");
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("No se pudo proteger el PIN de asistencia.", exception);
        }
    }
}
