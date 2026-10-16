package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.services.CategoriaService;
import com.tuapp.restaurante.services.InsumoService;
import com.tuapp.restaurante.services.ProveedorService;
import com.tuapp.restaurante.services.AuditoriaService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/insumos")
public class InsumoController {


    public InsumoController(InsumoService insumoService, CategoriaService categoriaService, ProveedorService proveedorService, AuditoriaService auditoriaService) {
        this.insumoService = insumoService;
        this.categoriaService = categoriaService;
        this.proveedorService = proveedorService;
        this.auditoriaService = auditoriaService;
    }

    private final InsumoService insumoService;
    private final CategoriaService categoriaService;
    private final ProveedorService proveedorService;
    private final AuditoriaService auditoriaService;

    @GetMapping
    public String listar(@RequestHeader(value = "X-Requested-With", required = false) String requestedWith, Model model) {
        model.addAttribute("insumos", insumoService.listarTodos());
        model.addAttribute("categorias", categoriaService.listarPorTipo("INSUMO"));
        model.addAttribute("proveedores", proveedorService.listarTodos());
        model.addAttribute("titulo", "Gestión de Insumos");
        
        if ("XMLHttpRequest".equals(requestedWith)) {
            return "insumos/lista :: tablaInsumos";
        }
        return "insumos/lista";
    }

    @GetMapping("/nuevo")
    public String mostrarFormularioNuevo(Model model) {
        model.addAttribute("insumo", new Insumo());
        model.addAttribute("categorias", categoriaService.listarPorTipo("INSUMO"));
        model.addAttribute("proveedores", proveedorService.listarTodos());
        model.addAttribute("titulo", "Nuevo Insumo");
        model.addAttribute("esNuevo", true);
        return "insumos/lista"; // Redirigir a lista ya que el form es un modal allí
    }

    @PostMapping("/guardar")
    public String guardar(@Valid @ModelAttribute("insumo") Insumo insumo,
                          BindingResult result,
                          RedirectAttributes flash,
                          Model model) {

        if (result.hasErrors()) {
            model.addAttribute("insumos", insumoService.listarTodos());
            model.addAttribute("categorias", categoriaService.listarPorTipo("INSUMO"));
            model.addAttribute("proveedores", proveedorService.listarTodos());
            model.addAttribute("titulo", "Gestión de Insumos");
            model.addAttribute("mensajeError", "Por favor corrija los errores en el formulario.");
            return "insumos/lista";
        }

        try {
            insumoService.guardar(insumo);
            flash.addFlashAttribute("mensajeExito", "Insumo guardado exitosamente.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error: " + e.getMessage());
        }

        return "redirect:/insumos";
    }

    @GetMapping("/editar/{id}")
    public String mostrarFormularioEditar(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return insumoService.buscarPorId(id).map(insumo -> {
            model.addAttribute("insumo", insumo);
            model.addAttribute("insumos", insumoService.listarTodos());
            model.addAttribute("categorias", categoriaService.listarPorTipo("INSUMO"));
            model.addAttribute("proveedores", proveedorService.listarTodos());
            model.addAttribute("titulo", "Gestión de Insumos");
            model.addAttribute("esNuevo", false);
            return "insumos/lista";
        }).orElseGet(() -> {
            flash.addFlashAttribute("mensajeError", "Insumo no encontrado.");
            return "redirect:/insumos";
        });
    }

    @PostMapping("/eliminar/{id}")
    public String eliminar(@PathVariable Long id, RedirectAttributes flash) {
        try {
            insumoService.eliminar(id);
            auditoriaService.registrarLog("INVENTARIO", "ELIMINAR_INSUMO", "Se eliminó el insumo ID: " + id);
            flash.addFlashAttribute("mensajeExito", "Insumo eliminado exitosamente.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al eliminar: " + e.getMessage());
        }
        return "redirect:/insumos";
    }
}
