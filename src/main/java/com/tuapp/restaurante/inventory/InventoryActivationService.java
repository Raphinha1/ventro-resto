package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.inventory.InventoryPermissionService.Permission;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Classification;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Evidence;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.StartingPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transición explícita y por insumo; no altera el saldo ni crea movimientos. */
@Service
public class InventoryActivationService {
    private final JdbcTemplate db;
    private final LegacyInventoryClassifier classifier;
    private final InventoryPermissionService permissions;

    public InventoryActivationService(JdbcTemplate db, LegacyInventoryClassifier classifier,
                                      InventoryPermissionService permissions) {
        this.db = db;
        this.classifier = classifier;
        this.permissions = permissions;
    }

    public record Command(UUID requestId, Long insumoId, Long actorId) {
        public Command {
            Objects.requireNonNull(requestId, "requestId obligatorio");
            if (insumoId == null || insumoId <= 0) throw new IllegalArgumentException("Insumo obligatorio");
            if (actorId == null || actorId <= 0) throw new IllegalArgumentException("Actor obligatorio");
        }
    }

    public record Activation(UUID requestId, Long insumoId, Long anchorId,
                             Long actorId, OffsetDateTime activatedAt) {}

    private record State(String status, String family, String unit, Long anchorId) {}

    private record Anchor(Long insumoId, OffsetDateTime reviewedAt, Long reviewerId,
                          String family, String unit, String fingerprint,
                          String algorithmVersion, String classification, String provenance,
                          String evidenceText, LocalDateTime startingAt, BigDecimal startingBalance,
                          BigDecimal approvedDelta) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Activation activate(Command command) {
        Objects.requireNonNull(command, "Comando obligatorio");
        permissions.require(command.actorId(), Permission.INVENTARIO_ACTIVAR_5A);

        // También serializa el mismo UUID cuando se intenta usar para otro insumo.
        long key = command.requestId().getMostSignificantBits()
                ^ command.requestId().getLeastSignificantBits();
        db.queryForObject("select pg_advisory_xact_lock(?) is null", Boolean.class, key);
        Activation previous = findByRequestId(command.requestId());
        if (previous != null) return sameCommand(previous, command);

        List<State> states = db.query("select estado_inventario_5a,unidad_base_familia," +
                        "unidad_base_codigo,reconciliacion_actual_id from insumo where id=? for update",
                (rs, row) -> new State(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class)), command.insumoId());
        if (states.isEmpty()) throw new IllegalArgumentException("Insumo no encontrado");
        State state = states.get(0);
        if (!"RECONCILIADO".equals(state.status())) {
            throw new IllegalStateException("Solo un insumo RECONCILIADO puede activarse");
        }
        if (state.anchorId() == null) throw new IllegalStateException("Falta la reconciliación actual");

        Anchor anchor = currentAnchor(state.anchorId(), command.insumoId());
        Evidence evidence = verifiedEvidence(state, anchor);
        // FOR UPDATE sobre insumo impide nuevos INSERT por la FK. FOR SHARE
        // estabiliza correcciones y borrados de movimientos legacy existentes.
        db.query("select id from movimiento_inventario where insumo_id=? for share",
                rs -> {}, command.insumoId());
        var now = classifier.classifyLocked(command.insumoId(), evidence);
        if (anchor.classification().equals(Classification.RECONCILIABLE.name())) {
            if (now.classification() != Classification.RECONCILIABLE) {
                throw new IllegalStateException("La reconciliación dejó de ser RECONCILIABLE");
            }
        } else if (anchor.classification().equals(Classification.ORIGEN_NO_VERIFICADO.name())) {
            if (now.classification() != Classification.ORIGEN_NO_VERIFICADO ||
                    now.excludedMovementCount() != 0 ||
                    now.unexplainedDelta().compareTo(anchor.approvedDelta()) != 0) {
                throw new IllegalStateException("El origen no verificado cambió desde la aprobación");
            }
        } else {
            throw new IllegalStateException("La clasificación aprobada no permite activación");
        }

