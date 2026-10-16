package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Gasto;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.services.GastoService;
import com.tuapp.restaurante.services.UsuarioService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/gastos")
public class GastoController {

    private final GastoService gastoService;
    private final UsuarioService usuarioService;

    public GastoController(GastoService gastoService, UsuarioService usuarioService) {
        this.gastoService = gastoService;
        this.usuarioService = usuarioService;
    }

    @GetMapping
    public String listar(Model model) {
        model.addAttribute("gastos", gastoService.listarActivos());
        return "gastos/lista";
    }

    @GetMapping("/nuevo")
    public String formulario(Model model) {
        Gasto gasto = new Gasto();
        gasto.setFecha(LocalDateTime.now());
        model.addAttribute("gasto", gasto);
        model.addAttribute("titulo", "Nuevo Gasto");
        return "gastos/formulario";
    }

    @PostMapping("/guardar")
    public String guardar(@ModelAttribute Gasto gasto, Authentication auth, RedirectAttributes flash) {
        try {
            Usuario usuario = usuarioService.buscarPorUsername(auth.getName()).orElseThrow();
            gasto.setUsuario(usuario);
            if (gasto.getFecha() == null) gasto.setFecha(LocalDateTime.now());
            gastoService.guardar(gasto);
            flash.addFlashAttribute("mensajeExito", "¡Gasto registrado con éxito!");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al registrar gasto: " + e.getMessage());
        }
        return "redirect:/gastos";
    }

    @PostMapping("/eliminar/{id}")
    public String eliminar(@PathVariable Long id, RedirectAttributes flash) {
        System.out.println("DEBUG >>> INGRESO A ELIMINAR GASTO ID: " + id);
        try {
            gastoService.eliminarLogico(id);
            flash.addFlashAttribute("mensajeExito", "¡Gasto eliminado correctamente!");
            System.out.println("DEBUG >>> GASTO ELIMINADO LOGICAMENTE EXITOSAMENTE");
        } catch (Exception e) {
            System.err.println("DEBUG ERROR >>> " + e.getMessage());
            flash.addFlashAttribute("mensajeError", "No se pudo eliminar el gasto: " + e.getMessage());
        }
        return "redirect:/gastos";
    }
}
