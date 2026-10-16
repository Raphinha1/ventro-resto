package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.dto.ConfiguracionUpdate;
import org.springframework.security.access.AccessDeniedException;
import com.tuapp.restaurante.services.ConfiguracionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/configuracion")
public class ConfiguracionController {

    private final ConfiguracionService configuracionService;

    public ConfiguracionController(ConfiguracionService configuracionService) {
        this.configuracionService = configuracionService;
    }

    @GetMapping
    public String index(Model model) {
        ConfiguracionSistema config = configuracionService.obtener();
        model.addAttribute("configuracion", config);
        model.addAttribute("titulo", "Configuración del Sistema");
        return "configuracion/index";
    }

    @PostMapping("/guardar")
    public String guardar(@ModelAttribute("configuracion") ConfiguracionUpdate configuracion, RedirectAttributes flash, jakarta.servlet.http.HttpServletRequest request) {
        try {
            configuracionService.guardar(configuracion);
            flash.addFlashAttribute("mensajeExito", "Configuración actualizada correctamente.");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al guardar la configuración: " + e.getMessage());
        }
        return "redirect:/configuracion";
    }
}
