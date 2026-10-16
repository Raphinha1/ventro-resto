package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.SubReceta;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.services.InsumoService;
import com.tuapp.restaurante.services.SubRecetaService;
import org.springframework.security.access.AccessDeniedException;
import com.tuapp.restaurante.services.ProduccionService;
import com.tuapp.restaurante.services.UsuarioService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/subrecetas")
public class SubRecetaController {

    private final InsumoService insumoService;
    private final SubRecetaService subRecetaService;
    private final ProduccionService produccionService;
    private final UsuarioService usuarioService;

    public SubRecetaController(InsumoService insumoService, 
                               SubRecetaService subRecetaService,
                               ProduccionService produccionService,
                               UsuarioService usuarioService) {
        this.insumoService = insumoService;
        this.subRecetaService = subRecetaService;
        this.produccionService = produccionService;
        this.usuarioService = usuarioService;
    }

    @GetMapping("/{insumoId}")
    public String verSubReceta(@PathVariable Long insumoId, 
                               @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                               Model model, RedirectAttributes flash) {
        return insumoService.buscarPorId(insumoId).map(insumo -> {
            model.addAttribute("insumoDestino", insumo);
            
            List<Insumo> disponibles = insumoService.listarTodos().stream()
                    .filter(i -> !i.getId().equals(insumo.getId()))
                    .collect(Collectors.toList());
            
            model.addAttribute("insumosDisponibles", disponibles);
            model.addAttribute("subReceta", new SubReceta());
            if (!model.containsAttribute("productionRequestId")) {
                model.addAttribute("productionRequestId", UUID.randomUUID());
            }
            model.addAttribute("titulo", "Composición de Insumo");
            
            BigDecimal costoProduccion = insumo.getIngredientesPreparacion().stream()
                    .map(sr -> sr.getCantidad().multiply(sr.getIngrediente().getCostoUnitario()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
            model.addAttribute("costoProduccion", costoProduccion);
            
            if ("XMLHttpRequest".equals(requestedWith)) {
                return "insumos/subreceta :: content";
            }
            return "insumos/subreceta";
        }).orElseGet(() -> {
            flash.addFlashAttribute("mensajeError", "Insumo no encontrado.");
            return "redirect:/insumos";
        });
    }

    @PostMapping("/agregar")
    public String agregarIngrediente(@RequestParam Long insumoDestinoId, 
                                     @RequestParam Long ingredienteId, 
                                     @RequestParam BigDecimal cantidad,
                                     @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                                     Model model, RedirectAttributes flash) {
        try {
            subRecetaService.agregar(insumoDestinoId, ingredienteId, cantidad);

            String msg = "Ingrediente añadido.";
            if ("XMLHttpRequest".equals(requestedWith)) model.addAttribute("mensajeExito", msg);
            else flash.addFlashAttribute("mensajeExito", msg);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            if ("XMLHttpRequest".equals(requestedWith)) model.addAttribute("mensajeError", e.getMessage());
            else flash.addFlashAttribute("mensajeError", e.getMessage());
        }
        
        if ("XMLHttpRequest".equals(requestedWith)) {
            return verSubReceta(insumoDestinoId, requestedWith, model, flash);
        }
        return "redirect:/subrecetas/" + insumoDestinoId;
    }

    @PostMapping("/eliminar/{id}")
    public String quitarIngrediente(@PathVariable Long id, 
                                    @RequestParam Long insumoDestinoId,
                                    @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                                    Model model, RedirectAttributes flash) {
        try {
            subRecetaService.quitar(insumoDestinoId, id);
            String msg = "Ingrediente retirado.";
            if ("XMLHttpRequest".equals(requestedWith)) model.addAttribute("mensajeExito", msg);
            else flash.addFlashAttribute("mensajeExito", msg);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            String msg = "Error al quitar ingrediente: " + e.getMessage();
            if ("XMLHttpRequest".equals(requestedWith)) model.addAttribute("mensajeError", msg);
            else flash.addFlashAttribute("mensajeError", msg);
        }
        
        if ("XMLHttpRequest".equals(requestedWith)) {
            return verSubReceta(insumoDestinoId, requestedWith, model, flash);
        }
        return "redirect:/subrecetas/" + insumoDestinoId;
    }

    @PostMapping("/producir")
    public String producirInsumo(@RequestParam Long insumoDestinoId, 
                                 @RequestParam BigDecimal cantidadAProducir,
                                 @RequestParam UUID requestId,
                                 @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                                 Model model, RedirectAttributes flash) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Usuario usuario = usuarioService.buscarPorUsername(auth.getName())
                    .orElseThrow(() -> new RuntimeException("Usuario no autenticado"));
            
            produccionService.producirInsumo(insumoDestinoId, cantidadAProducir,
                    usuario.getId(), requestId);
            String msg = "Producción registrada exitosamente.";
            if ("XMLHttpRequest".equals(requestedWith)) model.addAttribute("mensajeExito", msg);
            else flash.addFlashAttribute("mensajeExito", msg);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            String msg = "Error en producción: " + e.getMessage();
            if ("XMLHttpRequest".equals(requestedWith)) {
                model.addAttribute("mensajeError", msg);
                model.addAttribute("productionRequestId", requestId);
            }
            else flash.addFlashAttribute("mensajeError", msg);
        }
        
        if ("XMLHttpRequest".equals(requestedWith)) {
            return verSubReceta(insumoDestinoId, requestedWith, model, flash);
        }
        return "redirect:/insumos";
    }
}
