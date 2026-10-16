package com.tuapp.restaurante.models;

import com.tuapp.restaurante.inventory.Inventory5AStatus;
import com.tuapp.restaurante.inventory.UnitFamily;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

@Entity
@Table(name = "insumo")
public class Insumo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre del insumo es obligatorio")
    @Column(name = "nombre", nullable = false, length = 150)
    private String nombre;

    @NotNull(message = "El stock actual es obligatorio")
    @Min(value = 0, message = "El stock no puede ser negativo")
    @Column(name = "stock_actual", nullable = false, precision = 19, scale = 6)
    private BigDecimal stockActual = BigDecimal.ZERO;

    @NotNull(message = "El stock mínimo es obligatorio")
    @Min(value = 0, message = "El stock mínimo no puede ser negativo")
    @Column(name = "stock_minimo", nullable = false, precision = 10, scale = 2)
    private BigDecimal stockMinimo = BigDecimal.ZERO;

    @NotBlank(message = "La unidad de medida es obligatoria")
    @Column(name = "unidad_medida", nullable = false, length = 50)
    private String unidadMedida; // Ej: Kg, Litros, Piezas

    // Campos de expansión 5A: no interpretan ni reemplazan unidadMedida/stockActual.
    @Enumerated(EnumType.STRING)
    @Column(name = "unidad_base_familia", length = 8)
    private UnitFamily unidadBaseFamilia;

    @Column(name = "unidad_base_codigo", length = 8)
    private String unidadBaseCodigo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_inventario_5a", nullable = false, length = 16)
    private Inventory5AStatus estadoInventario5a = Inventory5AStatus.PENDIENTE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reconciliacion_actual_id")
    private InventarioReconciliacion reconciliacionActual;

    @NotNull(message = "El costo unitario es obligatorio")
    @Min(value = 0, message = "El costo no puede ser negativo")
    @Column(name = "costo_unitario", nullable = false, precision = 10, scale = 2)
    private BigDecimal costoUnitario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @Column(name = "activo", nullable = false, columnDefinition = "BOOLEAN DEFAULT true")
    private boolean activo = true;

    @OneToMany(mappedBy = "insumoDestino", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<SubReceta> ingredientesPreparacion = new java.util.ArrayList<>();

    public Insumo() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public BigDecimal getStockActual() { return stockActual; }
    public void setStockActual(BigDecimal stockActual) { this.stockActual = stockActual; }
    public BigDecimal getStockMinimo() { return stockMinimo; }
    public void setStockMinimo(BigDecimal stockMinimo) { this.stockMinimo = stockMinimo; }
    public String getUnidadMedida() { return unidadMedida; }
    public void setUnidadMedida(String unidadMedida) { this.unidadMedida = unidadMedida; }
    public UnitFamily getUnidadBaseFamilia() { return unidadBaseFamilia; }
    public String getUnidadBaseCodigo() { return unidadBaseCodigo; }
    public Inventory5AStatus getEstadoInventario5a() { return estadoInventario5a; }
    public InventarioReconciliacion getReconciliacionActual() { return reconciliacionActual; }
    public BigDecimal getCostoUnitario() { return costoUnitario; }
    public void setCostoUnitario(BigDecimal costoUnitario) { this.costoUnitario = costoUnitario; }
    public Categoria getCategoria() { return categoria; }
    public void setCategoria(Categoria categoria) { this.categoria = categoria; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public java.util.List<SubReceta> getIngredientesPreparacion() { return ingredientesPreparacion; }
    public void setIngredientesPreparacion(java.util.List<SubReceta> ingredientesPreparacion) { this.ingredientesPreparacion = ingredientesPreparacion; }
}
