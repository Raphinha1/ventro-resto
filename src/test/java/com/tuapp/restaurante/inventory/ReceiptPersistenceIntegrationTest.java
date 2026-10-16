package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.models.InventoryPurchasePresentation;
import com.tuapp.restaurante.models.InventoryReceipt;
import com.tuapp.restaurante.repositories.InventoryPurchasePresentationRepository;
import com.tuapp.restaurante.repositories.InventoryReceiptRepository;
import com.tuapp.restaurante.repositories.InventoryReceiptLineRepository;
import com.tuapp.restaurante.repositories.InsumoRepository;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import com.tuapp.restaurante.repositories.ProveedorRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Frontera PostgreSQL/JPA, exclusivamente sobre la BD descartable de Slice 0. */
@SpringBootTest
@ActiveProfiles("slice0")
class ReceiptPersistenceIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryActivationService activation;
    @Autowired InventoryPurchasePresentationRepository presentations;
    @Autowired InventoryReceiptRepository receipts;
    @Autowired InventoryReceiptLineRepository lines;
    @Autowired InsumoRepository insumos;
    @Autowired UsuarioRepository users;
    @Autowired ProveedorRepository suppliers;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    private long actor;
    private long supply;
    private TransactionTemplate tx;
    private static final String HASH = "a".repeat(64);

    @BeforeEach
    void syntheticData() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate usuario,insumo,proveedor restart identity cascade");
        actor = db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('receipt_actor','synthetic','Fixture','ROLE_ADMIN',true) returning id", Long.class);
        supply = db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                "stock_actual,stock_minimo,activo) values ('Fixture','texto no interpretable',9.75,12.25,0,true) returning id",
                Long.class);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(), supply,
                BaseUnit.UNIT, new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT, null), actor,
                "fixture", "conteo sintético"));
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(), supply, actor));
        tx = new TransactionTemplate(transactions);
    }

    private long presentation(String mode, String factor) {
        return db.queryForObject("insert into inventory_purchase_presentation(insumo_id,nombre,unidad_compra_codigo," +
                "modo,familia,unidad_base_codigo,factor_base,creado_por_usuario_id) " +
                "values (?,'Caja','caja',?,'COUNT','unidad',?::numeric,?) returning id",
                Long.class, supply, mode, factor, actor);
    }

    private long header(UUID request, String hash) {
        return db.queryForObject("insert into inventory_receipt(request_id,command_fingerprint,fingerprint_version," +
                "moneda,actor_usuario_id) values (?,?,'receipt-v1','MXN',?) returning id", Long.class, request, hash, actor);
    }

    private void line(long receipt, long presentation, int lineNo, UUID operation,
                      String quantity, String received, String effective, String cost) {
        db.update("insert into inventory_receipt_line(receipt_id,line_no,insumo_id,presentation_id," +
                "cantidad_comprada,cantidad_base_recibida,cantidad_base_efectiva,costo_total_real," +
                "presentation_nombre,unidad_compra_codigo,modo,familia,unidad_base_codigo,factor_base," +
                "calculation_version,operation_id) select ?,?,insumo_id,id,?::numeric,?::numeric,?::numeric," +
                "?::numeric,nombre,unidad_compra_codigo,modo,familia,unidad_base_codigo,factor_base,'conversion-v1',? " +
                "from inventory_purchase_presentation where id=?", receipt, lineNo, quantity, received, effective,
                cost, operation, presentation);
    }

    private long fixedReceipt(long presentation, UUID request) {
        return tx.execute(status -> {
            long id = header(request, HASH);
            line(id, presentation, 1, UUID.randomUUID(), "2", null, "48", "25.23");
            return id;
        });
    }

    private void assertUnchangedInventory() {
        assertThat(db.queryForObject("select stock_actual from insumo where id=?", BigDecimal.class, supply))
                .isEqualByComparingTo("12.25");
        assertThat(db.queryForObject("select costo_unitario from insumo where id=?", BigDecimal.class, supply))
                .isEqualByComparingTo("9.75");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario", Long.class)).isZero();
        assertThat(db.queryForObject("select estado_inventario_5a from insumo where id=?", String.class, supply))
                .isEqualTo("ACTIVO_5A");
    }

    @Test
    void emptyDatabaseMigratesReceiptStorage() {
        assertThat(db.queryForObject("select count(*) from pg_tables where schemaname='public' " +
                "and tablename in ('inventory_purchase_presentation','inventory_receipt','inventory_receipt_line')",
                Long.class)).isEqualTo(3);
        assertThat(db.queryForObject("select max(version::int) from flyway_schema_history", Integer.class)).isEqualTo(13);
    }

    @Test
    void databaseRejectsRoundingOverflowAndNonPositiveValues() {
        for (String[] example : new String[][] {
                {"inventory_5b_quantity", "0", "-1", "0.0000001", "10000000000000", "NaN", "Infinity"},
                {"inventory_5b_factor", "0", "-1", "0.0000000000001", "1000000000000", "NaN", "Infinity"},
                {"inventory_5b_cost", "0", "-1", "1.235", "100000000000000000", "NaN", "Infinity"}}) {
            for (int i = 1; i < example.length; i++) {
                String value = example[i];
                assertThatThrownBy(() -> db.queryForObject("select ?::" + example[0], BigDecimal.class, value))
                        .hasMessageContaining("violates check constraint");
            }
        }
        assertThat(db.queryForObject("select 9999999999999.999999::inventory_5b_quantity", BigDecimal.class))
                .isEqualByComparingTo("9999999999999.999999");
        assertThat(db.queryForObject("select 999999999999.999999999999::inventory_5b_factor", BigDecimal.class))
                .isEqualByComparingTo("999999999999.999999999999");
        assertThat(db.queryForObject("select 99999999999999999.99::inventory_5b_cost", BigDecimal.class))
                .isEqualByComparingTo("99999999999999999.99");
        assertThatThrownBy(() -> presentation("FIJA", "1.0000000000001")).hasMessageContaining("violates check constraint");
        long p = presentation("FIJA", "24");
        for (String[] example : new String[][] {{"2.0000001", "48", "25.23"},
                {"2", "48", "25.235"}, {"2", "48.0000001", "25.23"}}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                long id = header(UUID.randomUUID(), HASH);
                line(id, p, 1, UUID.randomUUID(), example[0], null, example[1], example[2]);
            })).hasMessageContaining("violates check constraint");
        }
        assertThat(db.queryForObject("select count(*) from inventory_receipt", Long.class)).isZero();
        assertUnchangedInventory();
    }

    @Test
    void mappingsPersistFixedAndVariableSnapshotsAndOptionalReferencesWithoutStock() {
        long supplier = db.queryForObject("insert into proveedor(nombre_empresa,activo) values ('Sintético',true) returning id", Long.class);
        UUID request = UUID.randomUUID();
        long receiptId = tx.execute(status -> {
            var fixed = presentations.saveAndFlush(new InventoryPurchasePresentation(insumos.getReferenceById(supply),
                    "Caja 24", "caja", InventoryPurchasePresentation.Mode.FIJA, new BigDecimal("24"), users.getReferenceById(actor)));
            var variable = presentations.saveAndFlush(new InventoryPurchasePresentation(insumos.getReferenceById(supply),
                    "Variable", "variable", InventoryPurchasePresentation.Mode.VARIABLE, null, users.getReferenceById(actor)));
            var receipt = new InventoryReceipt(request, HASH, "receipt-v1", "MXN", users.getReferenceById(actor),
                    suppliers.getReferenceById(supplier), "DOC-SINTETICO");
            receipt.addLine(2, fixed, new BigDecimal("2"), null, new BigDecimal("25.23"), UUID.randomUUID());
            receipt.addLine(1, variable, new BigDecimal("0.75"), new BigDecimal("3.5"), new BigDecimal("1.2300"), UUID.randomUUID());
            return receipts.saveAndFlush(receipt).getId();
        });
        var found = receipts.findByRequestId(request).orElseThrow();
        assertThat(found.getMoneda()).isEqualTo("MXN");
        assertThat(found.getCommandFingerprint()).isEqualTo(HASH);
        assertThat(found.getProveedor().getId()).isEqualTo(supplier);
        assertThat(found.getDocumentoExterno()).isEqualTo("DOC-SINTETICO");
        assertThat(found.getRegistradoEn()).isNotNull();
        assertThat(found.getLines()).extracting(line -> line.getLineNo()).containsExactly(1,2);
        assertThat(found.getLines().get(0).getCantidadBaseEfectiva()).isEqualByComparingTo("3.5");
        assertThat(found.getLines().get(0).getFactorBase()).isNull();
        assertThat(found.getLines().get(1).getCantidadBaseEfectiva()).isEqualByComparingTo("48");
        assertThat(lines.findByReceiptIdOrderByLineNoAsc(receiptId)).hasSize(2);
        assertUnchangedInventory();
    }

    @Test
    void presentationLifecycleAndHistoricSnapshotsRemainStable() {
        long p = presentation("FIJA", "24");
        long id = fixedReceipt(p, UUID.randomUUID());
        db.update("update inventory_purchase_presentation set nombre='Nuevo nombre',estado='INACTIVA' where id=?", p);
        assertThat(db.queryForObject("select presentation_nombre from inventory_receipt_line where receipt_id=?", String.class, id))
                .isEqualTo("Caja");
        assertThatThrownBy(() -> db.update("delete from inventory_purchase_presentation where id=?", p))
                .hasMessageContaining("foreign key constraint");
        for (String change : new String[] {"factor_base=25", "modo='VARIABLE',factor_base=null", "familia='MASS'", "insumo_id=insumo_id+1"}) {
            assertThatThrownBy(() -> db.update("update inventory_purchase_presentation set " + change + " where id=?", p))
                    .hasMessageContaining("nueva presentación");
        }
        assertThatThrownBy(() -> db.update("update inventory_purchase_presentation set estado='OTRO' where id=?", p))
                .hasMessageContaining("check constraint");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long h = header(UUID.randomUUID(), HASH);
            line(h,p,1,UUID.randomUUID(),"2",null,"48","25.23");
        })).hasMessageContaining("presentación ACTIVA");
        assertThat(presentation("FIJA", "25")).isNotEqualTo(p);
        assertUnchangedInventory();
    }

    @Test
    void invalidModesUnitsAndConversionCannotBePersisted() {
        assertThatThrownBy(() -> presentation("FIJA",null)).hasMessageContaining("check constraint");
        assertThatThrownBy(() -> presentation("VARIABLE","1")).hasMessageContaining("check constraint");
        assertThatThrownBy(() -> presentation("OTRO",null)).hasMessageContaining("check constraint");
        assertThatThrownBy(() -> db.update("insert into inventory_purchase_presentation(insumo_id,nombre,unidad_compra_codigo," +
                "modo,familia,unidad_base_codigo,creado_por_usuario_id) values (?,'L','L','VARIABLE','VOLUME','ml',?)", supply,actor))
                .hasMessageContaining("unidad coincidente");
        long p = presentation("FIJA","24");
        long v = presentation("VARIABLE",null);
        for (long choice : new long[] {p,v}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                long h = header(UUID.randomUUID(), HASH);
                line(h,choice,1,UUID.randomUUID(),"2",null,"49","25.23");
            })).hasMessageContaining("check constraint");
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long h = header(UUID.randomUUID(), HASH);
            line(h,p,1,UUID.randomUUID(),"2","48","48","25.23");
        })).hasMessageContaining("check constraint");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long h = header(UUID.randomUUID(), HASH);
            line(h,v,1,UUID.randomUUID(),"2","3","4","25.23");
        })).hasMessageContaining("check constraint");
        assertUnchangedInventory();
    }

    @Test
    void duplicateRequestLineNumberAndOperationAreRejectedAtomically() {
        long p = presentation("FIJA","24");
        UUID request = UUID.randomUUID();
        fixedReceipt(p, request);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> header(request,"b".repeat(64))))
                .hasMessageContaining("unique constraint");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> header(UUID.randomUUID(),"bad")))
                .hasMessageContaining("check constraint");
        for (boolean duplicateNumber : new boolean[] {true,false}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                long h=header(UUID.randomUUID(),HASH);
                UUID operation=UUID.randomUUID();
                line(h,p,1,operation,"2",null,"48","25.23");
                line(h,p,duplicateNumber ? 1 : 2, duplicateNumber ? UUID.randomUUID() : operation,"2",null,"48","25.23");
            })).hasMessageContaining("unique constraint");
        }
        assertThat(db.queryForObject("select count(*) from inventory_receipt", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from inventory_receipt_line", Long.class)).isEqualTo(1);
        assertUnchangedInventory();
    }

    @Test
    void confirmedHeaderAndLinesAreImmutableAndNoLateLineOrEmptyHeaderCanCommit() {
        long p=presentation("FIJA","24");
        UUID request=UUID.randomUUID();
        long h=fixedReceipt(p,request);
        assertThat(db.queryForObject("select sealed from inventory_receipt where id=?",Boolean.class,h)).isTrue();
        for (String sql : new String[] {
                "update inventory_receipt set moneda='USD' where id=?", "delete from inventory_receipt where id=?",
                "update inventory_receipt set sealed=false where id=?",
                "update inventory_receipt_line set costo_total_real=26 where receipt_id=?",
                "update inventory_receipt_line set presentation_nombre='Alterado' where receipt_id=?",
                "delete from inventory_receipt_line where receipt_id=?"}) {
            assertThatThrownBy(() -> db.update(sql,h)).hasMessageContaining("inmutable");
        }
        assertThatThrownBy(() -> line(h,p,2,UUID.randomUUID(),"2",null,"48","25.23"))
                .hasMessageContaining("junto a la recepción");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> header(UUID.randomUUID(),HASH)))
                .hasStackTraceContaining("Recepción confirmada requiere al menos una línea");
        assertThat(receipts.findByRequestId(request)).isPresent();
        assertThat(db.queryForObject("select count(*) from inventory_receipt", Long.class)).isEqualTo(1);
        assertUnchangedInventory();
    }

    @Test
    void snapshotsMustMatchPresentationAndLegacyIngredientCannotReceive() {
        long p=presentation("FIJA","24");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long h=header(UUID.randomUUID(),HASH);
            db.update("insert into inventory_receipt_line(receipt_id,line_no,insumo_id,presentation_id," +
                    "cantidad_comprada,cantidad_base_efectiva,costo_total_real,presentation_nombre,unidad_compra_codigo," +
                    "modo,familia,unidad_base_codigo,factor_base,calculation_version,operation_id) " +
                    "values (?,1,?,?,2,48,25,'Alterada','caja','FIJA','COUNT','unidad',24,'v1',?)",h,supply,p,UUID.randomUUID());
        })).hasMessageContaining("Snapshot");
        long legacy=db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario," +
                "stock_actual,stock_minimo,activo) values ('Legado','caja',1,2,0,true) returning id",Long.class);
        assertThatThrownBy(() -> db.update("insert into inventory_purchase_presentation(insumo_id,nombre,unidad_compra_codigo," +
                "modo,familia,unidad_base_codigo,factor_base,creado_por_usuario_id) " +
                "values (?,'Caja','caja','FIJA','COUNT','unidad',24,?)",legacy,actor))
                .hasMessageContaining("ACTIVO_5A");
        assertThat(db.queryForObject("select count(*) from inventory_receipt",Long.class)).isZero();
        assertUnchangedInventory();
    }

    @Test
    void v11LegacyMigratesWithoutChangingAnyInventoryRowsAndRepeatIsNoOp() throws Exception {
        String name="ventro_receipt_"+UUID.randomUUID().toString().replace("-", "");
        String url;
        try (var connection=dataSource.getConnection()) {
            url=connection.getMetaData().getURL().replaceFirst("/[^/]+$", "/"+name);
        }
        db.execute("create database "+name);
        var isolated=new DriverManagerDataSource(url, environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        var local=new JdbcTemplate(isolated);
        try {
            Flyway old=Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("11")).load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(11);
            local.update("insert into usuario(username,password,nombre_completo,rol,activo) values ('fixture','synthetic','Fixture','ROLE_ADMIN',true)");
            local.update("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) values ('Legado','ambiguo',9.75,12.25,0,true)");
            local.update("insert into movimiento_inventario(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha) values (1,1,'ENTRADA',2.5,now())");
            String stock=local.queryForObject("select row_to_json(i)::text from insumo i",String.class);
            String movement=local.queryForObject("select row_to_json(m)::text from movimiento_inventario m",String.class);
            var latest=Flyway.configure().dataSource(isolated).locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(local.queryForObject("select row_to_json(i)::text from insumo i",String.class)).isEqualTo(stock);
            assertThat(local.queryForObject("select row_to_json(m)::text from movimiento_inventario m",String.class)).isEqualTo(movement);
            assertThat(local.queryForObject("select count(*) from inventory_receipt",Long.class)).isZero();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
        } finally { db.execute("drop database "+name+" with (force)"); }
    }
}
