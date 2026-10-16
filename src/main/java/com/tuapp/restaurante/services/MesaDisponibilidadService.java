package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Mesa;
import com.tuapp.restaurante.models.Reserva;
import com.tuapp.restaurante.repositories.ReservaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MesaDisponibilidadService {
    private final ReservaRepository reservaRepository;
    private final int ventanaBloqueoMinutos;

    public MesaDisponibilidadService(ReservaRepository reservaRepository,
            @Value("${ventro.reservas.ventana-bloqueo-minutos:30}") int ventanaBloqueoMinutos) {
        if (ventanaBloqueoMinutos < 0) {
            throw new IllegalArgumentException("La ventana de bloqueo de reservas no puede ser negativa");
        }
        this.reservaRepository = reservaRepository;
        this.ventanaBloqueoMinutos = ventanaBloqueoMinutos;
    }

    public List<Reserva> proximas(LocalDateTime ahora) {
        return reservaRepository.findProximasActivas(ahora);
    }

    public Optional<Reserva> reservaBloqueante(Mesa mesa, List<Reserva> proximas, LocalDateTime ahora) {
        return proximas.stream()
                .filter(r -> r.getMesa() != null && mesa.getId().equals(r.getMesa().getId()))
                .filter(r -> "CONFIRMADA".equals(r.getEstado()))
                .filter(r -> !r.getFechaHora().isBefore(ahora)
                        && !r.getFechaHora().isAfter(ahora.plusMinutes(ventanaBloqueoMinutos)))
                .findFirst();
    }

    public String estadoEfectivo(Mesa mesa, List<Reserva> proximas, LocalDateTime ahora) {
        if ("OCUPADA".equals(mesa.getEstado()) || "SUCIA".equals(mesa.getEstado())) {
            return mesa.getEstado();
        }
        if (!"LIBRE".equals(mesa.getEstado()) && !"RESERVADA".equals(mesa.getEstado())) {
            throw new IllegalStateException("Estado de mesa desconocido: " + mesa.getEstado());
        }
        return reservaBloqueante(mesa, proximas, ahora).isPresent() ? "RESERVADA" : "LIBRE";
    }
}
