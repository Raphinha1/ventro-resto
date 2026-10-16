package com.tuapp.restaurante.inventory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryWritePolicyTest {
    @Test
    void routesEachKnownState() {
        assertThat(InventoryWritePolicy.resolve(Inventory5AStatus.PENDIENTE))
                .isEqualTo(InventoryWritePolicy.Mode.LEGACY);
        assertThat(InventoryWritePolicy.resolve(Inventory5AStatus.RECONCILIADO))
                .isEqualTo(InventoryWritePolicy.Mode.LEGACY);
        assertThat(InventoryWritePolicy.resolve(Inventory5AStatus.ACTIVO_5A))
                .isEqualTo(InventoryWritePolicy.Mode.APPLIER);
        InventoryWritePolicy.requireApplier(Inventory5AStatus.ACTIVO_5A);
        InventoryWritePolicy.requireLegacy(Inventory5AStatus.PENDIENTE);
        InventoryWritePolicy.requireLegacy(Inventory5AStatus.RECONCILIADO);
    }

    @Test
    void nullAndLegacyModesCannotEnterApplier() {
        assertThatThrownBy(() -> InventoryWritePolicy.resolve(null))
                .hasMessageContaining("Estado de inventario obligatorio");
        assertThatThrownBy(() -> InventoryWritePolicy.requireApplier(Inventory5AStatus.PENDIENTE))
                .hasMessageContaining("LEGACY");
        assertThatThrownBy(() -> InventoryWritePolicy.requireApplier(Inventory5AStatus.RECONCILIADO))
                .hasMessageContaining("LEGACY");
        assertThatThrownBy(() -> InventoryWritePolicy.requireLegacy(Inventory5AStatus.ACTIVO_5A))
                .hasMessageContaining("aplicador");
        assertThatThrownBy(() -> InventoryWritePolicy.requireLegacy(null))
                .hasMessageContaining("Estado de inventario obligatorio");
    }
}
