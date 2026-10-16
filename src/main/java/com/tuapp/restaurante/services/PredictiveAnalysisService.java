package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.*;
import com.tuapp.restaurante.repositories.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Motor de IA Predictiva de Compras (Ventro Intelligence)
 *
 * Variables que evalúa:
 *  V1. Tasa de consumo histórica (últimos 30 y 90 días)
 *  V2. Tendencia por día de semana (weekend vs. weekday factor)
 *  V3. Tasa de merma (% de desperdicio sobre consumo total)
 *  V4. Reservas confirmadas en los próximos 7 días (demanda futura conocida)
 *  V5. Stock actual vs. días de cobertura restantes
 *  V6. Confianza del pronóstico (basado en volumen de datos históricos)
 *
 * Output: RecomendacionCompra con nivel de urgencia, cantidad sugerida,
 *         justificación detallada y señales de alerta.
 */
@Service
public class PredictiveAnalysisService {

    private final InsumoRepository insumoRepository;
    private final MovimientoInventarioRepository movimientoRepository;
    private final VentaRepository ventaRepository;
    private final ReservaRepository reservaRepository;

    public PredictiveAnalysisService(InsumoRepository insumoRepository,
                                     MovimientoInventarioRepository movimientoRepository,
                                     VentaRepository ventaRepository,
                                     ReservaRepository reservaRepository) {
        this.insumoRepository = insumoRepository;
        this.movimientoRepository = movimientoRepository;
        this.ventaRepository = ventaRepository;
        this.reservaRepository = reservaRepository;
    }

    // ─────────────────────────────────────────────
    // DTO de salida
    // ─────────────────────────────────────────────

    public static class RecomendacionCompra {
        public Long insumoId;
        public String nombreInsumo;
        public String unidadMedida;
        public String proveedor;
        public BigDecimal stockActual;
        public BigDecimal stockMinimo;
        public BigDecimal cantidadRecomendada;
        public BigDecimal costoEstimado;
        public String nivelUrgencia;           // CRITICO / ALTO / MEDIO / BAJO
        public int diasCobertura;              // días que dura el stock actual
        public double tasaMerma;              // % de merma sobre consumo
        public double factorFinde;             // multiplicador de demanda en fin de semana
        public int reservasProximas;          // comensales reservados próximos 7 días
        public int confianzaPronostico;       // 0–100%
        public List<String> senalesAlerta;
        public String resumenIA;              // texto generado por la lógica predictiva
    }

    public static class ResumenPrediccion {
        public List<RecomendacionCompra> recomendaciones;
        public List<AlertaMerma> alertasMermas;
        public BigDecimal inversionTotalEstimada;
        public int insumosEnRiesgo;
        public int insumosEstables;
        public String proximaAlertaCritica;   // nombre del insumo más urgente
        public int confianzaGlobal;           // % promedio de confianza
        public Map<String, Object> estadisticas;
    }

    public static class AlertaMerma {
        public Long insumoId;
        public String nombreInsumo;
        public String unidadMedida;
        public BigDecimal stockEnRiesgo;
        public BigDecimal costoEnRiesgo;
        public int diasCobertura;
        public String sugerenciaAccion;
    }

    public static class PronosticoVentas {
        public List<String> labels;
        public List<BigDecimal> valores;
        public BigDecimal totalEsperado;
        public String insight;
    }

    // ─────────────────────────────────────────────
    // Método principal
    // ─────────────────────────────────────────────

