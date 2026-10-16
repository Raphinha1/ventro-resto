package com.tuapp.restaurante.models;

import com.tuapp.restaurante.inventory.UnitFamily;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Punto de corte auditable, sin efecto sobre el saldo ni el historial de movimientos. */
@Entity
@Table(name = "inventario_reconciliacion")
public class InventarioReconciliacion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "saldo_observado", nullable = false, precision = 19, scale = 6)
    private BigDecimal saldoObservado;

    @Column(name = "suma_movimientos_conocidos", nullable = false, precision = 19, scale = 6)
    private BigDecimal sumaMovimientosConocidos;

    @Column(name = "delta_no_explicado", nullable = false, precision = 19, scale = 6)
    private BigDecimal deltaNoExplicado;

    @Column(name = "corte_en", nullable = false)
    private OffsetDateTime corteEn;

    @Column(name = "version_calculo", nullable = false, length = 50)
    private String versionCalculo;

    @Column(name = "clasificacion", nullable = false, length = 50)
    private String clasificacion;

    @Column(name = "creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creado_por_usuario_id")
    private Usuario creadoPor;

    @Column(name = "revisado_en")
    private OffsetDateTime revisadoEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revisado_por_usuario_id")
    private Usuario revisadoPor;

    @Column(name = "procedencia", columnDefinition = "TEXT")
    private String procedencia;

    @Column(name = "evidencia", columnDefinition = "TEXT")
    private String evidencia;

    @Enumerated(EnumType.STRING)
    @Column(name = "unidad_historica_familia", length = 8)
    private UnitFamily unidadHistoricaFamilia;

    @Column(name = "unidad_historica_codigo", length = 8)
    private String unidadHistoricaCodigo;

    @Column(name = "punto_inicial_en")
    private LocalDateTime puntoInicialEn;

    @Column(name = "punto_inicial_saldo", precision = 19, scale = 6)
    private BigDecimal puntoInicialSaldo;

    @Column(name = "huella_comando", length = 64)
    private String huellaComando;

    public InventarioReconciliacion() {}

    public Long getId() { return id; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public BigDecimal getSaldoObservado() { return saldoObservado; }
    public void setSaldoObservado(BigDecimal saldoObservado) { this.saldoObservado = saldoObservado; }
    public BigDecimal getSumaMovimientosConocidos() { return sumaMovimientosConocidos; }
    public void setSumaMovimientosConocidos(BigDecimal sumaMovimientosConocidos) { this.sumaMovimientosConocidos = sumaMovimientosConocidos; }
    public BigDecimal getDeltaNoExplicado() { return deltaNoExplicado; }
    public void setDeltaNoExplicado(BigDecimal deltaNoExplicado) { this.deltaNoExplicado = deltaNoExplicado; }
    public OffsetDateTime getCorteEn() { return corteEn; }
    public void setCorteEn(OffsetDateTime corteEn) { this.corteEn = corteEn; }
    public String getVersionCalculo() { return versionCalculo; }
    public void setVersionCalculo(String versionCalculo) { this.versionCalculo = versionCalculo; }
    public String getClasificacion() { return clasificacion; }
    public void setClasificacion(String clasificacion) { this.clasificacion = clasificacion; }
    public OffsetDateTime getCreadoEn() { return creadoEn; }
    public Usuario getCreadoPor() { return creadoPor; }
    public void setCreadoPor(Usuario creadoPor) { this.creadoPor = creadoPor; }
    public OffsetDateTime getRevisadoEn() { return revisadoEn; }
    public void setRevisadoEn(OffsetDateTime revisadoEn) { this.revisadoEn = revisadoEn; }
    public Usuario getRevisadoPor() { return revisadoPor; }
    public void setRevisadoPor(Usuario revisadoPor) { this.revisadoPor = revisadoPor; }
    public String getProcedencia() { return procedencia; }
    public void setProcedencia(String procedencia) { this.procedencia = procedencia; }
    public String getEvidencia() { return evidencia; }
    public void setEvidencia(String evidencia) { this.evidencia = evidencia; }
    public UnitFamily getUnidadHistoricaFamilia() { return unidadHistoricaFamilia; }
    public String getUnidadHistoricaCodigo() { return unidadHistoricaCodigo; }
    public LocalDateTime getPuntoInicialEn() { return puntoInicialEn; }
    public BigDecimal getPuntoInicialSaldo() { return puntoInicialSaldo; }
    public String getHuellaComando() { return huellaComando; }
}
