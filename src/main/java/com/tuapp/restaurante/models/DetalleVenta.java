package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "venta_detalles")
public class DetalleVenta {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "venta_id", nullable = false)
    private Venta venta;

    @ManyToOne
    @JoinColumn(name = "producto_id", nullable = false)
    private Producto producto;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(name = "precio_unidad", nullable = false)
    private BigDecimal precioUnidad;

    @Column(name = "subtotal", nullable = false)
    private BigDecimal subtotal;

    @Column(length = 255)
    private String notas;

    @Column(length = 50, nullable = false)
    private String estado = "PENDIENTE"; // PENDIENTE, PREPARANDO, LISTO, ENTREGADO

    @Column(name = "canjeado", nullable = false)
    private boolean canjeado = false;

    public DetalleVenta() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Venta getVenta() { return venta; }
    public void setVenta(Venta venta) { this.venta = venta; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public BigDecimal getPrecioUnidad() { return precioUnidad; }
    public void setPrecioUnidad(BigDecimal precioUnidad) { this.precioUnidad = precioUnidad; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public String getNotas() { return notas; }
    public void setNotas(String notas) { this.notas = notas; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public boolean isCanjeado() { return canjeado; }
    public void setCanjeado(boolean canjeado) { this.canjeado = canjeado; }
}
