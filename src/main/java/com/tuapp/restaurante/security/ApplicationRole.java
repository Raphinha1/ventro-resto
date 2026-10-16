package com.tuapp.restaurante.security;

/** Canonical application roles; the optional legacy prefix is the only accepted alias. */
public enum ApplicationRole {
    ADMIN, GERENTE, EMPLEADO, MESERO, COCINERO, SUPER_ADMIN;

    public String authority() { return "ROLE_" + name(); }

    public static ApplicationRole parse(String value) {
        if (value == null) throw new IllegalArgumentException("El rol es obligatorio");
        try {
            return valueOf(value.startsWith("ROLE_") ? value.substring(5) : value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Rol no permitido");
        }
    }
}
