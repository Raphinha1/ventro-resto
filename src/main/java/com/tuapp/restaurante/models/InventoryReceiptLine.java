package com.tuapp.restaurante.models;

import com.tuapp.restaurante.inventory.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

import java.util.UUID;

/** Snapshot histórico de conversión/costo, sin saldo ni movimiento operativo. */
@Entity
@Table(name = "inventory_receipt_line")
public class InventoryReceiptLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private InventoryReceipt receipt;
    @Column(name = "line_no", nullable = false)
    private int lineNo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "presentation_id", nullable = false)
    private InventoryPurchasePresentation presentation;
    @Column(name = "cantidad_comprada", nullable = false, precision = 19, scale = 6, columnDefinition = "inventory_5b_quantity")
    private BigDecimal cantidadComprada;
    @Column(name = "cantidad_base_recibida", precision = 19, scale = 6, columnDefinition = "inventory_5b_quantity")
    private BigDecimal cantidadBaseRecibida;
    @Column(name = "cantidad_base_efectiva", nullable = false, precision = 19, scale = 6, columnDefinition = "inventory_5b_quantity")
    private BigDecimal cantidadBaseEfectiva;
    @Column(name = "costo_total_real", nullable = false, precision = 19, scale = 2, columnDefinition = "inventory_5b_cost")
    private BigDecimal costoTotalReal;
    @Column(name = "presentation_nombre", nullable = false, length = 150)
    private String presentationNombre;
    @Column(name = "unidad_compra_codigo", nullable = false, length = 50)
    private String unidadCompraCodigo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private InventoryPurchasePresentation.Mode modo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private UnitFamily familia;
    @Column(name = "unidad_base_codigo", nullable = false, length = 8)
    private String unidadBaseCodigo;
    @Column(name = "factor_base", precision = 24, scale = 12, columnDefinition = "inventory_5b_factor")
    private BigDecimal factorBase;
    @Column(name = "calculation_version", nullable = false, length = 50)
    private String calculationVersion = "receipt-conversion-v1";
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    protected InventoryReceiptLine() {}

    InventoryReceiptLine(InventoryReceipt receipt, int lineNo, InventoryPurchasePresentation presentation,
                         BigDecimal purchased, BigDecimal receivedBase, BigDecimal cost, UUID operationId) {
        if (lineNo <= 0) throw new IllegalArgumentException("lineNo debe ser positivo");
        this.receipt = receipt;
        this.lineNo = lineNo;
        this.presentation = Objects.requireNonNull(presentation, "Presentación obligatoria");
        if (presentation.getEstado() != InventoryPurchasePresentation.Status.ACTIVA) {
            throw new IllegalArgumentException("Presentación INACTIVA");
        }
        this.insumo = presentation.getInsumo();
        this.presentationNombre = presentation.getNombre();
        this.unidadCompraCodigo = presentation.getUnidadCompraCodigo();
        this.modo = presentation.getModo();
        this.familia = presentation.getFamilia();
        this.unidadBaseCodigo = presentation.getUnidadBaseCodigo();
        this.factorBase = presentation.getFactorBase();
        this.cantidadComprada = ReceiptNumbers.quantity(purchased);
        this.costoTotalReal = ReceiptNumbers.cost(cost);
        this.operationId = Objects.requireNonNull(operationId, "operationId obligatorio");
        if (modo == InventoryPurchasePresentation.Mode.FIJA) {
            if (receivedBase != null) throw new IllegalArgumentException("Base fija se calcula en servidor");
            this.cantidadBaseEfectiva = ReceiptNumbers.quantity(cantidadComprada.multiply(factorBase));
        } else {
            this.cantidadBaseRecibida = ReceiptNumbers.quantity(receivedBase);
            this.cantidadBaseEfectiva = cantidadBaseRecibida;
        }
    }

    @PrePersist
    private void validateNumbers() {
        cantidadComprada = ReceiptNumbers.quantity(cantidadComprada);
        cantidadBaseEfectiva = ReceiptNumbers.quantity(cantidadBaseEfectiva);
        if (cantidadBaseRecibida != null) cantidadBaseRecibida = ReceiptNumbers.quantity(cantidadBaseRecibida);
        if (factorBase != null) factorBase = ReceiptNumbers.factor(factorBase);
        costoTotalReal = ReceiptNumbers.cost(costoTotalReal);
    }
    public Long getId() { return id; }
    public InventoryReceipt getReceipt() { return receipt; }
    public int getLineNo() { return lineNo; }
    public Insumo getInsumo() { return insumo; }
    public InventoryPurchasePresentation getPresentation() { return presentation; }
    public BigDecimal getCantidadComprada() { return cantidadComprada; }
    public BigDecimal getCantidadBaseRecibida() { return cantidadBaseRecibida; }
    public BigDecimal getCantidadBaseEfectiva() { return cantidadBaseEfectiva; }
    public BigDecimal getCostoTotalReal() { return costoTotalReal; }
    public String getPresentationNombre() { return presentationNombre; }
    public String getUnidadCompraCodigo() { return unidadCompraCodigo; }
    public InventoryPurchasePresentation.Mode getModo() { return modo; }
    public UnitFamily getFamilia() { return familia; }
    public String getUnidadBaseCodigo() { return unidadBaseCodigo; }
    public BigDecimal getFactorBase() { return factorBase; }
    public String getCalculationVersion() { return calculationVersion; }
    public java.util.UUID getOperationId() { return operationId; }
}
