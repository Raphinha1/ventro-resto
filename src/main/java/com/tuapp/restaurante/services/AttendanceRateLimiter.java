package com.tuapp.restaurante.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AttendanceRateLimiter {
    private final JdbcTemplate db;
    private final AttendancePinCodec codec;

    public AttendanceRateLimiter(JdbcTemplate db, AttendancePinCodec codec) {
        this.db = db;
        this.codec = codec;
    }

    /** Mantiene bloqueada la fila de origen hasta que termine la transacción de fichaje. */
    public boolean lockAndAllow(String remoteAddress) {
        Integer count = db.queryForObject("""
                insert into attendance_pin_attempt(source_digest, attempt_count, window_started_at)
                values (?, 0, current_timestamp)
                on conflict (source_digest) do update set
                  attempt_count = case
                    when attendance_pin_attempt.window_started_at < current_timestamp - interval '10 minutes'
                    then 0 else attendance_pin_attempt.attempt_count end,
                  window_started_at = case
                    when attendance_pin_attempt.window_started_at < current_timestamp - interval '10 minutes'
                    then current_timestamp else attendance_pin_attempt.window_started_at end
                returning attempt_count
                """, Integer.class, codec.sourceDigest(remoteAddress));
        return count != null && count < 10;
    }

    public void recordFailure(String remoteAddress) {
        db.update("update attendance_pin_attempt set " +
                "window_started_at=case when attempt_count=0 then current_timestamp else window_started_at end, " +
                "attempt_count=least(10, attempt_count+1) " +
                "where source_digest=?", codec.sourceDigest(remoteAddress));
    }
}
