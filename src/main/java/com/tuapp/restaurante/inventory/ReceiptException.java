package com.tuapp.restaurante.inventory;

/** Rechazos esperados y sin efectos; no contiene errores técnicos de persistencia. */
public class ReceiptException extends IllegalArgumentException {
    public enum Kind { CONFLICTO_REQUEST_ID, NO_SELECCIONABLE, MONEDA_NO_CONFIGURADA }
    private final Kind kind;
    public ReceiptException(Kind kind, String message) { super(message); this.kind=kind; }
    public Kind kind() { return kind; }
}
