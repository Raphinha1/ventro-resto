package com.tuapp.restaurante.inventory;

/** Frontera de escritura durante la transición 5A; no activa insumos. */
public final class InventoryWritePolicy {
    public enum Mode { LEGACY, APPLIER }

    private InventoryWritePolicy() {}

    public static Mode resolve(Inventory5AStatus status) {
        if (status == null) throw new IllegalArgumentException("Estado de inventario obligatorio");
        return switch (status) {
            case PENDIENTE, RECONCILIADO -> Mode.LEGACY;
            case ACTIVO_5A -> Mode.APPLIER;
        };
    }

    public static void requireApplier(Inventory5AStatus status) {
        if (resolve(status) != Mode.APPLIER) {
            throw new IllegalStateException("El insumo todavía usa escritura LEGACY; aplicador no disponible");
        }
    }

    public static void requireLegacy(Inventory5AStatus status) {
        if (resolve(status) != Mode.LEGACY) {
            throw new IllegalStateException("El insumo ACTIVO_5A solo admite escritura mediante el aplicador");
        }
    }
}
