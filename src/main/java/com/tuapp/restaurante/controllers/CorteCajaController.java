package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.CorteCaja;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.models.Venta;
import com.tuapp.restaurante.repositories.CorteCajaRepository;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import com.tuapp.restaurante.repositories.VentaRepository;
import com.tuapp.restaurante.services.AuditoriaService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/caja")
public class CorteCajaController {


    public CorteCajaController(CorteCajaRepository corteCajaRepository, VentaRepository ventaRepository, UsuarioRepository usuarioRepository, AuditoriaService auditoriaService) {
        this.corteCajaRepository = corteCajaRepository;
        this.ventaRepository = ventaRepository;
        this.usuarioRepository = usuarioRepository;
        this.auditoriaService = auditoriaService;
    }

    private final CorteCajaRepository corteCajaRepository;
    private final VentaRepository ventaRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;

    // ─── PANEL PRINCIPAL ─────────────────────────────────────────────────────────
    @GetMapping
    public String panel(Model model) {
        Optional<CorteCaja> cajaActiva = corteCajaRepository.findFirstByEstadoOrderByFechaAperturaDesc("ABIERTO");
        List<CorteCaja> historial = corteCajaRepository.findAllByOrderByFechaAperturaDesc();
        BigDecimal totalVentasActivo = cajaActiva
                .map(caja -> calcularTotalesVenta(ventaRepository.findByCorteCajaIdOrderByFechaDesc(caja.getId())).totalVentas())
                .orElse(BigDecimal.ZERO);
        model.addAttribute("cajaActiva", cajaActiva.orElse(null));
        model.addAttribute("totalVentasActivo", totalVentasActivo);
        model.addAttribute("historial", historial);
        return "caja/panel";
    }

    // ─── APERTURA DE CAJA ────────────────────────────────────────────────────────
    @GetMapping("/abrir")
    public String formularioApertura(Model model) {
        // Redirigir al panel principal ya que ahora usamos un modal para la apertura
        return "redirect:/caja";
    }

    @PostMapping("/abrir")
    @Transactional
    public String abrirCaja(@RequestParam BigDecimal montoInicial,
                             Authentication auth,
                             RedirectAttributes flash) {
        if (!validMoney(montoInicial)) {
            flash.addFlashAttribute("mensajeError", "Monto inicial inválido.");
            return "redirect:/caja";
        }
        // Solo una caja activa a la vez
        Optional<CorteCaja> activa = corteCajaRepository.findFirstByEstadoOrderByFechaAperturaDesc("ABIERTO");
        if (activa.isPresent()) {
            flash.addFlashAttribute("mensajeError", "Ya hay un turno abierto. Ciérralo primero.");
            return "redirect:/caja";
        }

        Usuario cajero = usuarioRepository.findByUsername(auth.getName()).orElse(null);
        CorteCaja corte = new CorteCaja();
        corte.setFechaApertura(LocalDateTime.now());
        corte.setMontoInicial(montoInicial);
        corte.setEstado("ABIERTO");
        corte.setCajero(cajero);
        corte.setTotalEfectivo(BigDecimal.ZERO);
        corte.setTotalTarjeta(BigDecimal.ZERO);
        corte.setTotalVentas(BigDecimal.ZERO);
        corte.setTotalRetiros(BigDecimal.ZERO);
        corteCajaRepository.save(corte);

        auditoriaService.registrarLog("CAJA", "APERTURA_TURNO",
                "Turno abierto por " + auth.getName() + " con monto inicial $" + montoInicial);

        flash.addFlashAttribute("mensajeExito", "✅ Turno abierto correctamente con $" + montoInicial + " de fondo.");
        return "redirect:/caja";
    }

