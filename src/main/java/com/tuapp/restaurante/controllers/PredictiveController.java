package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.services.PredictiveAnalysisService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/ia")
public class PredictiveController {

    private final PredictiveAnalysisService predictiveService;

    public PredictiveController(PredictiveAnalysisService predictiveService) {
        this.predictiveService = predictiveService;
    }

    @GetMapping("/compras")
    public String dashboard(Model model) {
        try {
            PredictiveAnalysisService.ResumenPrediccion diagnostico = predictiveService.generarDiagnostico();
            model.addAttribute("diagnostico", diagnostico);
            model.addAttribute("titulo", "IA Predictiva de Compras");
        } catch (Exception e) {
            model.addAttribute("errorIA", "Error al generar el diagnóstico: " + e.getMessage());
        }
        return "ia/compras";
    }

    @GetMapping("/compras/api")
    @ResponseBody
    public PredictiveAnalysisService.ResumenPrediccion apiDiagnostico() {
        return predictiveService.generarDiagnostico();
    }

    @GetMapping("/ordenes-compra")
    public String ordenesCompra(Model model) {
        try {
            PredictiveAnalysisService.ResumenPrediccion diagnostico = predictiveService.generarDiagnostico();
            
            // Filtrar solo las que necesitan compra y agrupar por proveedor
            java.util.Map<String, java.util.List<PredictiveAnalysisService.RecomendacionCompra>> ordenesPorProveedor = diagnostico.recomendaciones.stream()
                .filter(r -> r.cantidadRecomendada != null && r.cantidadRecomendada.compareTo(java.math.BigDecimal.ZERO) > 0)
                .collect(java.util.stream.Collectors.groupingBy(r -> r.proveedor != null && !r.proveedor.isEmpty() ? r.proveedor : "Sin Proveedor Asignado"));
            
            model.addAttribute("ordenes", ordenesPorProveedor);
            model.addAttribute("titulo", "Órdenes de Compra Automáticas");
            
            // Calcular totales por proveedor
            java.util.Map<String, java.math.BigDecimal> totalesPorProveedor = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, java.util.List<PredictiveAnalysisService.RecomendacionCompra>> entry : ordenesPorProveedor.entrySet()) {
                java.math.BigDecimal total = entry.getValue().stream()
                    .map(r -> r.costoEstimado != null ? r.costoEstimado : java.math.BigDecimal.ZERO)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                totalesPorProveedor.put(entry.getKey(), total);
            }
            model.addAttribute("totales", totalesPorProveedor);
            
        } catch (Exception e) {
            model.addAttribute("error", "Error al generar las órdenes: " + e.getMessage());
        }
        return "ia/ordenes";
    }
}
