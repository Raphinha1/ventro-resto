package com.tuapp.restaurante.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.restaurante.models.InventoryPurchasePresentation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationContext;
import static org.mockito.Mockito.*;
import static com.tuapp.restaurante.inventory.InventoryPermissionService.Permission.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="ventro.inventory.currency=MXN")
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class ReceiptReversalWebIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryActivationService activation;
    @Autowired PurchasePresentationService presentations;
    @Autowired ReceiptService receiving;
    @Autowired InventoryChangeApplier applier;
    @Autowired ApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean InventoryPermissionService permissions;
    private long actor,ingredient,receipt;
    @AfterEach void resetPermissions() { reset(permissions); }

    @BeforeEach void fixtures() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate usuario,insumo,proveedor restart identity cascade");
        actor=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) values ('reversalweb','synthetic','Web <actor>','ROLE_ADMIN',true) returning id",Long.class);
        db.update("insert into usuario(username,password,nombre_completo,rol,activo) values ('reversalmanager','synthetic','Manager','ROLE_GERENTE',true)");
        ingredient=db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) values ('Ingrediente web','texto ambiguo',9.75,0,0,true) returning id",Long.class);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),ingredient,BaseUnit.UNIT,
                new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT,null),actor,"fixture","conteo sintético"));
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(),ingredient,actor));
        long presentation=presentations.create(actor,ingredient,new PurchasePresentationService.Create("Caja <histórica>","caja",InventoryPurchasePresentation.Mode.FIJA,new BigDecimal("24"))).id();
        receipt=receiving.confirm(new ReceiptService.Command(UUID.randomUUID(),actor,"MXN",null,"Documento <original>",
                List.of(new ReceiptService.Line(1,ingredient,presentation,new BigDecimal("2"),null,new BigDecimal("35.50"))))).receiptId();
    }
    private org.springframework.test.web.servlet.ResultActions reverse(UUID id,String reason) throws Exception {
        return mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalweb").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(Map.of("requestId",id,"motivo",reason))));
    }
    @Test void successfulReversalAndLostResponseRetryShowDerivedStateAndEscapedReadonlyAudit() throws Exception {
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reverso/nuevo").with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Reverso completo")));
        UUID id=UUID.randomUUID();
        String result=reverse(id,"Error <script>alert('x')</script>").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REVERTIDA")).andExpect(jsonPath("$.receiptId").value(receipt))
                .andExpect(jsonPath("$.requestId").value(id.toString())).andReturn().getResponse().getContentAsString();
        reverse(id,"Error <script>alert('x')</script>").andExpect(status().isOk()).andExpect(content().json(result));
        long reversal=json.readTree(result).get("reversalId").asLong();
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reversos/"+reversal).with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Error &lt;script&gt;")))
                .andExpect(content().string(containsString("REVERSO_RECEPCION_RAPIDA")))
                .andExpect(content().string(containsString("receipt:"+receipt+"/reversal:"+reversal+"/line:1")));
        mvc.perform(get("/inventario/recepciones").with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("REVERTIDA")));
        mvc.perform(get("/inventario/recepciones/"+receipt).with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("REVERTIDA")));
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("0");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("select actor_usuario_id from inventory_receipt_reversal",Long.class)).isEqualTo(actor);
    }

    @Test void readingAndRevertingPermissionsAreIndependentAndRequestIdGrantsNoAccess() throws Exception {
        long reversal=json.readTree(reverse(UUID.randomUUID(),"Motivo").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("reversalId").asLong();
        doThrow(new AccessDeniedException("solo lectura")).when(permissions).require(actor,INVENTARIO_REVERTIR_RECEPCION);
        doReturn(false).when(permissions).allowed(actor,INVENTARIO_REVERTIR_RECEPCION);
        mvc.perform(get("/inventario/recepciones").with(user("reversalweb").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/inventario/recepciones/"+receipt).with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("Iniciar reverso"))));
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reversos/"+reversal).with(user("reversalweb").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reverso/nuevo").with(user("reversalweb").roles("ADMIN"))).andExpect(status().isForbidden());
        reverse(UUID.randomUUID(),"No autorizado").andExpect(status().isForbidden());
        reset(permissions);
        doThrow(new AccessDeniedException("solo revierte")).when(permissions).require(actor,INVENTARIO_RECIBIR);
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reversos/"+reversal).with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reversos/"+reversal)).andExpect(status().is3xxRedirection());
    }
    @Test void revertPermissionAloneCanPrepareAndConfirmWithoutRequiringReadPermission() throws Exception {
        doThrow(new AccessDeniedException("solo revierte")).when(permissions).require(actor,INVENTARIO_RECIBIR);
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reverso/nuevo").with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk());
        reverse(UUID.randomUUID(),"Motivo").andExpect(status().isOk());
    }
    @Test void strictCaptureRejectsActorQuantityUnitOperationAndRouteIdentityInjection() throws Exception {
        for (String field : List.of("actorId","receiptId","cantidad","units","operationId","lines")) {
            String body=json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"motivo","Error",field,"123"));
            mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalweb").roles("ADMIN")).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}","{\"requestId\":null,\"motivo\":\"Error\"}",
                "{\"requestId\":\""+UUID.randomUUID()+"\",\"motivo\":\" \"}",
                "{\"requestId\":\""+UUID.randomUUID()+"\",\"motivo\":42}")) {
            mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalweb").roles("ADMIN")).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalweb").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"requestId\":\"invalid\",\"motivo\":\"Error\"}")).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isZero();
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("48");
    }
    @Test void csrfRolesAndInactiveUserAreHandledBeforeAnyEffects() throws Exception {
        String body=json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"motivo","Error"));
        mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalweb").roles("ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalmanager").roles("GERENTE")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        db.update("update usuario set activo=false where id=?",actor);
        // ActiveUserFilter invalida la sesión y redirige antes del controller/ExceptionHandler.
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reverso/nuevo").with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?expired=true"));
        mvc.perform(post("/inventario/recepciones/"+receipt+"/reversos").with(user("reversalweb").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().is3xxRedirection());
        assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isZero();
    }
    @Test void insufficientStockHasNoEffectsAndSameAttemptCanRetryAfterRealReplenishment() throws Exception {
        applier.apply(new InventoryChangeApplier.Command(UUID.randomUUID(),ingredient,InventoryChangeApplier.Type.SALIDA,
                new BigDecimal("47"),BaseUnit.UNIT,actor,"FIXTURE","consume","consumo sintético"));
        UUID id=UUID.randomUUID();
        reverse(id,"Error").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SALDO_INSUFICIENTE"))
                .andExpect(jsonPath("$.sinEfectos").value(true));
        assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("1");
        applier.apply(new InventoryChangeApplier.Command(UUID.randomUUID(),ingredient,InventoryChangeApplier.Type.ENTRADA,
                new BigDecimal("47"),BaseUnit.UNIT,actor,"FIXTURE","replenish","reposición sintética"));
        reverse(id,"Error").andExpect(status().isOk());
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("0");
    }
    @Test void historyDistinguishesStatesAndSecondAttemptOrWrongReceiptIsRejected() throws Exception {
        mvc.perform(get("/inventario/recepciones").with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("CONFIRMADA")));
        UUID id=UUID.randomUUID();
        long reversal=json.readTree(reverse(id,"Motivo").andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("reversalId").asLong();
        reverse(id,"Otro motivo").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLICTO_REQUEST_ID"));
        reverse(UUID.randomUUID(),"Motivo").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("YA_REVERTIDA"));
        mvc.perform(get("/inventario/recepciones/"+receipt+"/reverso/nuevo").with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("La recepción ya está REVERTIDA")))
                .andExpect(content().string(containsString("disabled=\"disabled\"")));
        mvc.perform(get("/inventario/recepciones/"+(receipt+1)+"/reversos/"+reversal).with(user("reversalweb").roles("ADMIN")))
                .andExpect(status().isUnprocessableEntity());
        for (var method : List.of(org.springframework.http.HttpMethod.DELETE,org.springframework.http.HttpMethod.PUT,org.springframework.http.HttpMethod.PATCH)) {
            mvc.perform(request(method,"/inventario/recepciones/"+receipt+"/reversos/"+reversal).with(user("reversalweb").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(request(method,"/inventario/recepciones/"+receipt).with(user("reversalweb").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isMethodNotAllowed());
        }
        assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isEqualTo(1);
    }
    @Test void causalStorageOnlyReceiptIsRejectedWithoutRepairingIt() throws Exception {
        long p=db.queryForObject("select id from inventory_purchase_presentation",Long.class);
        var command=new ReceiptService.Command(UUID.randomUUID(),actor,"MXN",null,null,
                List.of(new ReceiptService.Line(1,ingredient,p,BigDecimal.ONE,null,BigDecimal.ONE)));
        receipt=new TransactionTemplate(transactions).execute(status -> {
            var stored=new com.tuapp.restaurante.models.InventoryReceipt(command.requestId(),ReceiptFingerprint.calculate(command),
                    ReceiptFingerprint.VERSION,"MXN",context.getBean(com.tuapp.restaurante.repositories.UsuarioRepository.class).getReferenceById(actor),null,null);
            stored.addLine(1,context.getBean(com.tuapp.restaurante.repositories.InventoryPurchasePresentationRepository.class).getReferenceById(p),
                    BigDecimal.ONE,null,BigDecimal.ONE,UUID.randomUUID());
            return context.getBean(com.tuapp.restaurante.repositories.InventoryReceiptRepository.class).saveAndFlush(stored).getId();
        });
        reverse(UUID.randomUUID(),"Error").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("INTEGRIDAD_CAUSAL"));
        assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(1);
    }
    @Test void technicalFailureIsUnknownWithoutSqlDetailsAndExactRetryIsSafe() throws Exception {
        UUID id=UUID.randomUUID();
        db.execute("create function reversal_web_fail() returns trigger language plpgsql as $$ begin raise exception 'detalle SQL sintético'; end $$");
        db.execute("create trigger reversal_web_fail before insert on inventory_receipt_reversal for each row execute function reversal_web_fail()");
        try {
            reverse(id,"Motivo").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.codigo").value("RESULTADO_DESCONOCIDO"))
                    .andExpect(jsonPath("$.sinEfectos").value(false))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("detalle SQL"))));
            assertThat(db.queryForObject("select count(*) from inventory_receipt_reversal",Long.class)).isZero();
            assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("48");
        } finally {
            db.execute("drop trigger reversal_web_fail on inventory_receipt_reversal"); db.execute("drop function reversal_web_fail()");
        }
        reverse(id,"Motivo").andExpect(status().isOk());
        reverse(id,"Motivo").andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(2);
    }
}
