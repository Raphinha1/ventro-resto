package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Reserva;
import org.springframework.security.access.AccessDeniedException;
import com.tuapp.restaurante.models.Mesa;
import com.tuapp.restaurante.repositories.MesaRepository;
import com.tuapp.restaurante.services.ReservaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/reservas")
public class ReservaController {

    @Autowired
    private ReservaService reservaService;

    @Autowired
    private MesaRepository mesaRepository;

    @Autowired
    private com.tuapp.restaurante.repositories.ClienteRepository clienteRepository;

    @GetMapping
    public String index(@RequestParam(required = false) Long mesaId, Model model) {
        model.addAttribute("mesaIdPreselected", mesaId);
        model.addAttribute("titulo", "Gestión de Reservas");
        model.addAttribute("reservas", reservaService.getAllReservas());
        List<Mesa> mesasLibres = mesaRepository.findByActivoTrueOrderByNumeroAsc();
        model.addAttribute("mesas", mesasLibres);
        model.addAttribute("clientes", clienteRepository.findByActivoTrue());
        Reserva r = new Reserva();
        r.setMesa(new Mesa());
        model.addAttribute("reserva", r);
        return "reservas/index";
    }

    // Endpoint para FullCalendar JSON
    @GetMapping("/eventos")
    @ResponseBody
    public List<Map<String, Object>> getEventos() {
        List<Reserva> reservas = reservaService.getAllReservas();
        List<Map<String, Object>> eventos = new ArrayList<>();

        for (Reserva r : reservas) {
            Map<String, Object> event = new HashMap<>();
            event.put("id", r.getId());
            event.put("title", r.getNombreMostrar() + " (" + r.getNumeroPersonas() + " pers)");
            
            if (r.getFechaHora() != null) {
                event.put("start", r.getFechaHora().toString());
                event.put("end", r.getFechaHora().plusHours(1).plusMinutes(30).toString()); 
            }
            
            // Colores por estado
            String color = "#3b82f6"; // Azul por defecto
            if ("CONFIRMADA".equals(r.getEstado())) color = "#10b981"; // Verde
            if ("PENDIENTE".equals(r.getEstado())) color = "#f59e0b";  // Naranja
            if ("CANCELADA".equals(r.getEstado())) color = "#ef4444";  // Rojo
            
            event.put("backgroundColor", color);
            event.put("borderColor", color);
            
            Map<String, Object> props = new HashMap<>();
            props.put("mesa", r.getMesa() != null ? "Mesa " + r.getMesa().getNumero() : "Sin asignar");
            props.put("estado", r.getEstado());
            props.put("personas", r.getNumeroPersonas());
            event.put("extendedProps", props);
            
            eventos.add(event);
        }
        return eventos;
    }

    @PostMapping("/actualizar-horario")
    @ResponseBody
    public Map<String, Object> actualizarHorario(@RequestParam Long id, 
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime nuevaFecha) {
        Map<String, Object> response = new HashMap<>();
        try {
            reservaService.actualizarHorario(id, nuevaFecha);
            response.put("status", "success");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
        }
        return response;
    }

    @PostMapping("/guardar")
    public String guardar(@ModelAttribute Reserva reserva, RedirectAttributes flash) {
        try {
            reservaService.saveReserva(reserva);
            flash.addFlashAttribute("success", "Reserva guardada correctamente");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/reservas";
    }

    @PostMapping("/{id}/estado")
    public String cambiarEstado(@PathVariable Long id, @RequestParam String estado, RedirectAttributes flash) {
        try {
            reservaService.cambiarEstado(id, estado);
            flash.addFlashAttribute("success", "Estado actualizado a " + estado);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/reservas";
    }

    @GetMapping("/{id}/editar")
    @ResponseBody
    public Map<String, Object> getReservaParaEditar(@PathVariable Long id) {
        Reserva r = reservaService.getReservaById(id)
                .orElseThrow(() -> new RuntimeException("Reserva no encontrada"));
        Map<String, Object> data = new HashMap<>();
        data.put("id", r.getId());
        data.put("nombreCliente", r.getNombreCliente());
        data.put("telefonoCliente", r.getTelefonoCliente());
        data.put("numeroPersonas", r.getNumeroPersonas());
        data.put("estado", r.getEstado());
        data.put("origen", r.getOrigen());
        data.put("notas", r.getNotas() != null ? r.getNotas() : "");
        data.put("fechaHora", r.getFechaHora() != null ? r.getFechaHora().toString() : "");
        data.put("mesaId", r.getMesa() != null ? r.getMesa().getId() : null);
        data.put("clienteId", r.getCliente() != null ? r.getCliente().getId() : null);
        return data;
    }

    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable Long id, RedirectAttributes flash) {
        try {
            reservaService.deleteReserva(id);
            flash.addFlashAttribute("success", "Reserva eliminada correctamente.");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Error al eliminar: " + e.getMessage());
        }
        return "redirect:/reservas";
    }
}
