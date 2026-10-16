package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "clientes")
public class Cliente implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    private String email;
    private String telefono;

    @Column(length = 500)
    private String direccion;

    @Column(length = 500)
    private String referencias;

    @Column(name = "mapa_url", length = 1000)
    private String mapaUrl;

    @Column(name = "tipo", nullable = false)
    private String tipo = "NORMAL"; // NORMAL, VIP

    @Column(name = "puntos", nullable = false)
    private Integer puntos = 0;

    @Column(name = "saldo_credito", precision = 10, scale = 2, nullable = false)
    private BigDecimal saldoCredito = BigDecimal.ZERO;

    @Column(name = "limite_credito", precision = 10, scale = 2, nullable = false)
    private BigDecimal limiteCredito = BigDecimal.ZERO;

    @Column(name = "total_gastado", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalGastado = BigDecimal.ZERO;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    @Column(name = "activo", nullable = false, columnDefinition = "BOOLEAN DEFAULT true")
    private boolean activo = true;

    public Cliente() {}

    @PrePersist
    public void prePersist() {
        if (fechaRegistro == null) {
            fechaRegistro = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public Integer getPuntos() { return puntos; }
    public void setPuntos(Integer puntos) { this.puntos = puntos; }
    public BigDecimal getSaldoCredito() { return saldoCredito; }
    public void setSaldoCredito(BigDecimal saldoCredito) { this.saldoCredito = saldoCredito; }
    public BigDecimal getLimiteCredito() { return limiteCredito; }
    public void setLimiteCredito(BigDecimal limiteCredito) { this.limiteCredito = limiteCredito; }
    public BigDecimal getTotalGastado() { return totalGastado; }
    public void setTotalGastado(BigDecimal totalGastado) { this.totalGastado = totalGastado; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }

    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }

    public String getReferencias() { return referencias; }
    public void setReferencias(String referencias) { this.referencias = referencias; }

    public String getMapaUrl() { return mapaUrl; }
    public void setMapaUrl(String mapaUrl) { this.mapaUrl = mapaUrl; }
}
