package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Categoria;
import com.tuapp.restaurante.services.CategoriaService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controlador MVC para el CRUD de Categorías.
 *
 * @Controller        → Clase que maneja peticiones HTTP y retorna vistas.
 * @RequestMapping    → Prefijo de ruta para todos los métodos del controlador.
 * @RequiredArgsConstructor → Inyección por constructor (Lombok).
 */
@Controller
@RequestMapping("/categorias")
public class CategoriaController {


    public CategoriaController(CategoriaService categoriaService) {
        this.categoriaService = categoriaService;
    }

    private final CategoriaService categoriaService;

    private String mapUrlToTipo(String tipoUrl) {
        if ("insumos".equals(tipoUrl)) return "INSUMO";
        if ("menu".equals(tipoUrl)) return "MENU";
        return null;
    }

    /**
     * GET /categorias/{tipoUrl} → Lista categorías filtradas por tipo.
     */
    @GetMapping("/{tipoUrl}")
    public String listar(@PathVariable String tipoUrl, Model model) {
        String tipo = mapUrlToTipo(tipoUrl);
        if (tipo == null) return "redirect:/dashboard";

        model.addAttribute("categorias", categoriaService.listarPorTipo(tipo));
        model.addAttribute("titulo", tipo.equals("INSUMO") ? "Categorías de Insumos" : "Categorías de Menú");
        
        Categoria nueva = new Categoria();
        nueva.setTipo(tipo);
        model.addAttribute("nuevaCategoria", nueva);
        model.addAttribute("tipoUrl", tipoUrl);
        return "categorias/lista";
    }

    /**
     * POST /categorias/{tipoUrl}/guardar → Procesa el formulario de creación/edición.
     */
    @PostMapping("/{tipoUrl}/guardar")
    public String guardar(@PathVariable String tipoUrl,
                          @Valid @ModelAttribute("nuevaCategoria") Categoria categoria,
                          BindingResult resultado,
                          RedirectAttributes atributosRedireccion,
                          Model model) {
        
        String tipo = mapUrlToTipo(tipoUrl);
        if (tipo == null) return "redirect:/dashboard";

        if (resultado.hasErrors()) {
            model.addAttribute("categorias", categoriaService.listarPorTipo(tipo));
            model.addAttribute("titulo", tipo.equals("INSUMO") ? "Categorías de Insumos" : "Categorías de Menú");
            model.addAttribute("mensajeError", "Error al validar la categoría.");
            model.addAttribute("tipoUrl", tipoUrl);
            return "categorias/lista";
        }

        try {
            categoria.setTipo(tipo);
            categoriaService.guardar(categoria);
            atributosRedireccion.addFlashAttribute("mensajeExito", "Categoría guardada exitosamente.");
        } catch (Exception e) {
            atributosRedireccion.addFlashAttribute("mensajeError", "Error al guardar la categoría: " + e.getMessage());
        }

        return "redirect:/categorias/" + tipoUrl;
    }

    /**
     * GET /categorias/{tipoUrl}/editar/{id} → Muestra el formulario pre-llenado para editar.
     */
    @GetMapping("/{tipoUrl}/editar/{id}")
    public String mostrarFormularioEditar(@PathVariable String tipoUrl,
                                          @PathVariable Long id,
                                          Model model,
                                          RedirectAttributes atributosRedireccion) {
        String tipo = mapUrlToTipo(tipoUrl);
        if (tipo == null) return "redirect:/dashboard";

        return categoriaService.buscarPorId(id)
                .map(categoria -> {
                    model.addAttribute("categorias", categoriaService.listarPorTipo(tipo));
                    model.addAttribute("nuevaCategoria", categoria);
                    model.addAttribute("titulo", tipo.equals("INSUMO") ? "Editar Categoría de Insumos" : "Editar Categoría de Menú");
                    model.addAttribute("tipoUrl", tipoUrl);
                    return "categorias/lista";
                })
                .orElseGet(() -> {
                    atributosRedireccion.addFlashAttribute("mensajeError", "Categoría no encontrada.");
                    return "redirect:/categorias/" + tipoUrl;
                });
    }

    /**
     * POST /categorias/{tipoUrl}/eliminar/{id} → Elimina una categoría por ID.
     */
    @PostMapping("/{tipoUrl}/eliminar/{id}")
    public String eliminar(@PathVariable String tipoUrl,
                           @PathVariable Long id,
                           RedirectAttributes atributosRedireccion) {
        try {
            categoriaService.eliminar(id);
            atributosRedireccion.addFlashAttribute("mensajeExito", "Categoría eliminada exitosamente.");
        } catch (Exception e) {
            atributosRedireccion.addFlashAttribute("mensajeError", "Error al eliminar la categoría: " + e.getMessage());
        }
        return "redirect:/categorias/" + tipoUrl;
    }
}
