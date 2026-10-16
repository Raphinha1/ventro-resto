package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Cliente;
import com.tuapp.restaurante.repositories.ClienteRepository;
import com.tuapp.restaurante.services.ClienteBalanceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequestMapping("/clientes")
public class ClienteController {


    public ClienteController(ClienteRepository clienteRepository, ClienteBalanceService balances) {
        this.clienteRepository = clienteRepository;
        this.balances = balances;
    }

    private final ClienteRepository clienteRepository;
    private final ClienteBalanceService balances;

    // ─── LISTAR ─────────────────────────────────────────────────────────────────
    @GetMapping
    public String listar(Model model) {
        List<Cliente> todos = clienteRepository.findByActivoTrue();
        long totalVip = todos.stream().filter(c -> "VIP".equals(c.getTipo())).count();
        long conDeuda = clienteRepository.findClientesConDeuda().size();
        int totalPuntos = todos.stream().mapToInt(Cliente::getPuntos).sum();

        model.addAttribute("clientes", todos);
        model.addAttribute("totalVip", totalVip);
        model.addAttribute("conDeuda", conDeuda);
        model.addAttribute("totalPuntos", totalPuntos);
        return "clientes/lista";
    }

    // ─── FORMULARIO NUEVO ────────────────────────────────────────────────────────
    @GetMapping("/nuevo")
    public String formularioNuevo(Model model) {
        model.addAttribute("cliente", new Cliente());
        model.addAttribute("titulo", "Nuevo Cliente");
        return "clientes/formulario";
    }

    // ─── FORMULARIO EDITAR ───────────────────────────────────────────────────────
    @GetMapping("/editar/{id}")
    public String formularioEditar(@PathVariable Long id, Model model) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado"));
        model.addAttribute("cliente", cliente);
        model.addAttribute("titulo", "Editar Cliente");
        return "clientes/formulario";
    }

    // ─── GUARDAR / ACTUALIZAR ────────────────────────────────────────────────────
    @PostMapping("/guardar")
    public String guardar(@ModelAttribute Cliente cliente, RedirectAttributes flash) {
        try {
            balances.guardar(cliente);
            flash.addFlashAttribute("mensajeExito", "Cliente guardado correctamente.");
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al guardar: " + e.getMessage());
        }
        return "redirect:/clientes";
    }

    // ─── AGREGAR PUNTOS ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/puntos")
    public String agregarPuntos(@PathVariable Long id,
                                @RequestParam("puntos") Integer puntos,
                                RedirectAttributes flash) {
        try {
            balances.ajustarPuntos(id, puntos);
            flash.addFlashAttribute("mensajeExito", "Puntos actualizados.");
        } catch (RuntimeException ex) {
            flash.addFlashAttribute("mensajeError", ex.getMessage());
        }
        return "redirect:/clientes";
    }

    // ─── RESTAR CRÉDITO (registrar consumo a crédito VIP) ───────────────────────
    @PostMapping("/{id}/credito/consumir")
    public String consumirCredito(@PathVariable Long id,
                                  @RequestParam("monto") BigDecimal monto,
                                  RedirectAttributes flash) {
        try {
            BigDecimal saldo = balances.consumirCredito(id, monto);
            flash.addFlashAttribute("mensajeExito",
                    "Cargo a crédito registrado: $" + monto + ". Saldo pendiente: $" + saldo);
        } catch (RuntimeException ex) {
            flash.addFlashAttribute("mensajeError", ex.getMessage());
        }
        return "redirect:/clientes";
    }

    // ─── ABONAR A DEUDA ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/credito/abonar")
    public String abonarCredito(@PathVariable Long id,
                                @RequestParam("abono") BigDecimal abono,
                                RedirectAttributes flash) {
        try {
            balances.abonarCredito(id, abono);
            flash.addFlashAttribute("mensajeExito", "Abono de $" + abono + " registrado.");
        } catch (RuntimeException ex) {
            flash.addFlashAttribute("mensajeError", ex.getMessage());
        }
        return "redirect:/clientes";
    }

    // ─── ELIMINAR ────────────────────────────────────────────────────────────────
    @PostMapping("/eliminar/{id}")
    public String eliminar(@PathVariable Long id, RedirectAttributes flash) {
        clienteRepository.findById(id).ifPresent(c -> {
            c.setActivo(false);
            clienteRepository.save(c);
        });
        flash.addFlashAttribute("mensajeExito", "Cliente eliminado.");
        return "redirect:/clientes";
    }
}
