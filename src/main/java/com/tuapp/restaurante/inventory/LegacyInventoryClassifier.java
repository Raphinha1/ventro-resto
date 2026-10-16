package com.tuapp.restaurante.inventory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Calcula evidencia histórica sin escribir saldo, movimientos ni anclas. */
@Service
public class LegacyInventoryClassifier {
    public static final String ALGORITHM_VERSION = "legacy-inventory-v1";
    private static final int INSUMO_BATCH = 100;
    private static final int MOVEMENT_PAGE = 500;

    private final NamedParameterJdbcTemplate db;

    public LegacyInventoryClassifier(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    public enum Classification {
        REQUIERE_INTERVENCION, ORIGEN_NO_VERIFICADO, DISCREPANCIA, RECONCILIABLE
    }

    /** Unidad histórica constatada para el saldo y todos los movimientos del período. */
    public record Evidence(BaseUnit historicalUnit, StartingPoint trustedStart) {
        public static Evidence none() { return new Evidence(null, null); }
    }

    /** Fecha local heredada, comparable con movimiento_inventario.fecha; no es un instante UTC. */
    public record StartingPoint(LocalDateTime at, BigDecimal balance) {
        public StartingPoint {
            Objects.requireNonNull(at, "El instante inicial es obligatorio");
            balance = InventoryNumbers.base(balance);
        }
    }

    /** cutoff es el instante PostgreSQL del corte; en aprobación sigue al bloqueo de las filas. */
    public record Result(Long insumoId, BigDecimal observedStock, BigDecimal knownMovementSum,
                         BigDecimal unexplainedDelta, int includedMovementCount,
                         List<Long> includedMovementIds, int excludedMovementCount,
                         List<Long> excludedMovementIds, Classification classification,
                         BaseUnit historicalUnit, OffsetDateTime cutoff,
                         String algorithmVersion) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result classify(Long insumoId, Evidence evidence) {
        return classifyBatch(List.of(Objects.requireNonNull(insumoId, "Insumo obligatorio")),
                Map.of(insumoId, evidence == null ? Evidence.none() : evidence)).get(0);
    }

    /** Carga insumos por grupos y movimientos por páginas, sin una consulta por insumo. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Result> classifyBatch(Collection<Long> insumoIds, Map<Long, Evidence> evidenceByInsumo) {
        return calculate(insumoIds, evidenceByInsumo, "transaction_timestamp()");
    }

    /** Uso interno tras bloquear el insumo en READ COMMITTED: consulta un snapshot fresco. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result classifyLocked(Long insumoId, Evidence evidence) {
        return calculate(List.of(Objects.requireNonNull(insumoId, "Insumo obligatorio")),
                Map.of(insumoId, Objects.requireNonNull(evidence, "Evidencia obligatoria")),
                "statement_timestamp()").get(0);
    }

    private List<Result> calculate(Collection<Long> insumoIds, Map<Long, Evidence> evidenceByInsumo,
                                   String postgresClock) {
        Objects.requireNonNull(insumoIds, "Insumos obligatorios");
        Objects.requireNonNull(evidenceByInsumo, "Evidencia obligatoria");
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(insumoIds));
        if (ids.contains(null)) throw new IllegalArgumentException("Insumo obligatorio");
        if (ids.isEmpty()) return List.of();

        // En batch fija el snapshot REPEATABLE_READ; en aprobación, el llamador
        // ya bloqueó insumo y movimientos, y obtiene un corte SQL posterior.
        // La fecha heredada no se convierte ni se compara con un instante UTC.
        OffsetDateTime cutoff = db.queryForObject("select " + postgresClock, Map.of(),
                OffsetDateTime.class);
        Map<Long, Accumulator> results = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += INSUMO_BATCH) {
            List<Long> batch = ids.subList(offset, Math.min(offset + INSUMO_BATCH, ids.size()));
            var params = new MapSqlParameterSource("ids", batch);
            db.query("select id,stock_actual from insumo where id in (:ids)", params, rs -> {
                long id = rs.getLong("id");
                Evidence evidence = evidenceByInsumo.getOrDefault(id, Evidence.none());
                if (evidence == null) evidence = Evidence.none();
                results.put(id, new Accumulator(id, rs.getBigDecimal("stock_actual"), evidence));
            });
            if (batch.stream().anyMatch(id -> !results.containsKey(id))) {
                throw new IllegalArgumentException("Insumo no encontrado");
            }

            long lastId = 0;
            while (true) {
                var pageParams = new MapSqlParameterSource("ids", batch)
                        .addValue("lastId", lastId)
                        .addValue("pageSize", MOVEMENT_PAGE);
                List<Movement> page = db.query("select id,insumo_id,tipo_movimiento,cantidad,fecha " +
                                "from movimiento_inventario where insumo_id in (:ids) and id > :lastId " +
                                "order by id limit :pageSize", pageParams,
                        (rs, row) -> new Movement(rs.getLong("id"), rs.getLong("insumo_id"),
                                rs.getString("tipo_movimiento"), rs.getBigDecimal("cantidad"),
                                rs.getTimestamp("fecha").toLocalDateTime()));
                for (Movement movement : page) {
                    Accumulator target = results.get(movement.insumoId());
                    if (target.evidence.trustedStart() == null ||
                            movement.at().isAfter(target.evidence.trustedStart().at())) {
                        target.includeOrExclude(movement);
                    }
                }
                if (page.size() < MOVEMENT_PAGE) break;
                lastId = page.get(page.size() - 1).id();
            }
        }
        return ids.stream().map(id -> results.get(id).finish(cutoff)).toList();
    }

    private record Movement(long id, long insumoId, String type, BigDecimal quantity, LocalDateTime at) {}

    private static final class Accumulator {
        private final long id;
        private final BigDecimal stock;
        private final Evidence evidence;
        private final List<Long> included = new ArrayList<>();
        private final List<Long> excluded = new ArrayList<>();
        private BigDecimal knownSum = BigDecimal.ZERO;
        private boolean invalidNumber;

        private Accumulator(long id, BigDecimal stock, Evidence evidence) {
            this.id = id;
            this.stock = stock;
            this.evidence = evidence;
        }

        private void includeOrExclude(Movement movement) {
            BigDecimal effect = effect(movement);
            if (evidence.historicalUnit() == null || effect == null) {
                excluded.add(movement.id());
                return;
            }
            included.add(movement.id());
            knownSum = knownSum.add(effect);
            try {
                signedBase(knownSum);
            } catch (IllegalArgumentException ex) {
                invalidNumber = true;
            }
        }

        private Result finish(OffsetDateTime cutoff) {
            BigDecimal delta = stock == null ? null : stock.subtract(knownSum);
            boolean invalidStock = stock == null || stock.signum() < 0;
            try {
                if (stock != null) InventoryNumbers.base(stock.abs());
                if (delta != null) signedBase(delta);
            } catch (IllegalArgumentException ex) {
                invalidNumber = true;
            }
            Classification classification;
            if (invalidStock || invalidNumber || !excluded.isEmpty()) {
                classification = Classification.REQUIERE_INTERVENCION;
            } else if (evidence.trustedStart() == null) {
                classification = Classification.ORIGEN_NO_VERIFICADO;
            } else if (evidence.historicalUnit() == null) {
                classification = Classification.REQUIERE_INTERVENCION;
            } else if (evidence.trustedStart().balance().add(knownSum).compareTo(stock) == 0) {
                classification = Classification.RECONCILIABLE;
            } else {
                classification = Classification.DISCREPANCIA;
            }
            return new Result(id, stock, knownSum, delta, included.size(), List.copyOf(included),
                    excluded.size(), List.copyOf(excluded), classification,
                    evidence.historicalUnit(), cutoff, ALGORITHM_VERSION);
        }
    }

    private static BigDecimal effect(Movement movement) {
        BigDecimal amount = movement.quantity();
        if (amount == null || movement.type() == null) return null;
        String type = movement.type().toUpperCase(Locale.ROOT);
        if (amount.signum() == 0 || (!"AJUSTE".equals(type) && amount.signum() < 0)) return null;
        try {
            signedBase(amount);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return switch (type) {
            case "ENTRADA", "ENTRADA_PRODUCCION", "AJUSTE" -> amount;
            case "SALIDA", "MERMA", "SALIDA_VENTA", "SALIDA_PRODUCCION" -> amount.negate();
            default -> null;
        };
    }

    private static BigDecimal signedBase(BigDecimal value) {
        BigDecimal magnitude = InventoryNumbers.base(value.abs());
        return value.signum() < 0 ? magnitude.negate() : magnitude;
    }
}