    // ─── CIERRE DE CAJA ──────────────────────────────────────────────────────────
    @GetMapping("/cerrar/{id}")
    public String formularioCierre(@PathVariable Long id, Model model) {
        CorteCaja caja = corteCajaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Turno no encontrado"));

        List<Venta> ventasTurno = ventaRepository.findByCorteCajaIdOrderByFechaDesc(caja.getId());

        TotalesVenta totales = calcularTotalesVenta(ventasTurno);
        BigDecimal totalEfectivo = totales.efectivo();
        BigDecimal totalTarjeta = totales.tarjeta();
        BigDecimal totalVentas = totales.totalVentas();

        // Monto esperado en caja = fondo inicial + ventas en efectivo - retiros
        BigDecimal esperadoEnCaja = caja.getMontoInicial()
                .add(totalEfectivo)
                .subtract(caja.getTotalRetiros());

        model.addAttribute("caja", caja);
        model.addAttribute("ventasTurno", ventasTurno);
        model.addAttribute("ticketsTurno", ventasTurno.size() + ventaRepository.contarSinCobroEnPeriodo(
                caja.getFechaApertura(), caja.getFechaCierre() == null ? LocalDateTime.now() : caja.getFechaCierre()));
        model.addAttribute("totalEfectivo", totalEfectivo);
        model.addAttribute("totalTarjeta", totalTarjeta);
        model.addAttribute("totalVentas", totalVentas);
        model.addAttribute("esperadoEnCaja", esperadoEnCaja);
        return "caja/cierre";
    }

    @PostMapping("/cerrar/{id}")
    @Transactional
    public String cerrarCaja(@PathVariable Long id,
                              @RequestParam BigDecimal montoContado,
                              @RequestParam(required = false) String notasCierre,
                              Authentication auth,
                              RedirectAttributes flash) {
        if (!validMoney(montoContado)) {
            flash.addFlashAttribute("mensajeError", "Monto contado inválido.");
            return "redirect:/caja";
        }
        CorteCaja caja = corteCajaRepository.lockById(id)
                .orElseThrow(() -> new RuntimeException("Turno no encontrado"));
        if (!"ABIERTO".equals(caja.getEstado())) {
            flash.addFlashAttribute("mensajeError", "El turno ya está cerrado.");
            return "redirect:/caja";
        }

        List<Venta> ventasTurno = ventaRepository.findByCorteCajaIdOrderByFechaDesc(caja.getId());

        TotalesVenta totales = calcularTotalesVenta(ventasTurno);
        BigDecimal totalEfectivo = totales.efectivo();
        BigDecimal totalTarjeta = totales.tarjeta();
        BigDecimal totalVentas = totales.totalVentas();
        BigDecimal esperado = caja.getMontoInicial().add(totalEfectivo).subtract(caja.getTotalRetiros());
        BigDecimal diferencia = montoContado.subtract(esperado);

        caja.setFechaCierre(LocalDateTime.now());
        caja.setTotalEfectivo(totalEfectivo);
        caja.setTotalTarjeta(totalTarjeta);
        caja.setTotalVentas(totalVentas);
        caja.setMontoContado(montoContado);
        caja.setDiferencia(diferencia);
        caja.setNotasCierre(notasCierre);
        caja.setEstado("CERRADO");
        corteCajaRepository.save(caja);

        String tipo = diferencia.compareTo(BigDecimal.ZERO) < 0 ? "FALTANTE" : "SOBRANTE";
        auditoriaService.registrarLog("CAJA", "CIERRE_TURNO",
                "Turno cerrado por " + auth.getName() +
                " | Ventas: $" + totalVentas +
                " | " + tipo + ": $" + diferencia.abs());

        flash.addFlashAttribute("mensajeExito", "💰 Turno cerrado. " + tipo + ": $" + diferencia.abs());
        return "redirect:/caja";
    }

    private boolean validMoney(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0) return false;
        try { value.setScale(2, RoundingMode.UNNECESSARY); return true; }
        catch (ArithmeticException ex) { return false; }
    }

    private TotalesVenta calcularTotalesVenta(List<Venta> ventas) {
        BigDecimal efectivo = ventas.stream()
                .map(v -> (v.getMontoEfectivo() != null ? v.getMontoEfectivo() : BigDecimal.ZERO)
                        .subtract(v.getCambio() != null ? v.getCambio() : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tarjeta = ventas.stream()
                .map(v -> v.getMontoTarjeta() != null ? v.getMontoTarjeta() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TotalesVenta(efectivo, tarjeta, efectivo.add(tarjeta));
    }

    private record TotalesVenta(BigDecimal efectivo, BigDecimal tarjeta, BigDecimal totalVentas) { }
}
