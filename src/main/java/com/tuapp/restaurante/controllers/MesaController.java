package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Mesa;
import com.tuapp.restaurante.repositories.MesaRepository;
import com.tuapp.restaurante.services.AuditoriaService;
import com.tuapp.restaurante.services.MesaDisponibilidadService;
import com.tuapp.restaurante.models.Reserva;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

@Controller
@RequestMapping("/mesas")
public class MesaController {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;


    private final MesaRepository mesaRepository;
    private final com.tuapp.restaurante.security.AccessPolicy access;
    private final AuditoriaService auditoriaService;
    private final com.tuapp.restaurante.repositories.ReservaRepository reservaRepository;
    private final MesaDisponibilidadService disponibilidadService;

    public MesaController(MesaRepository mesaRepository, AuditoriaService auditoriaService,
            com.tuapp.restaurante.repositories.ReservaRepository reservaRepository,
            MesaDisponibilidadService disponibilidadService, com.tuapp.restaurante.security.AccessPolicy access) {
        this.access = access;
        this.mesaRepository = mesaRepository;
        this.auditoriaService = auditoriaService;
        this.reservaRepository = reservaRepository;
        this.disponibilidadService = disponibilidadService;
    }

    // ─── MAPA VISUAL ─────────────────────────────────────────────────────────────
    @GetMapping
    public String mapa(Model model) {
        List<Mesa> todas = mesaRepository.findByActivoTrueOrderByNumeroAsc();
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime enDosHoras = ahora.plusHours(2);
        List<Reserva> proximas = disponibilidadService.proximas(ahora);
        Map<Long, String> estadosMesa = new HashMap<>();
        Map<Long, Long> reservasBloqueantes = new HashMap<>();
        for (Mesa mesa : todas) {
            estadosMesa.put(mesa.getId(), disponibilidadService.estadoEfectivo(mesa, proximas, ahora));
            disponibilidadService.reservaBloqueante(mesa, proximas, ahora)
                    .ifPresent(r -> reservasBloqueantes.put(mesa.getId(), r.getId()));
        }
        long libres = estadosMesa.values().stream().filter("LIBRE"::equals).count();
        long ocupadas = estadosMesa.values().stream().filter("OCUPADA"::equals).count();
        long sucias = estadosMesa.values().stream().filter("SUCIA"::equals).count();
        long reservadas = estadosMesa.values().stream().filter("RESERVADA"::equals).count();

        Map<Long, Reserva> proximasReservas = new HashMap<>();
        for (Reserva r : proximas) {
            if (r.getMesa() != null && r.getFechaHora().isBefore(enDosHoras)) {
                // Conservar la confirmada como aviso prioritario; si no, mostrar la más próxima.
                Reserva actual = proximasReservas.get(r.getMesa().getId());
                if (actual == null || ("PENDIENTE".equals(actual.getEstado())
                        && "CONFIRMADA".equals(r.getEstado()))) {
                    proximasReservas.put(r.getMesa().getId(), r);
                }
            }
        }

        model.addAttribute("mesas", todas);
        model.addAttribute("totalMesas", todas.size());
        model.addAttribute("libres", libres);
        model.addAttribute("ocupadas", ocupadas);
        model.addAttribute("sucias", sucias);
        model.addAttribute("reservadas", reservadas);
        model.addAttribute("estadosMesa", estadosMesa);
        model.addAttribute("reservasBloqueantes", reservasBloqueantes);
        model.addAttribute("proximasReservas", proximasReservas);
        return "mesas/mapa";
    }

    // ─── FORMULARIO NUEVA MESA ───────────────────────────────────────────────────
    @GetMapping("/nueva")
    public String formularioNueva(Model model) {
        model.addAttribute("mesa", new Mesa());
        model.addAttribute("titulo", "Nueva Mesa");
        return "mesas/formulario";
    }

    // ─── FORMULARIO EDITAR ───────────────────────────────────────────────────────
    @GetMapping("/editar/{id}")
    public String formularioEditar(@PathVariable Long id, Model model) {
        Mesa mesa = mesaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Mesa no encontrada"));
        model.addAttribute("mesa", mesa);
        model.addAttribute("titulo", "Editar Mesa #" + mesa.getNumero());
        return "mesas/formulario";
    }

