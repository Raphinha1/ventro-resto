package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Classification;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Evidence;
import com.tuapp.restaurante.inventory.LegacyInventoryClassifier.Result;
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

/** Aprobación interna 5A.4. No activa el flujo nuevo ni modifica existencias. */
@Service
public class InventoryReconciliationService {
    private final JdbcTemplate db;
    private final LegacyInventoryClassifier classifier;
    private final InventoryPermissionService permissions;

    public InventoryReconciliationService(JdbcTemplate db, LegacyInventoryClassifier classifier,
                                          InventoryPermissionService permissions) {
        this.db = db;
        this.classifier = classifier;
        this.permissions = permissions;
    }

    public record Command(UUID requestId, Long insumoId, BaseUnit approvedUnit,
                          Evidence historicalEvidence, Long reviewerId,
                          String provenance, String evidenceText) {
        public Command {
            Objects.requireNonNull(requestId, "requestId obligatorio");
            Objects.requireNonNull(insumoId, "Insumo obligatorio");
            Objects.requireNonNull(approvedUnit, "Unidad aprobada obligatoria");
            Objects.requireNonNull(historicalEvidence, "Evidencia histórica obligatoria");
            Objects.requireNonNull(reviewerId, "Revisor obligatorio");
            if (provenance == null || provenance.isBlank()) {
                throw new IllegalArgumentException("La procedencia revisada es obligatoria");
            }
        }
    }

    /** Se lee siempre de la fila persistida, también en reintentos. */
    public record ApprovedAnchor(Long id, UUID requestId, Long insumoId,
                                 BigDecimal observedStock, BigDecimal knownMovementSum,
                                 BigDecimal unexplainedDelta, OffsetDateTime cutoff,
                                 String algorithmVersion, Classification classification,
                                 BaseUnit historicalUnit, Long reviewerId,
                                 OffsetDateTime reviewedAt, String provenance,
                                 String evidenceText, LocalDateTime startingAt,
                                 BigDecimal startingBalance) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApprovedAnchor approve(Command command) {
        Objects.requireNonNull(command, "Comando obligatorio");
        permissions.require(command.reviewerId(), InventoryPermissionService.Permission.INVENTARIO_RECONCILIAR);
        if (command.approvedUnit() != command.historicalEvidence().historicalUnit()) {
            throw new IllegalArgumentException("La unidad aprobada difiere de la unidad histórica demostrada");
        }
        String fingerprint = InventoryReconciliationFingerprint.calculate(command.insumoId(),
                command.approvedUnit(), command.historicalEvidence(), command.reviewerId(),
                command.provenance(), command.evidenceText());

        // Serializa reintentos del mismo UUID aun cuando apunten a insumos distintos.
        long advisoryKey = command.requestId().getMostSignificantBits()
                ^ command.requestId().getLeastSignificantBits();
        db.queryForObject("select pg_advisory_xact_lock(?) is null", Boolean.class, advisoryKey);
        ApprovedAnchor existing = findByRequestId(command.requestId());
        if (existing != null) return sameCommand(existing, fingerprint);

        // FOR UPDATE también impide nuevos INSERT de movimientos: su FK necesita
        // KEY SHARE sobre esta fila. La lectura posterior usa READ COMMITTED fresco.
        List<String> states = db.query("select estado_inventario_5a from insumo where id=? for update",
                (rs, row) -> rs.getString(1), command.insumoId());
        if (states.isEmpty()) throw new IllegalArgumentException("Insumo no encontrado");
        existing = findByRequestId(command.requestId());
        if (existing != null) return sameCommand(existing, fingerprint);
        if (!"PENDIENTE".equals(states.get(0))) {
            throw new IllegalStateException("Solo un insumo PENDIENTE puede reconciliarse");
        }
        // El FOR UPDATE del insumo detiene inserciones por su FK. Estas filas
        // quedan estables ante correcciones/borrados de historia ya existente.
        db.query("select id from movimiento_inventario where insumo_id=? for share",
                rs -> {}, command.insumoId());
        Result result = classifier.classifyLocked(command.insumoId(), command.historicalEvidence());
        if (result.historicalUnit() != command.approvedUnit()) {
            throw new IllegalArgumentException("La unidad calculada difiere de la unidad aprobada");
        }
        if (result.classification() == Classification.REQUIERE_INTERVENCION ||
                result.classification() == Classification.DISCREPANCIA) {
            throw new IllegalStateException("La clasificación " + result.classification() +
                    " impide reconciliar el insumo");
        }
        if (result.classification() == Classification.ORIGEN_NO_VERIFICADO &&
                (command.evidenceText() == null || command.evidenceText().isBlank())) {
            throw new IllegalArgumentException("ORIGEN_NO_VERIFICADO requiere evidencia no vacía");
        }
        var start = command.historicalEvidence().trustedStart();
        Long anchorId = db.queryForObject("insert into inventario_reconciliacion(" +
                "insumo_id,request_id,saldo_observado,suma_movimientos_conocidos," +
                "delta_no_explicado,corte_en,version_calculo,clasificacion," +
                "creado_por_usuario_id,revisado_en,revisado_por_usuario_id,procedencia,evidencia," +
                "unidad_historica_familia,unidad_historica_codigo,punto_inicial_en," +
                "punto_inicial_saldo,huella_comando) values (?,?,?,?,?,?,?,?,?,clock_timestamp()," +
                "?,?,?,?,?,?,?,?) returning id", Long.class,
                command.insumoId(), command.requestId(), result.observedStock(),
                result.knownMovementSum(), result.unexplainedDelta(), result.cutoff(),
                result.algorithmVersion(), result.classification().name(), command.reviewerId(),
                command.reviewerId(),
                command.provenance(), command.evidenceText(), result.historicalUnit().family().name(),
                result.historicalUnit().symbol(), start == null ? null : start.at(),
                start == null ? null : start.balance(), fingerprint);
        int updated = db.update("update insumo set unidad_base_familia=?,unidad_base_codigo=?," +
                "reconciliacion_actual_id=?,estado_inventario_5a='RECONCILIADO' " +
                "where id=? and estado_inventario_5a='PENDIENTE'",
                command.approvedUnit().family().name(), command.approvedUnit().symbol(),
                anchorId, command.insumoId());
        if (updated != 1) throw new IllegalStateException("El insumo cambió durante la aprobación");
        return Objects.requireNonNull(findByRequestId(command.requestId()));
    }

