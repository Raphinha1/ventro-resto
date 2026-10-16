package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.repositories.InsumoRepository;
import com.tuapp.restaurante.repositories.MovimientoInventarioRepository;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Aplicador causal para insumos ACTIVO_5A; participa en la transacción del caller. */
@Service
public class InventoryChangeApplier {
    private final JdbcTemplate db;
    private final InsumoRepository insumos;
    private final MovimientoInventarioRepository movimientos;
    private final UsuarioRepository usuarios;

    public InventoryChangeApplier(JdbcTemplate db, InsumoRepository insumos,
                                  MovimientoInventarioRepository movimientos, UsuarioRepository usuarios) {
        this.db = db;
        this.insumos = insumos;
        this.movimientos = movimientos;
        this.usuarios = usuarios;
    }

    public enum Type {
        ENTRADA(true), SALIDA(false), MERMA(false), SALIDA_VENTA(false),
        ENTRADA_PRODUCCION(true), SALIDA_PRODUCCION(false);

        private final boolean incoming;
        Type(boolean incoming) { this.incoming = incoming; }
    }

    /** Cantidad positiva y tipo causal; nunca un stock absoluto ni un AJUSTE genérico. */
    public record Command(UUID operationId, Long insumoId, Type type, BigDecimal quantity,
                          BaseUnit unit, Long actorId, String causalOrigin,
                          String causalReference, String comment) {
        public Command {
            Objects.requireNonNull(operationId, "operationId obligatorio");
            if (insumoId == null || insumoId <= 0) throw new IllegalArgumentException("Insumo obligatorio");
            Objects.requireNonNull(type, "Tipo causal obligatorio");
            if (actorId == null || actorId <= 0) throw new IllegalArgumentException("Actor obligatorio");
            if (causalOrigin == null || causalOrigin.isBlank() || causalOrigin.length() > 100) {
                throw new IllegalArgumentException("Origen causal inválido");
            }
            if (causalReference == null || causalReference.isBlank() || causalReference.length() > 200) {
                throw new IllegalArgumentException("Referencia causal inválida");
            }
        }
    }

    /** resultingStock es un dato de auditoría del movimiento, no otro saldo autoritativo. */
    public record Result(Long movementId, Long insumoId, BigDecimal resultingStock) {}

    @Transactional
    public Result apply(Command command) {
        return applyInsideTransaction(command, true);
    }

    /** Los comandos de varios insumos adquieren sus bloqueos en orden de ID. */
    @Transactional
    public List<Result> applyAll(List<Command> commands) {
        Objects.requireNonNull(commands, "Comandos obligatorios");
        List<IndexedCommand> ordered = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            ordered.add(new IndexedCommand(i, Objects.requireNonNull(commands.get(i), "Comando obligatorio")));
        }
        ordered.sort(Comparator.comparing((IndexedCommand item) -> item.command().insumoId())
                .thenComparing(item -> item.command().operationId())
                .thenComparingInt(IndexedCommand::index));
        // Reservar todas las claves idempotentes antes de tomar filas evita que
        // lotes concurrentes esperen una clave mientras retienen otro insumo.
        reserveOperations(ordered.stream().map(item -> item.command().operationId()).toList());
        Result[] results = new Result[commands.size()];
        for (IndexedCommand item : ordered) {
            results[item.index()] = applyInsideTransaction(item.command(), false);
        }
        return List.of(results);
    }

    private record IndexedCommand(int index, Command command) {}

    /** Para callers que necesitan validar/persistir antes de aplicar: claves antes de filas. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveOperations(List<UUID> operationIds) {
        Objects.requireNonNull(operationIds, "Identidades obligatorias");
        operationIds.stream().map(id -> operationLockKey(Objects.requireNonNull(id)))
                .distinct().sorted().forEach(this::lockOperationKey);
    }

    private Result applyInsideTransaction(Command command, boolean acquireOperationLock) {
        Objects.requireNonNull(command, "Comando obligatorio");
        BigDecimal quantity = InventoryNumbers.base(command.quantity());
        if (quantity.signum() == 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
        String fingerprint = fingerprint(command, quantity);

        // Serializa reintentos incluso si el mismo UUID apunta a otro insumo.
        if (acquireOperationLock) lockOperation(command.operationId());
        // PESSIMISTIC_WRITE bloquea solo este insumo antes de leer stock; al usar
        // la entidad administrada no dejamos desincronizado el contexto JPA del caller.
        Insumo insumo = insumos.lockById(command.insumoId())
                .orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
        InventoryWritePolicy.requireApplier(insumo.getEstadoInventario5a());
        var existing = movimientos.findByOperationId(command.operationId());
        if (existing.isPresent()) {
            MovimientoInventario movement = existing.get();
            if (!fingerprint.equals(movement.getOperationFingerprint())) {
                throw new IllegalArgumentException("operationId reutilizado con otro comando");
            }
            return new Result(movement.getId(), command.insumoId(), movement.getResultingStock());
        }

        BaseUnit storedUnit = baseUnitOf(insumo);
        if (storedUnit == null) throw new IllegalStateException("El insumo activo requiere unidad base");
        if (storedUnit != command.unit()) {
            throw new IllegalArgumentException("La unidad del comando difiere de la unidad base del insumo");
        }
        BigDecimal current = InventoryNumbers.base(insumo.getStockActual());

        BigDecimal next = command.type().incoming ? current.add(quantity) : current.subtract(quantity);
        if (next.signum() < 0) throw new IllegalArgumentException("Stock insuficiente");
        next = InventoryNumbers.base(next);
        Usuario actor = usuarios.findById(command.actorId())
                .orElseThrow(() -> new IllegalArgumentException("Actor no encontrado"));

        insumo.setStockActual(next);
        MovimientoInventario movement = new MovimientoInventario();
        movement.setInsumo(insumo);
        movement.setUsuario(actor);
        movement.setTipoMovimiento(command.type().name());
        movement.setCantidad(quantity);
        movement.setComentario(command.comment());
        movement.setOperationId(command.operationId());
        movement.setOperationFingerprint(fingerprint);
        movement.setCausalOrigin(command.causalOrigin());
        movement.setCausalReference(command.causalReference());
        movement.setResultingStock(next);
        MovimientoInventario saved = movimientos.saveAndFlush(movement);
        return new Result(saved.getId(), insumo.getId(), next);
    }

    private static BaseUnit baseUnitOf(Insumo insumo) {
        if (insumo.getUnidadBaseFamilia() == null && insumo.getUnidadBaseCodigo() == null) return null;
        return BaseUnit.require(insumo.getUnidadBaseFamilia(), insumo.getUnidadBaseCodigo());
    }

    private void lockOperation(UUID operationId) {
        lockOperationKey(operationLockKey(operationId));
    }

    private static long operationLockKey(UUID operationId) {
        return operationId.getMostSignificantBits() ^ operationId.getLeastSignificantBits();
    }

    private void lockOperationKey(long key) {
        db.queryForObject("select pg_advisory_xact_lock(?) is null", Boolean.class, key);
    }

    private static String fingerprint(Command command, BigDecimal quantity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, command.insumoId().toString());
            add(digest, command.type().name());
            add(digest, quantity.stripTrailingZeros().toPlainString());
            add(digest, command.unit() == null ? null : command.unit().name());
            add(digest, command.actorId().toString());
            add(digest, command.causalOrigin());
            add(digest, command.causalReference());
            add(digest, command.comment());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
