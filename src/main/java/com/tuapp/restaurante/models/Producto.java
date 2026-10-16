package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

@Entity
@Table(name = "producto")
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre del producto es obligatorio")
    @Column(name = "nombre", nullable = false, length = 150)
    private String nombre;

    @NotNull(message = "El precio de venta es obligatorio")
    @Min(value = 0, message = "El precio no puede ser negativo")
    @Column(name = "precio_venta", nullable = false, precision = 10, scale = 2)
    private BigDecimal precioVenta;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @OneToMany(mappedBy = "producto", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<Receta> ingredientes;

    @Column(name = "activo", nullable = false, columnDefinition = "BOOLEAN DEFAULT true")
    private boolean activo = true;

    @Column(name = "imagen_url", length = 255)
    private String imagenUrl;

    @Column(name = "puntos_otorgados", nullable = false)
    private Integer puntosOtorgados = 0;

    @Column(name = "puntos_precio", nullable = false)
    private Integer puntosPrecio = 0;

    /** Si es true, este producto descuenta directamente un insumo (sin receta) */
    @Column(name = "es_reventa", nullable = false, columnDefinition = "BOOLEAN DEFAULT false")
    private boolean esReventa = false;

    /**
     * Insumo que se descuenta al vender este producto de reventa.
     * Cantidad descontada = 1 unidad por cada producto vendido.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insumo_reventa_id")
    private Insumo insumoReventa;

    public Producto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public BigDecimal getPrecioVenta() { return precioVenta; }
    public void setPrecioVenta(BigDecimal precioVenta) { this.precioVenta = precioVenta; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public Categoria getCategoria() { return categoria; }
    public void setCategoria(Categoria categoria) { this.categoria = categoria; }
    public java.util.List<Receta> getIngredientes() { return ingredientes; }
    public void setIngredientes(java.util.List<Receta> ingredientes) { this.ingredientes = ingredientes; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public String getImagenUrl() { return imagenUrl; }
    public void setImagenUrl(String imagenUrl) { this.imagenUrl = imagenUrl; }
    public Integer getPuntosOtorgados() { return puntosOtorgados; }
    public void setPuntosOtorgados(Integer puntosOtorgados) { this.puntosOtorgados = puntosOtorgados; }
    public Integer getPuntosPrecio() { return puntosPrecio; }
    public void setPuntosPrecio(Integer puntosPrecio) { this.puntosPrecio = puntosPrecio; }
    public boolean isEsReventa() { return esReventa; }
    public void setEsReventa(boolean esReventa) { this.esReventa = esReventa; }
    public Insumo getInsumoReventa() { return insumoReventa; }
    public void setInsumoReventa(Insumo insumoReventa) { this.insumoReventa = insumoReventa; }
}
