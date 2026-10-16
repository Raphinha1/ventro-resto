package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.services.AsistenciaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/asistencia")
public class AsistenciaController {

    private final AsistenciaService asistenciaService;

    public AsistenciaController(AsistenciaService asistenciaService) {
        this.asistenciaService = asistenciaService;
    }

    // Pantalla pública para empleados
    @GetMapping("/reloj")
    public String relojAsistencia(Model model) {
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "asistencia/reloj";
    }

    @PostMapping("/reloj")
    public String registrarAsistencia(@RequestParam(value = "pin", required = false) String pin,
                                      @RequestParam(value = "requestId", required = false) String requestId,
                                      HttpServletRequest request, RedirectAttributes flash) {
        try {
            AsistenciaService.MarkResult result = asistenciaService.registrarAsistenciaPorPin(
                    pin, requestId, request.getRemoteAddr());
            flash.addFlashAttribute(result.success() ? "mensajeExito" : "mensajeError", result.message());
        } catch (RuntimeException e) {
            flash.addFlashAttribute("mensajeError", "No se pudo registrar la asistencia. Intenta de nuevo.");
        }
        return "redirect:/asistencia/reloj";
    }

    // Pantalla administrativa para ver el reporte
    @GetMapping("/reporte")
    public String reporteAsistencias(Model model) {
        model.addAttribute("asistencias", asistenciaService.listarTodas());
        model.addAttribute("reporteActualizadoEn", LocalDateTime.now());
        model.addAttribute("titulo", "Reporte de Asistencia");
        return "asistencia/reporte";
    }
}
