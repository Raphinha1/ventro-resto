package com.tuapp.restaurante.config;

import com.tuapp.restaurante.services.AttendancePinCodec;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.sql.Connection;

/** Baseline heredado solo bajo solicitud explicita y despues de verificar el catalogo. */
@Configuration
public class SchemaMigrationConfig {

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(Environment environment, AttendancePinCodec pinCodec) {
        return flyway -> {
            // Fallar antes de aplicar V4: sin esta clave, los PIN heredados quedarían invalidados
            // y el despliegue no podría arrancar para restablecerlos.
            pinCodec.secretCheck();
            if (flyway.getConfiguration().isBaselineOnMigrate()) {
                throw new IllegalStateException("baselineOnMigrate no esta permitido; usar verificacion heredada explicita");
            }
            String request = environment.getProperty("ventro.legacy-baseline-requested", "false");
            if (!"true".equalsIgnoreCase(request) && !"false".equalsIgnoreCase(request)) {
                throw new IllegalStateException("ventro.legacy-baseline-requested debe ser true o false");
            }
            if ("true".equalsIgnoreCase(request)) {
                String expectedDatabase = environment.getProperty("ventro.legacy-baseline-database");
                try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                    LegacySchemaCompatibility.verify(connection, expectedDatabase);
                } catch (Exception e) {
                    throw new IllegalStateException("Baseline heredado rechazado; no se modifico el esquema", e);
                }
                flyway.baseline();
            }
            flyway.migrate();
        };
    }
}
