package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.repositories.VentaRepository;
import com.tuapp.restaurante.services.CategoriaService;
import com.tuapp.restaurante.services.ConfiguracionService;
import com.tuapp.restaurante.services.ProductoService;
import com.tuapp.restaurante.services.UsuarioService;
import com.tuapp.restaurante.services.VentaService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/pos")
public class VentaController {

    private final VentaService ventaService;
    private final ProductoService productoService;
    private final UsuarioService usuarioService;
    private final CategoriaService categoriaService;
    private final VentaRepository ventaRepository;
    private final ConfiguracionService configuracionService;
    private final com.tuapp.restaurante.repositories.MesaRepository mesaRepository;
    private final com.tuapp.restaurante.repositories.ClienteRepository clienteRepository;

    public VentaController(VentaService ventaService, ProductoService productoService,
                           UsuarioService usuarioService, CategoriaService categoriaService,
                           VentaRepository ventaRepository, ConfiguracionService configuracionService,
                           com.tuapp.restaurante.repositories.MesaRepository mesaRepository,
                           com.tuapp.restaurante.repositories.ClienteRepository clienteRepository) {
        this.ventaService = ventaService;
        this.productoService = productoService;
        this.usuarioService = usuarioService;
        this.categoriaService = categoriaService;
        this.ventaRepository = ventaRepository;
        this.configuracionService = configuracionService;
        this.mesaRepository = mesaRepository;
        this.clienteRepository = clienteRepository;
    }

    @GetMapping
    public String terminalPuntoVenta(@RequestParam(value = "mesaId", required = false) Long mesaId, Model model) {
        List<com.tuapp.restaurante.models.Producto> productos = productoService.listarTodos();
        
        // Bloqueo Inteligente: Calcular cuántas porciones de cada producto se pueden preparar
        java.util.Map<Long, Integer> disponibilidadMap = new java.util.HashMap<>();
        
        for (com.tuapp.restaurante.models.Producto p : productos) {
            int maxPorciones = 999;
            
            if (p.isEsReventa()) {
                // Si es reventa, la disponibilidad es el stock actual del insumo vinculado
                if (p.getInsumoReventa() != null) {
                    maxPorciones = p.getInsumoReventa().getStockActual().intValue();
                } else {
                    maxPorciones = 0; // Error de configuración
                }
            } else if (p.getIngredientes() != null && !p.getIngredientes().isEmpty()) {
                // Si es preparado, se calcula en base a la receta
                for (com.tuapp.restaurante.models.Receta r : p.getIngredientes()) {
                    if (r.getInsumo() != null && r.getCantidad().compareTo(BigDecimal.ZERO) > 0) {
                        int porcionesPosibles = r.getInsumo().getStockActual().divideToIntegralValue(r.getCantidad()).intValue();
                        if (porcionesPosibles < maxPorciones) {
                            maxPorciones = porcionesPosibles;
                        }
                    }
                }
            } else {
                // Producto sin receta y no es reventa (bloqueado por seguridad)
                maxPorciones = 0; 
            }
            
            if (maxPorciones < 0) maxPorciones = 0;
            disponibilidadMap.put(p.getId(), maxPorciones);
        }

        model.addAttribute("productos", productos);
        model.addAttribute("disponibilidad", disponibilidadMap);
        model.addAttribute("categorias", categoriaService.listarTodas());
        model.addAttribute("clientes", clienteRepository.findByActivoTrue());
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        
        if (mesaId != null) {
            mesaRepository.findById(mesaId).ifPresent(mesa -> model.addAttribute("mesaActiva", mesa));
        }
        
        return "pos/index";
    }

    @GetMapping("/historial")
    public String historialVentas(Model model) {
        model.addAttribute("ventas", ventaRepository.findByOrderByFechaDesc());
        return "pos/historial";
    }

    @GetMapping("/ticket/{id}")
    public String verTicket(@PathVariable Long id, Model model) {
        model.addAttribute("venta", ventaRepository.findById(id).orElseThrow());
        model.addAttribute("config", configuracionService.obtener());
        return "pos/ticket";
    }

    @GetMapping("/cocina/{id}")
    public String verComandaCocina(@PathVariable Long id, Model model) {
        model.addAttribute("venta", ventaRepository.findById(id).orElseThrow());
        model.addAttribute("config", configuracionService.obtener());
        return "pos/cocina";
    }

    @PostMapping("/venta-multiple")
    public String procesarVentaMultiple(
            @RequestParam("productoId") List<Long> productoIds,
            @RequestParam("cantidadVendida") List<Integer> cantidades,
            @RequestParam MultiValueMap<String, String> formFields,
            @RequestParam(value = "metodoPago", defaultValue = "EFECTIVO") String metodoPago,
            @RequestParam(value = "montoEfectivo", defaultValue = "0") BigDecimal montoEfectivo,
            @RequestParam(value = "montoTarjeta", defaultValue = "0") BigDecimal montoTarjeta,
            @RequestParam(value = "mesaId", required = false) Long mesaId,
            @RequestParam(value = "clienteId", required = false) Long clienteId,
            @RequestParam(value = "esCanjeado", required = false) List<Boolean> esCanjeado,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            Authentication auth,
            RedirectAttributes flash) {
        try {
            Usuario usuarioAuth = usuarioService.buscarPorUsername(auth.getName()).orElseThrow();
            com.tuapp.restaurante.models.Venta v = ventaService.procesarVentasMultiples(
                    productoIds, cantidades, formFields.get("notas"), usuarioAuth.getId(),
                    metodoPago, montoEfectivo, montoTarjeta, mesaId, clienteId, esCanjeado, idempotencyKey);
            
            flash.addFlashAttribute("mensajeExito", "¡Venta procesada con éxito!");
            flash.addFlashAttribute("ultimoTicketId", v.getId());
            flash.addFlashAttribute("ventaAceptadaKey", idempotencyKey);
        } catch (RuntimeException re) {
            flash.addFlashAttribute("mensajeError", "Error al procesar pedido: " + re.getMessage());
            flash.addFlashAttribute("ventaRechazadaKey", idempotencyKey);
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error crítico: " + e.getMessage());
            flash.addFlashAttribute("ventaRechazadaKey", idempotencyKey);
        }
        return mesaId == null ? "redirect:/pos" : "redirect:/pos?mesaId=" + mesaId;
    }
}
