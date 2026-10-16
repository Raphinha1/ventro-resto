package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.inventory.InventoryWritePolicy;
import com.tuapp.restaurante.repositories.InsumoRepository;
import com.tuapp.restaurante.repositories.MovimientoInventarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class MovimientoInventarioService {

    private final MovimientoInventarioRepository movimientoRepository;
    private final InsumoRepository insumoRepository;
    private final NotificacionService notificacionService;
    private final ConfiguracionService configuracionService;

    public MovimientoInventarioService(MovimientoInventarioRepository movimientoRepository, 
                                     InsumoRepository insumoRepository,
                                     NotificacionService notificacionService,
                                     ConfiguracionService configuracionService) {
        this.movimientoRepository = movimientoRepository;
        this.insumoRepository = insumoRepository;
        this.notificacionService = notificacionService;
        this.configuracionService = configuracionService;
    }

    @Transactional(readOnly = true)
    public List<MovimientoInventario> listarTodos() {
        return movimientoRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<MovimientoInventario> listarPorInsumo(Long insumoId) {
        return movimientoRepository.findByInsumoIdOrderByFechaDesc(insumoId);
    }

    @Transactional
    public MovimientoInventario registrarMovimiento(MovimientoInventario movimiento) {
        if (movimiento.getId() != null || movimiento.getOperationId() != null ||
                movimiento.getOperationFingerprint() != null || movimiento.getCausalOrigin() != null ||
                movimiento.getCausalReference() != null || movimiento.getResultingStock() != null) {
            throw new IllegalArgumentException("El registro manual solo admite movimientos legacy nuevos.");
        }
        if (movimiento.getInsumo() == null || movimiento.getInsumo().getId() == null ||
                movimiento.getCantidad() == null || movimiento.getTipoMovimiento() == null ||
                (!"AJUSTE".equalsIgnoreCase(movimiento.getTipoMovimiento()) &&
                        movimiento.getCantidad().signum() <= 0)) {
            throw new IllegalArgumentException("Movimiento o cantidad inválida.");
        }
        if (!List.of("ENTRADA", "SALIDA", "MERMA", "AJUSTE")
                .contains(movimiento.getTipoMovimiento().toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Tipo de movimiento inválido.");
        }
        try {
            movimiento.setCantidad(movimiento.getCantidad().setScale(2, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("La cantidad de inventario admite dos decimales.");
        }
        if (movimiento.getCantidad().signum() == 0 ||
                movimiento.getCantidad().abs().compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("Cantidad de inventario inválida.");
        }
        Insumo insumo = insumoRepository.lockById(movimiento.getInsumo().getId())
                .orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
        InventoryWritePolicy.requireLegacy(insumo.getEstadoInventario5a());

        // Se actualiza el stock dependiendo del tipo de movimiento
        if ("ENTRADA".equalsIgnoreCase(movimiento.getTipoMovimiento())) {
            insumo.setStockActual(insumo.getStockActual().add(movimiento.getCantidad()));
        } else if ("SALIDA".equalsIgnoreCase(movimiento.getTipoMovimiento()) || "MERMA".equalsIgnoreCase(movimiento.getTipoMovimiento())) {
            // Validar que haya suficiente stock para una salida o merma
            if (insumo.getStockActual().compareTo(movimiento.getCantidad()) < 0) {
                String tipo = "MERMA".equalsIgnoreCase(movimiento.getTipoMovimiento()) ? "la merma" : "la salida";
                throw new IllegalArgumentException("No hay suficiente inventario de " + insumo.getNombre() + 
                    " para registrar " + tipo + ". Stock actual: " + 
                    insumo.getStockActual() + " " + insumo.getUnidadMedida());
            }
            insumo.setStockActual(insumo.getStockActual().subtract(movimiento.getCantidad()));
        } else if ("AJUSTE".equalsIgnoreCase(movimiento.getTipoMovimiento())) {
            // Un ajuste positivo o negativo. Si el resultado es negativo, Hibernate lanzará error por @Min(0)
            // pero podemos validarlo aquí también para un mensaje más limpio
            BigDecimal nuevoStock = insumo.getStockActual().add(movimiento.getCantidad());
            if (nuevoStock.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("El ajuste resultaría en un stock negativo (" + nuevoStock + "). Verifique la cantidad.");
            }
            insumo.setStockActual(nuevoStock);
        }

        // V9 amplió el typmod SQL: la frontera legacy conserva también el
        // rango del saldo resultante que antes imponía NUMERIC(10,2).
        insumo.setStockActual(LegacyInventoryPrecision.forStorage(insumo.getStockActual()));
        insumoRepository.save(insumo); 

        // Lógica de Alerta de Stock Bajo
        if (insumo.getStockActual().compareTo(insumo.getStockMinimo()) < 0) {
            String tel = configuracionService.obtener().getTelefono();
            if (tel != null && !tel.isEmpty()) {
                String mensaje = "⚠️ *ALERTA DE STOCK BAJO*\n\n" +
                                "El insumo *" + insumo.getNombre() + "* ha bajado de su nivel mínimo.\n" +
                                "Stock actual: " + insumo.getStockActual() + " " + insumo.getUnidadMedida() + "\n" +
                                "Stock mínimo: " + insumo.getStockMinimo() + " " + insumo.getUnidadMedida();
                notificacionService.enviarAlertaWhatsApp(tel, mensaje);
            }
        }

        return movimientoRepository.save(movimiento); 
    }
}
