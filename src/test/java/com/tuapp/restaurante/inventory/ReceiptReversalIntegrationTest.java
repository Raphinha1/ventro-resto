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
class ReceiptReversalIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ReceiptService service;
    @Autowired ReceiptReversalService reversal;
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

    private ReceiptReversalService.Command reverseCommand(long receipt) {
        return new ReceiptReversalService.Command(UUID.randomUUID(),receipt,actor,"Corrección sintética");
    }
    private long count(String table) { return db.queryForObject("select count(*) from "+table,Long.class); }
    private long outgoing() { return db.queryForObject("select count(*) from movimiento_inventario where causal_origin='REVERSO_RECEPCION_RAPIDA'",Long.class); }
    private String originals() { return db.queryForObject("select md5(string_agg(row_to_json(r)::text||row_to_json(l)::text,',' order by l.id)) from inventory_receipt r join inventory_receipt_line l on l.receipt_id=r.id",String.class); }
    private void noReversal() {
        assertThat(count("inventory_receipt_reversal")).isZero();
        assertThat(count("inventory_receipt_reversal_line")).isZero();
        assertThat(outgoing()).isZero();
    }
    @Test void fixedVariableMultilineAndRepeatedIngredientReverseOriginalSnapshotsToExactZero() {
        long a=active("0"),b=active("0"),fixed=presentation(a,"FIJA","24"),variable=presentation(a,"VARIABLE",null),other=presentation(b,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(3,b,other,"0.000001",null,"1"),
                line(2,a,variable,"2","3.5","1"),line(1,a,fixed,"2",null,"1"))));
        String original=originals();
        db.update("update inventory_purchase_presentation set nombre='Retirada',estado='INACTIVA'");
        var result=reversal.reverse(reverseCommand(receipt.receiptId()));
        assertThat(result.lines()).extracting(ReceiptReversalService.ReversedLine::lineNo).containsExactly(1,2,3);
        assertThat(result.lines()).extracting(ReceiptReversalService.ReversedLine::baseQuantity)
                .containsExactly(new BigDecimal("48.000000"),new BigDecimal("3.500000"),new BigDecimal("0.000001"));
        assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(stock(b)).isEqualByComparingTo("0");
        assertThat(outgoing()).isEqualTo(3); assertThat(count("movimiento_inventario")).isEqualTo(6);
        assertThat(originals()).isEqualTo(original);
        assertThat(db.queryForList("select costo_unitario from insumo",BigDecimal.class)).allSatisfy(c -> assertThat(c).isEqualByComparingTo("9.75"));
        assertThat(db.queryForObject("select sealed from inventory_receipt_reversal",Boolean.class)).isTrue();
        assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal_line l join inventory_receipt_reversal r on r.id=l.reversal_id join movimiento_inventario m on m.operation_id=l.operation_id where m.tipo_movimiento='SALIDA' and m.causal_reference='receipt:'||r.receipt_id||'/reversal:'||r.id||'/line:'||l.line_no",Long.class)).isEqualTo(3);
    }
    @Test void retryAfterLostResponseUsesDurableHistoryWithoutCheckingCurrentStockCatalogOrConfiguration() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"))));
        var c=reverseCommand(receipt.receiptId()); var result=reversal.reverse(c); // simula respuesta perdida tras commit
        db.update("update inventory_purchase_presentation set nombre='Otra',estado='INACTIVA'");
        var another=new ReceiptReversalService(db,context.getBean(InventoryPermissionService.class),context.getBean(InventoryChangeApplier.class),service,
                context.getBean(com.tuapp.restaurante.repositories.InventoryReceiptRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.InventoryReceiptReversalRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.InsumoRepository.class),context.getBean(com.tuapp.restaurante.repositories.UsuarioRepository.class),
                context.getBean(com.tuapp.restaurante.repositories.MovimientoInventarioRepository.class));
        ReceiptReversalService.Result replay=new TransactionTemplate(transactions).execute(status -> another.reverse(c));
        assertThat(replay).isEqualTo(result);
        assertThat(reversal.reverse(new ReceiptReversalService.Command(c.requestId(),c.receiptId(),actor,"  "+c.motivo()+"  "))).isEqualTo(result);
        assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(outgoing()).isEqualTo(1);
    }

    @Test void insufficientAggregateBlocksEntireReversalAndNewAttemptCanRetryAfterRollback() {
        long a=active("0"),b=active("0"),p=presentation(a,"FIJA","1"),q=presentation(b,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"3",null,"1"),line(2,a,p,"3",null,"1"),line(3,b,q,"1",null,"1"))));
        context.getBean(InventoryChangeApplier.class).apply(new InventoryChangeApplier.Command(UUID.randomUUID(),a,
                InventoryChangeApplier.Type.SALIDA,new BigDecimal("1"),BaseUnit.UNIT,actor,"FIXTURE","fixture:consumo",null));
        var c=reverseCommand(receipt.receiptId());
        assertThatThrownBy(() -> reversal.reverse(c)).isInstanceOf(ReceiptReversalException.class).hasMessageContaining("Saldo insuficiente");
        noReversal(); assertThat(stock(a)).isEqualByComparingTo("5"); assertThat(stock(b)).isEqualByComparingTo("1");
        service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        reversal.reverse(c); assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(stock(b)).isEqualByComparingTo("0");
    }
    @Test void changedIntentAndDifferentRequestForRevertedReceiptHaveDistinctRejections() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        var c=reverseCommand(receipt.receiptId()); reversal.reverse(c);
        for (var changed : List.of(new ReceiptReversalService.Command(c.requestId(),c.receiptId(),actor,"Otro motivo"),
                new ReceiptReversalService.Command(c.requestId(),c.receiptId()+1,actor,c.motivo()))) {
            assertThatThrownBy(() -> reversal.reverse(changed)).isInstanceOfSatisfying(ReceiptReversalException.class,
                    e -> assertThat(e.kind()).isEqualTo(ReceiptReversalException.Kind.CONFLICTO_REQUEST_ID));
        }
        long other=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) values ('other','fixture','Fixture','ROLE_ADMIN',true) returning id",Long.class);
        assertThatThrownBy(() -> reversal.reverse(new ReceiptReversalService.Command(c.requestId(),c.receiptId(),other,c.motivo())))
                .hasMessageContaining("requestId reutilizado");
        assertThatThrownBy(() -> reversal.reverse(reverseCommand(receipt.receiptId())))
                .isInstanceOfSatisfying(ReceiptReversalException.class,e -> assertThat(e.kind()).isEqualTo(ReceiptReversalException.Kind.YA_REVERTIDA));
        assertThat(outgoing()).isEqualTo(1); assertThat(stock(a)).isEqualByComparingTo("0");
    }
    @Test void authorizationIsRequiredForNewOperationsAndHistoricalRetriesAndReasonIsMandatory() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        for (String reason : List.of("","  ","x".repeat(1001))) {
            assertThatThrownBy(() -> reversal.reverse(new ReceiptReversalService.Command(UUID.randomUUID(),receipt.receiptId(),actor,reason)))
                    .hasMessageContaining("Motivo");
        }
        noReversal(); var c=reverseCommand(receipt.receiptId()); reversal.reverse(c);
        for (String role : List.of("ROLE_GERENTE","ROLE_CAJERO","ROLE_SUPER_ADMIN")) {
            db.update("update usuario set rol=? where id=?",role,actor);
            assertThatThrownBy(() -> reversal.reverse(c)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reversal.reverse(reverseCommand(receipt.receiptId()))).isInstanceOf(AccessDeniedException.class);
        }
        db.update("update usuario set rol='ROLE_ADMIN',activo=false where id=?",actor);
        assertThatThrownBy(() -> reversal.reverse(c)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reversal.reverse(new ReceiptReversalService.Command(UUID.randomUUID(),receipt.receiptId(),null,"Motivo")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(outgoing()).isEqualTo(1);
    }
    @Test void missingOriginalCauseIsNotRepairedOrReversed() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var original=command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1")));
        long id=new TransactionTemplate(transactions).execute(status -> {
            var stored=new com.tuapp.restaurante.models.InventoryReceipt(original.requestId(),ReceiptFingerprint.calculate(original),
                    ReceiptFingerprint.VERSION,"MXN",context.getBean(com.tuapp.restaurante.repositories.UsuarioRepository.class).getReferenceById(actor),null,null);
            stored.addLine(1,context.getBean(com.tuapp.restaurante.repositories.InventoryPurchasePresentationRepository.class).getReferenceById(p),
                    BigDecimal.ONE,null,BigDecimal.ONE,UUID.randomUUID());
            return context.getBean(com.tuapp.restaurante.repositories.InventoryReceiptRepository.class).saveAndFlush(stored).getId();
        });
        assertThatThrownBy(() -> reversal.reverse(reverseCommand(id))).hasMessageContaining("integridad causal");
        noReversal(); assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(count("inventory_receipt")).isEqualTo(1);
    }
    @Test void legacyStateCannotReverseButCommittedRetryDoesNotDependOnCurrentState() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        var c=reverseCommand(receipt.receiptId());
        db.update("update insumo set estado_inventario_5a='RECONCILIADO' where id=?",a);
        assertThatThrownBy(() -> reversal.reverse(c)).hasMessageContaining("ACTIVO_5A"); noReversal();
        // Solo fixture sintético: no hay degradación operativa de estado.
        db.update("update insumo set estado_inventario_5a='ACTIVO_5A' where id=?",a);
        var result=reversal.reverse(c);
        db.update("update insumo set estado_inventario_5a='RECONCILIADO' where id=?",a);
        assertThat(reversal.reverse(c)).isEqualTo(result); assertThat(outgoing()).isEqualTo(1);
    }
    @Test void failureAfterFirstMovementRollsBackEverythingAndAllowsExactRetry() {
        long a=active("0"),b=active("0"),p=presentation(a,"FIJA","1"),q=presentation(b,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"),line(2,b,q,"3",null,"1"))));
        var c=reverseCommand(receipt.receiptId());
        db.execute("create function reversal_test_fail() returns trigger language plpgsql as $$ begin if NEW.causal_origin='REVERSO_RECEPCION_RAPIDA' and NEW.insumo_id="+b+" then raise exception 'fallo reverso sintético'; end if; return NEW; end $$");
        db.execute("create trigger reversal_test_fail before insert on movimiento_inventario for each row execute function reversal_test_fail()");
        try {
            assertThatThrownBy(() -> reversal.reverse(c)).hasStackTraceContaining("fallo reverso sintético");
            noReversal(); assertThat(stock(a)).isEqualByComparingTo("2"); assertThat(stock(b)).isEqualByComparingTo("3");
        } finally { db.execute("drop trigger reversal_test_fail on movimiento_inventario"); db.execute("drop function reversal_test_fail()"); }
        reversal.reverse(c); assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(stock(b)).isEqualByComparingTo("0");
    }
    @Test void failureAfterApplyAllAndDeferredCommitBothRollBackAndKeepRequestReusable() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"))));
        var c=reverseCommand(receipt.receiptId());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            reversal.reverse(c); throw new IllegalStateException("fallo posterior");
        })).hasMessage("fallo posterior"); noReversal(); assertThat(stock(a)).isEqualByComparingTo("2");
        db.execute("create function reversal_commit_fail() returns trigger language plpgsql as $$ begin raise exception 'fallo commit reverso'; end $$");
        db.execute("create constraint trigger reversal_commit_fail after insert on inventory_receipt_reversal deferrable initially deferred for each row execute function reversal_commit_fail()");
        try {
            assertThatThrownBy(() -> reversal.reverse(c)).hasStackTraceContaining("fallo commit reverso");
            noReversal(); assertThat(stock(a)).isEqualByComparingTo("2");
        } finally { db.execute("drop trigger reversal_commit_fail on inventory_receipt_reversal"); db.execute("drop function reversal_commit_fail()"); }
        reversal.reverse(c); assertThat(stock(a)).isEqualByComparingTo("0");
    }
    private List<Object> race(java.util.concurrent.Callable<?> one,java.util.concurrent.Callable<?> two) throws Exception {
        try (var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            var calls=List.of(one,two).stream().map(call -> executor.submit(() -> {
                ready.countDown(); start.await();
                try { return (Object)call.call(); } catch (IllegalArgumentException expected) { return (Object)expected; }
            })).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(calls.get(0).get(20,TimeUnit.SECONDS),calls.get(1).get(20,TimeUnit.SECONDS));
        }
    }
    @Test void concurrentSameRequestCommitsOneReversalAndDifferentKeysCannotReverseTwice() throws Exception {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"))));
        var c=reverseCommand(receipt.receiptId());
        var equal=race(() -> reversal.reverse(c),() -> reversal.reverse(c));
        assertThat(equal.get(0)).isInstanceOf(ReceiptReversalService.Result.class).isEqualTo(equal.get(1));
        assertThat(outgoing()).isEqualTo(1); assertThat(stock(a)).isEqualByComparingTo("0");
        var another=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"))));
        var distinct=race(() -> reversal.reverse(reverseCommand(another.receiptId())),() -> reversal.reverse(reverseCommand(another.receiptId())));
        assertThat(distinct.stream().filter(ReceiptReversalService.Result.class::isInstance).count()).isEqualTo(1);
        assertThat(distinct.stream().filter(ReceiptReversalException.class::isInstance).count()).isEqualTo(1);
        assertThat(distinct.stream().filter(ReceiptReversalException.class::isInstance).map(ReceiptReversalException.class::cast).findFirst().orElseThrow().kind())
                .isEqualTo(ReceiptReversalException.Kind.YA_REVERTIDA);
        assertThat(outgoing()).isEqualTo(2); assertThat(stock(a)).isEqualByComparingTo("0");
    }
    @Test void concurrentSameKeyDifferentPayloadRecognizesOnlyOneIntent() throws Exception {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"))));
        var c=reverseCommand(receipt.receiptId());
        var outcomes=race(() -> reversal.reverse(c),() -> reversal.reverse(new ReceiptReversalService.Command(c.requestId(),c.receiptId(),actor,"Otro motivo")));
        assertThat(outcomes.stream().filter(ReceiptReversalService.Result.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(ReceiptReversalException.class::isInstance).count()).isEqualTo(1);
        assertThat(outgoing()).isEqualTo(1);
    }
    @Test void concurrentDifferentReceiptAndReversalPreserveBothChanges() throws Exception {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"3",null,"1"))));
        var outcomes=race(() -> reversal.reverse(reverseCommand(receipt.receiptId())),
                () -> service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"4",null,"1")))));
        assertThat(outcomes).noneMatch(IllegalArgumentException.class::isInstance);
        assertThat(stock(a)).isEqualByComparingTo("4"); assertThat(count("movimiento_inventario")).isEqualTo(3);
    }
    @Test void reversalVersusProductionEitherRejectsWholeConsumptionOrWholeReversal() throws Exception {
        long a=active("0"),b=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"3",null,"1"))));
        db.update("insert into sub_receta(insumo_destino_id,ingrediente_id,cantidad) values (?,?,2)",b,a);
        var outcomes=race(() -> reversal.reverse(reverseCommand(receipt.receiptId())),() -> {
            com.tuapp.restaurante.SyntheticSecurity.runAs(db, actor, () -> context.getBean(com.tuapp.restaurante.services.ProduccionService.class).producirInsumo(b,BigDecimal.ONE,actor,UUID.randomUUID())); return Boolean.TRUE;
        });
        assertThat(outcomes.stream().filter(IllegalArgumentException.class::isInstance).count()).isEqualTo(1);
        if (outcomes.get(0) instanceof ReceiptReversalService.Result) {
            assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(stock(b)).isEqualByComparingTo("0"); assertThat(outgoing()).isEqualTo(1);
        } else {
            assertThat(stock(a)).isEqualByComparingTo("1"); assertThat(stock(b)).isEqualByComparingTo("1"); noReversal();
        }
    }
    @Test void reversalVersusSaleLeavesNoNegativeStockOrPartialSale() throws Exception {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"3",null,"1"))));
        long product=db.queryForObject("insert into producto(nombre,precio_venta,puntos_otorgados,puntos_precio,activo,es_reventa) values ('Fixture',10,0,0,true,false) returning id",Long.class);
        db.update("insert into receta(producto_id,insumo_id,cantidad) values (?,?,2)",product,a);
        db.update("insert into corte_caja(fecha_apertura,monto_inicial,estado,total_efectivo,total_tarjeta,total_ventas,total_retiros,usuario_id) values (now(),0,'ABIERTO',0,0,0,0,?)",actor);
        var outcomes=race(() -> reversal.reverse(reverseCommand(receipt.receiptId())),() ->
                context.getBean(com.tuapp.restaurante.services.VentaService.class).procesarVentasMultiples(List.of(product),List.of(1),List.of(""),actor,
                        "EFECTIVO",new BigDecimal("10"),BigDecimal.ZERO,null,null,List.of(false),UUID.randomUUID().toString()));
        assertThat(outcomes.stream().filter(IllegalArgumentException.class::isInstance).count()).isEqualTo(1);
        if (outcomes.get(0) instanceof ReceiptReversalService.Result) {
            assertThat(stock(a)).isEqualByComparingTo("0"); assertThat(count("ventas")).isZero();
        } else {
            assertThat(stock(a)).isEqualByComparingTo("1"); assertThat(count("ventas")).isEqualTo(1); noReversal();
        }
    }

    private long draft(long receipt,UUID request) {
        return db.queryForObject("insert into inventory_receipt_reversal(receipt_id,request_id,command_fingerprint,fingerprint_version,actor_usuario_id,motivo) values (?,?,repeat('a',64),'fixture-v1',?,'Fixture') returning id",Long.class,receipt,request,actor);
    }
    private void copyLine(long reversalId,long originalLine,UUID operation,String quantity) {
        db.update("insert into inventory_receipt_reversal_line(reversal_id,original_receipt_line_id,line_no,insumo_id,familia,unidad_base_codigo,cantidad_base_compensada,operation_id) select ?,id,line_no,insumo_id,familia,unidad_base_codigo,?::numeric,? from inventory_receipt_line where id=?",reversalId,quantity,operation,originalLine);
    }
    @Test void directDatabaseRejectsImmutableChangesAppendUnsealAndPreservesRetry() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"2",null,"1"))));
        var c=reverseCommand(receipt.receiptId()); var result=reversal.reverse(c);
        for (String sql : List.of("update inventory_receipt_reversal set motivo='Otro'", "delete from inventory_receipt_reversal",
                "update inventory_receipt_reversal set sealed=false", "update inventory_receipt_reversal_line set cantidad_base_compensada=1",
                "delete from inventory_receipt_reversal_line", "insert into inventory_receipt_reversal_line(reversal_id,original_receipt_line_id,line_no,insumo_id,familia,unidad_base_codigo,cantidad_base_compensada,operation_id) select reversal_id,original_receipt_line_id,line_no,insumo_id,familia,unidad_base_codigo,cantidad_base_compensada,gen_random_uuid() from inventory_receipt_reversal_line")) {
            assertThatThrownBy(() -> db.update(sql)).hasMessageContaining(sql.startsWith("insert")?"junto al reverso":"inmutable");
        }
        assertThatThrownBy(() -> db.update("delete from movimiento_inventario where causal_origin='REVERSO_RECEPCION_RAPIDA'"))
                .hasMessageContaining("inmutable");
        assertThat(reversal.reverse(c)).isEqualTo(result); assertThat(outgoing()).isEqualTo(1);
    }
    @Test void directDatabaseRequiresCompleteCoverageCausalEffectsAndSnapshotBelonging() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var first=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"),line(2,a,p,"1",null,"1"))));
        var second=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        List<Long> lines=db.queryForList("select id from inventory_receipt_line where receipt_id=? order by line_no",Long.class,first.receiptId());
        long foreign=db.queryForObject("select id from inventory_receipt_line where receipt_id=?",Long.class,second.receiptId());
        for (String bad : List.of("empty","missing-effects","partial","foreign","quantity","precision","duplicate-line","duplicate-operation")) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                long id=draft(first.receiptId(),UUID.randomUUID());
                UUID operation=UUID.randomUUID();
                if (bad.equals("foreign")) copyLine(id,foreign,operation,"1");
                else if (bad.equals("quantity")) copyLine(id,lines.get(0),operation,"2");
                else if (bad.equals("precision")) copyLine(id,lines.get(0),operation,"1.0000001");
                else if (!bad.equals("empty")) {
                    copyLine(id,lines.get(0),operation,"1");
                    if (bad.equals("duplicate-line")) copyLine(id,lines.get(0),UUID.randomUUID(),"1");
                    if (bad.equals("duplicate-operation")) copyLine(id,lines.get(1),operation,"1");
                    if (bad.equals("missing-effects")) copyLine(id,lines.get(1),UUID.randomUUID(),"1");
                }
            })).isInstanceOf(RuntimeException.class);
            noReversal();
        }
        assertThat(stock(a)).isEqualByComparingTo("3");
    }
    @Test void directDatabaseUniquenessGuardsReceiptRequestAndOperationIdentity() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var first=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        var second=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        var c=reverseCommand(first.receiptId()); var result=reversal.reverse(c);
        assertThatThrownBy(() -> draft(first.receiptId(),UUID.randomUUID())).hasMessageContaining("unique");
        assertThatThrownBy(() -> draft(second.receiptId(),c.requestId())).hasMessageContaining("unique");
        long original=db.queryForObject("select id from inventory_receipt_line where receipt_id=?",Long.class,second.receiptId());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                copyLine(draft(second.receiptId(),UUID.randomUUID()),original,result.lines().get(0).operationId(),"1")))
                .hasMessageContaining("unique");
        assertThat(count("inventory_receipt_reversal")).isEqualTo(1); assertThat(outgoing()).isEqualTo(1);
    }


    @Test void migrateV12WithHistoricalReceiptsWithoutAlteringSnapshotsOrStock() throws Exception {
        String name="ventro_reversal_"+UUID.randomUUID().toString().replace("-", "");
        var env=context.getBean(org.springframework.core.env.Environment.class);
        db.execute("create database "+name);
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(
                env.getRequiredProperty("spring.datasource.url").replaceFirst("/[^/]+$", "/"+name),
                env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"));
        var local=new JdbcTemplate(source);
        try {
            var previous=org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration").target("12").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(12);
            local.update("insert into usuario(username,password,nombre_completo,rol,activo) values ('admin','fixture','Fixture','ROLE_ADMIN',true)");
            for (String file : List.of("inventory5a-restore-fixture.sql","inventory5b1-storage-fixture.sql")) {
                String script=new org.springframework.core.io.ClassPathResource(file).getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                        .replace("ventro_legacy",name);
                // JDBC execute envía el fixture entero, preservando bloques dollar-quoted de PostgreSQL.
                local.execute(script);
            }
            var tables=List.of("usuario","insumo","movimiento_inventario","inventory_receipt","inventory_receipt_line","inventory_purchase_presentation");
            var snapshots=new java.util.HashMap<String,List<String>>();
            tables.forEach(t -> snapshots.put(t,local.queryForList("select row_to_json(t)::text from "+t+" t order by id",String.class)));
            var latest=org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            tables.forEach(t -> assertThat(local.queryForList("select row_to_json(t)::text from "+t+" t order by id",String.class)).isEqualTo(snapshots.get(t)));
            assertThat(local.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isZero();
            assertThat(latest.migrate().migrationsExecuted).isZero(); latest.validate();
        } finally { db.execute("drop database "+name+" with (force)"); }
    }

    @Test void changedHistoricalUnitBlocksNewReversalWithoutReinterpretingIt() {
        long a=active("0"),p=presentation(a,"FIJA","1");
        var receipt=service.confirm(command(UUID.randomUUID(),List.of(line(1,a,p,"1",null,"1"))));
        // Corrupción sintética para ejercitar defensa del servicio; jamás ruta operativa.
        db.execute("alter table insumo disable trigger user");
        try { db.update("update insumo set unidad_base_familia='MASS',unidad_base_codigo='g' where id=?",a); }
        finally { db.execute("alter table insumo enable trigger user"); }
        assertThatThrownBy(() -> reversal.reverse(reverseCommand(receipt.receiptId()))).hasMessageContaining("Unidad histórica incompatible");
        noReversal(); assertThat(stock(a)).isEqualByComparingTo("1");
    }
    @Test void malformedCommandOrMissingReceiptCannotWriteAnything() {
        for (var c : List.of(new ReceiptReversalService.Command(null,1L,actor,"Motivo"),
                new ReceiptReversalService.Command(UUID.randomUUID(),null,actor,"Motivo"),
                new ReceiptReversalService.Command(UUID.randomUUID(),0L,actor,"Motivo"),
                new ReceiptReversalService.Command(UUID.randomUUID(),1L,actor,null),
                new ReceiptReversalService.Command(UUID.randomUUID(),99999L,actor,"Motivo"))) {
            assertThatThrownBy(() -> reversal.reverse(c)).isInstanceOf(IllegalArgumentException.class);
            noReversal();
        }
    }
}
