package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Categoria;
import com.tuapp.restaurante.models.Producto;
import com.tuapp.restaurante.models.Receta;
import com.tuapp.restaurante.services.CategoriaService;
import com.tuapp.restaurante.services.ProductoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/menu")
public class MenuWebController {

    private final ProductoService productoService;
    private final CategoriaService categoriaService;

    public MenuWebController(ProductoService productoService, CategoriaService categoriaService) {
        this.productoService = productoService;
        this.categoriaService = categoriaService;
    }

    @GetMapping
    public String verMenuPublico(Model model) {
        // Obtenemos solo categorías y productos activos
        List<Categoria> categorias = categoriaService.listarTodas();
        List<Producto> productos = productoService.listarTodos();

        // Lógica "Smart Menu": Verificar disponibilidad en base al stock real de los ingredientes
        Map<Long, Boolean> disponibilidadMap = new HashMap<>();
        
        for (Producto p : productos) {
            boolean disponible = true;
            if (p.getIngredientes() != null && !p.getIngredientes().isEmpty()) {
                for (Receta r : p.getIngredientes()) {
                    if (r.getInsumo() != null && r.getInsumo().getStockActual().compareTo(r.getCantidad()) < 0) {
                        disponible = false; // Agotado por falta de ingredientes
                        break;
                    }
                }
            }
            disponibilidadMap.put(p.getId(), disponible);
        }

        // Agrupamos productos por categoría para la vista
        Map<String, List<Producto>> productosPorCategoria = productos.stream()
                .filter(p -> p.getCategoria() != null)
                .collect(Collectors.groupingBy(p -> p.getCategoria().getNombre()));

        model.addAttribute("categorias", categorias);
        model.addAttribute("productosPorCategoria", productosPorCategoria);
        model.addAttribute("disponibilidad", disponibilidadMap);
        model.addAttribute("titulo", "Menú Digital");

        return "menu/index"; // Retorna una vista pública (sin login)
    }
}
