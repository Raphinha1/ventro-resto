package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.services.InsumoService;
import com.tuapp.restaurante.services.MovimientoInventarioService;
import com.tuapp.restaurante.services.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/movimientos")
public class MovimientoInventarioController {

    private final MovimientoInventarioService movimientoService;
    private final InsumoService insumoService;
    private final UsuarioService usuarioService;

    public MovimientoInventarioController(MovimientoInventarioService movimientoService,
                                          InsumoService insumoService,
                                          UsuarioService usuarioService) {
        this.movimientoService = movimientoService;
        this.insumoService = insumoService;
        this.usuarioService = usuarioService;
    }

    @GetMapping("/insumo/{insumoId}")
    public String listarPorInsumo(@PathVariable Long insumoId, Model model, RedirectAttributes flash) {
        return insumoService.buscarPorId(insumoId).map(insumo -> {
            model.addAttribute("insumo", insumo);
            model.addAttribute("movimientos", movimientoService.listarPorInsumo(insumoId));
            model.addAttribute("titulo", "Historial de Movimientos: " + insumo.getNombre());
            return "movimientos/lista";
        }).orElseGet(() -> {
            flash.addFlashAttribute("mensajeError", "Insumo no encontrado.");
            return "redirect:/insumos";
        });
    }

    @GetMapping("/registrar/{insumoId}")
    public String mostrarFormularioRegistrar(@PathVariable Long insumoId, Model model, RedirectAttributes flash) {
        return insumoService.buscarPorId(insumoId).map(insumo -> {
            MovimientoInventario mov = new MovimientoInventario();
            mov.setInsumo(insumo);
            model.addAttribute("movimiento", mov);
            model.addAttribute("titulo", "Registrar Movimiento para: " + insumo.getNombre());
            return "movimientos/registrar";
        }).orElseGet(() -> {
            flash.addFlashAttribute("mensajeError", "Insumo no encontrado.");
            return "redirect:/insumos";
        });
    }

    @PostMapping("/guardar")
    public String guardar(@Valid @ModelAttribute("movimiento") MovimientoInventario movimiento,
                          BindingResult result,
                          Authentication auth,
                          RedirectAttributes flash,
                          Model model) {
        if (result.hasErrors()) {
            model.addAttribute("titulo", "Registrar Movimiento");
            return "movimientos/registrar";
        }
        try {
            Usuario usuarioActual = usuarioService.buscarPorUsername(auth.getName())
                    .orElseThrow(() -> new RuntimeException("Usuario autenticado no existe en BD"));
            movimiento.setUsuario(usuarioActual);
            movimientoService.registrarMovimiento(movimiento);
            flash.addFlashAttribute("mensajeExito", "Movimiento registrado y stock actualizado.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al registrar: " + e.getMessage());
        }
        return "redirect:/insumos";
    }
    @GetMapping("/merma/{insumoId}")
    public String modalMerma(@PathVariable Long insumoId, Model model, 
                             @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        return insumoService.buscarPorId(insumoId).map(insumo -> {
            MovimientoInventario mov = new MovimientoInventario();
            mov.setInsumo(insumo);
            mov.setTipoMovimiento("MERMA");
            model.addAttribute("movimiento", mov);
            if ("XMLHttpRequest".equals(requestedWith)) {
                return "movimientos/merma :: content";
            }
            return "redirect:/insumos";
        }).orElse("redirect:/insumos");
    }

    @PostMapping("/merma/guardar")
    public String guardarMerma(@ModelAttribute("movimiento") MovimientoInventario movimiento,
                               Authentication auth, Model model,
                               @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        try {
            Usuario usuarioActual = usuarioService.buscarPorUsername(auth.getName())
                    .orElseThrow(() -> new RuntimeException("Usuario autenticado no existe en BD"));
            movimiento.setUsuario(usuarioActual);
            movimiento.setTipoMovimiento("MERMA");
            if(movimiento.getCantidad() == null || movimiento.getCantidad().doubleValue() <= 0) {
                throw new RuntimeException("La cantidad debe ser mayor a 0.");
            }
            if(movimiento.getComentario() == null || movimiento.getComentario().trim().isEmpty()) {
                throw new RuntimeException("Debes justificar la merma.");
            }
            movimientoService.registrarMovimiento(movimiento);
            model.addAttribute("mensajeExito", "Merma registrada correctamente.");
        } catch (Exception e) {
            model.addAttribute("mensajeError", e.getMessage());
        }
        
        // Recargar el mismo fragmento para mostrar los mensajes sin salir del modal
        return insumoService.buscarPorId(movimiento.getInsumo().getId()).map(ins -> {
            MovimientoInventario mov = new MovimientoInventario();
            mov.setInsumo(ins);
            mov.setTipoMovimiento("MERMA");
            model.addAttribute("movimiento", mov);
            return "movimientos/merma :: content";
        }).orElse("redirect:/insumos");
    }
}
