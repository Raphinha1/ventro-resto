package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "corte_caja")
public class CorteCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fechaApertura;

    private LocalDateTime fechaCierre;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal montoInicial = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalEfectivo = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalTarjeta = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalVentas = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalRetiros = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal montoContado;

    @Column(precision = 12, scale = 2)
    private BigDecimal diferencia;

    @Column(length = 500)
    private String notasCierre;

    @Column(nullable = false)
    private String estado = "ABIERTO";

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id")
    private Usuario cajero;

    // Standard Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getFechaApertura() { return fechaApertura; }
    public void setFechaApertura(LocalDateTime fechaApertura) { this.fechaApertura = fechaApertura; }
    public LocalDateTime getFechaCierre() { return fechaCierre; }
    public void setFechaCierre(LocalDateTime fechaCierre) { this.fechaCierre = fechaCierre; }
    public BigDecimal getMontoInicial() { return montoInicial; }
    public void setMontoInicial(BigDecimal montoInicial) { this.montoInicial = montoInicial; }
    public BigDecimal getTotalEfectivo() { return totalEfectivo; }
    public void setTotalEfectivo(BigDecimal totalEfectivo) { this.totalEfectivo = totalEfectivo; }
    public BigDecimal getTotalTarjeta() { return totalTarjeta; }
    public void setTotalTarjeta(BigDecimal totalTarjeta) { this.totalTarjeta = totalTarjeta; }
    public BigDecimal getTotalVentas() { return totalVentas; }
    public void setTotalVentas(BigDecimal totalVentas) { this.totalVentas = totalVentas; }
    public BigDecimal getTotalRetiros() { return totalRetiros; }
    public void setTotalRetiros(BigDecimal totalRetiros) { this.totalRetiros = totalRetiros; }
    public BigDecimal getMontoContado() { return montoContado; }
    public void setMontoContado(BigDecimal montoContado) { this.montoContado = montoContado; }
    public BigDecimal getDiferencia() { return diferencia; }
    public void setDiferencia(BigDecimal diferencia) { this.diferencia = diferencia; }
    public String getNotasCierre() { return notasCierre; }
    public void setNotasCierre(String notasCierre) { this.notasCierre = notasCierre; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public Usuario getCajero() { return cajero; }
    public void setCajero(Usuario cajero) { this.cajero = cajero; }
}
