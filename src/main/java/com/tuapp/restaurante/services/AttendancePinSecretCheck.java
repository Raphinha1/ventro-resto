package com.tuapp.restaurante.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AttendancePinSecretCheck implements ApplicationRunner {
    private final JdbcTemplate db;
    private final AttendancePinCodec codec;

    public AttendancePinSecretCheck(JdbcTemplate db, AttendancePinCodec codec) {
        this.db = db;
        this.codec = codec;
    }

    @Override
    public void run(ApplicationArguments args) {
        String expected = codec.secretCheck();
        db.update("insert into attendance_pin_config(singleton, secret_check) values (1, ?) " +
                "on conflict (singleton) do nothing", expected);
        String actual = db.queryForObject(
                "select secret_check from attendance_pin_config where singleton=1", String.class);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("VENTRO_ATTENDANCE_PIN_PEPPER no coincide con el configurado para esta BD.");
        }
    }
}
