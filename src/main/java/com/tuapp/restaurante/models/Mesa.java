package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "mesas")
public class Mesa implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Integer numero;

    @Column(nullable = false)
    private Integer capacidad = 4;

    @Column(nullable = false)
    private String estado = "LIBRE";

    private String zona;

    @Column(name = "hora_apertura")
    private java.time.LocalDateTime horaApertura;

    private String mozo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venta_id")
    private Venta ventaActual;

    @Column(name = "activo", nullable = false, columnDefinition = "BOOLEAN DEFAULT true")
    private boolean activo = true;

    public Mesa() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getNumero() { return numero; }
    public void setNumero(Integer numero) { this.numero = numero; }
    public Integer getCapacidad() { return capacidad; }
    public void setCapacidad(Integer capacidad) { this.capacidad = capacidad; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getZona() { return zona; }
    public void setZona(String zona) { this.zona = zona; }
    public java.time.LocalDateTime getHoraApertura() { return horaApertura; }
    public void setHoraApertura(java.time.LocalDateTime horaApertura) { this.horaApertura = horaApertura; }
    public String getMozo() { return mozo; }
    public void setMozo(String mozo) { this.mozo = mozo; }
    public Venta getVentaActual() { return ventaActual; }
    public void setVentaActual(Venta ventaActual) { this.ventaActual = ventaActual; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
}
