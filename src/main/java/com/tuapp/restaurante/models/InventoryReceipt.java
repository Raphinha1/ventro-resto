package com.tuapp.restaurante.models;

import com.tuapp.restaurante.inventory.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Agregado persistente; no recibe mercancía operativamente ni modifica stock. */
@Entity
@Table(name = "inventory_receipt")
public class InventoryReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "request_id", nullable = false)
    private UUID requestId;
    @Column(name = "command_fingerprint", nullable = false, length = 64)
    private String commandFingerprint;
    @Column(name = "fingerprint_version", nullable = false, length = 50)
    private String fingerprintVersion;
    @Column(nullable = false, length = 10)
    private String estado = "CONFIRMADA";
    @Column(nullable = false, length = 3)
    private String moneda;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_usuario_id", nullable = false)
    private Usuario actor;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;
    @Column(name = "documento_externo", length = 200)
    private String documentoExterno;
    @Column(name = "registrado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime registradoEn;
    @OneToMany(mappedBy = "receipt", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo ASC")
    private List<InventoryReceiptLine> lines = new ArrayList<>();

    protected InventoryReceipt() {}

    public InventoryReceipt(UUID requestId, String commandFingerprint, String fingerprintVersion,
                            String moneda, Usuario actor, Proveedor proveedor, String documentoExterno) {
        this.requestId = Objects.requireNonNull(requestId, "requestId obligatorio");
        if (commandFingerprint == null || !commandFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fingerprint SHA-256 obligatorio");
        }
        if (fingerprintVersion == null || fingerprintVersion.isBlank()) {
            throw new IllegalArgumentException("Versión de huella obligatoria");
        }
        if (moneda == null || !moneda.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Código de moneda obligatorio");
        }
        this.commandFingerprint = commandFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.moneda = moneda;
        this.actor = Objects.requireNonNull(actor, "Actor obligatorio");
        this.proveedor = proveedor;
        this.documentoExterno = documentoExterno;
    }

    public void addLine(int lineNo, InventoryPurchasePresentation presentation, BigDecimal purchased,
                        BigDecimal receivedBase, BigDecimal cost, UUID operationId) {
        if (id != null) throw new IllegalStateException("Recepción persistida inmutable");
        if (lines.stream().anyMatch(line -> line.getLineNo() == lineNo)) {
            throw new IllegalArgumentException("lineNo duplicado");
        }
        lines.add(new InventoryReceiptLine(this, lineNo, presentation, purchased, receivedBase, cost, operationId));
    }

    public List<InventoryReceiptLine> getLines() { return List.copyOf(lines); }
    public Long getId() { return id; }
    public java.util.UUID getRequestId() { return requestId; }
    public String getCommandFingerprint() { return commandFingerprint; }
    public String getFingerprintVersion() { return fingerprintVersion; }
    public String getEstado() { return estado; }
    public String getMoneda() { return moneda; }
    public Usuario getActor() { return actor; }
    public Proveedor getProveedor() { return proveedor; }
    public String getDocumentoExterno() { return documentoExterno; }
    public OffsetDateTime getRegistradoEn() { return registradoEn; }
}
