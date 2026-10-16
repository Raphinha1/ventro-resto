package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.DetalleVenta;
import com.tuapp.restaurante.repositories.DetalleVentaRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;

@Controller
@RequestMapping("/kds")
public class KdsController {

    private final DetalleVentaRepository detalleVentaRepository;
    
    // Lista estática para almacenar notificaciones de la IA (Especiales del día)
    private static final java.util.List<java.util.Map<String, String>> notificacionesChef = new java.util.ArrayList<>();

    public KdsController(DetalleVentaRepository detalleVentaRepository) {
        this.detalleVentaRepository = detalleVentaRepository;
    }

    @PostMapping("/api/notificacion-especial")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> recibirNotificacionEspecial(@RequestParam String insumo) {
        java.util.Map<String, String> notif = new java.util.HashMap<>();
        notif.put("id", java.util.UUID.randomUUID().toString());
        notif.put("insumo", insumo);
        notif.put("fecha", java.time.LocalDateTime.now().toString());
        notificacionesChef.add(notif);
        return org.springframework.http.ResponseEntity.ok().build();
    }

    @PostMapping("/api/marcar-notificacion-leida")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> marcarNotificacionLeida(@RequestParam String id) {
        notificacionesChef.removeIf(n -> n.get("id").equals(id));
        return org.springframework.http.ResponseEntity.ok().build();
    }

    @GetMapping
    public String index(Model model) {
        // Obtenemos solo los tickets pendientes o en preparación
        List<DetalleVenta> pedidosPendientes = detalleVentaRepository.findByEstadoInOrderByVenta_FechaAsc(
                Arrays.asList("PENDIENTE", "PREPARANDO"));

        model.addAttribute("pedidos", pedidosPendientes);
        model.addAttribute("titulo", "Pantalla de Cocina (KDS)");
        return "kds/index";
    }

    @PostMapping("/actualizar-estado")
    public String actualizarEstado(@RequestParam Long id, @RequestParam String estado, RedirectAttributes flash) {
        try {
            DetalleVenta detalle = detalleVentaRepository.findById(id).orElseThrow();
            detalle.setEstado(estado);
            detalleVentaRepository.save(detalle);
            flash.addFlashAttribute("mensajeExito", "Estado actualizado a " + estado);
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al actualizar estado.");
        }
        return "redirect:/kds";
    }

    // Nuevo endpoint para AJAX - KDS 2.0
    @GetMapping("/api/datos")
    @ResponseBody
    public java.util.Map<String, Object> obtenerDatosKds() {
        List<DetalleVenta> pedidos = detalleVentaRepository.findByEstadoInOrderByVenta_FechaAsc(
                Arrays.asList("PENDIENTE", "PREPARANDO"));
        
        long pendientes = pedidos.stream().filter(p -> p.getEstado().equals("PENDIENTE")).count();
        long preparando = pedidos.stream().filter(p -> p.getEstado().equals("PREPARANDO")).count();
        
        // Calcular urgentes (más de 10 minutos)
        java.time.LocalDateTime hace10Min = java.time.LocalDateTime.now().minusMinutes(10);
        long urgentes = pedidos.stream()
                .filter(p -> p.getVenta().getFecha().isBefore(hace10Min))
                .count();

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("pedidos", pedidos.stream().map(p -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", p.getId());
            map.put("ventaId", p.getVenta().getId());
            map.put("productoNombre", p.getProducto().getNombre());
            map.put("cantidad", p.getCantidad());
            map.put("estado", p.getEstado());
            map.put("notas", p.getNotas());
            map.put("fecha", p.getVenta().getFecha().toString());
            map.put("usuario", p.getVenta().getUsuario().getUsername());
            return map;
        }).toList());
        
        response.put("stats", java.util.Map.of(
            "pendientes", pendientes,
            "preparando", preparando,
            "urgentes", urgentes
        ));
        
        response.put("notificaciones", notificacionesChef);
        
        return response;
    }
}
