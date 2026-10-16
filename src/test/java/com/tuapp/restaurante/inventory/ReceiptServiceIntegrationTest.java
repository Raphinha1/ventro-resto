package com.tuapp.restaurante.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Solo PostgreSQL descartable de slice0-test.sh; nunca configuración real. */
@SpringBootTest(properties = "ventro.inventory.currency=MXN")
@ActiveProfiles("slice0")
class ReceiptServiceIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ReceiptService service;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryActivationService activation;
    @Autowired ApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    private long actor;

    @BeforeEach
    void fixtures() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate usuario,insumo,proveedor restart identity cascade");
        actor=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('receipt5b','synthetic','Fixture','ROLE_ADMIN',true) returning id",Long.class);
    }

    private long active(String stock) {
        long id=db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) " +
                "values ('Fixture','no inferir',9.75,?::numeric,0,true) returning id",Long.class,stock);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),id,BaseUnit.UNIT,
                new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT,null),actor,"fixture","conteo sintético"));
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(),id,actor));
        return id;
    }

    private long presentation(long insumo, String mode, String factor) {
        return db.queryForObject("insert into inventory_purchase_presentation(insumo_id,nombre,unidad_compra_codigo," +
                "modo,familia,unidad_base_codigo,factor_base,creado_por_usuario_id) " +
                "values (?,'Caja','caja',?,'COUNT','unidad',?::numeric,?) returning id",Long.class,insumo,mode,factor,actor);
    }

    private ReceiptService.Line line(int no,long insumo,long presentation,String quantity,String base,String cost) {
        return new ReceiptService.Line(no,insumo,presentation,new BigDecimal(quantity),
                base==null?null:new BigDecimal(base),new BigDecimal(cost));
    }

    private ReceiptService.Command command(UUID id,List<ReceiptService.Line> lines) {
        return new ReceiptService.Command(id,actor,"MXN",null,null,lines);
    }

    private BigDecimal stock(long id) {
        return db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,id);
    }

    @Test
    void fixedReceiptAddsExactlyOneCausalEntryAndPreservesLegacyCost() {
        long insumo=active("12.25"), p=presentation(insumo,"FIJA","24");
        var result=service.confirm(command(UUID.randomUUID(),List.of(line(1,insumo,p,"2",null,"25.23"))));
        assertThat(stock(insumo)).isEqualByComparingTo("60.25");
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).baseQuantity()).isEqualByComparingTo("48");
        assertThat(db.queryForObject("select costo_unitario from insumo where id=?",BigDecimal.class,insumo))
                .isEqualByComparingTo("9.75");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario " +
                "where tipo_movimiento='ENTRADA' and causal_origin='RECEPCION_RAPIDA'",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select operation_id from inventory_receipt_line where receipt_id=?",UUID.class,result.receiptId()))
                .isEqualTo(result.lines().get(0).operationId());
    }

    private long count(String table) { return db.queryForObject("select count(*) from "+table,Long.class); }

    private void emptyReceiptsAndEffects() {
        assertThat(count("inventory_receipt")).isZero();
        assertThat(count("inventory_receipt_line")).isZero();
        assertThat(count("movimiento_inventario")).isZero();
    }

    private ReceiptService configured(String currency) {
        return new ReceiptService(db,context.getBean(InventoryPermissionService.class),
                context.getBean(InventoryChangeApplier.class),context.getBean(com.tuapp.restaurante.repositories.InventoryReceiptRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.InventoryPurchasePresentationRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.InsumoRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.UsuarioRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.ProveedorRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.MovimientoInventarioRepository.class),currency);
    }

    @Test
    void fixedAndVariableMultilineKeepsEachCostAndAddsDuplicateIngredientOnlyByItsActualQuantities() {
        long a=active("12.25"),b=active("1");
        long fixed=presentation(a,"FIJA","24"),variable=presentation(a,"VARIABLE",null),other=presentation(b,"FIJA","1");
        long supplier=db.queryForObject("insert into proveedor(nombre_empresa,activo) values ('Sintético',true) returning id",Long.class);
        var result=service.confirm(new ReceiptService.Command(UUID.randomUUID(),actor,"MXN",supplier,"  FACT-SINTÉTICA  ",List.of(
                line(3,b,other,"0.000001",null,"0.01"),line(2,a,variable,"2","3.5","12.34"),line(1,a,fixed,"2",null,"25.23"))));
        assertThat(result.lines()).extracting(ReceiptService.ReceivedLine::lineNo).containsExactly(1,2,3);
        assertThat(stock(a)).isEqualByComparingTo("63.75");
        assertThat(stock(b)).isEqualByComparingTo("1.000001");
        assertThat(count("movimiento_inventario")).isEqualTo(3);
        assertThat(db.queryForList("select costo_total_real from inventory_receipt_line order by line_no",BigDecimal.class))
                .containsExactly(new BigDecimal("25.23"),new BigDecimal("12.34"),new BigDecimal("0.01"));
        assertThat(db.queryForObject("select documento_externo from inventory_receipt",String.class)).isEqualTo("FACT-SINTÉTICA");
        assertThat(db.queryForObject("select proveedor_id from inventory_receipt",Long.class)).isEqualTo(supplier);
        assertThat(db.queryForList("select costo_unitario from insumo order by id",BigDecimal.class))
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("9.75"));
        assertThat(db.queryForObject("select count(*) from inventory_receipt_line l join movimiento_inventario m " +
                "on m.operation_id=l.operation_id where m.causal_reference='receipt:'||l.receipt_id||'/line:'||l.line_no " +
                "and m.causal_origin='RECEPCION_RAPIDA' and m.tipo_movimiento='ENTRADA'",Long.class)).isEqualTo(3);
    }

    @Test
    void exactRetryUsesHistoricalIntentAndSnapshotsWithoutReapplyingEvenAfterPresentationBecomesInactive() {
        long a=active("1"),p=presentation(a,"FIJA","24"),v=presentation(a,"VARIABLE",null);
        UUID id=UUID.randomUUID();
        var original=command(id,List.of(line(2,a,v,"2","3.5","1.23"),line(1,a,p,"1",null,"2.34")));
        var result=service.confirm(original);
        db.update("update inventory_purchase_presentation set nombre='Otro nombre',estado='INACTIVA'");
        var retry=command(id,List.of(line(1,a,p,"1.000000",null,"2.3400"),line(2,a,v,"2.0","3.500000","1.2300")));
        assertThat(service.confirm(retry)).isEqualTo(result);
        // Un despliegue con moneda distinta/no configurada no reescribe un hecho histórico.
        for (String currency : List.of("USD","")) {
            ReceiptService.Result historic=new TransactionTemplate(transactions).execute(status -> configured(currency).confirm(retry));
            assertThat(historic).isEqualTo(result);
        }
        assertThat(stock(a)).isEqualByComparingTo("28.5");
        assertThat(count("inventory_receipt")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(2);
        assertThat(db.queryForList("select presentation_nombre from inventory_receipt_line",String.class)).containsOnly("Caja");
    }

    @Test
    void fingerprintRejectsChangesToEveryAcceptedCommandField() {
        long a=active("1"),p=presentation(a,"FIJA","1"),q=presentation(a,"FIJA","2");
        long supplier=db.queryForObject("insert into proveedor(nombre_empresa,activo) values ('Fixture',true) returning id",Long.class);
        long otherActor=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) " +
                "values ('other','synthetic','Other','ROLE_ADMIN',true) returning id",Long.class);
        UUID id=UUID.randomUUID();
        var original=command(id,List.of(line(1,a,p,"1",null,"2.34")));
        service.confirm(original);
        List<ReceiptService.Command> changes=List.of(
                new ReceiptService.Command(id,otherActor,"MXN",null,null,original.lines()),
                new ReceiptService.Command(id,actor,"USD",null,null,original.lines()),
                new ReceiptService.Command(id,actor,"MXN",supplier,null,original.lines()),
                new ReceiptService.Command(id,actor,"MXN",null,"documento",original.lines()),
                command(id,List.of(line(2,a,p,"1",null,"2.34"))),
                command(id,List.of(line(1,a+1,p,"1",null,"2.34"))),
                command(id,List.of(line(1,a,q,"1",null,"2.34"))),
                command(id,List.of(line(1,a,p,"2",null,"2.34"))),
                command(id,List.of(line(1,a,p,"1","1","2.34"))),
                command(id,List.of(line(1,a,p,"1",null,"3.45"))));
        for (var change : changes) {
            assertThatThrownBy(() -> service.confirm(change)).hasMessageContaining("requestId reutilizado");
        }
        assertThat(count("inventory_receipt")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("2");
    }

    @Test
    void operationIdsAreNamespacedAndStableByRequestAndLineNumber() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        var c=command(UUID.fromString("00000000-0000-4000-8000-000000000602"),List.of(line(3,a,p,"1",null,"1")));
        var result=service.confirm(c);
        assertThat(result.lines().get(0).operationId()).isEqualTo(UUID.fromString("78c77264-2037-3f23-9b28-932f9f1e9be8"));
        assertThat(service.confirm(c)).isEqualTo(result);
        var next=service.confirm(command(UUID.randomUUID(),c.lines()));
        assertThat(next.lines().get(0).operationId()).isNotEqualTo(result.lines().get(0).operationId());
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void inactivePresentationAndPendingOrReviewedIngredientsCannotReceive() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        db.update("update inventory_purchase_presentation set estado='INACTIVA' where id=?",p);
        assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")))))
                .hasMessageContaining("INACTIVA");
        db.update("update inventory_purchase_presentation set estado='ACTIVA' where id=?",p);
        // Fixture SQL inconsistente con catálogo operativo, exclusivamente descartable:
        // comprueba que el servicio no confía en que la presentación implique estado activo.
        for (String state : List.of("RECONCILIADO","PENDIENTE")) {
            db.update("update insumo set estado_inventario_5a=? where id=?",state,a);
            assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")))))
                    .hasMessageContaining("LEGACY");
        }
        emptyReceiptsAndEffects();
        assertThat(stock(a)).isEqualByComparingTo("1");
    }

    @Test
    void currencyAndSupplierErrorsAreRejectedWithoutAnyEffects() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        var lines=List.of(line(1,a,p,"1",null,"1"));
        assertThatThrownBy(() -> service.confirm(new ReceiptService.Command(UUID.randomUUID(),actor,"USD",null,null,lines)))
                .hasMessageContaining("moneda");
        assertThatThrownBy(() -> service.confirm(new ReceiptService.Command(UUID.randomUUID(),actor,"MXN",999999L,null,lines)))
                .hasMessageContaining("Proveedor no encontrado");
        for (String currency : List.of("","not-a-code")) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                    configured(currency).confirm(command(UUID.randomUUID(),lines))))
                    .hasMessageContaining("Moneda del restaurante no configurada");
        }
        emptyReceiptsAndEffects();
        assertThat(stock(a)).isEqualByComparingTo("1");
    }

    @Test
    void authorizationIsCurrentAndRequiredAlsoOnRetry() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        service.confirm(c);
        for (String role : List.of("ROLE_CAJERO","ROLE_COCINERO","ROLE_SUPER_ADMIN")) {
            db.update("update usuario set rol=? where id=?",role,actor);
            assertThatThrownBy(() -> service.confirm(c)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),c.lines()))).isInstanceOf(AccessDeniedException.class);
        }
        db.update("update usuario set rol='ROLE_ADMIN',activo=false where id=?",actor);
        assertThatThrownBy(() -> service.confirm(c)).isInstanceOf(AccessDeniedException.class);
        assertThat(count("inventory_receipt")).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("2");
    }

    @Test
    void numericAndConversionErrorsRollbackTheWholeReceipt() {
        long a=active("1"),p=presentation(a,"FIJA","1"),v=presentation(a,"VARIABLE",null);
        long fractional=presentation(a,"FIJA","0.0000001");
        List<ReceiptService.Line> invalid=List.of(
                line(2,a,p,"0",null,"1"),line(2,a,p,"-1",null,"1"),line(2,a,p,"0.0000001",null,"1"),
                line(2,a,p,"10000000000000",null,"1"),line(2,a,p,"1",null,"0"),line(2,a,p,"1",null,"-1"),
                line(2,a,p,"1",null,"1.235"),line(2,a,p,"1",null,"100000000000000000"),
                line(2,a,p,"1","1","1"),line(2,a,v,"1",null,"1"),line(2,a,v,"1","0.0000001","1"),
                line(2,a,fractional,"1",null,"1"),
                new ReceiptService.Line(2,a,p,null,null,BigDecimal.ONE),
                new ReceiptService.Line(2,a,p,BigDecimal.ONE,null,null));
        for (var bad : invalid) {
            assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"),bad))))
                    .isInstanceOf(IllegalArgumentException.class);
            emptyReceiptsAndEffects();
            assertThat(stock(a)).isEqualByComparingTo("1");
        }
        UUID retry=UUID.randomUUID();
        assertThatThrownBy(() -> service.confirm(command(retry,List.of(line(1,a,p,"1",null,"1.235")))))
                .isInstanceOf(IllegalArgumentException.class);
        service.confirm(command(retry,List.of(line(1,a,p,"1",null,"1.23"))));
        assertThat(stock(a)).isEqualByComparingTo("2");
    }

    @Test
    void aggregateOverflowAndInvalidOwnershipOrLineNumbersHaveNoEffects() {
        long a=active("9999999999999.99"),b=active("1"),p=presentation(a,"FIJA","1"),other=presentation(b,"FIJA","1");
        assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),List.of(
                line(1,a,p,"0.006",null,"1"),line(2,a,p,"0.005",null,"1")))))
                .hasMessageContaining("NUMERIC(19,6)");
        for (var lines : List.of(List.of(line(1,a,other,"1",null,"1")),
                List.of(line(0,a,p,"1",null,"1")),List.of(line(1,a,p,"1",null,"1"),line(1,a,p,"1",null,"1")),
                List.of(line(1,a,999999,"1",null,"1")))) {
            assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),lines))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.confirm(command(UUID.randomUUID(),List.of()))).hasMessageContaining("requiere líneas");
        emptyReceiptsAndEffects();
        assertThat(stock(a)).isEqualByComparingTo("9999999999999.99");
    }

    @Test
    void applierFailureAfterAnEarlierLineRollsBackHeaderLinesStockAndMovementsAndAllowsRetry() {
        long a=active("1"),b=active("2"),p=presentation(a,"FIJA","1"),q=presentation(b,"FIJA","1");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"),line(2,b,q,"1",null,"1")));
        db.execute("create function receipt_test_fail() returns trigger language plpgsql as $$ begin " +
                "if NEW.insumo_id="+b+" then raise exception 'fallo applier sintético'; end if; return NEW; end $$");
        db.execute("create trigger receipt_test_fail before insert on movimiento_inventario for each row execute function receipt_test_fail()");
        try {
            assertThatThrownBy(() -> service.confirm(c)).hasStackTraceContaining("fallo applier sintético");
            emptyReceiptsAndEffects();
            assertThat(stock(a)).isEqualByComparingTo("1");
            assertThat(stock(b)).isEqualByComparingTo("2");
        } finally {
            db.execute("drop trigger receipt_test_fail on movimiento_inventario");
            db.execute("drop function receipt_test_fail()");
        }
        service.confirm(c);
        assertThat(stock(a)).isEqualByComparingTo("2");
        assertThat(stock(b)).isEqualByComparingTo("3");
        assertThat(count("movimiento_inventario")).isEqualTo(2);
    }

    @Test
    void confirmedReceiptSnapshotsAndCausalEntriesCannotBeEditedOrDeleted() {
        long a=active("1"),p=presentation(a,"FIJA","24");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        var result=service.confirm(c);
        for (String sql : List.of("update inventory_receipt set moneda='USD'", "delete from inventory_receipt",
                "update inventory_receipt_line set factor_base=25", "delete from inventory_receipt_line",
                "update movimiento_inventario set cantidad=25", "delete from movimiento_inventario")) {
            assertThatThrownBy(() -> db.update(sql)).hasMessageContaining("inmutable");
        }
        assertThat(service.confirm(c)).isEqualTo(result);
        assertThat(stock(a)).isEqualByComparingTo("25");
    }

    private List<ReceiptService.Result> concurrent(ReceiptService.Command first,ReceiptService.Command second) throws Exception {
        try (var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);
            var start=new CountDownLatch(1);
            var a=executor.submit(() -> { ready.countDown(); start.await(); return service.confirm(first); });
            var b=executor.submit(() -> { ready.countDown(); start.await(); return service.confirm(second); });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSameRequestCommitsExactlyOneReceiptAndOneEffectPerLine() throws Exception {
        long a=active("1"),p=presentation(a,"FIJA","24");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        var results=concurrent(c,c);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(stock(a)).isEqualByComparingTo("25");
        assertThat(count("inventory_receipt")).isEqualTo(1);
        assertThat(count("inventory_receipt_line")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
    }

    @Test
    void concurrentDistinctReceiptsSerializeSharedIngredientsEvenWithReverseLineOrder() throws Exception {
        long a=active("1"),b=active("2"),p=presentation(a,"FIJA","24"),q=presentation(b,"FIJA","1");
        var results=concurrent(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"),line(2,b,q,"2",null,"1"))),
                command(UUID.randomUUID(),List.of(line(1,b,q,"3",null,"1"),line(2,a,p,"2",null,"1"))));
        assertThat(results.get(0).receiptId()).isNotEqualTo(results.get(1).receiptId());
        assertThat(stock(a)).isEqualByComparingTo("73");
        assertThat(stock(b)).isEqualByComparingTo("7");
        assertThat(count("inventory_receipt")).isEqualTo(2);
        assertThat(count("inventory_receipt_line")).isEqualTo(4);
        assertThat(count("movimiento_inventario")).isEqualTo(4);
    }

    @Test
    void concurrentSameRequestWithDifferentPayloadAcceptsOnlyOneIntent() throws Exception {
        long a=active("1"),p=presentation(a,"FIJA","1");
        UUID id=UUID.randomUUID();
        var first=command(id,List.of(line(1,a,p,"1",null,"1")));
        var second=command(id,List.of(line(1,a,p,"2",null,"1")));
        try (var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            var futures=List.of(first,second).stream().map(c -> executor.submit(() -> {
                ready.countDown(); start.await();
                try { return (Object)service.confirm(c); }
                catch (IllegalArgumentException failure) { return (Object)failure; }
            })).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results=List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(ReceiptService.Result.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(IllegalArgumentException.class::isInstance).count()).isEqualTo(1);
            var failure=(IllegalArgumentException)results.stream().filter(IllegalArgumentException.class::isInstance).findFirst().orElseThrow();
            assertThat(failure).hasMessageContaining("requestId reutilizado");
        }
        assertThat(count("inventory_receipt")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo(db.queryForObject(
                "select 1+cantidad_base_efectiva from inventory_receipt_line",BigDecimal.class));
    }

    @Test
    void outerTransactionFailureRollsBackTheWholeReceiptInsteadOfLeavingAnIndependentCommit() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.confirm(c);
            throw new IllegalStateException("fallo posterior sintético");
        })).hasMessage("fallo posterior sintético");
        emptyReceiptsAndEffects();
        assertThat(stock(a)).isEqualByComparingTo("1");
        service.confirm(c);
        assertThat(stock(a)).isEqualByComparingTo("2");
    }

    @Test
    void deferredCommitFailureRollsBackEvenAfterApplyingAllEntries() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        db.execute("create function receipt_commit_fail() returns trigger language plpgsql as $$ " +
                "begin raise exception 'fallo commit sintético'; end $$");
        db.execute("create constraint trigger receipt_commit_fail after insert on inventory_receipt " +
                "deferrable initially deferred for each row execute function receipt_commit_fail()");
        try {
            assertThatThrownBy(() -> service.confirm(c)).hasStackTraceContaining("fallo commit sintético");
            emptyReceiptsAndEffects();
            assertThat(stock(a)).isEqualByComparingTo("1");
        } finally {
            db.execute("drop trigger receipt_commit_fail on inventory_receipt");
            db.execute("drop function receipt_commit_fail()");
        }
        service.confirm(c);
        assertThat(stock(a)).isEqualByComparingTo("2");
    }

    @Test
    void storageOnlyAggregateIsNotAcknowledgedAsAnOperationalReceiptWithoutCausalEffects() {
        long a=active("1"),p=presentation(a,"FIJA","1");
        var c=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var stored=new com.tuapp.restaurante.models.InventoryReceipt(c.requestId(),ReceiptFingerprint.calculate(c),
                    ReceiptFingerprint.VERSION,"MXN",context.getBean(com.tuapp.restaurante.repositories.UsuarioRepository.class)
                    .getReferenceById(actor),null,null);
            stored.addLine(1,context.getBean(com.tuapp.restaurante.repositories.InventoryPurchasePresentationRepository.class)
                    .getReferenceById(p),BigDecimal.ONE,null,BigDecimal.ONE,UUID.randomUUID());
            context.getBean(com.tuapp.restaurante.repositories.InventoryReceiptRepository.class).saveAndFlush(stored);
        });
        assertThatThrownBy(() -> service.confirm(c)).hasMessageContaining("sin efecto causal íntegro");
        assertThat(stock(a)).isEqualByComparingTo("1");
        assertThat(count("inventory_receipt")).isEqualTo(1);
        assertThat(count("movimiento_inventario")).isZero();
    }

    @Test
    void receiptAndProductionShareInventoryLocksWithoutLostChangesOrDuplicateMovements() throws Exception {
        long a=active("10"),b=active("1"),p=presentation(a,"FIJA","1"),q=presentation(b,"FIJA","1");
        db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) values (?,?,2)",b,a);
        var c=command(UUID.randomUUID(),List.of(line(1,b,q,"2",null,"1"),line(2,a,p,"4",null,"1")));
        try (var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            var receiving=executor.submit(() -> { ready.countDown(); start.await(); return service.confirm(c); });
            var producing=executor.submit(() -> { ready.countDown(); start.await();
                com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> context.getBean(com.tuapp.restaurante.services.ProduccionService.class)
                        .producirInsumo(b,BigDecimal.ONE,actor,UUID.randomUUID())); return true; });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(receiving.get(20,TimeUnit.SECONDS).lines()).hasSize(2);
            assertThat(producing.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(stock(a)).isEqualByComparingTo("12");
        assertThat(stock(b)).isEqualByComparingTo("4");
        assertThat(count("movimiento_inventario")).isEqualTo(4);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario where causal_origin='RECEPCION_RAPIDA'",Long.class))
                .isEqualTo(2);
    }
}
