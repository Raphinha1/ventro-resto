package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Producto;
import com.tuapp.restaurante.repositories.InsumoRepository;
import com.tuapp.restaurante.services.AuditoriaService;
import com.tuapp.restaurante.services.CategoriaService;
import com.tuapp.restaurante.services.ProductoService;
import com.tuapp.restaurante.config.DeploymentStorage;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/productos")
public class ProductoController {


    public ProductoController(ProductoService productoService, CategoriaService categoriaService,
                               AuditoriaService auditoriaService, InsumoRepository insumoRepository,
                               DeploymentStorage storage) {
        this.productoService = productoService;
        this.categoriaService = categoriaService;
        this.auditoriaService = auditoriaService;
        this.insumoRepository = insumoRepository;
        this.storage = storage;
    }

    private final ProductoService productoService;
    private final CategoriaService categoriaService;
    private final AuditoriaService auditoriaService;
    private final InsumoRepository insumoRepository;
    private final DeploymentStorage storage;

    @GetMapping
    public String listar(Model model) {
        model.addAttribute("productos", productoService.listarTodos());
        model.addAttribute("categorias", categoriaService.listarPorTipo("MENU"));
        model.addAttribute("insumos", insumoRepository.findAll());
        model.addAttribute("titulo", "Menú / Productos");
        return "productos/lista";
    }

    @GetMapping("/nuevo")
    public String mostrarFormularioNuevo(Model model) {
        model.addAttribute("producto", new Producto());
        model.addAttribute("productos", productoService.listarTodos());
        model.addAttribute("categorias", categoriaService.listarPorTipo("MENU"));
        model.addAttribute("insumos", insumoRepository.findAll());
        model.addAttribute("titulo", "Menú / Productos");
        model.addAttribute("esNuevo", true);
        return "productos/lista";
    }

    @PostMapping("/guardar")
    public String guardar(@Valid @ModelAttribute("producto") Producto producto,
                          BindingResult result,
                          @RequestParam(value = "foto", required = false) org.springframework.web.multipart.MultipartFile foto,
                          @RequestParam(value = "eliminarFoto", required = false, defaultValue = "false") boolean eliminarFoto,
                          @RequestParam(value = "insumoReventaId", required = false) Long insumoReventaId,
                          RedirectAttributes flash,
                          Model model) {

        if (result.hasErrors()) {
            model.addAttribute("productos", productoService.listarTodos());
            model.addAttribute("categorias", categoriaService.listarPorTipo("MENU"));
            model.addAttribute("insumos", insumoRepository.findAll());
            model.addAttribute("titulo", "Menú / Productos");
            model.addAttribute("mensajeError", "Por favor corrija los errores en el formulario.");
            return "productos/lista";
        }

        try {
            if (eliminarFoto) {
                producto.setImagenUrl(null);
            } else if (foto != null && !foto.isEmpty()) {
                // Validación básica de extensión para seguridad
                String originalName = foto.getOriginalFilename();
                String ext = (originalName != null && originalName.contains(".")) 
                             ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase() 
                             : "";
                
                if (!ext.matches(".(jpg|jpeg|png|gif|webp)")) {
                    throw new IllegalArgumentException("Tipo de archivo no permitido. Solo se aceptan imágenes (jpg, png, gif, webp).");
                }

                String nombreArchivo = java.util.UUID.randomUUID().toString() + ext;
                
                java.nio.file.Path rutaDirectorio = storage.root();
                
                if (!java.nio.file.Files.exists(rutaDirectorio)) {
                    java.nio.file.Files.createDirectories(rutaDirectorio);
                }
                java.nio.file.Path rutaCompleta = rutaDirectorio.resolve(nombreArchivo);
                java.nio.file.Files.copy(foto.getInputStream(), rutaCompleta, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                producto.setImagenUrl("/uploads/" + nombreArchivo);
            } else if (producto.getId() != null) {
                // Keep the old image if a new one wasn't uploaded during edit
                productoService.buscarPorId(producto.getId()).ifPresent(p -> producto.setImagenUrl(p.getImagenUrl()));
            }

            productoService.guardar(producto);

            // Vincular el insumo de reventa
            if (producto.isEsReventa() && insumoReventaId != null) {
                insumoRepository.findById(insumoReventaId).ifPresent(insumo -> {
                    producto.setInsumoReventa(insumo);
                    productoService.guardar(producto);
                });
            } else if (!producto.isEsReventa()) {
                producto.setInsumoReventa(null);
                productoService.guardar(producto);
            }

            flash.addFlashAttribute("mensajeExito", "Producto guardado exitosamente.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error: " + e.getMessage());
        }

        return "redirect:/productos";
    }

    @GetMapping("/editar/{id}")
    public String mostrarFormularioEditar(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return productoService.buscarPorId(id).map(producto -> {
            model.addAttribute("producto", producto);
            model.addAttribute("productos", productoService.listarTodos());
            model.addAttribute("categorias", categoriaService.listarPorTipo("MENU"));
            model.addAttribute("insumos", insumoRepository.findAll());
            model.addAttribute("titulo", "Menú / Productos");
            model.addAttribute("esNuevo", false);
            return "productos/lista";
        }).orElseGet(() -> {
            flash.addFlashAttribute("mensajeError", "Producto no encontrado.");
            return "redirect:/productos";
        });
    }

    @PostMapping("/eliminar/{id}")
    public String eliminar(@PathVariable Long id, RedirectAttributes flash) {
        try {
            productoService.eliminar(id);
            auditoriaService.registrarLog("MENU", "ELIMINAR_PRODUCTO", "Se eliminó el producto ID: " + id);
            flash.addFlashAttribute("mensajeExito", "Producto eliminado exitosamente.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al eliminar: " + e.getMessage());
        }
        return "redirect:/productos";
    }
}
