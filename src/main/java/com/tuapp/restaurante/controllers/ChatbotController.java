package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.*;
import com.tuapp.restaurante.repositories.*;
import com.tuapp.restaurante.services.PredictiveAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chatbot")
public class ChatbotController {

    private final VentaRepository ventaRepository;
    private final InsumoRepository insumoRepository;
    private final ReservaRepository reservaRepository;
    private final ClienteRepository clienteRepository;
    private final PredictiveAnalysisService iaService;

    public ChatbotController(VentaRepository ventaRepository,
                             InsumoRepository insumoRepository,
                             ReservaRepository reservaRepository,
                             ClienteRepository clienteRepository,
                             PredictiveAnalysisService iaService) {
        this.ventaRepository = ventaRepository;
        this.insumoRepository = insumoRepository;
        this.reservaRepository = reservaRepository;
        this.clienteRepository = clienteRepository;
        this.iaService = iaService;
    }

    @PostMapping("/mensaje")
    public ResponseEntity<Map<String, String>> responder(@RequestBody Map<String, String> body) {
        String pregunta = body.getOrDefault("mensaje", "").toLowerCase().trim();
        // Normalizamos la entrada para ignorar acentos
        String respuesta = procesarPregunta(normalizar(pregunta));
        return ResponseEntity.ok(Map.of("respuesta", respuesta));
    }

    private String procesarPregunta(String q) {
        // Saludos
        if (contiene(q, "hola", "buenos dias", "buenas", "que tal", "hey")) {
            return "¡Hola! Soy Ventro AI 🤖 Tu asistente inteligente. Puedes preguntarme sobre ventas, stock, reservas, clientes o recomendaciones. ¿En qué te ayudo?";
        }

        // Ventas
        if (contiene(q, "ventas de hoy", "cuanto vendi hoy", "venta hoy")) {
            return getVentasHoy();
        }
        if (contiene(q, "ventas de ayer")) {
            return getVentasAyer();
        }
        if (contiene(q, "ventas", "ingresos", "dinero", "cuanto vendi")) {
            return getResumenVentas();
        }

        // Stock / Inventario (Diferenciando por especificidad)
        if (contiene(q, "stock critico", "insumos criticos", "que me falta", "inventario critico", "alertas stock")) {
            return getStockCritico();
        }
        if (contiene(q, "resumen de inventario", "inventario", "stock", "insumos")) {
            return getResumenInventario();
        }

        // Reservas
        if (contiene(q, "reservas de hoy", "reservas hoy")) {
            return getReservasHoy();
        }
        if (contiene(q, "reservas de mañana", "reservas mañana")) {
            return getReservasMañana();
        }
        if (contiene(q, "proximas reservas", "reservas", "cuantas reservas")) {
            return getResumenReservas();
        }

        // Clientes
        if (contiene(q, "cuantos clientes", "total clientes", "clientes registrados")) {
            long total = clienteRepository.count();
            long vip = clienteRepository.findAll().stream().filter(c -> "VIP".equals(c.getTipo())).count();
            return String.format("📋 Tienes **%d clientes** registrados en el CRM, de los cuales **%d son VIP**.", total, vip);
        }

        // Ayuda
        if (contiene(q, "ayuda", "que puedes hacer", "que sabes", "funciones", "opciones")) {
            return "👋 ¡Claro! Aquí tienes todo lo que puedo hacer por ti:\n\n" +
                    "💰 **Ventas**: Prueba con \"ventas de hoy\", \"ventas de ayer\" o \"resumen de ventas\".\n" +
                    "📦 **Inventario**: Pregúntame por \"stock crítico\" o \"resumen de inventario\".\n" +
                    "📅 **Reservas**: Consulta \"reservas de hoy\", \"mañana\" o \"proximas reservas\".\n" +
                    "👥 **Clientes**: Escribe \"cuantos clientes\" para ver estadísticas del CRM.\n" +
                    "🧠 **Inteligencia**: Pídeme un \"diagnostico\" (estado actual) o un \"analisis\" (proyecciones).\n" +
                    "💡 **Consejos**: Escribe \"recomendacion\" para recibir tips de mejora.\n\n" +
                    "¿Sobre qué te gustaría obtener información ahora?";
        }

        // IA / Diagnóstico (Estado Actual de Insumos)
        if (contiene(q, "diagnostico", "ia", "compras", "que comprar")) {
            return getDiagnosticoIA();
        }

        // Recomendaciones
        if (contiene(q, "recomendacion", "consejo", "que hacer", "mejoras")) {
            return getRecomendacion();
        }

        // Proyección / Análisis (Predicciones Futuras)
        if (contiene(q, "analisis", "proyeccion", "prediccion", "futuro", "semana", "cuanto voy a vender")) {
            PredictiveAnalysisService.PronosticoVentas p = iaService.predecirVentas7Dias();
            return String.format("🔮 **Análisis Predictivo Ventro**\n\nSegún mis cálculos, la proyección de ingresos para los próximos 7 días es de **$%s**.\n\n💡 %s",
                    p.totalEsperado.toPlainString(), p.insight);
        }

        return "No entendí bien tu pregunta 🤔 Puedes preguntarme sobre **ventas**, **stock**, **reservas**, **clientes** o **proyecciones**. También puedes escribir *ayuda* para ver todo lo que sé.";
    }

