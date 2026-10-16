package com.tuapp.restaurante.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.restaurante.inventory.BaseUnit;
import com.tuapp.restaurante.inventory.InventoryChangeApplier;
import com.tuapp.restaurante.inventory.InventoryWritePolicy;
import com.tuapp.restaurante.models.*;
import com.tuapp.restaurante.repositories.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VentaService {
    private static final BigDecimal MAX_PAYMENT = new BigDecimal("99999999.99");

    private final ProductoRepository productos;
    private final InsumoRepository insumos;
    private final MovimientoInventarioRepository movimientos;
    private final VentaRepository ventas;
    private final UsuarioRepository usuarios;
    private final AuditoriaService auditoria;
    private final MesaRepository mesas;
    private final ClienteRepository clientes;
    private final CorteCajaRepository cortesCaja;
    private final InventoryChangeApplier inventoryApplier;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public VentaService(ProductoRepository productos, InsumoRepository insumos,
                        MovimientoInventarioRepository movimientos, VentaRepository ventas,
                        UsuarioRepository usuarios, AuditoriaService auditoria,
                        MesaRepository mesas, ClienteRepository clientes, CorteCajaRepository cortesCaja,
                        InventoryChangeApplier inventoryApplier, JdbcTemplate db, ObjectMapper json) {
        this.productos = productos;
        this.insumos = insumos;
        this.movimientos = movimientos;
        this.ventas = ventas;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.mesas = mesas;
        this.clientes = clientes;
        this.cortesCaja = cortesCaja;
        this.inventoryApplier = inventoryApplier;
        this.db = db;
        this.json = json;
    }

    private record Line(Producto producto, int quantity, String note, boolean redeemed, BigDecimal subtotal) {}
    private record Usage(Long insumoId, BigDecimal amount, String comment) {}
    private record Payload(List<Long> products, List<Integer> quantities, List<String> notes,
                           List<Boolean> redeemed, Long userId, String method,
                           BigDecimal cash, BigDecimal card, Long tableId, Long clientId) {}

    @Transactional
    public Venta procesarVentasMultiples(List<Long> productoIds, List<Integer> cantidades,
                                         List<String> notas, Long usuarioId, String metodoPago,
                                         BigDecimal montoEfectivo, BigDecimal montoTarjeta,
                                         Long mesaId, Long clienteId, List<Boolean> esCanjeado,
                                         String idempotencyKey) {
        if (productoIds == null || productoIds.isEmpty() || cantidades == null ||
                productoIds.size() != cantidades.size()) {
            throw new IllegalArgumentException("Productos y cantidades inválidos.");
        }
        if (notas != null && notas.size() != productoIds.size()) {
            throw new IllegalArgumentException("Las notas no coinciden con los productos.");
        }
        if (esCanjeado != null && esCanjeado.size() != productoIds.size()) {
            throw new IllegalArgumentException("Los canjes no coinciden con los productos.");
        }
        if (idempotencyKey == null) throw new IllegalArgumentException("Clave de idempotencia obligatoria.");
        UUID key;
        try { key = UUID.fromString(idempotencyKey); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Clave de idempotencia inválida."); }

        List<String> lineNotes = new ArrayList<>();
        List<Boolean> redeemed = new ArrayList<>();
        for (int i = 0; i < productoIds.size(); i++) {
            if (productoIds.get(i) == null || cantidades.get(i) == null || cantidades.get(i) <= 0) {
                throw new IllegalArgumentException("Cada producto requiere una cantidad positiva.");
            }
            String note = notas == null || notas.get(i) == null ? "" : notas.get(i);
            if (note.length() > 255) throw new IllegalArgumentException("La nota supera 255 caracteres.");
            lineNotes.add(note);
            redeemed.add(esCanjeado != null && Boolean.TRUE.equals(esCanjeado.get(i)));
        }
        BigDecimal cash = money(montoEfectivo == null ? BigDecimal.ZERO : montoEfectivo, "Efectivo");
        BigDecimal card = money(montoTarjeta == null ? BigDecimal.ZERO : montoTarjeta, "Tarjeta");
        String method = metodoPago == null ? "EFECTIVO" : metodoPago;
        String fingerprint = fingerprint(new Payload(productoIds, cantidades, lineNotes, redeemed,
                usuarioId, method, cash, card, mesaId, clienteId));
        int inserted = db.update("insert into pos_sale_request(idempotency_key,request_hash) " +
                "values (?,?) on conflict (idempotency_key) do nothing", key, fingerprint);
        if (inserted == 0) {
            return db.queryForObject("select request_hash, venta_id from pos_sale_request where idempotency_key=?",
                    (rs, row) -> {
                        if (!fingerprint.equals(rs.getString(1))) {
                            throw new IllegalArgumentException("La clave de idempotencia ya corresponde a otra venta.");
                        }
                        long saleId = rs.getLong(2);
                        if (rs.wasNull()) throw new IllegalStateException("Venta idempotente sin resultado.");
                        return ventas.findById(saleId).orElseThrow();
                    }, key);
        }

        Usuario user = usuarios.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));
        Mesa mesa = mesaId == null ? null : mesas.findById(mesaId)
                .orElseThrow(() -> new IllegalArgumentException("Mesa no encontrada."));
        Cliente cliente = clienteId == null ? null : clientes.lockById(clienteId)
                .filter(Cliente::isActivo)
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado o inactivo."));

        List<Line> lines = new ArrayList<>();
        List<Usage> uses = new ArrayList<>();
        Map<Long, BigDecimal> required = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int earned = 0;
        int spent = 0;
        for (int n = 0; n < productoIds.size(); n++) {
            int qty = cantidades.get(n);
            Producto product = productos.findById(productoIds.get(n))
                    .filter(Producto::isActivo)
                    .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado o inactivo."));
            BigDecimal price = money(product.getPrecioVenta(), "Precio del producto");
            if (product.getPuntosOtorgados() == null || product.getPuntosOtorgados() < 0 ||
                    product.getPuntosPrecio() == null || product.getPuntosPrecio() < 0) {
                throw new IllegalArgumentException("Puntos inválidos en producto.");
            }
            boolean isRedeemed = redeemed.get(n);
            BigDecimal subtotal = isRedeemed ? BigDecimal.ZERO : price.multiply(BigDecimal.valueOf(qty));
            total = total.add(subtotal);
            try {
                if (isRedeemed) {
                    if (product.getPuntosPrecio() == 0) {
                        throw new IllegalArgumentException("El producto no es canjeable por puntos.");
                    }
                    spent = Math.addExact(spent, Math.multiplyExact(product.getPuntosPrecio(), qty));
                } else {
                    earned = Math.addExact(earned, Math.multiplyExact(product.getPuntosOtorgados(), qty));
                }
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("La cantidad de puntos excede el límite.");
            }
            lines.add(new Line(product, qty, lineNotes.get(n), isRedeemed, subtotal));
            if (product.isEsReventa()) {
                if (product.getInsumoReventa() == null) {
                    throw new IllegalArgumentException("Producto de reventa sin insumo vinculado.");
                }
                addUsage(required, uses, product.getInsumoReventa().getId(), BigDecimal.valueOf(qty),
                        "Reventa: " + qty + "x " + product.getNombre());
            } else {
                if (product.getIngredientes() == null || product.getIngredientes().isEmpty()) {
                    throw new IllegalArgumentException("Producto sin receta ni insumo de reventa.");
                }
                for (Receta recipe : product.getIngredientes()) {
                    if (recipe.getInsumo() == null || recipe.getCantidad() == null ||
                            recipe.getCantidad().signum() <= 0) {
                        throw new IllegalArgumentException("Receta o insumo inválido.");
                    }
                    addUsage(required, uses, recipe.getInsumo().getId(),
                            recipe.getCantidad().multiply(BigDecimal.valueOf(qty)),
                            "Venta ticket: " + qty + "x " + product.getNombre());
                }
            }
        }
        validatePayment(method, cash, card, total);
        // La misma fila se bloquea al cerrar: o se confirma esta venta dentro del
        // turno todavía abierto, o el cierre gana y el cobro se rechaza sin efectos.
        CorteCaja turno = total.signum() > 0 ? cortesCaja.lockOpenShift()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Abre un turno de caja antes de cobrar esta venta.")) : null;
        if (spent > 0 && cliente == null) throw new IllegalArgumentException("Debe seleccionar un cliente para realizar canjes.");
        if (cliente != null) {
            if (cliente.getPuntos() == null || cliente.getPuntos() < spent) {
                throw new IllegalArgumentException("El cliente no tiene puntos suficientes.");
            }
            try { cliente.setPuntos(Math.addExact(Math.subtractExact(cliente.getPuntos(), spent), earned)); }
            catch (ArithmeticException ex) { throw new IllegalArgumentException("Saldo de puntos fuera de rango."); }
            cliente.setTotalGastado(cliente.getTotalGastado().add(total));
        }

        Map<Long, Insumo> locked = new HashMap<>();
        Map<Long, InventoryWritePolicy.Mode> writeModes = new HashMap<>();
        for (Long id : required.keySet().stream().sorted().toList()) {
            Insumo supply = insumos.lockById(id)
                    .filter(Insumo::isActivo)
                    .orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado o inactivo."));
            writeModes.put(id, InventoryWritePolicy.resolve(supply.getEstadoInventario5a()));
            if (supply.getStockActual().compareTo(required.get(id)) < 0) {
                throw new IllegalArgumentException("Stock insuficiente de: " + supply.getNombre());
            }
            locked.put(id, supply);
        }
        List<Usage> legacyUses = new ArrayList<>();
        for (Usage usage : uses) {
            if (writeModes.get(usage.insumoId()) == InventoryWritePolicy.Mode.LEGACY) {
                legacyUses.add(new Usage(usage.insumoId(), legacyQuantity(usage.amount()), usage.comment()));
            }
        }

        Venta sale = new Venta();
        sale.setFecha(LocalDateTime.now());
        sale.setUsuario(user);
        sale.setMesa(mesa);
        sale.setCliente(cliente);
        sale.setCorteCaja(turno);
        sale.setTotal(total);
        sale.setMetodoPago(method);
        sale.setMontoEfectivo(cash);
        sale.setMontoTarjeta(card);
        sale.setCambio(cash.add(card).subtract(total));
        sale.setPuntosGanados(earned);
        sale.setPuntosCanjeados(spent);
        sale.setDetalles(new ArrayList<>());
        for (Line line : lines) {
            DetalleVenta detail = new DetalleVenta();
            detail.setVenta(sale);
            detail.setProducto(line.producto());
            detail.setCantidad(line.quantity());
            detail.setCanjeado(line.redeemed());
            detail.setPrecioUnidad(line.redeemed() ? BigDecimal.ZERO : line.producto().getPrecioVenta());
            detail.setSubtotal(line.subtotal());
            detail.setNotas(line.note());
            sale.getDetalles().add(detail);
        }
        // El ID de la venta es la referencia causal persistente del movimiento 5A.
        // Sigue en esta misma transacción; cualquier fallo posterior revierte también la venta.
        Venta saved = writeModes.containsValue(InventoryWritePolicy.Mode.APPLIER)
                ? ventas.saveAndFlush(sale) : null;
        List<InventoryChangeApplier.Command> activeChanges = new ArrayList<>();
        for (Long id : required.keySet().stream().sorted().toList()) {
            if (writeModes.get(id) == InventoryWritePolicy.Mode.APPLIER) {
                Insumo supply = locked.get(id);
                BaseUnit unit = BaseUnit.require(supply.getUnidadBaseFamilia(), supply.getUnidadBaseCodigo());
                // La clave identifica este efecto de venta de forma determinista en los reintentos.
                UUID operationId = UUID.nameUUIDFromBytes(("VENTA_POS|" + key + "|" + id)
                        .getBytes(StandardCharsets.UTF_8));
                activeChanges.add(new InventoryChangeApplier.Command(operationId, id,
                        InventoryChangeApplier.Type.SALIDA_VENTA, required.get(id), unit, user.getId(),
                        "VENTA_POS", "VENTA:" + saved.getId(), "Consumo agregado por venta POS"));
            }
        }
        if (!activeChanges.isEmpty()) {
            inventoryApplier.applyAll(activeChanges);
            for (InventoryChangeApplier.Command change : activeChanges) {
                logCriticalStock(locked.get(change.insumoId()));
            }
        }
        for (Usage usage : legacyUses) {
            Insumo supply = locked.get(usage.insumoId());
            supply.setStockActual(supply.getStockActual().subtract(usage.amount()));
            MovimientoInventario movement = new MovimientoInventario();
            movement.setInsumo(supply);
            movement.setUsuario(user);
            movement.setTipoMovimiento("SALIDA_VENTA");
            movement.setCantidad(usage.amount());
            movement.setFecha(LocalDateTime.now());
            movement.setComentario(usage.comment());
            movimientos.save(movement);
            logCriticalStock(supply);
        }
        if (cliente != null) clientes.save(cliente);
        if (total.compareTo(new BigDecimal("1000")) > 0) {
            auditoria.registrarLog("POS", "VENTA_ALTO_VALOR",
                    "Venta de alto valor registrada: $" + total + ". Cajero: " + user.getUsername());
        }
        if (saved == null) saved = ventas.saveAndFlush(sale);
        db.update("update pos_sale_request set venta_id=? where idempotency_key=?", saved.getId(), key);
        if (mesa != null) {
            mesa.setEstado("SUCIA");
            mesa.setHoraApertura(null);
            mesa.setMozo(null);
            mesa.setVentaActual(null);
            mesas.save(mesa);
        }
        return saved;
    }

    private void addUsage(Map<Long, BigDecimal> required, List<Usage> uses,
                          Long id, BigDecimal quantity, String comment) {
        if (id == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Cantidad de receta incompatible con el stock a dos decimales.");
        }
        required.merge(id, quantity, BigDecimal::add);
        uses.add(new Usage(id, quantity, comment));
    }

    private BigDecimal legacyQuantity(BigDecimal quantity) {
        try { return quantity.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Cantidad de receta incompatible con el stock a dos decimales.");
        }
    }

    private void logCriticalStock(Insumo supply) {
        if (supply.getStockActual().compareTo(supply.getStockMinimo()) <= 0) {
            auditoria.registrarLog("INVENTARIO", "STOCK_CRITICO",
                    "El insumo '" + supply.getNombre() + "' llegó a stock crítico: " + supply.getStockActual());
        }
    }

    private BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_PAYMENT) > 0) {
            throw new IllegalArgumentException(label + " inválido.");
        }
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(label + " debe tener dos decimales."); }
    }

    private void validatePayment(String method, BigDecimal cash, BigDecimal card, BigDecimal total) {
        boolean valid = switch (method) {
            case "EFECTIVO" -> card.signum() == 0 && cash.compareTo(total) >= 0;
            case "TARJETA" -> cash.signum() == 0 && card.compareTo(total) == 0;
            case "MIXTO" -> card.signum() > 0 && card.compareTo(total) < 0 &&
                    cash.signum() > 0 && cash.add(card).compareTo(total) >= 0;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Método de pago o importe insuficiente/inválido.");
    }

    private String fingerprint(Payload payload) {
        try {
            byte[] encoded = json.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("No se pudo calcular la huella de la venta.", ex);
        }
    }
}
