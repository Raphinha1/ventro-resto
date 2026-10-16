package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

@Entity
@Table(name = "sub_receta")
public class SubReceta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // El insumo que se va a producir (Ej: Salsa de Tomate)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insumo_destino_id", nullable = false)
    private Insumo insumoDestino;

    // El ingrediente requerido para producirlo (Ej: Tomate crudo)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingrediente_id", nullable = false)
    private Insumo ingrediente;

    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor a cero")
    @Column(name = "cantidad", nullable = false, precision = 10, scale = 4)
    private BigDecimal cantidad;

    public SubReceta() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Insumo getInsumoDestino() { return insumoDestino; }
    public void setInsumoDestino(Insumo insumoDestino) { this.insumoDestino = insumoDestino; }
    public Insumo getIngrediente() { return ingrediente; }
    public void setIngrediente(Insumo ingrediente) { this.ingrediente = ingrediente; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
}