    private String getVentasHoy() {
        LocalDateTime inicio = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        List<Venta> ventas = ventaRepository.findByFechaBetweenOrderByFechaDesc(inicio, LocalDateTime.now());
        BigDecimal total = ventas.stream().map(v -> v.getTotal() != null ? v.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return String.format("💰 Hoy has generado **%d ventas** con un total de **$%s**.", ventas.size(), total.toPlainString());
    }

    private String getVentasAyer() {
        LocalDateTime inicio = LocalDateTime.now().minusDays(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime fin = LocalDateTime.now().minusDays(1).withHour(23).withMinute(59).withSecond(59);
        List<Venta> ventas = ventaRepository.findByFechaBetweenOrderByFechaDesc(inicio, fin);
        BigDecimal total = ventas.stream().map(v -> v.getTotal() != null ? v.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return String.format("📊 Ayer tuviste **%d ventas** con un total de **$%s**.", ventas.size(), total.toPlainString());
    }

    private String getResumenVentas() {
        LocalDateTime hace7d = LocalDateTime.now().minusDays(7);
        List<Venta> ventas = ventaRepository.findByFechaBetweenOrderByFechaDesc(hace7d, LocalDateTime.now());
        BigDecimal total = ventas.stream().map(v -> v.getTotal() != null ? v.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal promedio = ventas.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(ventas.size()), 2, java.math.RoundingMode.HALF_UP);
        return String.format("📈 En los últimos **7 días**:\n• Total de ventas: **$%s**\n• Número de transacciones: **%d**\n• Ticket promedio: **$%s**",
                total.toPlainString(), ventas.size(), promedio.toPlainString());
    }

    private String getStockCritico() {
        List<Insumo> criticos = insumoRepository.findInsumosConStockCritico();
        if (criticos.isEmpty()) return "✅ ¡Excelente! Todos los insumos están en niveles óptimos. No hay alertas de stock crítico.";
        String lista = criticos.stream()
                .map(i -> String.format("• **%s**: %.2f %s (mín: %.2f)", i.getNombre(), i.getStockActual(), i.getUnidadMedida(), i.getStockMinimo()))
                .collect(Collectors.joining("\n"));
        return String.format("⚠️ Hay **%d insumo(s)** con stock crítico:\n%s\n\n🛒 Te recomiendo ir a [Compras IA](/ia/compras) para generar una orden.", criticos.size(), lista);
    }

    private String getResumenInventario() {
        List<Insumo> todos = insumoRepository.findByActivoTrueOrderByIdAsc();
        long criticos = todos.stream().filter(i -> i.getStockActual().compareTo(i.getStockMinimo()) <= 0).count();
        return String.format("📦 Tienes **%d insumos activos** en inventario. De ellos, **%d** están por debajo del stock mínimo.", todos.size(), criticos);
    }

    private String getReservasHoy() {
        LocalDateTime inicio = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime fin = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
        List<Reserva> reservas = reservaRepository.findByFechaHoraBetweenOrderByFechaHoraAsc(inicio, fin);
        int comensales = reservas.stream().mapToInt(r -> r.getNumeroPersonas() != null ? r.getNumeroPersonas() : 0).sum();
        if (reservas.isEmpty()) return "📅 No tienes reservas para hoy. Puedes ver la agenda en el módulo de Reservas.";
        return String.format("📅 Hoy tienes **%d reserva(s)** con un total de **%d comensales** esperados.", reservas.size(), comensales);
    }

    private String getReservasMañana() {
        LocalDateTime inicio = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime fin = LocalDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
        List<Reserva> reservas = reservaRepository.findByFechaHoraBetweenOrderByFechaHoraAsc(inicio, fin);
        int comensales = reservas.stream().mapToInt(r -> r.getNumeroPersonas() != null ? r.getNumeroPersonas() : 0).sum();
        if (reservas.isEmpty()) return "📅 No hay reservas registradas para mañana todavía.";
        return String.format("📅 Mañana tienes **%d reserva(s)** programada(s) con **%d comensales** en total.", reservas.size(), comensales);
    }

    private String getResumenReservas() {
        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> proximas = reservaRepository.findByFechaHoraBetweenOrderByFechaHoraAsc(ahora, ahora.plusDays(7));
        int comensales = proximas.stream().mapToInt(r -> r.getNumeroPersonas() != null ? r.getNumeroPersonas() : 0).sum();
        return String.format("📅 En los próximos **7 días** tienes **%d reserva(s)** con **%d comensales** esperados. ¡Prepara el inventario!", proximas.size(), comensales);
    }

    private String getDiagnosticoIA() {
        PredictiveAnalysisService.ResumenPrediccion d = iaService.generarDiagnostico();
        String alerta = "Ninguno".equals(d.proximaAlertaCritica) ? "ninguno" : "**" + d.proximaAlertaCritica + "**";
        return String.format("🧠 Diagnóstico de Ventro Intelligence:\n• Insumos en riesgo: **%d**\n• Insumos estables: **%d**\n• Inversión sugerida: **$%.2f**\n• Insumo más crítico: %s\n\nVe al [Diagnóstico completo](/ia/compras) para más detalles.",
                d.insumosEnRiesgo, d.insumosEstables, d.inversionTotalEstimada, alerta);
    }

    private String getRecomendacion() {
        List<Insumo> criticos = insumoRepository.findInsumosConStockCritico();
        LocalDateTime inicio = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        List<Venta> ventasHoy = ventaRepository.findByFechaBetweenOrderByFechaDesc(inicio, LocalDateTime.now());

        if (!criticos.isEmpty()) {
            return String.format("💡 Mi recomendación principal: **Reponer stock** de %s. Tienes %d insumo(s) crítico(s) que podrían afectar el servicio de hoy.",
                    criticos.get(0).getNombre(), criticos.size());
        }
        if (ventasHoy.isEmpty()) {
            return "💡 Aún no hay ventas hoy. Considera activar una **promoción del día** o comunicarte con clientes VIP para invitarlos.";
        }
        return "💡 Todo marcha bien. Revisa la **Matriz BCG** en el Dashboard para identificar qué platillos pueden subir de precio sin perder popularidad.";
    }

    private boolean contiene(String texto, String... palabras) {
        for (String p : palabras) {
            if (texto.contains(normalizar(p.toLowerCase()))) return true;
        }
        return false;
    }

    private String normalizar(String texto) {
        if (texto == null) return null;
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
