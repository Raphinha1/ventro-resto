package com.tuapp.restaurante.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creation input deliberately has no persistence identifier or PIN digest. */
public class CreateUsuarioRequest {
    @NotBlank(message = "El nombre de usuario es obligatorio")
    @Size(min = 3, max = 50, message = "El username debe tener entre 3 y 50 caracteres")
    private String username;
    @NotBlank(message = "La contraseña es obligatoria")
    private String password;
    @NotBlank(message = "El nombre completo es obligatorio")
    @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
    private String nombreCompleto;
    @NotBlank(message = "El rol es obligatorio")
    private String rol;
    private boolean activo = true;

    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public String getNombreCompleto() { return nombreCompleto; }
    public void setNombreCompleto(String value) { nombreCompleto = value; }
    public String getRol() { return rol; }
    public void setRol(String value) { rol = value; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean value) { activo = value; }
}
