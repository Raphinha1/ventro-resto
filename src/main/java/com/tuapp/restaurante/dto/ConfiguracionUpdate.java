package com.tuapp.restaurante.dto;

import java.math.BigDecimal;

/** Explicit editable fields. Null means omitted; existing settings are preserved. */
public class ConfiguracionUpdate {
    private String nombreNegocio;
    private String slogan;
    private String rfc;
    private String telefono;
    private String direccion;
    private String moneda;
    private String simboloMoneda;
    private BigDecimal porcentajeIva;
    private Boolean ivaIncluido;
    private Boolean permitirDescuentos;
    private String mensajeTicket;
    private String nivelPlan;
    private Boolean modMesas;
    private Boolean modReservas;
    private Boolean modKds;
    private Boolean modClientes;
    private Boolean modCaja;
    private Boolean modHistorial;
    private Boolean modReportes;
    private Boolean modGastos;
    private Boolean modAsistencia;
    private Boolean modIa;
    private String fechaExpiracion;
    private Integer limiteUsuarios;
    private Integer limiteProductos;
    private Integer limiteDispositivos;
    private BigDecimal precioPersonalizado;
    private String notasMaestras;

    public String getNombreNegocio() { return nombreNegocio; }
    public void setNombreNegocio(String value) { nombreNegocio = value; }
    public String getSlogan() { return slogan; }
    public void setSlogan(String value) { slogan = value; }
    public String getRfc() { return rfc; }
    public void setRfc(String value) { rfc = value; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String value) { telefono = value; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String value) { direccion = value; }
    public String getMoneda() { return moneda; }
    public void setMoneda(String value) { moneda = value; }
    public String getSimboloMoneda() { return simboloMoneda; }
    public void setSimboloMoneda(String value) { simboloMoneda = value; }
    public BigDecimal getPorcentajeIva() { return porcentajeIva; }
    public void setPorcentajeIva(BigDecimal value) { porcentajeIva = value; }
    public Boolean getIvaIncluido() { return ivaIncluido; }
    public void setIvaIncluido(Boolean value) { ivaIncluido = value; }
    public Boolean getPermitirDescuentos() { return permitirDescuentos; }
    public void setPermitirDescuentos(Boolean value) { permitirDescuentos = value; }
    public String getMensajeTicket() { return mensajeTicket; }
    public void setMensajeTicket(String value) { mensajeTicket = value; }
    public String getNivelPlan() { return nivelPlan; }
    public void setNivelPlan(String value) { nivelPlan = value; }
    public Boolean getModMesas() { return modMesas; }
    public void setModMesas(Boolean value) { modMesas = value; }
    public Boolean getModReservas() { return modReservas; }
    public void setModReservas(Boolean value) { modReservas = value; }
    public Boolean getModKds() { return modKds; }
    public void setModKds(Boolean value) { modKds = value; }
    public Boolean getModClientes() { return modClientes; }
    public void setModClientes(Boolean value) { modClientes = value; }
    public Boolean getModCaja() { return modCaja; }
    public void setModCaja(Boolean value) { modCaja = value; }
    public Boolean getModHistorial() { return modHistorial; }
    public void setModHistorial(Boolean value) { modHistorial = value; }
    public Boolean getModReportes() { return modReportes; }
    public void setModReportes(Boolean value) { modReportes = value; }
    public Boolean getModGastos() { return modGastos; }
    public void setModGastos(Boolean value) { modGastos = value; }
    public Boolean getModAsistencia() { return modAsistencia; }
    public void setModAsistencia(Boolean value) { modAsistencia = value; }
    public Boolean getModIa() { return modIa; }
    public void setModIa(Boolean value) { modIa = value; }
    public String getFechaExpiracion() { return fechaExpiracion; }
    public void setFechaExpiracion(String value) { fechaExpiracion = value; }
    public Integer getLimiteUsuarios() { return limiteUsuarios; }
    public void setLimiteUsuarios(Integer value) { limiteUsuarios = value; }
    public Integer getLimiteProductos() { return limiteProductos; }
    public void setLimiteProductos(Integer value) { limiteProductos = value; }
    public Integer getLimiteDispositivos() { return limiteDispositivos; }
    public void setLimiteDispositivos(Integer value) { limiteDispositivos = value; }
    public BigDecimal getPrecioPersonalizado() { return precioPersonalizado; }
    public void setPrecioPersonalizado(BigDecimal value) { precioPersonalizado = value; }
    public String getNotasMaestras() { return notasMaestras; }
    public void setNotasMaestras(String value) { notasMaestras = value; }

    public boolean hasMasterFields() {
        return nivelPlan != null
                || modMesas != null
                || modReservas != null
                || modKds != null
                || modClientes != null
                || modCaja != null
                || modHistorial != null
                || modReportes != null
                || modGastos != null
                || modAsistencia != null
                || modIa != null
                || fechaExpiracion != null
                || limiteUsuarios != null
                || limiteProductos != null
                || limiteDispositivos != null
                || precioPersonalizado != null
                || notasMaestras != null;
    }
}
