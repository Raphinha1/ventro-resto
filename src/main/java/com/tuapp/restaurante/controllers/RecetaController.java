package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Receta;
import com.tuapp.restaurante.services.InsumoService;
import com.tuapp.restaurante.services.ProductoService;
import com.tuapp.restaurante.services.RecetaService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/recetas")
public class RecetaController {


    public RecetaController(RecetaService recetaService, ProductoService productoService, InsumoService insumoService) {
        this.recetaService = recetaService;
        this.productoService = productoService;
        this.insumoService = insumoService;
    }

    private final RecetaService recetaService;
    private final ProductoService productoService;
    private final InsumoService insumoService;

    // Mostrar modal o pantalla para administrar recetas de un Platillo
    @GetMapping("/producto/{productoId}")
    public String gestionarReceta(@PathVariable Long productoId, Model model, RedirectAttributes flash) {
        return productoService.buscarPorId(productoId).map(producto -> {
            Receta nuevaReceta = new Receta();
            nuevaReceta.setProducto(producto);
            
            BigDecimal costoProduccion = productoService.calcularCostoProduccion(productoId);
            
            model.addAttribute("producto", producto);
            model.addAttribute("receta", nuevaReceta); // Para el objeto binding del formulario
            model.addAttribute("insumos", insumoService.listarTodos());
            model.addAttribute("costoProduccion", costoProduccion);
            model.addAttribute("titulo", "Receta: " + producto.getNombre());
            return "productos/receta";
        }).orElseGet(() -> {
            flash.addFlashAttribute("mensajeError", "Producto no encontrado.");
            return "redirect:/productos";
        });
    }

    @PostMapping("/agregar")
    public String agregarIngrediente(@Valid @ModelAttribute("receta") Receta receta,
                                     BindingResult result,
                                     RedirectAttributes flash) {
        
        Long productoId = receta.getProducto().getId();
        
        if (result.hasErrors()) {
            flash.addFlashAttribute("mensajeError", "Debes seleccionar un insumo y una cantidad válida.");
            return "redirect:/recetas/producto/" + productoId;
        }

        try {
            recetaService.agregarIngrediente(receta);
            flash.addFlashAttribute("mensajeExito", "Ingrediente agregado a la receta.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al agregar el ingrediente: " + e.getMessage());
        }

        return "redirect:/recetas/producto/" + productoId;
    }

    @PostMapping("/eliminar/{id}")
    public String eliminarIngrediente(@PathVariable Long id, 
                                      @RequestParam("productoId") Long productoId,
                                      RedirectAttributes flash) {
        try {
            recetaService.eliminarIngrediente(id);
            flash.addFlashAttribute("mensajeExito", "Ingrediente retirado.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error: " + e.getMessage());
        }
        return "redirect:/recetas/producto/" + productoId;
    }
}
