package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Mesa;
import com.tuapp.restaurante.security.AccessPolicy;
import com.tuapp.restaurante.security.ApplicationRole;
import org.springframework.security.access.AccessDeniedException;
import com.tuapp.restaurante.models.Reserva;
import com.tuapp.restaurante.repositories.MesaRepository;
import com.tuapp.restaurante.repositories.ReservaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservaService {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;
    private final ReservaRepository reservaRepository;
    private final AccessPolicy access;
    private final MesaRepository mesaRepository;
    private final MesaDisponibilidadService disponibilidadService;

    public ReservaService(ReservaRepository reservaRepository, MesaRepository mesaRepository,
            MesaDisponibilidadService disponibilidadService, AccessPolicy access) {
        this.access = access;
        this.reservaRepository = reservaRepository;
        this.mesaRepository = mesaRepository;
        this.disponibilidadService = disponibilidadService;
    }

    public List<Reserva> getAllReservas() {
        return reservaRepository.findAll();
    }

    public List<Reserva> getProximasActivas() {
        return reservaRepository.findProximasActivas(LocalDateTime.now().minusHours(1));
    }

    public Optional<Reserva> getReservaById(Long id) {
        return reservaRepository.findById(id);
    }

    @Transactional
    public Reserva saveReserva(Reserva entrada) {
        var actor = access.requireReservationAccess();
        if (entrada.getId() != null) {
            access.requireManagement();
        } else if (Set.of(ApplicationRole.EMPLEADO, ApplicationRole.MESERO)
                .contains(ApplicationRole.parse(actor.getRol()))
                && entrada.getEstado() != null && !"PENDIENTE".equals(entrada.getEstado())) {
            throw new AccessDeniedException("El alta de reservas solo permite estado PENDIENTE");
        }
        Reserva reserva = entrada.getId() == null ? new Reserva() : lockReservationAndTables(entrada.getId(), entrada.getMesa(), true);
        String nuevoEstado = entrada.getEstado() == null ? "PENDIENTE" : entrada.getEstado();
        validarTransicion(reserva.getId() == null ? null : reserva.getEstado(), nuevoEstado);
        Mesa mesa = buscarMesa(entrada.getMesa());
        if ("EN_CURSO".equals(reserva.getEstado())
                && (reserva.getMesa() == null || mesa == null
                || !reserva.getMesa().getId().equals(mesa.getId()))) {
            throw new IllegalArgumentException("No se puede cambiar la mesa de una reserva en curso");
        }
        validarDatos(entrada.getFechaHora(), entrada.getNumeroPersonas());
        validarConflictos(mesa, entrada.getFechaHora(), nuevoEstado, reserva.getId());

        reserva.setNombreCliente(entrada.getNombreCliente());
        reserva.setTelefonoCliente(entrada.getTelefonoCliente());
        if (reserva.getId() == null || entrada.getEmailCliente() != null) {
            reserva.setEmailCliente(entrada.getEmailCliente());
        }
        reserva.setCliente(entrada.getCliente() != null && entrada.getCliente().getId() != null
                ? entrada.getCliente() : null);
        reserva.setMesa(mesa);
        reserva.setFechaHora(entrada.getFechaHora());
        reserva.setNumeroPersonas(entrada.getNumeroPersonas());
        reserva.setNotas(entrada.getNotas());
        reserva.setOrigen(entrada.getOrigen() == null ? "TELEFONO" : entrada.getOrigen());
        aplicarEstado(reserva, nuevoEstado, mesa);
        return reservaRepository.save(reserva);
    }

    @Transactional
    public Reserva actualizarHorario(Long id, LocalDateTime fecha) {
        access.requireManagement();
        Reserva reserva = lockReservationAndTables(id, null, false);
        Mesa mesa = buscarMesa(reserva.getMesa());
        validarDatos(fecha, reserva.getNumeroPersonas());
        validarConflictos(mesa, fecha, reserva.getEstado(), reserva.getId());
        reserva.setFechaHora(fecha);
        return reserva;
    }

    @Transactional
    public void deleteReserva(Long id) {
        access.requireManagement();
        Reserva reserva = lockReservationAndTables(id, null, false);
        if (reserva.getMesa() != null) buscarMesa(reserva.getMesa());
        reservaRepository.delete(reserva);
    }

    @Transactional
    public Reserva cambiarEstado(Long id, String nuevoEstado) {
        access.requireManagement();
        Reserva reserva = lockReservationAndTables(id, null, false);
        validarTransicion(reserva.getEstado(), nuevoEstado);
        Mesa mesa = buscarMesa(reserva.getMesa());
        validarConflictos(mesa, reserva.getFechaHora(), nuevoEstado, reserva.getId());
        aplicarEstado(reserva, nuevoEstado, mesa);
        return reserva;
    }

    /**
     * Lock tables (old and requested, by ID), then the reservation. Table deletion
     * and seating use the same Mesa -> Reserva order. The scalar snapshot bypasses
     * the first-level cache; reject a changed association rather than acquiring a
     * newly discovered table lock after the reservation, which would invert order.
     */
    private Reserva lockReservationAndTables(Long id, Mesa requested, boolean replacingTable) {
        Long previous = reservaRepository.findMesaId(id);
        Long next = replacingTable && requested != null ? requested.getId() : null;
        var tableIds = new java.util.TreeSet<Long>();
        if (previous != null) tableIds.add(previous);
        if (next != null) tableIds.add(next);
        for (Long tableId : tableIds) {
            var table = mesaRepository.findByIdForUpdate(tableId);
            if (table.isPresent()) {
                entities.refresh(table.get(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            } else if (tableId.equals(next)) {
                throw new IllegalArgumentException("La mesa de la reserva ya no existe; vuelve a intentarlo");
            }
        }
        Reserva reservation = reservaRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Reserva no encontrada"));
        entities.refresh(reservation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Long current = reservation.getMesa() == null ? null : reservation.getMesa().getId();
        if (!java.util.Objects.equals(previous, current)) {
            throw new IllegalArgumentException("La mesa de la reserva cambió; vuelve a intentarlo");
        }
        return reservation;
    }

    private Mesa buscarMesa(Mesa referencia) {
        if (referencia == null || referencia.getId() == null) return null;
        Mesa mesa = mesaRepository.findByIdForUpdate(referencia.getId())
                .orElseThrow(() -> new IllegalArgumentException("Mesa no encontrada"));
        // Also refresh new reservations' tables, which may have been cached before waiting.
        entities.refresh(mesa, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!mesa.isActivo()) throw new IllegalArgumentException("La mesa está inactiva");
        return mesa;
    }

    private void validarDatos(LocalDateTime fecha, Integer personas) {
        if (fecha == null || personas == null || personas < 1) {
            throw new IllegalArgumentException("Fecha y número de personas válidos son obligatorios");
        }
    }

    private void validarConflictos(Mesa mesa, LocalDateTime fecha, String estado, Long idActual) {
        if (mesa == null || !Set.of("CONFIRMADA", "EN_CURSO").contains(estado)) return;
        // Se conserva el margen de dos horas del flujo existente; se valida también al editar/confirmar.
        boolean conflicto = reservaRepository.findConflictosMesa(mesa.getId(), fecha.minusHours(2), fecha.plusHours(2))
                .stream().anyMatch(r -> !r.getId().equals(idActual));
        if (conflicto) throw new IllegalArgumentException("La mesa ya tiene una reserva cercana en esta fecha/hora.");
    }

    private void validarTransicion(String actual, String nuevo) {
        if (nuevo == null) throw new IllegalArgumentException("El estado de reserva es obligatorio");
        boolean valida = switch (actual == null ? "NUEVA" : actual) {
            case "NUEVA" -> Set.of("PENDIENTE", "CONFIRMADA").contains(nuevo);
            case "PENDIENTE" -> Set.of("PENDIENTE", "CONFIRMADA", "CANCELADA").contains(nuevo);
            case "CONFIRMADA" -> Set.of("CONFIRMADA", "EN_CURSO", "CANCELADA", "NO_SHOW").contains(nuevo);
            case "EN_CURSO" -> Set.of("EN_CURSO", "COMPLETADA", "CANCELADA").contains(nuevo);
            case "COMPLETADA", "CANCELADA", "NO_SHOW" -> actual.equals(nuevo);
            default -> false;
        };
        if (!valida) throw new IllegalArgumentException("Transición de reserva no permitida: " + actual + " → " + nuevo);
    }

    private void aplicarEstado(Reserva reserva, String nuevoEstado, Mesa mesa) {
        if ("EN_CURSO".equals(nuevoEstado) && !"EN_CURSO".equals(reserva.getEstado())) {
            if (mesa == null) throw new IllegalArgumentException("Asigna una mesa antes de iniciar la reserva");
            if ("OCUPADA".equals(mesa.getEstado()) || "SUCIA".equals(mesa.getEstado())) {
                throw new IllegalArgumentException("La mesa no está disponible para iniciar la reserva");
            }
            LocalDateTime ahora = LocalDateTime.now();
            Optional<Reserva> bloqueo = disponibilidadService.reservaBloqueante(mesa,
                    disponibilidadService.proximas(ahora), ahora);
            if (bloqueo.isPresent() && !bloqueo.get().getId().equals(reserva.getId())) {
                throw new IllegalArgumentException("La mesa está bloqueada por otra reserva confirmada");
            }
            mesa.setEstado("OCUPADA");
            mesa.setHoraApertura(ahora);
        }
        reserva.setEstado(nuevoEstado);
    }
}
