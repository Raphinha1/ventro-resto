package com.tuapp.restaurante.inventory;

public enum BaseUnit {
    GRAM(UnitFamily.MASS, "g"),
    MILLILITER(UnitFamily.VOLUME, "ml"),
    UNIT(UnitFamily.COUNT, "unidad");

    private final UnitFamily family;
    private final String symbol;

    BaseUnit(UnitFamily family, String symbol) {
        this.family = family;
        this.symbol = symbol;
    }

    public UnitFamily family() {
        return family;
    }

    public String symbol() {
        return symbol;
    }

    public static BaseUnit require(UnitFamily family, String symbol) {
        for (BaseUnit unit : values()) {
            if (unit.family == family && unit.symbol.equals(symbol)) return unit;
        }
        throw new IllegalStateException("Unidad base del insumo inválida");
    }
}
