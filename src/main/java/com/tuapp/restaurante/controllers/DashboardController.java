package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.repositories.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
public class DashboardController {

    private final ProductoRepository productoRepository;
    private final InsumoRepository insumoRepository;
    private final MovimientoInventarioRepository movimientoRepository;
    private final VentaRepository ventaRepository;
    private final GastoRepository gastoRepository;
    private final com.tuapp.restaurante.services.PredictiveAnalysisService iaService;
    private final com.tuapp.restaurante.services.ConfiguracionService configuracionService;

    public DashboardController(ProductoRepository productoRepository, 
                               InsumoRepository insumoRepository, 
                               MovimientoInventarioRepository movimientoRepository, 
                               VentaRepository ventaRepository, 
                               GastoRepository gastoRepository,
                               com.tuapp.restaurante.services.PredictiveAnalysisService iaService,
                               com.tuapp.restaurante.services.ConfiguracionService configuracionService) {
        this.productoRepository = productoRepository;
        this.insumoRepository = insumoRepository;
        this.movimientoRepository = movimientoRepository;
        this.ventaRepository = ventaRepository;
        this.gastoRepository = gastoRepository;
        this.iaService = iaService;
        this.configuracionService = configuracionService;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        long totalProductos = productoRepository.countByActivoTrue();
        List<Insumo> insumosCriticos = insumoRepository.findInsumosConStockCritico();
        List<MovimientoInventario> ultimosMovimientos = movimientoRepository.findTop10ByOrderByFechaDesc();

        // Finanzas Generales
        BigDecimal totalVentas = ventaRepository.sumTotal();
        BigDecimal totalGastos = gastoRepository.sumMontoActivos();

        BigDecimal utilidad = totalVentas.subtract(totalGastos);

        // --- DATOS PARA GRÁFICOS ---
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime sieteDiasAtras = ahora.minusDays(7).withHour(0).withMinute(0).withSecond(0);

        // 1. Ventas últimos 7 días
        Map<LocalDate, BigDecimal> ventasPorDia = new TreeMap<>();
        ventaRepository.sumarPorDia(sieteDiasAtras, ahora)
                .forEach(row -> ventasPorDia.put(row.getDia(), row.getTotal()));
        
        List<String> labelsVentas = new ArrayList<>();
        List<BigDecimal> dataVentas = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM");
        
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            labelsVentas.add(d.format(fmt));
            dataVentas.add(ventasPorDia.getOrDefault(d, BigDecimal.ZERO));
        }

        // 2. Top 5 Productos (Basado en cantidad vendida)
        List<com.tuapp.restaurante.repositories.ProductQuantityProjection> topProductos =
                ventaRepository.cantidadesPorProducto(sieteDiasAtras, ahora);
        List<String> labelsTopProd = topProductos.stream()
                .sorted(Comparator.comparing(com.tuapp.restaurante.repositories.ProductQuantityProjection::getCantidad).reversed())
                .limit(5)
                .map(com.tuapp.restaurante.repositories.ProductQuantityProjection::getNombre)
                .toList();

        List<Long> dataTopProd = topProductos.stream()
                .sorted(Comparator.comparing(com.tuapp.restaurante.repositories.ProductQuantityProjection::getCantidad).reversed())
                .limit(5)
                .map(com.tuapp.restaurante.repositories.ProductQuantityProjection::getCantidad)
                .toList();

        // 3. Matriz de Ingeniería de Menú (Últimos 30 días para datos más estables)
        LocalDateTime treintaDiasAtras = LocalDateTime.now().minusDays(30).withHour(0).withMinute(0);
        List<com.tuapp.restaurante.repositories.ProductPerformanceProjection> ventas30d =
                ventaRepository.rendimientoProductos(treintaDiasAtras, LocalDateTime.now());

        long totalVendidos30d = ventas30d.stream().mapToLong(com.tuapp.restaurante.repositories.ProductPerformanceProjection::getCantidad).sum();
        double mediaPopularidad = ventas30d.isEmpty() ? 0 : (double) totalVendidos30d / ventas30d.size();