        int changed = db.update("update insumo set estado_inventario_5a='ACTIVO_5A' " +
                        "where id=? and estado_inventario_5a='RECONCILIADO' and reconciliacion_actual_id=?",
                command.insumoId(), state.anchorId());
        if (changed != 1) throw new IllegalStateException("El insumo cambió durante la activación");
        db.update("insert into inventory_activation(request_id,insumo_id,reconciliacion_id," +
                        "actor_usuario_id) values (?,?,?,?)",
                command.requestId(), command.insumoId(), state.anchorId(), command.actorId());
        return Objects.requireNonNull(findByRequestId(command.requestId()));
    }

    private Evidence verifiedEvidence(State state, Anchor anchor) {
        if (anchor.reviewedAt() == null || anchor.reviewerId() == null ||
                anchor.family() == null || anchor.unit() == null ||
                anchor.fingerprint() == null || !anchor.fingerprint().matches("[0-9a-f]{64}") ||
                anchor.provenance() == null || anchor.provenance().isBlank() ||
                !LegacyInventoryClassifier.ALGORITHM_VERSION.equals(anchor.algorithmVersion())) {
            throw new IllegalStateException("La reconciliación actual no tiene revisión y evidencia V8 completas");
        }
        if (!Objects.equals(anchor.family(), state.family()) ||
                !Objects.equals(anchor.unit(), state.unit())) {
            throw new IllegalStateException("La unidad histórica difiere de la unidad base del insumo");
        }
        if (anchor.startingAt() == null ^ anchor.startingBalance() == null) {
            throw new IllegalStateException("El punto inicial de la reconciliación está incompleto");
        }
        if (Classification.RECONCILIABLE.name().equals(anchor.classification()) &&
                anchor.startingAt() == null) {
            throw new IllegalStateException("RECONCILIABLE requiere un punto inicial fiable");
        }
        if (Classification.ORIGEN_NO_VERIFICADO.name().equals(anchor.classification()) &&
                (anchor.startingAt() != null || anchor.evidenceText() == null ||
                        anchor.evidenceText().isBlank())) {
            throw new IllegalStateException("ORIGEN_NO_VERIFICADO requiere evidencia sin punto inicial");
        }
        BaseUnit unit = BaseUnit.require(UnitFamily.valueOf(anchor.family()), anchor.unit());
        Evidence evidence = new Evidence(unit, anchor.startingAt() == null ? null :
                new StartingPoint(anchor.startingAt(), anchor.startingBalance()));
        String expected = InventoryReconciliationFingerprint.calculate(anchor.insumoId(),
                BaseUnit.require(UnitFamily.valueOf(state.family()), state.unit()), evidence,
                anchor.reviewerId(), anchor.provenance(), anchor.evidenceText());
        if (!expected.equals(anchor.fingerprint())) {
            throw new IllegalStateException("La huella no coincide con la aprobación persistida");
        }
        return evidence;
    }

    private Anchor currentAnchor(Long anchorId, Long insumoId) {
        List<Anchor> rows = db.query("select insumo_id,revisado_en,revisado_por_usuario_id," +
                        "unidad_historica_familia,unidad_historica_codigo,huella_comando," +
                        "version_calculo,clasificacion,procedencia,evidencia,punto_inicial_en," +
                        "punto_inicial_saldo,delta_no_explicado from inventario_reconciliacion " +
                        "where id=? and insumo_id=? for share",
                (rs, row) -> new Anchor(rs.getLong(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, Long.class), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getObject(11, LocalDateTime.class),
                        rs.getBigDecimal(12), rs.getBigDecimal(13)), anchorId, insumoId);
        if (rows.isEmpty()) throw new IllegalStateException("La reconciliación actual no pertenece al insumo");
        return rows.get(0);
    }

    private Activation findByRequestId(UUID requestId) {
        List<Activation> rows = db.query("select request_id,insumo_id,reconciliacion_id," +
                        "actor_usuario_id,activated_at from inventory_activation where request_id=?",
                (rs, row) -> new Activation(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4), rs.getObject(5, OffsetDateTime.class)), requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Activation sameCommand(Activation previous, Command command) {
        if (!previous.insumoId().equals(command.insumoId()) ||
                !previous.actorId().equals(command.actorId())) {
            throw new IllegalArgumentException("requestId reutilizado con otro comando de activación");
        }
        return previous;
    }
}
