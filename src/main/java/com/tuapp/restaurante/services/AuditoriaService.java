package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Auditoria;
import com.tuapp.restaurante.repositories.AuditoriaRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

@Service
public class AuditoriaService {


    public AuditoriaService(AuditoriaRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    private final AuditoriaRepository auditRepo;

    /**
     * Registra una acción de auditoría con el usuario logueado actualmente.
     */
    public void registrarLog(String modulo, String accion, String descripcion) {
        String usuarioActual = SecurityContextHolder.getContext().getAuthentication() != null 
                             ? SecurityContextHolder.getContext().getAuthentication().getName() 
                             : "SISTEMA";
        
        Auditoria log = new Auditoria();
        log.setUsuario(usuarioActual);
        log.setModulo(modulo);
        log.setAccion(accion);
        log.setDescripcion(descripcion);
        log.setFecha(LocalDateTime.now());
        
        auditRepo.save(log);
        
        // Simulación de Alerta a dispositivos externos (Telegram/WhatsApp)
        if (accion.contains("CRITICO") || accion.contains("ALTO") || accion.contains("ELIMINAR")) {
            enviarNotificacionRemota(log);
        }
    }

    private void enviarNotificacionRemota(Auditoria log) {
        // En una implementación real, aquí se llamaría a la API de Telegram o WhatsApp
        System.out.println(">>> 🚨 ALERTA DE SEGURIDAD/INTELIGENCIA ENVIADA A TELEGRAM/WHATSAPP <<<");
        System.out.println("INFO -> " + log.getModulo() + " [" + log.getAccion() + "]: " + log.getDescripcion());
    }
}
