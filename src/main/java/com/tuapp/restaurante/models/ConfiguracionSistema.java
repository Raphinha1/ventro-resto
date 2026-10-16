package com.tuapp.restaurante.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "configuracion_sistema")
public class ConfiguracionSistema {

    @Id
    private Long id = 1L;

    @Column(name = "nombre_negocio")
    private String nombreNegocio = "Ventro Resto";
    private String slogan = "Sistema de Gestión";
    private String telefono;
    private String email;

    @Column(length = 500)
    private String direccion;

    private String rfc;
    private String moneda = "MXN";
    @Column(name = "simbolo_moneda")
    private String simboloMoneda = "$";

    @Column(name = "porcentaje_iva", precision = 5, scale = 2)
    private BigDecimal porcentajeIva = BigDecimal.ZERO;

    @Column(name = "iva_incluido")
    private Boolean ivaIncluido = false;

    @Column(name = "permitir_descuentos")
    private Boolean permitirDescuentos = true;

    @Column(name = "moneda_fecha")
    private String monedaFecha = "es-MX";

    @Column(name = "zona_horaria")
    private String zonaHoraria = "America/Mexico_City";

    @Column(name = "mensaje_ticket", length = 1000)
    private String mensajeTicket = "¡Gracias por su preferencia!";

    @Column(name = "nivel_plan", length = 20, nullable = false)
    private String nivelPlan = "PREMIUM"; // LITE, PRO, PREMIUM, CUSTOM

    // ── Módulos "A la Carta" (solo aplican si nivelPlan = CUSTOM) ──
    @Column(name = "mod_mesas", nullable = false) private Boolean modMesas        = false;
    @Column(name = "mod_reservas", nullable = false) private Boolean modReservas     = false;
    @Column(name = "mod_kds", nullable = false) private Boolean modKds          = false;
    @Column(name = "mod_clientes", nullable = false) private Boolean modClientes     = false;
    @Column(name = "mod_caja", nullable = false) private Boolean modCaja         = false;
    @Column(name = "mod_historial", nullable = false) private Boolean modHistorial    = false;
    @Column(name = "mod_reportes", nullable = false) private Boolean modReportes     = false;
    @Column(name = "mod_gastos", nullable = false) private Boolean modGastos       = false;
    @Column(name = "mod_asistencia", nullable = false) private Boolean modAsistencia   = false;
    @Column(name = "mod_ia", nullable = false) private Boolean modIa           = false;
    
    // ── Campos Maestros (Solo Super Admin) ──
    @Column(name = "fecha_expiracion")
    private String fechaExpiracion;
    
    @Column(name = "limite_usuarios")
    private Integer limiteUsuarios = 5;
    
    @Column(name = "limite_productos")
    private Integer limiteProductos = 100;
    
    @Column(name = "limite_dispositivos")
    private Integer limiteDispositivos = 1;
    
    @Column(name = "precio_personalizado")
    private BigDecimal precioPersonalizado = BigDecimal.ZERO;
    
    @Column(name = "notas_maestras", length = 2000)
    private String notasMaestras;

    public ConfiguracionSistema() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNombreNegocio() { return nombreNegocio; }
    public void setNombreNegocio(String nombreNegocio) { this.nombreNegocio = nombreNegocio; }

    public String getSlogan() { return slogan; }
    public void setSlogan(String slogan) { this.slogan = slogan; }

    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }

    public String getRfc() { return rfc; }
    public void setRfc(String rfc) { this.rfc = rfc; }

    public String getMoneda() { return moneda; }
    public void setMoneda(String moneda) { this.moneda = moneda; }

    public String getSimboloMoneda() { return simboloMoneda; }
    public void setSimboloMoneda(String simboloMoneda) { this.simboloMoneda = simboloMoneda; }

    public BigDecimal getPorcentajeIva() { return porcentajeIva; }
    public void setPorcentajeIva(BigDecimal porcentajeIva) { this.porcentajeIva = porcentajeIva; }

    public Boolean getIvaIncluido() { return ivaIncluido; }
    public void setIvaIncluido(Boolean ivaIncluido) { this.ivaIncluido = ivaIncluido; }

    public Boolean getPermitirDescuentos() { return permitirDescuentos; }
    public void setPermitirDescuentos(Boolean permitirDescuentos) { this.permitirDescuentos = permitirDescuentos; }

    public String getMonedaFecha() { return monedaFecha; }
    public void setMonedaFecha(String monedaFecha) { this.monedaFecha = monedaFecha; }

    public String getZonaHoraria() { return zonaHoraria; }
    public void setZonaHoraria(String zonaHoraria) { this.zonaHoraria = zonaHoraria; }

    public String getMensajeTicket() { return mensajeTicket; }
    public void setMensajeTicket(String mensajeTicket) { this.mensajeTicket = mensajeTicket; }

    public String getNivelPlan() { return nivelPlan; }
    public void setNivelPlan(String nivelPlan) { this.nivelPlan = nivelPlan; }

    public Boolean getModMesas()      { return modMesas; }
    public void setModMesas(Boolean v)      { this.modMesas = v; }

    public Boolean getModReservas()   { return modReservas; }
    public void setModReservas(Boolean v)   { this.modReservas = v; }

    public Boolean getModKds()        { return modKds; }
    public void setModKds(Boolean v)        { this.modKds = v; }

    public Boolean getModClientes()   { return modClientes; }
    public void setModClientes(Boolean v)   { this.modClientes = v; }

    public Boolean getModCaja()       { return modCaja; }
    public void setModCaja(Boolean v)       { this.modCaja = v; }

    public Boolean getModHistorial()  { return modHistorial; }
    public void setModHistorial(Boolean v)  { this.modHistorial = v; }

    public Boolean getModReportes()   { return modReportes; }
    public void setModReportes(Boolean v)   { this.modReportes = v; }

    public Boolean getModGastos()     { return modGastos; }
    public void setModGastos(Boolean v)     { this.modGastos = v; }

    public Boolean getModAsistencia() { return modAsistencia; }
    public void setModAsistencia(Boolean v) { this.modAsistencia = v; }

    public Boolean getModIa()         { return modIa; }
    public void setModIa(Boolean v)         { this.modIa = v; }

    public String getFechaExpiracion() { return fechaExpiracion; }
    public void setFechaExpiracion(String fechaExpiracion) { this.fechaExpiracion = fechaExpiracion; }

    public Integer getLimiteUsuarios() { return limiteUsuarios; }
    public void setLimiteUsuarios(Integer limiteUsuarios) { this.limiteUsuarios = limiteUsuarios; }

    public Integer getLimiteProductos() { return limiteProductos; }
    public void setLimiteProductos(Integer limiteProductos) { this.limiteProductos = limiteProductos; }

    public Integer getLimiteDispositivos() { return limiteDispositivos; }
    public void setLimiteDispositivos(Integer limiteDispositivos) { this.limiteDispositivos = limiteDispositivos; }

    public BigDecimal getPrecioPersonalizado() { return precioPersonalizado; }
    public void setPrecioPersonalizado(BigDecimal precioPersonalizado) { this.precioPersonalizado = precioPersonalizado; }

    public String getNotasMaestras() { return notasMaestras; }
    public void setNotasMaestras(String notasMaestras) { this.notasMaestras = notasMaestras; }
}
