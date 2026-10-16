package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Cliente;
import com.tuapp.restaurante.repositories.ClienteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ClienteBalanceService {
    private final ClienteRepository clientes;

    public ClienteBalanceService(ClienteRepository clientes) {
        this.clientes = clientes;
    }

    @Transactional
    public void guardar(Cliente input) {
        if (input.getNombre() == null || input.getNombre().isBlank())
            throw new IllegalArgumentException("Nombre obligatorio.");
        if (!"NORMAL".equals(input.getTipo()) && !"VIP".equals(input.getTipo()))
            throw new IllegalArgumentException("Tipo de cliente inválido.");
        BigDecimal limite = nonnegativeMoney(input.getLimiteCredito() == null ? BigDecimal.ZERO : input.getLimiteCredito());
        if ("NORMAL".equals(input.getTipo())) limite = BigDecimal.ZERO;
        Cliente cliente;
        if (input.getId() == null) {
            cliente = input;
            if (cliente.getPuntos() == null || cliente.getPuntos() < 0)
                throw new IllegalArgumentException("Puntos iniciales inválidos.");
            cliente.setSaldoCredito(BigDecimal.ZERO);
            cliente.setTotalGastado(BigDecimal.ZERO);
        } else {
            cliente = clientes.lockById(input.getId()).orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado."));
            if (cliente.getSaldoCredito().compareTo(limite) > 0)
                throw new IllegalArgumentException("El límite no puede ser menor a la deuda actual.");
            cliente.setNombre(input.getNombre());
            cliente.setEmail(input.getEmail());
            cliente.setTelefono(input.getTelefono());
            cliente.setDireccion(input.getDireccion());
            cliente.setReferencias(input.getReferencias());
            cliente.setMapaUrl(input.getMapaUrl());
            cliente.setTipo(input.getTipo());
        }
        cliente.setLimiteCredito(limite);
        clientes.save(cliente);
    }

    @Transactional
    public void ajustarPuntos(Long id, int delta) {
        Cliente c = active(id);
        int result;
        try { result = Math.addExact(c.getPuntos(), delta); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Puntos fuera de rango."); }
        if (result < 0) throw new IllegalArgumentException("Puntos insuficientes.");
        c.setPuntos(result);
    }

    @Transactional
    public BigDecimal consumirCredito(Long id, BigDecimal amount) {
        Cliente c = active(id);
        BigDecimal value = positiveMoney(amount);
        if (!"VIP".equals(c.getTipo())) throw new IllegalArgumentException("El cliente no tiene crédito VIP.");
        BigDecimal balance = c.getSaldoCredito().add(value);
        if (balance.compareTo(c.getLimiteCredito()) > 0) throw new IllegalArgumentException("Crédito insuficiente.");
        c.setSaldoCredito(balance);
        c.setTotalGastado(c.getTotalGastado().add(value));
        return balance;
    }

    @Transactional
    public BigDecimal abonarCredito(Long id, BigDecimal amount) {
        Cliente c = active(id);
        BigDecimal value = positiveMoney(amount);
        if (value.compareTo(c.getSaldoCredito()) > 0) throw new IllegalArgumentException("El abono excede la deuda.");
        c.setSaldoCredito(c.getSaldoCredito().subtract(value));
        return c.getSaldoCredito();
    }

    private Cliente active(Long id) {
        return clientes.lockById(id).filter(Cliente::isActivo)
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado o inactivo."));
    }

    private BigDecimal positiveMoney(BigDecimal value) {
        BigDecimal amount = nonnegativeMoney(value);
        if (amount.signum() == 0) throw new IllegalArgumentException("El importe debe ser positivo.");
        return amount;
    }

    private BigDecimal nonnegativeMoney(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new IllegalArgumentException("Importe inválido.");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Importe con más de dos decimales."); }
    }
}
