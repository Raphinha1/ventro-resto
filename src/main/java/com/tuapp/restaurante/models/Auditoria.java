package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "auditoria")
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String usuario; // Quién lo hizo (o "SISTEMA")

    @Column(nullable = false)
    private String accion; // Ej: ELIMINAR_PRODUCTO, EDITAR_STOCK, VENTA_ALTA

    @Column(length = 2000)
    private String descripcion; // Detalles de lo ocurrido

    @Column(nullable = false)
    private LocalDateTime fecha;

    private String modulo; // Inventario, POS, Menú, etc.

    public Auditoria() {}

    public Auditoria(String usuario, String modulo, String accion, String descripcion) {
        this.usuario = usuario;
        this.modulo = modulo;
        this.accion = accion;
        this.descripcion = descripcion;
        this.fecha = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public String getAccion() { return accion; }
    public void setAccion(String accion) { this.accion = accion; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
    public String getModulo() { return modulo; }
    public void setModulo(String modulo) { this.modulo = modulo; }
}
