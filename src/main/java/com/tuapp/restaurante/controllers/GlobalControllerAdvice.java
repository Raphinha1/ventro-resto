package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.services.ConfiguracionService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalControllerAdvice {

    private final ConfiguracionService configuracionService;

    public GlobalControllerAdvice(ConfiguracionService configuracionService) {
        this.configuracionService = configuracionService;
    }

    @ModelAttribute("planActivo")
    public String getPlanActivo() {
        ConfiguracionSistema config = configuracionService.obtener();
        return config != null && config.getNivelPlan() != null ? config.getNivelPlan() : "PREMIUM";
    }

    @ModelAttribute("configSistema")
    public ConfiguracionSistema getConfigSistema() {
        ConfiguracionSistema config = configuracionService.obtener();
        return config != null ? config : new ConfiguracionSistema();
    }
}