    // ─── GUARDAR ────────────────────────────────────────────────────────────────
    @PostMapping("/guardar")
    @Transactional
    public String guardar(@ModelAttribute Mesa mesa,
                          @RequestParam(value = "estado", required = false) String estadoSolicitado,
                          RedirectAttributes flash) {
        try {
            // Verificar si ya existe una mesa con ese número
            Mesa existente = mesaRepository.findByNumero(mesa.getNumero()).orElse(null);
            
            if (mesa.getId() == null) {
                if (estadoSolicitado != null && !"LIBRE".equals(estadoSolicitado)) {
                    throw new IllegalArgumentException("Una mesa nueva debe comenzar LIBRE; cambia su estado desde el mapa.");
                }
                mesa.setEstado("LIBRE");
                // CASO NUEVA MESA
                if (existente != null) {
                    if (!existente.isActivo()) {
                        // Reactivar mesa existente
                        existente.setActivo(true);
                        existente.setCapacidad(mesa.getCapacidad());
                        existente.setZona(mesa.getZona());
                        existente.setEstado("LIBRE");
                        mesaRepository.save(existente);
                        flash.addFlashAttribute("mensajeExito", "Mesa #" + mesa.getNumero() + " reactivada correctamente.");
                        return "redirect:/mesas";
                    } else {
                        flash.addFlashAttribute("mensajeError", "Ya existe una mesa activa con el número " + mesa.getNumero());
                        return "redirect:/mesas";
                    }
                }
            } else {
                // CASO EDICIÓN
                Mesa actual = mesaRepository.findByIdForUpdate(mesa.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Mesa no encontrada"));
                if (estadoSolicitado != null && !estadoSolicitado.equals(actual.getEstado())) {
                    throw new IllegalArgumentException("Cambia el estado de la mesa desde las acciones del mapa.");
                }
                if (existente != null && !existente.getId().equals(mesa.getId())) {
                    flash.addFlashAttribute("mensajeError", "No puedes usar el número " + mesa.getNumero() + " porque ya pertenece a otra mesa.");
                    return "redirect:/mesas";
                }
                actual.setNumero(mesa.getNumero());
                actual.setCapacidad(mesa.getCapacidad());
                actual.setZona(mesa.getZona());
                mesaRepository.save(actual);
                flash.addFlashAttribute("mensajeExito", "Mesa #" + mesa.getNumero() + " guardada correctamente.");
                return "redirect:/mesas";
            }
            
            mesaRepository.save(mesa);
            flash.addFlashAttribute("mensajeExito", "Mesa #" + mesa.getNumero() + " guardada correctamente.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al guardar: " + e.getMessage());
        }
        return "redirect:/mesas";
    }

    // ─── CAMBIAR ESTADO ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/estado")
    @Transactional
    public String cambiarEstado(@PathVariable Long id,
                                @RequestParam("estado") String estado,
                                @RequestParam(value = "decisionLiberacion", required = false) String decisionLiberacion,
                                @RequestParam(value = "reservaId", required = false) Long reservaId,
                                Authentication auth,
                                RedirectAttributes flash) {
        Mesa actual = mesaRepository.findByIdForUpdate(id).orElse(null);
        if (actual == null) {
            flash.addFlashAttribute("mensajeError", "No se encontró la mesa solicitada.");
            return "redirect:/mesas";
        }
        entities.refresh(actual, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> proximas = disponibilidadService.proximas(ahora);
        String estadoActual = disponibilidadService.estadoEfectivo(actual, proximas, ahora);
        if (!estadosConocidos().contains(estado) || !transicionPermitida(estadoActual, estado)) {
            flash.addFlashAttribute("mensajeError", "Transición de mesa no permitida: " + estadoActual + " → " + estado + ".");
            return "redirect:/mesas";
        }
        boolean seLibera = ("OCUPADA".equals(estadoActual) && "SUCIA".equals(estado))
                || ("SUCIA".equals(estadoActual) && "LIBRE".equals(estado));
        if (seLibera && (decisionLiberacion == null
                || !Set.of("SIN_CARRITO", "DESCARTAR").contains(decisionLiberacion))) {
            // Es una declaración del cliente: el servidor no puede inspeccionar sessionStorage.
            flash.addFlashAttribute("mensajeError", "Confirma qué hacer con el carrito antes de liberar la mesa.");
            return "redirect:/mesas";
        }
        String estadoAnterior = actual.getEstado();
        if ("RESERVADA".equals(estadoActual) && "OCUPADA".equals(estado)) {
            Reserva reserva = disponibilidadService.reservaBloqueante(actual, proximas, ahora).orElseThrow();
            if (!reserva.getId().equals(reservaId)) {
                flash.addFlashAttribute("mensajeError", "Selecciona la reserva confirmada para sentar a sus clientes.");
                return "redirect:/mesas";
            }
            // Mesa is already locked. All reservation writers acquire Mesa before Reserva.
            entities.refresh(reserva, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (disponibilidadService.reservaBloqueante(actual, List.of(reserva), ahora).isEmpty()) {
                flash.addFlashAttribute("mensajeError", "La reserva cambió; vuelve a intentarlo.");
                return "redirect:/mesas";
            }
            reserva.setEstado("EN_CURSO");
            reservaRepository.save(reserva);
        }
        actual.setEstado(estado);

        if ("OCUPADA".equals(estado)) {
            actual.setHoraApertura(ahora);
            actual.setMozo(auth != null ? auth.getName() : "Sistema");
        } else if ("LIBRE".equals(estado)) {
            actual.setHoraApertura(null);
            actual.setMozo(null);
            actual.setVentaActual(null);
        }

        mesaRepository.save(actual);
        auditoriaService.registrarLog("MESAS", "CAMBIO_ESTADO_MESA",
                "Mesa #" + actual.getNumero() + " cambió de " + estadoAnterior + " a " + estado +
                (auth != null ? ". Usuario: " + auth.getName() : ""));
        if (seLibera && "DESCARTAR".equals(decisionLiberacion)) {
            flash.addFlashAttribute("carritoDescartadoMesaId", id);
        }
        flash.addFlashAttribute("mensajeExito", "Estado de mesa actualizado.");
        return "redirect:/mesas";
    }

    private Set<String> estadosConocidos() {
        return Set.of("LIBRE", "OCUPADA", "SUCIA", "RESERVADA");
    }

    private boolean transicionPermitida(String origen, String destino) {
        if (origen == null) return false;
        return switch (origen) {
            case "LIBRE" -> "OCUPADA".equals(destino);
            case "OCUPADA" -> "SUCIA".equals(destino);
            case "SUCIA" -> "LIBRE".equals(destino);
            case "RESERVADA" -> "OCUPADA".equals(destino);
            default -> false;
        };
    }

    // ─── ELIMINAR ────────────────────────────────────────────────────────────────
    @PostMapping("/eliminar/{id}")
    @Transactional
    public String eliminar(@PathVariable Long id, RedirectAttributes flash) {
        // Serialize the authorization decision with reservation creation, which
        // takes this same row lock before assigning a table.
        mesaRepository.findByIdForUpdate(id).ifPresent(mesa -> {
            entities.refresh(mesa, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (reservaRepository.existsByMesaId(id)) access.requireManagement();
            if ("OCUPADA".equals(mesa.getEstado())) {
                flash.addFlashAttribute("mensajeError", "No se puede eliminar una mesa ocupada.");
                return;
            }
            
            // Desvincular reservas asociadas a esta mesa antes de eliminarla
            reservaRepository.desvincularMesa(mesa.getId());
            
            // Cambiamos a eliminación REAL para evitar conflictos de número único
            mesaRepository.delete(mesa);
            flash.addFlashAttribute("mensajeExito", "Mesa eliminada permanentemente.");
        });
        return "redirect:/mesas";
    }
}
