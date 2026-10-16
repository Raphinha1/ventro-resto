package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "movimiento_inventario")
public class MovimientoInventario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "tipo_movimiento", nullable = false, length = 50)
    private String tipoMovimiento; // "ENTRADA", "SALIDA", "AJUSTE"

    @Column(name = "cantidad", nullable = false, precision = 19, scale = 6)
    private BigDecimal cantidad;

    // Solo los movimientos del aplicador tienen identidad y causa explícitas.
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "operation_fingerprint", length = 64)
    private String operationFingerprint;

    @Column(name = "causal_origin", length = 100)
    private String causalOrigin;

    @Column(name = "causal_reference", length = 200)
    private String causalReference;

    @Column(name = "resulting_stock", precision = 19, scale = 6)
    private BigDecimal resultingStock;

    @Column(name = "fecha", nullable = false, updatable = false)
    private LocalDateTime fecha;

    @Column(name = "comentario", columnDefinition = "TEXT")
    private String comentario;

    public MovimientoInventario() {}

    @PrePersist
    protected void onCreate() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public String getTipoMovimiento() { return tipoMovimiento; }
    public void setTipoMovimiento(String tipoMovimiento) { this.tipoMovimiento = tipoMovimiento; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID operationId) { this.operationId = operationId; }
    public String getOperationFingerprint() { return operationFingerprint; }
    public void setOperationFingerprint(String operationFingerprint) { this.operationFingerprint = operationFingerprint; }
    public String getCausalOrigin() { return causalOrigin; }
    public void setCausalOrigin(String causalOrigin) { this.causalOrigin = causalOrigin; }
    public String getCausalReference() { return causalReference; }
    public void setCausalReference(String causalReference) { this.causalReference = causalReference; }
    public BigDecimal getResultingStock() { return resultingStock; }
    public void setResultingStock(BigDecimal resultingStock) { this.resultingStock = resultingStock; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
    public String getComentario() { return comentario; }
    public void setComentario(String comentario) { this.comentario = comentario; }
}
