package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Venta;
import com.tuapp.restaurante.repositories.VentaRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/reportes")
public class ReporteController {

    private final VentaRepository ventaRepository;
    private final com.tuapp.restaurante.repositories.InsumoRepository insumoRepository;

    public ReporteController(VentaRepository ventaRepository, com.tuapp.restaurante.repositories.InsumoRepository insumoRepository) {
        this.ventaRepository = ventaRepository;
        this.insumoRepository = insumoRepository;
    }

    @GetMapping("/insumos/pdf")
    public String reporteInventario(Model model) {
        model.addAttribute("insumos", insumoRepository.findAll());
        model.addAttribute("titulo", "Reporte de Existencias");
        model.addAttribute("fechaActual", LocalDate.now());
        return "reportes/inventario_pdf";
    }

    @GetMapping("/ventas/pdf")
    public String reporteVentasPdf(
            @RequestParam(value = "fecha", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            Model model) {
        if (fecha == null) fecha = LocalDate.now();
        LocalDateTime inicio = fecha.atStartOfDay();
        LocalDateTime fin = fecha.atTime(LocalTime.MAX);
        List<Venta> ventas = ventaRepository.findByFechaBetweenOrderByFechaDesc(inicio, fin);
        BigDecimal totalRecaudado = ventas.stream()
                .map(v -> v.getTotal() != null ? v.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("ventas", ventas);
        model.addAttribute("totalRecaudado", totalRecaudado);
        model.addAttribute("fechaActual", fecha);
        model.addAttribute("titulo", "Reporte de Ventas Diario");
        return "reportes/ventas_pdf";
    }

    @GetMapping
    public String index(@RequestParam(value = "fecha", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha, Model model) {
        if (fecha == null) fecha = LocalDate.now();

        LocalDateTime inicio = fecha.atStartOfDay();
        LocalDateTime fin = fecha.atTime(LocalTime.MAX);

        List<Venta> ventas = ventaRepository.findByFechaBetweenOrderByFechaDesc(inicio, fin);
        
        BigDecimal totalRecaudado = ventas.stream()
                .map(v -> v.getTotal() != null ? v.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("ventas", ventas);
        model.addAttribute("totalRecaudado", totalRecaudado);
        model.addAttribute("fechaSeleccionada", fecha);
        model.addAttribute("titulo", "Reporte de Ventas");

        return "reportes/index";
    }

    @GetMapping("/exportar")
    public ResponseEntity<byte[]> exportarReporte(
            @RequestParam(value = "fecha", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        
        if (fecha == null) fecha = LocalDate.now();

        LocalDateTime inicio = fecha.atStartOfDay();
        LocalDateTime fin = fecha.atTime(LocalTime.MAX);

        List<Venta> ventas = ventaRepository.findByFechaBetweenOrderByFechaDesc(inicio, fin);

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff'); // BOM
        sb.append("sep=;\n");
        sb.append("ID Ticket;Hora;Cajero;Metodo de Pago;Monto Total\n");
        
        BigDecimal totalGeneral = BigDecimal.ZERO;

        for (Venta v : ventas) {
            String hora = v.getFecha().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            String cajero = v.getUsuario().getUsername();
            String metodo = v.getMetodoPago();
            BigDecimal total = v.getTotal() != null ? v.getTotal() : BigDecimal.ZERO;
            totalGeneral = totalGeneral.add(total);
            
            sb.append(String.format("%d;%s;%s;%s;%s\n", 
                    v.getId(), hora, cajero, metodo, total.toString().replace('.', ',')));
        }

        sb.append(";;;;\n");
        sb.append("TOTAL DEL DIA;;;;" + totalGeneral.toString().replace('.', ',') + "\n");

        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = "reporte_ventas_" + fecha.toString() + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/csv"))
                .contentLength(content.length)
                .body(content);
    }
}
