package com.tuapp.restaurante.services;

import com.tuapp.restaurante.inventory.BaseUnit;
import com.tuapp.restaurante.inventory.InventoryChangeApplier;
import com.tuapp.restaurante.inventory.InventoryWritePolicy;
import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.security.AccessPolicy;
import org.springframework.security.access.AccessDeniedException;
import com.tuapp.restaurante.repositories.InsumoRepository;
import com.tuapp.restaurante.repositories.MovimientoInventarioRepository;
import com.tuapp.restaurante.repositories.UsuarioRepository;
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
public class ProduccionService {
    private final InsumoRepository insumos;
    private final MovimientoInventarioRepository movimientos;
    private final AuditoriaService auditoria;
    private final UsuarioRepository usuarios;
    private final InventoryChangeApplier inventoryApplier;
    private final JdbcTemplate db;
    private final AccessPolicy access;

    public ProduccionService(InsumoRepository insumos, MovimientoInventarioRepository movimientos,
                             AuditoriaService auditoria, UsuarioRepository usuarios,
                             InventoryChangeApplier inventoryApplier, JdbcTemplate db, AccessPolicy access) {
        this.access = access;
        this.insumos = insumos;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.usuarios = usuarios;
        this.inventoryApplier = inventoryApplier;
        this.db = db;
    }

    private record IngredientLine(Long insumoId, BigDecimal amountPerUnit) {}

    /** Compatibilidad de callers legacy: no ofrece reintentos sin una clave estable. */
    @Transactional
    public void producirInsumo(Long insumoDestinoId, BigDecimal cantidadAProducir, Long usuarioId) {
        produce(insumoDestinoId, cantidadAProducir, usuarioId, null);
    }

    /** La clave identifica una orden lógica y hace idempotente también su parte legacy. */
    @Transactional
    public void producirInsumo(Long insumoDestinoId, BigDecimal cantidadAProducir,
                               Long usuarioId, UUID requestId) {
        if (requestId == null) throw new IllegalArgumentException("Identificador de producción obligatorio.");
        produce(insumoDestinoId, cantidadAProducir, usuarioId, requestId);
    }

    private void produce(Long destinationId, BigDecimal quantity, Long actorId, UUID requestId) {
        Usuario actor = access.requireManagement();
        if (!actor.getId().equals(actorId)) {
            throw new AccessDeniedException("El actor no coincide con la sesión");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad a producir debe ser mayor a cero.");
        }
        if (requestId != null) {
            String hash = fingerprint(destinationId, quantity, actorId);
            int inserted = db.update("insert into production_request(request_id,request_hash) " +
                    "values (?,?) on conflict (request_id) do nothing", requestId, hash);
            if (inserted == 0) {
                String previous = db.queryForObject("select request_hash from production_request where request_id=?",
                        String.class, requestId);
                if (!hash.equals(previous)) {
                    throw new IllegalArgumentException("El identificador ya corresponde a otra producción.");
                }
                return;
            }
        }

        List<IngredientLine> lines = db.query("select ingrediente_id,cantidad from sub_receta " +
                "where insumo_destino_id=? order by id",
                (rs, row) -> new IngredientLine(rs.getLong(1), rs.getBigDecimal(2)), destinationId);
        Map<Long, BigDecimal> required = new HashMap<>();
        for (IngredientLine line : lines) {
            required.merge(line.insumoId(), line.amountPerUnit().multiply(quantity), BigDecimal::add);
        }
        List<Long> ids = new ArrayList<>(required.keySet());
        if (!ids.contains(destinationId)) ids.add(destinationId);
        ids.sort(Long::compareTo);
        Map<Long, Insumo> locked = new HashMap<>();
        Map<Long, InventoryWritePolicy.Mode> modes = new HashMap<>();
        for (Long id : ids) {
            Insumo supply = insumos.lockById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado."));
            locked.put(id, supply);
            modes.put(id, InventoryWritePolicy.resolve(supply.getEstadoInventario5a()));
        }
        Insumo destination = locked.get(destinationId);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Este insumo no tiene una sub-receta configurada para ser producido.");
        }
        if (requestId == null && modes.containsValue(InventoryWritePolicy.Mode.APPLIER)) {
            throw new IllegalArgumentException("Identificador de producción obligatorio para insumos activos.");
        }

        for (Long id : required.keySet().stream().sorted().toList()) {
            Insumo ingredient = locked.get(id);
            BigDecimal amount = required.get(id);
            if (ingredient.getStockActual().compareTo(amount) < 0) {
                throw new IllegalArgumentException("Stock insuficiente de '" + ingredient.getNombre() +
                        "'. Se requieren " + amount + " " + ingredient.getUnidadMedida() +
                        " pero hay " + ingredient.getStockActual());
            }
        }

