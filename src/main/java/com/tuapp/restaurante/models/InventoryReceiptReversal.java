package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Compensación inmutable; no cambia el hecho original. */
@Entity
@Table(name="inventory_receipt_reversal")
public class InventoryReceiptReversal {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="receipt_id",nullable=false) private InventoryReceipt receipt;
    @Column(name="request_id",nullable=false) private UUID requestId;
    @Column(name="command_fingerprint",nullable=false,length=64) private String commandFingerprint;
    @Column(name="fingerprint_version",nullable=false,length=50) private String fingerprintVersion;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="actor_usuario_id",nullable=false) private Usuario actor;
    @Column(nullable=false,length=1000) private String motivo;
    @Column(nullable=false,length=10) private String estado="CONFIRMADO";
    @Column(name="registrado_en",nullable=false,insertable=false,updatable=false) private OffsetDateTime registradoEn;
    @OneToMany(mappedBy="reversal",cascade=CascadeType.PERSIST) @OrderBy("lineNo ASC")
    private List<InventoryReceiptReversalLine> lines=new ArrayList<>();

    protected InventoryReceiptReversal() {}
    public InventoryReceiptReversal(InventoryReceipt receipt,UUID requestId,String hash,String version,Usuario actor,String motivo) {
        this.receipt=Objects.requireNonNull(receipt);
        this.requestId=Objects.requireNonNull(requestId);
        if (hash==null || !hash.matches("[0-9a-f]{64}") || version==null || version.isBlank()) {
            throw new IllegalArgumentException("Huella de reverso inválida");
        }
        if (motivo==null || motivo.isBlank() || motivo.length()>1000) throw new IllegalArgumentException("Motivo inválido");
        this.commandFingerprint=hash; this.fingerprintVersion=version;
        this.actor=Objects.requireNonNull(actor); this.motivo=motivo;
    }
    public void addLine(InventoryReceiptLine original,UUID operationId) {
        if (id!=null) throw new IllegalStateException("Reverso persistido inmutable");
        if (!receipt.getId().equals(original.getReceipt().getId()) ||
                lines.stream().anyMatch(l -> l.getLineNo()==original.getLineNo())) {
            throw new IllegalArgumentException("Línea original ajena o duplicada");
        }
        lines.add(new InventoryReceiptReversalLine(this,original,operationId));
    }
    public Long getId() { return id; }
    public InventoryReceipt getReceipt() { return receipt; }
    public UUID getRequestId() { return requestId; }
    public String getCommandFingerprint() { return commandFingerprint; }
    public String getFingerprintVersion() { return fingerprintVersion; }
    public Usuario getActor() { return actor; }
    public String getMotivo() { return motivo; }
    public String getEstado() { return estado; }
    public OffsetDateTime getRegistradoEn() { return registradoEn; }
    public List<InventoryReceiptReversalLine> getLines() { return List.copyOf(lines); }
}
