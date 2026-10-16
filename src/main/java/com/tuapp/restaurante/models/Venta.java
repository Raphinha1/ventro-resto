package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "ventas")
public class Venta {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(name = "metodo_pago", nullable = true)
    private String metodoPago = "EFECTIVO"; // EFECTIVO, TARJETA, MIXTO

    @Column(name = "monto_efectivo", precision = 10, scale = 2)
    private BigDecimal montoEfectivo = BigDecimal.ZERO;

    @Column(name = "monto_tarjeta", precision = 10, scale = 2)
    private BigDecimal montoTarjeta = BigDecimal.ZERO;

    @Column(name = "cambio", precision = 10, scale = 2)
    private BigDecimal cambio = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mesa_id")
    private Mesa mesa;

    @ManyToOne
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corte_caja_id")
    private CorteCaja corteCaja;

    @Column(name = "puntos_ganados")
    private Integer puntosGanados = 0;

    @Column(name = "puntos_canjeados")
    private Integer puntosCanjeados = 0;

    @OneToMany(mappedBy = "venta", cascade = CascadeType.ALL)
    private List<DetalleVenta> detalles;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getMetodoPago() { return metodoPago; }
    public void setMetodoPago(String metodoPago) { this.metodoPago = metodoPago; }
    public BigDecimal getMontoEfectivo() { return montoEfectivo; }
    public void setMontoEfectivo(BigDecimal montoEfectivo) { this.montoEfectivo = montoEfectivo; }
    public BigDecimal getMontoTarjeta() { return montoTarjeta; }
    public void setMontoTarjeta(BigDecimal montoTarjeta) { this.montoTarjeta = montoTarjeta; }
    public BigDecimal getCambio() { return cambio; }
    public void setCambio(BigDecimal cambio) { this.cambio = cambio; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public Mesa getMesa() { return mesa; }
    public void setMesa(Mesa mesa) { this.mesa = mesa; }
    public Cliente getCliente() { return cliente; }
    public void setCliente(Cliente cliente) { this.cliente = cliente; }
    public CorteCaja getCorteCaja() { return corteCaja; }
    public void setCorteCaja(CorteCaja corteCaja) { this.corteCaja = corteCaja; }
    public Integer getPuntosGanados() { return puntosGanados; }
    public void setPuntosGanados(Integer puntosGanados) { this.puntosGanados = puntosGanados; }
    public Integer getPuntosCanjeados() { return puntosCanjeados; }
    public void setPuntosCanjeados(Integer puntosCanjeados) { this.puntosCanjeados = puntosCanjeados; }
    public List<DetalleVenta> getDetalles() { return detalles; }
    public void setDetalles(List<DetalleVenta> detalles) { this.detalles = detalles; }
}
