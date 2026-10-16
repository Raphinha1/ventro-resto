package com.tuapp.restaurante.config;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Comparacion cerrada con el esquema generado por la version heredada en PostgreSQL 15. */
final class LegacySchemaCompatibility {
    private LegacySchemaCompatibility() {}

    static void verify(Connection connection, String expectedDatabase) throws Exception {
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalStateException("Se requiere el nombre exacto de la BD heredada");
        }
        if (connection.getMetaData().getDatabaseMajorVersion() != 15) {
            throw new IllegalStateException("El inventario heredado solo esta certificado para PostgreSQL 15");
        }
        try (Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("select current_database()")) {
                result.next();
                if (!expectedDatabase.equals(result.getString(1))) {
                    throw new IllegalStateException("El nombre confirmado no coincide con la BD conectada");
                }
            }
            try (ResultSet result = statement.executeQuery(
                    "select to_regclass('public.flyway_schema_history')")) {
                result.next();
                if (result.getString(1) != null) {
                    throw new IllegalStateException("La BD ya tiene historial Flyway; no repetir baseline");
                }
            }
            try (ResultSet result = statement.executeQuery(
                    "select nspname from pg_namespace where nspname not in ('public','information_schema') " +
                    "and nspname not like 'pg_%' limit 1")) {
                if (result.next()) {
                    throw new IllegalStateException("Hay un esquema adicional no inventariado: " + result.getString(1));
                }
            }
        }

        List<String> expected = readLines("db/legacy-schema-manifest.txt");
        String query = readText("db/legacy-schema-manifest.sql");
        List<String> actual = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) actual.add(result.getString(1));
        }
        if (!expected.equals(actual)) {
            int mismatch = 0;
            while (mismatch < expected.size() && mismatch < actual.size()
                    && expected.get(mismatch).equals(actual.get(mismatch))) mismatch++;
            throw new IllegalStateException("Catalogo distinto del esquema heredado certificado: " +
                    "objetos esperados=" + expected.size() + ", encontrados=" + actual.size() +
                    ", primera diferencia=" + mismatch);
        }
    }

    private static String readText(String path) throws Exception {
        try (InputStream stream = LegacySchemaCompatibility.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Falta el inventario " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> readLines(String path) throws Exception {
        try (InputStream stream = LegacySchemaCompatibility.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Falta el inventario " + path);
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList();
        }
    }
}
