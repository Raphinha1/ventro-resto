package com.tuapp.restaurante.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.access.AccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static com.tuapp.restaurante.inventory.InventoryPermissionService.Permission.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="ventro.inventory.currency=MXN")
@AutoConfigureMockMvc
@ActiveProfiles("slice0")
class ReceiptWebIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired InventoryReconciliationService reconciliation;
    @Autowired InventoryActivationService activation;
    @SpyBean InventoryPermissionService permissions;
    private long actor;
    @AfterEach void restorePermissionPolicy() { reset(permissions); }
    @BeforeEach void fixtures() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("ventro_slice0");
        db.execute("truncate usuario,insumo,proveedor restart identity cascade");
        actor=db.queryForObject("insert into usuario(username,password,nombre_completo,rol,activo) values ('web5b','synthetic','Web','ROLE_ADMIN',true) returning id",Long.class);
        db.update("insert into usuario(username,password,nombre_completo,rol,activo) values ('manager5b','synthetic','Manager','ROLE_GERENTE',true)");

    }
    @Test void receiverCanOpenNewReceiptAndReadEmptyHistory() throws Exception {
        mvc.perform(get("/inventario/recepciones/nueva").with(user("web5b").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nueva recepción")));
        mvc.perform(get("/inventario/recepciones").with(user("web5b").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private long active() {
        long id=db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) "+
                "values ('Ingrediente web','texto ambiguo',9.75,1,0,true) returning id",Long.class);
        reconciliation.approve(new InventoryReconciliationService.Command(UUID.randomUUID(),id,BaseUnit.UNIT,
                new LegacyInventoryClassifier.Evidence(BaseUnit.UNIT,null),actor,"fixture","conteo sintético"));
        activation.activate(new InventoryActivationService.Command(UUID.randomUUID(),id,actor));
        return id;
    }
    private long create(long ingredient,String mode,String factor) throws Exception {
        String response=mvc.perform(post("/insumos/"+ingredient+"/presentaciones").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(new PurchasePresentationService.Create(
                        "Caja <texto>","caja",com.tuapp.restaurante.models.InventoryPurchasePresentation.Mode.valueOf(mode),
                        factor==null?null:new BigDecimal(factor)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }
    private ReceiptService.Line line(int no,long ingredient,long presentation,String quantity,String base,String cost) {
        return new ReceiptService.Line(no,ingredient,presentation,new BigDecimal(quantity),base==null?null:new BigDecimal(base),new BigDecimal(cost));
    }
    private String capture(UUID id,List<ReceiptService.Line> lines) throws Exception {
        return json.writeValueAsString(new com.tuapp.restaurante.controllers.InventoryReceiptController.Capture(id,"MXN",null,"DOC <sintético>",lines));
    }
    private org.springframework.test.web.servlet.ResultActions confirm(String body) throws Exception {
        return mvc.perform(post("/inventario/recepciones").with(user("web5b").roles("ADMIN")).with(csrf()).contentType("application/json").content(body));
    }
    @Test void catalogCreatesFixedAndVariableThenRenamesAndChangesOnlyStatus() throws Exception {
        long ingredient=active(),fixed=create(ingredient,"FIJA","24"),variable=create(ingredient,"VARIABLE",null);
        mvc.perform(get("/insumos/"+ingredient+"/presentaciones").with(user("web5b").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Caja &lt;texto&gt;")));
        mvc.perform(post("/insumos/"+ingredient+"/presentaciones/"+fixed+"/nombre").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"nombre\":\"Otro nombre\"}")).andExpect(status().isOk());
        mvc.perform(post("/insumos/"+ingredient+"/presentaciones/"+fixed+"/estado").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"estado\":\"INACTIVA\"}")).andExpect(status().isOk());
        mvc.perform(get("/insumos/"+ingredient+"/presentaciones?captura=true").with(user("web5b").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(variable)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/insumos/"+ingredient+"/presentaciones/"+fixed+"/estado").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"estado\":\"ACTIVA\"}")).andExpect(status().isOk());
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("1");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isZero();
    }
    @Test void ruleFieldsCannotBeBoundIntoRenameOrStateAndOwnershipIsValidated() throws Exception {
        long ingredient=active(),other=active(),p=create(ingredient,"FIJA","24");
        for (String field: List.of("insumoId","modo","familia","unidadBase","unidadCompra","factor")) {
            confirmCatalog(ingredient,p,"nombre","{\"nombre\":\"Cambio\",\""+field+"\":\"123\"}").andExpect(status().isBadRequest());
            confirmCatalog(ingredient,p,"estado","{\"estado\":\"INACTIVA\",\""+field+"\":\"123\"}").andExpect(status().isBadRequest());
        }
        confirmCatalog(other,p,"nombre","{\"nombre\":\"Cambio\"}").andExpect(status().isUnprocessableEntity());
        assertThat(db.queryForObject("select factor_base from inventory_purchase_presentation where id=?",BigDecimal.class,p)).isEqualByComparingTo("24");
    }
    private org.springframework.test.web.servlet.ResultActions confirmCatalog(long ingredient,long p,String action,String body) throws Exception {
        return mvc.perform(post("/insumos/"+ingredient+"/presentaciones/"+p+"/"+action).with(user("web5b").roles("ADMIN")).with(csrf()).contentType("application/json").content(body));
    }
    @Test void webReceiptFixedVariableAndMultilineIsAtomicAndHistoricalDetailUsesSnapshots() throws Exception {
        long ingredient=active(),fixed=create(ingredient,"FIJA","24"),variable=create(ingredient,"VARIABLE",null);
        UUID request=UUID.randomUUID();
        String body=capture(request,List.of(line(1,ingredient,fixed,"2",null,"25.23"),line(3,ingredient,variable,"2","3.5","12.34")));
        var result=confirm(body).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.requestId").value(request.toString())).andReturn();
        long receipt=json.readTree(result.getResponse().getContentAsString()).get("receiptId").asLong();
        confirmCatalog(ingredient,fixed,"nombre","{\"nombre\":\"Renombrado\"}").andExpect(status().isOk());
        mvc.perform(get("/inventario/recepciones/"+receipt).with(user("web5b").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Caja &lt;texto&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RECEPCION_RAPIDA")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("receipt:"+receipt+"/line:3")));
        mvc.perform(get("/inventario/recepciones?requestId="+request).with(user("web5b").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("DOC &lt;sintético&gt;")));
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("52.5");
        assertThat(db.queryForObject("select costo_unitario from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("9.75");
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("select actor_usuario_id from inventory_receipt where id=?",Long.class,receipt)).isEqualTo(actor);
    }
    @Test void failedCaptureKeepsUuidAndRetryRecognizesExactlyOneReceipt() throws Exception {
        long ingredient=active(),p=create(ingredient,"FIJA","24");
        UUID request=UUID.randomUUID();
        confirm(capture(request,List.of(line(1,ingredient,p,"1",null,"1.235"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.sinEfectos").value(true));
        assertThat(db.queryForObject("select count(*) from inventory_receipt",Long.class)).isZero();
        String body=capture(request,List.of(line(1,ingredient,p,"1",null,"1.23")));
        String accepted=confirm(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        confirm(body).andExpect(status().isOk()).andExpect(content().json(accepted));
        confirm(capture(request,List.of(line(1,ingredient,p,"2",null,"1.23"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLICTO_REQUEST_ID"));
        assertThat(db.queryForObject("select count(*) from inventory_receipt",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("25");
    }
    @Test void permissionChecksAreIndependentAtTheAuthorizationBoundary() throws Exception {
        long ingredient=active(),p=create(ingredient,"FIJA","1");
        // La asignación v1 es ADMIN para ambos. Simular concesiones separadas solo en esta frontera,
        // manteniendo reales servicio, PostgreSQL, permisos de sesión y todo efecto de inventario.
        doThrow(new AccessDeniedException("solo recibe")).when(permissions).require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        doReturn(false).when(permissions).allowed(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        mvc.perform(get("/insumos/"+ingredient+"/presentaciones?captura=true").with(user("web5b").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/insumos/"+ingredient+"/presentaciones").with(user("web5b").roles("ADMIN"))).andExpect(status().isForbidden());
        confirmCatalog(ingredient,p,"nombre","{\"nombre\":\"No autorizado\"}").andExpect(status().isForbidden());
        mvc.perform(post("/insumos/"+ingredient+"/presentaciones").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        confirmCatalog(ingredient,p,"estado","{\"estado\":\"INACTIVA\"}").andExpect(status().isForbidden());
        confirm(capture(UUID.randomUUID(),List.of(line(1,ingredient,p,"1",null,"1")))).andExpect(status().isOk());
        reset(permissions);
        doThrow(new AccessDeniedException("solo gestiona")).when(permissions).require(actor,INVENTARIO_RECIBIR);
        confirmCatalog(ingredient,p,"nombre","{\"nombre\":\"Permitido\"}").andExpect(status().isOk());
        mvc.perform(get("/insumos/"+ingredient+"/presentaciones?captura=true").with(user("web5b").roles("ADMIN"))).andExpect(status().isForbidden());
        confirm(capture(UUID.randomUUID(),List.of(line(1,ingredient,p,"1",null,"1")))).andExpect(status().isForbidden());
    }
    @Test void realRoleAuthorizationCsrfAndActorInputCannotBypassTrustedIdentity() throws Exception {
        long ingredient=active(),p=create(ingredient,"FIJA","1");
        var body=capture(UUID.randomUUID(),List.of(line(1,ingredient,p,"1",null,"1")));
        mvc.perform(post("/inventario/recepciones").with(user("web5b").roles("ADMIN")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/inventario/recepciones")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/inventario/recepciones").with(user("manager5b").roles("GERENTE"))).andExpect(status().isForbidden());
        mvc.perform(post("/inventario/recepciones").with(user("manager5b").roles("GERENTE")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        var tree=json.readTree(body); ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("actorId",999);
        confirm(tree.toString()).andExpect(status().isBadRequest());
        mvc.perform(get("/insumos").with(user("web5b").roles("ADMIN"))).andExpect(status().isOk());
        db.update("update usuario set activo=false where id=?",actor);
        mvc.perform(get("/inventario/recepciones/nueva").with(user("web5b").roles("ADMIN"))).andExpect(status().is3xxRedirection());
        assertThat(db.queryForObject("select count(*) from inventory_receipt",Long.class)).isZero();
    }
    @Test void errorsAreClearAndHistoryHasNoMutationRoutes() throws Exception {
        long ingredient=active(),p=create(ingredient,"VARIABLE",null);
        confirm(capture(UUID.randomUUID(),List.of(line(1,ingredient,p,"1",null,"1")))).andExpect(status().isUnprocessableEntity());
        confirmCatalog(ingredient,p,"estado","{\"estado\":\"OTRO\"}").andExpect(status().isBadRequest());
        confirm("{}").andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/inventario/recepciones/1").with(user("web5b").roles("ADMIN")).with(csrf())).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/inventario/recepciones/1").with(user("web5b").roles("ADMIN")).with(csrf())).andExpect(status().isMethodNotAllowed());
        assertThat(db.queryForObject("select count(*) from inventory_receipt",Long.class)).isZero();
    }

    @Test void invalidSecondLineRollsBackAllWebEffectsAndAllowsCorrectedRetry() throws Exception {
        long ingredient=active(),p=create(ingredient,"FIJA","1"),v=create(ingredient,"VARIABLE",null);
        UUID id=UUID.randomUUID();
        confirm(capture(id,List.of(line(1,ingredient,p,"2",null,"1"),line(2,ingredient,v,"1",null,"1"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.sinEfectos").value(true));
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("1");
        assertThat(db.queryForObject("select count(*) from inventory_receipt_line",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isZero();
        confirm(capture(id,List.of(line(1,ingredient,p,"2",null,"1"),line(2,ingredient,v,"1","3.5","1")))).andExpect(status().isOk());
        assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("6.5");
    }
    @Test void catalogRejectsLegacyInvalidNumbersAndMissingModeWithoutInternalErrors() throws Exception {
        long legacy=db.queryForObject("insert into insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo) "+
                "values ('Legado web','g textual',1,0,0,true) returning id",Long.class);
        mvc.perform(post("/insumos/"+legacy+"/presentaciones").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"nombre\":\"Caja\",\"unidadCompra\":\"caja\",\"modo\":\"FIJA\",\"factor\":\"24\"}"))
                .andExpect(status().isUnprocessableEntity());
        long ingredient=active();
        for (String factor: List.of("0","-1","0.0000000000001","1000000000000")) {
            mvc.perform(post("/insumos/"+ingredient+"/presentaciones").with(user("web5b").roles("ADMIN")).with(csrf())
                    .contentType("application/json").content("{\"nombre\":\"Caja\",\"unidadCompra\":\"caja\",\"modo\":\"FIJA\",\"factor\":\""+factor+"\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post("/insumos/"+ingredient+"/presentaciones").with(user("web5b").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"nombre\":\"Caja\",\"unidadCompra\":\"caja\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(db.queryForObject("select count(*) from inventory_purchase_presentation",Long.class)).isZero();
    }
    @Test void technicalFailureReturnsUncertainResultWithoutSqlDetailsAndExactRetryWorks() throws Exception {
        long ingredient=active(),p=create(ingredient,"FIJA","1");
        String body=capture(UUID.randomUUID(),List.of(line(1,ingredient,p,"1",null,"1")));
        db.execute("create function receipt_web_fail() returns trigger language plpgsql as $$ begin raise exception 'detalle SQL sintético'; end $$");
        db.execute("create trigger receipt_web_fail before insert on movimiento_inventario for each row execute function receipt_web_fail()");
        try {
            confirm(body).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.sinEfectos").value(false))
                    .andExpect(jsonPath("$.codigo").value("RESULTADO_DESCONOCIDO"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("detalle SQL"))));
            assertThat(db.queryForObject("select count(*) from inventory_receipt",Long.class)).isZero();
            assertThat(db.queryForObject("select stock_actual from insumo where id=?",BigDecimal.class,ingredient)).isEqualByComparingTo("1");
        } finally {
            db.execute("drop trigger receipt_web_fail on movimiento_inventario");
            db.execute("drop function receipt_web_fail()");
        }
        confirm(body).andExpect(status().isOk());
        confirm(body).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from movimiento_inventario",Long.class)).isEqualTo(1);
    }
}