    public PronosticoVentas predecirVentas7Dias() {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime hace90Dias = ahora.minusDays(90);
        
        List<String> labels = new ArrayList<>();
        List<BigDecimal> valores = new ArrayList<>();
        BigDecimal totalEsperado = BigDecimal.ZERO;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM");

        // Factor base de ventas por comensal (promedio histórico)
        com.tuapp.restaurante.repositories.SalesPeriodTotals ventas90d = ventaRepository.totalesPeriodo(hace90Dias, ahora);
        BigDecimal ticketPromedio = BigDecimal.valueOf(15.0); // fallback
        if (ventas90d.getCuenta() > 0) {
            ticketPromedio = ventas90d.getTotal().divide(BigDecimal.valueOf(ventas90d.getCuenta()), 2, RoundingMode.HALF_UP);
        }

        double factorFinde = calcularFactorFinde(hace90Dias);
        LocalDateTime inicioReservas = ahora;
        LocalDateTime finReservas = ahora.plusDays(6).withHour(23).withMinute(59);
        Map<LocalDate, Integer> comensalesPorDia = new HashMap<>();
        reservaRepository.sumarPersonasPorDia(inicioReservas, finReservas)
                .forEach(row -> comensalesPorDia.put(row.getDia(), row.getComensales().intValue()));
        
        for (int i = 0; i < 7; i++) {
            LocalDateTime dia = ahora.plusDays(i);
            labels.add(dia.format(fmt));
            
            // Lógica: Base + Reservas + Factor Día
            int comensalesReservados = comensalesPorDia.getOrDefault(dia.toLocalDate(), 0);
            
            // Estimación de comensales espontáneos (walk-ins) basada en promedio histórico diario
            double walkInsBase = ventas90d.getCuenta() / 90.0 * 2.0; // Simplificado
            
            DayOfWeek dow = dia.getDayOfWeek();
            double multiplicadorDia = (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? factorFinde : 1.0;
            
            BigDecimal predicho = ticketPromedio.multiply(BigDecimal.valueOf((comensalesReservados + walkInsBase) * multiplicadorDia))
                    .setScale(2, RoundingMode.HALF_UP);
            
            valores.add(predicho);
            totalEsperado = totalEsperado.add(predicho);
        }

        PronosticoVentas pronostico = new PronosticoVentas();
        pronostico.labels = labels;
        pronostico.valores = valores;
        pronostico.totalEsperado = totalEsperado;
        
        // Generar insight inteligente
        if (factorFinde > 1.5) {
            pronostico.insight = "Tendencia alcista para el fin de semana (+"+String.format("%.0f%%", (factorFinde-1)*100)+"). Refuerza personal en salón.";
        } else if (totalEsperado.compareTo(BigDecimal.valueOf(1000)) > 0) {
            pronostico.insight = "Semana con alta demanda proyectada. Revisa el stock de productos Estrella.";
        } else {
            pronostico.insight = "Demanda estable. Buen momento para lanzar promociones en productos 'Perro' o 'Rompecabezas'.";
        }

        return pronostico;
    }

    public ResumenPrediccion generarDiagnostico() {
        List<Insumo> insumos = insumoRepository.findByActivoTrueOrderByIdAsc();
        LocalDateTime hace90Dias = LocalDateTime.now().minusDays(90);
        LocalDateTime hace30Dias = LocalDateTime.now().minusDays(30);
        LocalDateTime ahora = LocalDateTime.now();

        // V4: Reservas próximos 7 días → comensales totales esperados
        int totalComensalesReservados = reservaRepository.sumarPersonasEntre(ahora, ahora.plusDays(7)).intValue();

        // V2: Factor fin de semana: ¿cuánto más se vende Viernes/Sábado/Domingo?
        double factorFindeGlobal = calcularFactorFinde(hace90Dias);

        // Mermas globales últimos 90 días
        Map<Long, com.tuapp.restaurante.repositories.MovementAggregateProjection> consumoPorInsumo =
                movimientoRepository.resumirConsumo(List.of("SALIDA", "SALIDA_PRODUCCION", "MERMA"), hace30Dias)
                        .stream().collect(Collectors.toMap(
                                com.tuapp.restaurante.repositories.MovementAggregateProjection::getInsumoId, row -> row));
        Map<Long, com.tuapp.restaurante.repositories.MovementAggregateProjection> mermasPorInsumo =
                movimientoRepository.resumirMermas(hace90Dias).stream().collect(Collectors.toMap(
                        com.tuapp.restaurante.repositories.MovementAggregateProjection::getInsumoId, row -> row));
        long totalMermas90d = mermasPorInsumo.values().stream()
                .mapToLong(com.tuapp.restaurante.repositories.MovementAggregateProjection::getCuenta).sum();
        Map<Long, Insumo> insumosPorId = insumos.stream().collect(Collectors.toMap(Insumo::getId, insumo -> insumo));

        List<RecomendacionCompra> recomendaciones = new ArrayList<>();

        for (Insumo insumo : insumos) {
            RecomendacionCompra rec = analizarInsumo(insumo, consumoPorInsumo.get(insumo.getId()),
                    mermasPorInsumo.get(insumo.getId()), totalComensalesReservados, factorFindeGlobal);
            recomendaciones.add(rec);
        }

        // Ordenar por urgencia
        recomendaciones.sort(Comparator.comparingInt(r -> urgenciaOrdinal(r.nivelUrgencia)));

        // Calcular resumen
        ResumenPrediccion resumen = new ResumenPrediccion();
        resumen.recomendaciones = recomendaciones;
        resumen.inversionTotalEstimada = recomendaciones.stream()
                .filter(r -> !r.nivelUrgencia.equals("BAJO"))
                .map(r -> r.costoEstimado != null ? r.costoEstimado : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        resumen.insumosEnRiesgo = (int) recomendaciones.stream()
                .filter(r -> r.nivelUrgencia.equals("CRITICO") || r.nivelUrgencia.equals("ALTO"))
                .count();
        resumen.insumosEstables = (int) recomendaciones.stream()
                .filter(r -> r.nivelUrgencia.equals("BAJO"))
                .count();
        resumen.proximaAlertaCritica = recomendaciones.stream()
                .filter(r -> r.nivelUrgencia.equals("CRITICO"))
                .map(r -> r.nombreInsumo)
                .findFirst().orElse("Ninguno");

        List<AlertaMerma> alertasMermas = new ArrayList<>();
        for (RecomendacionCompra rec : recomendaciones) {
            Insumo insumo = insumosPorId.get(rec.insumoId);
            if (insumo != null && esPerecedero(insumo)) {
                if (rec.diasCobertura > 7 && rec.stockActual.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal demandaDiaria = rec.stockActual.divide(BigDecimal.valueOf(rec.diasCobertura), 2, RoundingMode.HALF_UP);
                    BigDecimal demanda7Dias = demandaDiaria.multiply(BigDecimal.valueOf(7));
                    BigDecimal stockEnRiesgo = rec.stockActual.subtract(demanda7Dias);
                    
                    if (stockEnRiesgo.compareTo(BigDecimal.ZERO) > 0) {
                        AlertaMerma alerta = new AlertaMerma();
                        alerta.insumoId = insumo.getId();
                        alerta.nombreInsumo = insumo.getNombre();
                        alerta.unidadMedida = insumo.getUnidadMedida();
                        alerta.stockEnRiesgo = stockEnRiesgo.setScale(2, RoundingMode.HALF_UP);
                        alerta.costoEnRiesgo = stockEnRiesgo.multiply(insumo.getCostoUnitario()).setScale(2, RoundingMode.HALF_UP);
                        alerta.diasCobertura = rec.diasCobertura;
                        alerta.sugerenciaAccion = generarSugerenciaMerma(insumo.getNombre(), alerta.stockEnRiesgo.toPlainString(), alerta.unidadMedida);
                        alertasMermas.add(alerta);
                    }
                }
            }
        }
        resumen.alertasMermas = alertasMermas;

        resumen.confianzaGlobal = recomendaciones.isEmpty() ? 0 : 
                (int) recomendaciones.stream().mapToInt(r -> r.confianzaPronostico).average().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalInsumos", insumos.size());
        stats.put("totalComensalesReservados7d", totalComensalesReservados);
        stats.put("factorFinDeSemana", String.format("%.0f%%", factorFindeGlobal * 100));
        stats.put("mermasTotales90d", totalMermas90d);
        resumen.estadisticas = stats;

        return resumen;
    }

    // ─────────────────────────────────────────────
    // Análisis por insumo
    // ─────────────────────────────────────────────

    private RecomendacionCompra analizarInsumo(Insumo insumo,
                                               com.tuapp.restaurante.repositories.MovementAggregateProjection consumo,
                                               com.tuapp.restaurante.repositories.MovementAggregateProjection merma,
                                               int totalComensalesReservados,
                                               double factorFindeGlobal) {
        RecomendacionCompra rec = new RecomendacionCompra();
        rec.insumoId = insumo.getId();
        rec.nombreInsumo = insumo.getNombre();
        rec.unidadMedida = insumo.getUnidadMedida();
        rec.proveedor = insumo.getProveedor() != null ? insumo.getProveedor().getNombreEmpresa() : "Sin proveedor";
        rec.stockActual = insumo.getStockActual();
        rec.stockMinimo = insumo.getStockMinimo();
        rec.senalesAlerta = new ArrayList<>();

        // V1: Consumo histórico (SALIDA_PRODUCCION + SALIDA + MERMA en 30 días)
        BigDecimal consumo30d = consumo == null ? BigDecimal.ZERO : consumo.getCantidad();

        BigDecimal consumoDiarioPromedio = consumo30d.compareTo(BigDecimal.ZERO) > 0
                ? consumo30d.divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(0.01); // mínimo para evitar divisiones por cero

        // V3: Tasa de merma
        BigDecimal mermaInsumo = merma == null ? BigDecimal.ZERO : merma.getCantidad();

        rec.tasaMerma = consumo30d.compareTo(BigDecimal.ZERO) > 0
                ? mermaInsumo.divide(consumo30d, 4, RoundingMode.HALF_UP).doubleValue() * 100
                : 0.0;

        // V4: Impacto de reservas en consumo de este insumo
        // Si hay reservas, estimamos un +X% adicional proporcional a la capacidad
        double factorReservas = 1.0;
        if (totalComensalesReservados > 0) {
            // Estimamos un factor simple: cada 10 comensales reservados = +3% de demanda
            factorReservas = 1.0 + (totalComensalesReservados / 10.0 * 0.03);
            rec.reservasProximas = totalComensalesReservados;
            if (totalComensalesReservados > 20) {
                rec.senalesAlerta.add("🗓️ " + totalComensalesReservados + " comensales reservados próx. 7 días");
            }
        }

        // V2: Factor fin de semana (próximos 7 días: ¿cuántos son finde?)
        long diasFindeEnProximos7 = contarDiasFindePorVenir(7);
        rec.factorFinde = factorFindeGlobal;
        double factorDemandaFinal = consumoDiarioPromedio.doubleValue() * factorReservas *
                (1.0 + (diasFindeEnProximos7 * (factorFindeGlobal - 1.0) / 7.0));

        // Demanda proyectada a 7 días con todas las variables
        double demandaProyectada7d = factorDemandaFinal * 7;

        // Agregar buffer de seguridad por tasa de merma
        double bufferMerma = 1.0 + (rec.tasaMerma / 100.0);
        demandaProyectada7d *= bufferMerma;

        // V5: Días de cobertura con stock actual
        rec.diasCobertura = consumoDiarioPromedio.compareTo(BigDecimal.ZERO) > 0
                ? (int) (insumo.getStockActual().doubleValue() / consumoDiarioPromedio.doubleValue())
                : 999;

        // Confianza basada en cantidad de datos históricos
        long movimientos30d = consumo == null ? 0 : consumo.getCuenta();
        rec.confianzaPronostico = calcularConfianza((int) Math.min(Integer.MAX_VALUE, movimientos30d));

        // Cantidad a pedir: diferencia entre demanda proyectada y stock disponible
        double cantidadNecesaria = demandaProyectada7d - insumo.getStockActual().doubleValue();
        if (cantidadNecesaria < 0) cantidadNecesaria = 0;

        // Stock objetivo = demanda 14 días (2 semanas de buffer)
        double stockObjetivo = factorDemandaFinal * 14;
        double cantidadRecomendada = Math.max(cantidadNecesaria, stockObjetivo - insumo.getStockActual().doubleValue());
        cantidadRecomendada = Math.max(cantidadRecomendada, 0);

        rec.cantidadRecomendada = BigDecimal.valueOf(cantidadRecomendada).setScale(2, RoundingMode.CEILING);
        rec.costoEstimado = rec.cantidadRecomendada.multiply(insumo.getCostoUnitario()).setScale(2, RoundingMode.HALF_UP);

        // Señales de alerta adicionales
        if (rec.tasaMerma > 15) rec.senalesAlerta.add("⚠️ Tasa de merma alta: " + String.format("%.1f%%", rec.tasaMerma));
        if (rec.diasCobertura <= 2) rec.senalesAlerta.add("🚨 Stock crítico: solo " + rec.diasCobertura + " días de cobertura");
        if (rec.diasCobertura <= 7 && rec.diasCobertura > 2) rec.senalesAlerta.add("⏰ Stock bajo: " + rec.diasCobertura + " días de cobertura");
        if (diasFindeEnProximos7 >= 2) rec.senalesAlerta.add("📈 " + diasFindeEnProximos7 + " días de fin de semana próximos (alta demanda)");
        if (movimientos30d == 0) rec.senalesAlerta.add("📊 Sin datos de consumo reciente (baja confianza)");

        // Determinar nivel de urgencia
        rec.nivelUrgencia = determinarUrgencia(rec.diasCobertura, insumo, demandaProyectada7d);

        // Resumen IA en lenguaje natural
        rec.resumenIA = generarResumenTexto(rec, consumo30d, demandaProyectada7d);

        return rec;
    }

    // ─────────────────────────────────────────────
    // Métodos auxiliares
    // ─────────────────────────────────────────────

    private double calcularFactorFinde(LocalDateTime desde) {
        // Obtener ventas por día de semana de los últimos 90 días
        // DAYOFWEEK: 1=Domingo, 2=Lunes, ... 6=Viernes, 7=Sábado
        try {
            Map<Integer, com.tuapp.restaurante.repositories.WeekdaySalesProjection> resumen =
                    ventaRepository.resumenPorDiaSemana(desde).stream().collect(Collectors.toMap(
                            com.tuapp.restaurante.repositories.WeekdaySalesProjection::getDiaSemana, row -> row));
            double sumaFin = totalDia(resumen, 1) + totalDia(resumen, 6) + totalDia(resumen, 7);
            double conteoFin = Math.max(conteoDia(resumen, 1), 1) + Math.max(conteoDia(resumen, 6), 1) +
                    Math.max(conteoDia(resumen, 7), 1);
            double promedioFinde = sumaFin / conteoFin;
            double promedioSemana = totalDia(resumen, 4) / Math.max(conteoDia(resumen, 4), 1);

            if (promedioSemana > 0 && promedioFinde > 0) {
                return Math.min(promedioFinde / promedioSemana, 2.5); // Cap en 250%
            }
        } catch (Exception ignored) {}
        return 1.3; // factor por defecto conservador
    }

    private double totalDia(Map<Integer, com.tuapp.restaurante.repositories.WeekdaySalesProjection> resumen, int dia) {
        var row = resumen.get(dia);
        return row == null || row.getTotal() == null ? 0 : row.getTotal().doubleValue();
    }

    private long conteoDia(Map<Integer, com.tuapp.restaurante.repositories.WeekdaySalesProjection> resumen, int dia) {
        var row = resumen.get(dia);
        return row == null ? 0 : row.getCuenta();
    }

    private long contarDiasFindePorVenir(int dias) {
        long count = 0;
        LocalDate hoy = LocalDate.now();
        for (int i = 0; i < dias; i++) {
            DayOfWeek dow = hoy.plusDays(i).getDayOfWeek();
            if (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    private String determinarUrgencia(int diasCobertura, Insumo insumo, double demandaProyectada7d) {
        boolean stockBajoMinimo = insumo.getStockActual().compareTo(insumo.getStockMinimo()) <= 0;
        
        // Lógica inteligente: Si tengo stock para más de 15 días, no es crítico ni alto, 
        // aunque el mínimo manual diga lo contrario (evita sobre-stock innecesario)
        if (diasCobertura > 15) {
            return stockBajoMinimo ? "MEDIO" : "BAJO";
        }

        if (diasCobertura <= 1 || stockBajoMinimo) return "CRITICO";
        if (diasCobertura <= 3) return "ALTO";
        if (diasCobertura <= 7 || insumo.getStockActual().doubleValue() < demandaProyectada7d) return "MEDIO";
        return "BAJO";
    }

    private int calcularConfianza(int numMovimientos) {
        // Escala logarítmica: 5 movimientos = 40%, 30 = 80%, 100+ = 95%
        if (numMovimientos == 0) return 15;
        return (int) Math.min(95, 30 + (Math.log(numMovimientos) / Math.log(100)) * 70);
    }

    private int urgenciaOrdinal(String urgencia) {
        return switch (urgencia) {
            case "CRITICO" -> 0;
            case "ALTO" -> 1;
            case "MEDIO" -> 2;
            default -> 3;
        };
    }

    private String generarResumenTexto(RecomendacionCompra rec, BigDecimal consumo30d, double demanda7d) {
        StringBuilder sb = new StringBuilder();
        if (rec.nivelUrgencia.equals("CRITICO")) {
            sb.append("⛔ Acción inmediata requerida. ");
        } else if (rec.nivelUrgencia.equals("ALTO")) {
            sb.append("🔴 Compra urgente recomendada. ");
        } else if (rec.nivelUrgencia.equals("MEDIO")) {
            sb.append("🟡 Planificar compra esta semana. ");
        } else {
            sb.append("✅ Stock suficiente. ");
        }

        sb.append("Consumo promedio: ").append(consumo30d.toPlainString()).append(" ")
                .append(rec.unidadMedida).append(" / últimos 30 días. ");
        sb.append("Demanda proyectada 7 días: ").append(String.format("%.2f", demanda7d)).append(" ")
                .append(rec.unidadMedida).append(". ");

        if (rec.reservasProximas > 0) {
            sb.append("Se esperan ").append(rec.reservasProximas).append(" comensales por reservas. ");
        }
        if (rec.tasaMerma > 5) {
            sb.append(String.format("Factor merma del %.1f%% incluido en el cálculo. ", rec.tasaMerma));
        }
        if (rec.cantidadRecomendada.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("Comprar ").append(rec.cantidadRecomendada.toPlainString()).append(" ")
                    .append(rec.unidadMedida).append(" (costo estimado: $")
                    .append(rec.costoEstimado.toPlainString()).append(").");
        } else {
            sb.append("No se requiere compra en este momento.");
        }

        return sb.toString();
    }

    private boolean esPerecedero(Insumo insumo) {
        String n = insumo.getNombre() != null ? insumo.getNombre().toLowerCase() : "";
        String c = insumo.getCategoria() != null && insumo.getCategoria().getNombre() != null 
                   ? insumo.getCategoria().getNombre().toLowerCase() : "";
        return n.contains("carne") || n.contains("pollo") || n.contains("pescado") 
            || n.contains("salmon") || n.contains("leche") || n.contains("queso")
            || n.contains("tomate") || n.contains("cebolla") || n.contains("lechuga")
            || c.contains("lácteo") || c.contains("carne") || c.contains("verdura") 
            || c.contains("fruta") || c.contains("marisco") || c.contains("perecedero")
            || c.contains("lacteo");
    }

    private String generarSugerenciaMerma(String nombre, String cantidad, String unidad) {
        String n = nombre.toLowerCase();
        if (n.contains("leche") || n.contains("crema") || n.contains("queso") || n.contains("yogurt")) {
            return "Sugiere incluir postres, malteadas o platillos gratinados para aprovechar " + cantidad + " " + unidad + ".";
        } else if (n.contains("carne") || n.contains("hamburguesa") || n.contains("res") || n.contains("cerdo")) {
            return "Lanza una promoción 2x1 o un combo especial de parrilla para mover " + cantidad + " " + unidad + ".";
        } else if (n.contains("pollo")) {
            return "Considera agregar alitas, ensaladas con pollo o tacos dorados para reducir " + cantidad + " " + unidad + ".";
        } else if (n.contains("pescado") || n.contains("salmon") || n.contains("camaron") || n.contains("marisco")) {
            return "Ofrece un 'Especial del Mar' o ceviches como entrada para agotar " + cantidad + " " + unidad + ".";
        } else if (n.contains("salsa") || n.contains("tomate") || n.contains("cebolla") || n.contains("ajo") || n.contains("chile") || n.contains("pimiento")) {
            return "Útil para preparar salsas caseras, guisos o bases para pastas; aprovecha " + cantidad + " " + unidad + ".";
        } else if (n.contains("fresa") || n.contains("fruta") || n.contains("platano") || n.contains("limon") || n.contains("naranja")) {
            return "Ideal para coctelería, aguas frescas, smoothies o postres. Usa " + cantidad + " " + unidad + ".";
        } else if (n.contains("pan") || n.contains("tortilla") || n.contains("bolillo")) {
            return "Promociona sándwiches, tapas o chilaquiles para consumir " + cantidad + " " + unidad + ".";
        } else {
            return "Crea un 'Especial del Día' impulsado por el chef para mover " + cantidad + " " + unidad + " extra.";
        }
    }
}
