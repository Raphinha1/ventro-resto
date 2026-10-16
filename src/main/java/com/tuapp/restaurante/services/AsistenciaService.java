package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Asistencia;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.repositories.AsistenciaRepository;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Service
public class AsistenciaService {

    private static final DateTimeFormatter MESSAGE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AsistenciaRepository asistenciaRepository;
    private final UsuarioRepository usuarioRepository;
    private final AttendancePinCodec pinCodec;
    private final AttendanceRateLimiter rateLimiter;
    private final JdbcTemplate db;

    public AsistenciaService(AsistenciaRepository asistenciaRepository, UsuarioRepository usuarioRepository,
                             AttendancePinCodec pinCodec, AttendanceRateLimiter rateLimiter, JdbcTemplate db) {
        this.asistenciaRepository = asistenciaRepository;
        this.usuarioRepository = usuarioRepository;
        this.pinCodec = pinCodec;
        this.rateLimiter = rateLimiter;
        this.db = db;
    }

    public List<Asistencia> listarTodas() {
        return asistenciaRepository.findByOrderByHoraEntradaDesc();
    }

    public record MarkResult(boolean success, String message) {}

    private record SavedMark(String pinDigest, String message) {}

    @Transactional
    public MarkResult registrarAsistenciaPorPin(String pin, String requestId, String remoteAddress) {
        UUID key;
        try {
            key = UUID.fromString(requestId);
            if (!key.toString().equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return new MarkResult(false, "Identificador de intento inválido. Recarga el reloj.");
        }

        String digest;
        try {
            digest = pinCodec.pinDigest(pin);
        } catch (IllegalArgumentException exception) {
            digest = null;
        }

        SavedMark previous = findSavedMark(key);
        if (previous != null && digest != null && previous.pinDigest().equals(digest)) {
            return new MarkResult(true, completedMessage(previous));
        }
        if (!rateLimiter.lockAndAllow(remoteAddress)) {
            return new MarkResult(false, "Demasiados intentos. Espera 10 minutos.");
        }
        if (digest == null || previous != null) {
            rateLimiter.recordFailure(remoteAddress);
            return new MarkResult(false, "PIN incorrecto o usuario inactivo");
        }

        int claimed = db.update("insert into attendance_mark_request(request_id,pin_digest) " +
                "values (?,?) on conflict (request_id) do nothing", key, digest);
        if (claimed == 0) {
            SavedMark completed = findSavedMark(key);
            if (completed != null && completed.pinDigest().equals(digest)) {
                return new MarkResult(true, completedMessage(completed));
            }
            rateLimiter.recordFailure(remoteAddress);
            return new MarkResult(false, "PIN incorrecto o usuario inactivo");
        }

        Optional<Usuario> userOpt = usuarioRepository.lockActiveByPinDigest(digest);

        if (userOpt.isEmpty()) {
            db.update("delete from attendance_mark_request where request_id=?", key);
            rateLimiter.recordFailure(remoteAddress);
            return new MarkResult(false, "PIN incorrecto o usuario inactivo");
        }

        Usuario usuario = userOpt.get();

        // Buscar si ya tiene una entrada sin salida
        Optional<Asistencia> asistenciaAbierta = asistenciaRepository.findTopByUsuarioAndHoraSalidaIsNullOrderByHoraEntradaDesc(usuario);

        String message;
        if (asistenciaAbierta.isPresent()) {
            // Marcar salida
            Asistencia a = asistenciaAbierta.get();
            a.marcarSalida(LocalDateTime.now());
            asistenciaRepository.saveAndFlush(a);
            message = "¡Adiós " + usuario.getNombreCompleto() + "! Salida registrada a las " +
                    a.getHoraSalida().toLocalTime().format(MESSAGE_TIME_FORMAT);
        } else {
            // Marcar entrada
            Asistencia nueva = new Asistencia(usuario, LocalDateTime.now());
            asistenciaRepository.saveAndFlush(nueva);
            message = "¡Hola " + usuario.getNombreCompleto() + "! Entrada registrada a las " +
                    nueva.getHoraEntrada().toLocalTime().format(MESSAGE_TIME_FORMAT);
        }
        db.update("update attendance_mark_request set result_message=? where request_id=?", message, key);
        return new MarkResult(true, message);
    }

    private SavedMark findSavedMark(UUID key) {
        return db.query("select pin_digest, result_message from attendance_mark_request where request_id=?",
                result -> result.next() ? new SavedMark(result.getString(1), result.getString(2)) : null, key);
    }

    private String completedMessage(SavedMark mark) {
        if (mark.message() == null) throw new IllegalStateException("Marca idempotente sin resultado confirmado.");
        return mark.message();
    }
}