        // El costo sigue sumando cada renglón sin redondeo intermedio, incluso si
        // el aplicador agrupa varias líneas del mismo insumo para un solo movimiento.
        BigDecimal totalCost = BigDecimal.ZERO;
        for (IngredientLine line : lines) {
            Insumo ingredient = locked.get(line.insumoId());
            totalCost = totalCost.add(ingredient.getCostoUnitario()
                    .multiply(line.amountPerUnit().multiply(quantity)));
        }
        BigDecimal unitCost = totalCost.divide(quantity, 2, RoundingMode.HALF_UP);

        List<InventoryChangeApplier.Command> activeExits = new ArrayList<>();
        for (Long id : required.keySet().stream().sorted().toList()) {
            if (modes.get(id) == InventoryWritePolicy.Mode.APPLIER) {
                activeExits.add(command(requestId, id, InventoryChangeApplier.Type.SALIDA_PRODUCCION,
                        required.get(id), locked.get(id), actorId,
                        "Transformación para producir " + quantity + " " +
                                destination.getUnidadMedida() + " de " + destination.getNombre()));
            }
        }
        if (!activeExits.isEmpty()) inventoryApplier.applyAll(activeExits);

        // El camino legacy conserva un movimiento por renglón y la coerción
        // NUMERIC(10,2) previa; no se sustituye por el aplicador.
        Map<Long, BigDecimal> legacyBalances = new HashMap<>();
        for (IngredientLine line : lines) {
            if (modes.get(line.insumoId()) != InventoryWritePolicy.Mode.LEGACY) continue;
            Insumo ingredient = locked.get(line.insumoId());
            BigDecimal amount = line.amountPerUnit().multiply(quantity);
            BigDecimal next = legacyBalances.getOrDefault(line.insumoId(), ingredient.getStockActual())
                    .subtract(amount);
            legacyBalances.put(line.insumoId(), next);
            ingredient.setStockActual(LegacyInventoryPrecision.forStorage(next));
            insumos.save(ingredient);
            legacyMovement(ingredient, actor, "SALIDA_PRODUCCION",
                    LegacyInventoryPrecision.forStorage(amount),
                    "Transformación para producir " + quantity + " " +
                            destination.getUnidadMedida() + " de " + destination.getNombre());
        }

        if (modes.get(destinationId) == InventoryWritePolicy.Mode.APPLIER) {
            inventoryApplier.apply(command(requestId, destinationId,
                    InventoryChangeApplier.Type.ENTRADA_PRODUCCION, quantity,
                    destination, actorId, "Producción interna finalizada."));
        } else {
            BigDecimal next = legacyBalances.getOrDefault(destinationId, destination.getStockActual())
                    .add(quantity);
            destination.setStockActual(LegacyInventoryPrecision.forStorage(next));
            legacyMovement(destination, actor, "ENTRADA_PRODUCCION",
                    LegacyInventoryPrecision.forStorage(quantity), "Producción interna finalizada.");
        }
        destination.setCostoUnitario(unitCost);
        insumos.save(destination);
        auditoria.registrarLog("INVENTARIO", "PRODUCCION",
                "Se produjeron " + quantity + " " + destination.getUnidadMedida() +
                        " de " + destination.getNombre());
    }

    private InventoryChangeApplier.Command command(UUID requestId, Long insumoId,
                                                    InventoryChangeApplier.Type type, BigDecimal quantity,
                                                    Insumo supply, Long actorId, String comment) {
        String direction = type == InventoryChangeApplier.Type.ENTRADA_PRODUCCION ? "ENTRADA" : "SALIDA";
        UUID operationId = UUID.nameUUIDFromBytes(("PRODUCCION|" + requestId + "|" + insumoId +
                "|" + direction).getBytes(StandardCharsets.UTF_8));
        return new InventoryChangeApplier.Command(operationId, insumoId, type, quantity,
                BaseUnit.require(supply.getUnidadBaseFamilia(), supply.getUnidadBaseCodigo()), actorId,
                "PRODUCCION", "PRODUCCION:" + requestId, comment);
    }

    private void legacyMovement(Insumo supply, Usuario actor, String type,
                                BigDecimal quantity, String comment) {
        MovimientoInventario movement = new MovimientoInventario();
        movement.setInsumo(supply);
        movement.setUsuario(actor);
        movement.setTipoMovimiento(type);
        movement.setCantidad(quantity);
        movement.setFecha(LocalDateTime.now());
        movement.setComentario(comment);
        movimientos.save(movement);
    }

    private String fingerprint(Long destinationId, BigDecimal quantity, Long actorId) {
        try {
            String payload = destinationId + "|" + quantity.stripTrailingZeros().toPlainString() + "|" + actorId;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
