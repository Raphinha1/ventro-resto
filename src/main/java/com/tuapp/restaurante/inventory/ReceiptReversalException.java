package com.tuapp.restaurante.inventory;

/** Rechazo de dominio, sin aceptación parcial de un nuevo reverso. */
public class ReceiptReversalException extends IllegalArgumentException {
    public enum Kind { CONFLICTO_REQUEST_ID, YA_REVERTIDA, NO_SELECCIONABLE, SALDO_INSUFICIENTE, INTEGRIDAD_CAUSAL }
    private final Kind kind;
    public ReceiptReversalException(Kind kind,String message) { super(message); this.kind=kind; }
    public Kind kind() { return kind; }
}