        List<Map<String, Object>> ingenieriaMenu = new ArrayList<>();
        BigDecimal gananciaTotal = BigDecimal.ZERO;

        for (com.tuapp.restaurante.repositories.ProductPerformanceProjection row : ventas30d) {
            Long cantidad = row.getCantidad();
            BigDecimal costoProduccion = row.getCosto();
            BigDecimal margen = row.getPrecio().subtract(costoProduccion);
            gananciaTotal = gananciaTotal.add(margen.multiply(BigDecimal.valueOf(cantidad)));
            
            Map<String, Object> itemData = new HashMap<>();
            itemData.put("nombre", row.getNombre());
            itemData.put("popularidad", cantidad);
            itemData.put("margen", margen);
            itemData.put("costo", costoProduccion);
            itemData.put("precio", row.getPrecio());
            ingenieriaMenu.add(itemData);
        }

        double mediaRentabilidad = totalVendidos30d == 0 ? 0 : gananciaTotal.doubleValue() / totalVendidos30d;

        // Clasificar según la matriz de Boston (BCG) adaptada a Menú
        for (Map<String, Object> item : ingenieriaMenu) {
            double pop = ((Long) item.get("popularidad")).doubleValue();
            double marg = ((BigDecimal) item.get("margen")).doubleValue();
            
            String categoria = "";
            if (pop >= mediaPopularidad && marg >= mediaRentabilidad) categoria = "Estrella";
            else if (pop >= mediaPopularidad && marg < mediaRentabilidad) categoria = "Caballo de Batalla";
            else if (pop < mediaPopularidad && marg >= mediaRentabilidad) categoria = "Rompecabezas";
            else categoria = "Perro";
            
            item.put("categoriaBCG", categoria);
        }
        
        // Ordenar estrellas primero
        ingenieriaMenu.sort((a, b) -> {
            String catA = (String) a.get("categoriaBCG");
            if (catA.equals("Estrella")) return -1;
            return 1; // Simplificado para que las estrellas suban
        });

        // 4. Datos del Módulo IA (Ventro Intelligence)
        // Obtenemos solo el resumen de alertas críticas generadas por la IA
        com.tuapp.restaurante.services.PredictiveAnalysisService.ResumenPrediccion diagnosticoIA = iaService.generarDiagnostico();
        long alertasCriticasIA = diagnosticoIA.recomendaciones.stream()
                                  .filter(r -> "CRITICO".equals(r.nivelUrgencia) || "ALTO".equals(r.nivelUrgencia))
                                  .count();
 
        com.tuapp.restaurante.services.PredictiveAnalysisService.PronosticoVentas pronosticoVentas = iaService.predecirVentas7Dias();

        // Atributos base
        model.addAttribute("totalProductos", totalProductos);
        model.addAttribute("insumosCriticos", insumosCriticos);
        model.addAttribute("ultimosMovimientos", ultimosMovimientos);
        model.addAttribute("totalVentas", totalVentas);
        model.addAttribute("totalGastos", totalGastos);
        model.addAttribute("utilidad", utilidad);

        // Atributos Gráficos y Avanzados
        model.addAttribute("chartLabelsVentas", labelsVentas);
        model.addAttribute("chartDataVentas", dataVentas);
        model.addAttribute("chartLabelsTopProd", labelsTopProd);
        model.addAttribute("chartDataTopProd", dataTopProd);
        model.addAttribute("ingenieriaMenu", ingenieriaMenu);
        model.addAttribute("alertasCriticasIA", alertasCriticasIA);
        model.addAttribute("confianzaIA", diagnosticoIA.confianzaGlobal);
        model.addAttribute("alertasMermas", diagnosticoIA.alertasMermas);
        model.addAttribute("pronosticoVentas", pronosticoVentas);
        model.addAttribute("totalEsperadoIA", pronosticoVentas.totalEsperado);
        model.addAttribute("config", configuracionService.obtener());

        return "dashboard";
    }
}
