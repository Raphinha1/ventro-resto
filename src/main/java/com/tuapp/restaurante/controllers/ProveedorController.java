package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Proveedor;
import com.tuapp.restaurante.services.ProveedorService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controlador MVC para el CRUD de Proveedores.
 * Misma estructura que CategoriaController aplicando el patrón MVC limpio.
 */
@Controller
@RequestMapping("/proveedores")
public class ProveedorController {


    public ProveedorController(ProveedorService proveedorService) {
        this.proveedorService = proveedorService;
    }

    private final ProveedorService proveedorService;

    /**
     * GET /proveedores → Lista todos los proveedores.
     */
    @GetMapping
    public String listar(Model model) {
        model.addAttribute("proveedores", proveedorService.listarTodos());
        model.addAttribute("titulo", "Gestión de Proveedores");
        return "proveedores/lista";  // templates/proveedores/lista.html
    }

    /**
     * GET /proveedores/nuevo → Formulario para nuevo proveedor.
     */
    @GetMapping("/nuevo")
    public String mostrarFormularioNuevo(Model model) {
        model.addAttribute("proveedores", proveedorService.listarTodos());
        model.addAttribute("proveedor", new Proveedor());
        model.addAttribute("titulo", "Gestión de Proveedores");
        return "proveedores/lista";
    }

    /**
     * POST /proveedores/guardar → Procesa el formulario con validación.
     */
    @PostMapping("/guardar")
    public String guardar(@Valid @ModelAttribute("proveedor") Proveedor proveedor,
                          BindingResult resultado,
                          RedirectAttributes atributosRedireccion,
                          Model model) {

        if (resultado.hasErrors()) {
            model.addAttribute("proveedores", proveedorService.listarTodos());
            model.addAttribute("titulo", "Gestión de Proveedores");
            model.addAttribute("mensajeError", "Error al validar el proveedor.");
            return "proveedores/lista";
        }

        try {
            proveedorService.guardar(proveedor);
            atributosRedireccion.addFlashAttribute("mensajeExito",
                    "Proveedor guardado exitosamente.");
        } catch (Exception e) {
            atributosRedireccion.addFlashAttribute("mensajeError",
                    "Error al guardar el proveedor: " + e.getMessage());
        }

        return "redirect:/proveedores";
    }

    /**
     * GET /proveedores/editar/{id} → Formulario pre-llenado para editar.
     */
    @GetMapping("/editar/{id}")
    public String mostrarFormularioEditar(@PathVariable Long id,
                                           Model model,
                                           RedirectAttributes atributosRedireccion) {
        return proveedorService.buscarPorId(id)
                .map(proveedor -> {
                    model.addAttribute("proveedores", proveedorService.listarTodos());
                    model.addAttribute("proveedor", proveedor);
                    model.addAttribute("titulo", "Gestión de Proveedores");
                    return "proveedores/lista";
                })
                .orElseGet(() -> {
                    atributosRedireccion.addFlashAttribute("mensajeError",
                            "Proveedor no encontrado con ID: " + id);
                    return "redirect:/proveedores";
                });
    }

    /**
     * POST /proveedores/eliminar/{id} → Elimina el proveedor por ID.
     * Usamos POST (form) para que Spring Security incluya el token CSRF automáticamente.
     */
    @PostMapping("/eliminar/{id}")
    public String eliminar(@PathVariable Long id,
                            RedirectAttributes atributosRedireccion) {
        try {
            proveedorService.eliminar(id);
            atributosRedireccion.addFlashAttribute("mensajeExito",
                    "Proveedor eliminado exitosamente.");
        } catch (Exception e) {
            atributosRedireccion.addFlashAttribute("mensajeError",
                    "Error al eliminar el proveedor: " + e.getMessage());
        }
        return "redirect:/proveedores";
    }
}
