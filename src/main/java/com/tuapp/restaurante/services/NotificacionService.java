package com.tuapp.restaurante.services;

import org.springframework.stereotype.Service;

/**
 * External messaging is intentionally disabled in the public portfolio snapshot.
 * The service contract remains so existing callers compile without sending or
 * logging phone numbers and message content.
 */
@Service
public class NotificacionService {

    public void enviarAlertaWhatsApp(String telefonoDestino, String mensaje) {
        // No external provider, credentials, network request, or personal-data log.
    }
}
