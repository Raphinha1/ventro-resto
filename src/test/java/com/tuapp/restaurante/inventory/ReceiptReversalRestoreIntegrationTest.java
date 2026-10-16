package com.tuapp.restaurante.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: solo después del dump/destrucción/restore sintético de Slice 1. */
@SpringBootTest
@ActiveProfiles("slice0")
@EnabledIfSystemProperty(named="ventro.reversal.restore",matches="true")
class ReceiptReversalRestoreIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ReceiptReversalService service;
    @Test void restoredCommittedReversalRecognizesExactRetryWithoutChangingAnyRows() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("ventro_legacy");
        UUID key=UUID.fromString("00000000-0000-4000-8000-000000000615");
        var row=db.queryForMap("select * from inventory_receipt_reversal where request_id=?",key);
        String before=snapshot();
        var command=new ReceiptReversalService.Command(key,((Number)row.get("receipt_id")).longValue(),
                ((Number)row.get("actor_usuario_id")).longValue(),(String)row.get("motivo"));
        var result=service.reverse(command);
        assertThat(result.reversalId()).isEqualTo(((Number)row.get("id")).longValue());
        assertThat(service.reverse(command)).isEqualTo(result);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(db.queryForObject("select stock_actual from insumo where nombre='Activo backup sintetico'",BigDecimal.class))
                .isEqualByComparingTo("8.123456");
    }
    private String snapshot() {
        return db.queryForObject("select md5(string_agg(row_to_json(r)::text||row_to_json(l)::text||row_to_json(m)::text,',' order by l.id)) from inventory_receipt_reversal r join inventory_receipt_reversal_line l on l.reversal_id=r.id join movimiento_inventario m on m.operation_id=l.operation_id",String.class);
    }
}
