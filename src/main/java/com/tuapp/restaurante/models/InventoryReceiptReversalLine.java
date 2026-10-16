package com.tuapp.restaurante.models;

import com.tuapp.restaurante.inventory.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name="inventory_receipt_reversal_line")
public class InventoryReceiptReversalLine {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="reversal_id",nullable=false) private InventoryReceiptReversal reversal;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="original_receipt_line_id",nullable=false) private InventoryReceiptLine originalLine;
    @Column(name="line_no",nullable=false) private int lineNo;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="insumo_id",nullable=false) private Insumo insumo;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=8) private UnitFamily familia;
    @Column(name="unidad_base_codigo",nullable=false,length=8) private String unidadBaseCodigo;
    @Column(name="cantidad_base_compensada",nullable=false,precision=19,scale=6,columnDefinition="inventory_5b_quantity")
    private BigDecimal cantidadBaseCompensada;
    @Column(name="operation_id",nullable=false) private UUID operationId;
    protected InventoryReceiptReversalLine() {}
    InventoryReceiptReversalLine(InventoryReceiptReversal reversal,InventoryReceiptLine original,UUID operationId) {
        this.reversal=reversal; this.originalLine=original; this.lineNo=original.getLineNo();
        this.insumo=original.getInsumo(); this.familia=original.getFamilia(); this.unidadBaseCodigo=original.getUnidadBaseCodigo();
        this.cantidadBaseCompensada=ReceiptNumbers.quantity(original.getCantidadBaseEfectiva());
        this.operationId=Objects.requireNonNull(operationId);
    }
    public Long getId() { return id; }
    public InventoryReceiptLine getOriginalLine() { return originalLine; }
    public int getLineNo() { return lineNo; }
    public Insumo getInsumo() { return insumo; }
    public UnitFamily getFamilia() { return familia; }
    public String getUnidadBaseCodigo() { return unidadBaseCodigo; }
    public BigDecimal getCantidadBaseCompensada() { return cantidadBaseCompensada; }
    public UUID getOperationId() { return operationId; }
}
