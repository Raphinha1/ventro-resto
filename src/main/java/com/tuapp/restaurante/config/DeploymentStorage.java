package com.tuapp.restaurante.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Raíz de archivos fijada por el despliegue, sin segmentos provenientes de la petición. */
@Component
public class DeploymentStorage {
    private final Path root;

    public DeploymentStorage(@Value("${app.upload-dir}") String configuredRoot) throws IOException {
        if (configuredRoot.isBlank() || !Path.of(configuredRoot).isAbsolute()) {
            throw new IllegalStateException("UPLOAD_DIR debe ser una ruta absoluta del despliegue");
        }
        root = Path.of(configuredRoot).normalize();
        Files.createDirectories(root);
    }

    public Path root() { return root; }
}
