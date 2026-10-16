package com.tuapp.restaurante.models;

import com.tuapp.restaurante.inventory.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Regla de compra con identidad estable; una regla diferente requiere otra fila. */
@Entity
@Table(name = "inventory_purchase_presentation")
public class InventoryPurchasePresentation {
    public enum Mode { FIJA, VARIABLE }
    public enum Status { ACTIVA, INACTIVA }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;
    @Column(nullable = false, length = 150)
    private String nombre;
    @Column(name = "unidad_compra_codigo", nullable = false, length = 50)
    private String unidadCompraCodigo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private Mode modo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private UnitFamily familia;
    @Column(name = "unidad_base_codigo", nullable = false, length = 8)
    private String unidadBaseCodigo;
    @Column(name = "factor_base", precision = 24, scale = 12, columnDefinition = "inventory_5b_factor")
    private BigDecimal factorBase;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private Status estado = Status.ACTIVA;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creado_por_usuario_id", nullable = false)
    private Usuario creadoPor;
    @Column(name = "creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    protected InventoryPurchasePresentation() {}

    public InventoryPurchasePresentation(Insumo insumo, String nombre, String unidadCompraCodigo,
                                         Mode modo, BigDecimal factorBase, Usuario actor) {
        this.insumo = Objects.requireNonNull(insumo, "Insumo obligatorio");
        InventoryWritePolicy.requireApplier(insumo.getEstadoInventario5a());
        BaseUnit unit = BaseUnit.require(insumo.getUnidadBaseFamilia(), insumo.getUnidadBaseCodigo());
        this.nombre = nombre;
        this.unidadCompraCodigo = unidadCompraCodigo;
        this.modo = Objects.requireNonNull(modo, "Modo obligatorio");
        this.familia = unit.family();
        this.unidadBaseCodigo = unit.symbol();
        this.factorBase = factorBase;
        this.creadoPor = Objects.requireNonNull(actor, "Actor obligatorio");
        validateNumbers();
    }

    @PrePersist @PreUpdate
    private void validateNumbers() {
        if (modo == Mode.FIJA) factorBase = ReceiptNumbers.factor(factorBase);
        else if (modo != Mode.VARIABLE || factorBase != null) {
            throw new IllegalArgumentException("Presentación variable no tiene factor");
        }
    }

    public void setEstado(Status estado) { this.estado = Objects.requireNonNull(estado); }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public Long getId() { return id; }
    public Insumo getInsumo() { return insumo; }
    public String getNombre() { return nombre; }
    public String getUnidadCompraCodigo() { return unidadCompraCodigo; }
    public Mode getModo() { return modo; }
    public UnitFamily getFamilia() { return familia; }
    public String getUnidadBaseCodigo() { return unidadBaseCodigo; }
    public BigDecimal getFactorBase() { return factorBase; }
    public Status getEstado() { return estado; }
    public Usuario getCreadoPor() { return creadoPor; }
    public OffsetDateTime getCreadoEn() { return creadoEn; }
}