    private ApprovedAnchor sameCommand(ApprovedAnchor existing, String fingerprint) {
        String stored = db.queryForObject("select huella_comando from inventario_reconciliacion where id=?",
                String.class, existing.id());
        if (!fingerprint.equals(stored)) {
            throw new IllegalArgumentException("requestId reutilizado con otro comando");
        }
        return existing;
    }

    private ApprovedAnchor findByRequestId(UUID requestId) {
        List<ApprovedAnchor> rows = db.query("select id,request_id,insumo_id,saldo_observado," +
                "suma_movimientos_conocidos,delta_no_explicado,corte_en,version_calculo," +
                "clasificacion,unidad_historica_codigo,revisado_por_usuario_id,revisado_en," +
                "procedencia,evidencia,punto_inicial_en,punto_inicial_saldo " +
                "from inventario_reconciliacion where request_id=?", (rs, row) -> {
            String symbol = rs.getString("unidad_historica_codigo");
            BaseUnit unit = null;
            if (symbol != null) {
                for (BaseUnit candidate : BaseUnit.values()) {
                    if (candidate.symbol().equals(symbol)) unit = candidate;
                }
            }
            return new ApprovedAnchor(rs.getLong("id"), rs.getObject("request_id", UUID.class),
                    rs.getLong("insumo_id"), rs.getBigDecimal("saldo_observado"),
                    rs.getBigDecimal("suma_movimientos_conocidos"), rs.getBigDecimal("delta_no_explicado"),
                    rs.getObject("corte_en", OffsetDateTime.class), rs.getString("version_calculo"),
                    Classification.valueOf(rs.getString("clasificacion")), unit,
                    rs.getObject("revisado_por_usuario_id", Long.class),
                    rs.getObject("revisado_en", OffsetDateTime.class), rs.getString("procedencia"),
                    rs.getString("evidencia"), rs.getObject("punto_inicial_en", LocalDateTime.class),
                    rs.getBigDecimal("punto_inicial_saldo"));
        }, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

}
