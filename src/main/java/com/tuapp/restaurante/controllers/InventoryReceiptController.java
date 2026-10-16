package com.tuapp.restaurante.controllers;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.tuapp.restaurante.inventory.*;
import com.tuapp.restaurante.models.InventoryPurchasePresentation;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.*;
import static com.tuapp.restaurante.inventory.InventoryPermissionService.Permission.*;

@Controller
public class InventoryReceiptController {
    private final ReceiptService receipts;
    private final ReceiptReversalService reversals;
    private final PurchasePresentationService presentations;
    private final ReceiptQueryService queries;
    private final InventoryPermissionService permissions;
    private final UsuarioRepository users;
    private final ObjectMapper json;
    public InventoryReceiptController(ReceiptService receipts, PurchasePresentationService presentations,
            ReceiptQueryService queries, InventoryPermissionService permissions, UsuarioRepository users, ObjectMapper json,
            ReceiptReversalService reversals) {
        this.receipts=receipts; this.presentations=presentations; this.queries=queries;
        this.permissions=permissions; this.users=users; this.json=json; this.reversals=reversals;
    }
    private Long actor(Authentication auth) {
        if (auth==null) throw new AccessDeniedException("Inicia sesión");
        return users.findByUsername(auth.getName()).filter(u -> u.isActivo())
                .orElseThrow(() -> new AccessDeniedException("Usuario inactivo")).getId();
    }
    @GetMapping("/inventario/recepciones/nueva")
    public String newReceipt(Authentication auth,Model model) {
        Long actor=actor(auth);
        permissions.require(actor,INVENTARIO_RECIBIR);
        model.addAttribute("titulo","Nueva recepción");
        model.addAttribute("currency",queries.currency());
        model.addAttribute("ingredients",queries.ingredients(actor));
        model.addAttribute("suppliers",queries.suppliers(actor));
        model.addAttribute("canManage",permissions.allowed(actor,INVENTARIO_GESTIONAR_PRESENTACIONES));
        model.addAttribute("actorContext",actor);
        return "inventario/recepciones/nueva";
    }
    public record Capture(UUID requestId,String currency,Long supplierId,String externalDocument,List<ReceiptService.Line> lines) {}
    @PostMapping("/inventario/recepciones")
    @ResponseBody
    public Map<String,Object> confirm(Authentication auth,@RequestBody JsonNode body) throws JsonProcessingException {
        Long actor=actor(auth);
        permissions.require(actor,INVENTARIO_RECIBIR);
        var c=parse(body,Capture.class);
        var result=receipts.confirm(new ReceiptService.Command(c.requestId(),actor,c.currency(),c.supplierId(),c.externalDocument(),c.lines()));
        // La llamada vuelve después del commit del proxy transaccional.
        return Map.of("estado","CONFIRMADA","receiptId",result.receiptId(),"requestId",result.requestId(),
                "detailUrl","/inventario/recepciones/"+result.receiptId());
    }
    @GetMapping("/inventario/recepciones")
    public String history(Authentication auth,@RequestParam(defaultValue="0") int page,
                          @RequestParam(required=false) UUID requestId,Model model) {
        model.addAttribute("titulo","Historial de recepciones");
        model.addAttribute("receipts",queries.history(actor(auth),page,requestId));
        model.addAttribute("requestId",requestId);
        return "inventario/recepciones/historial";
    }
    @GetMapping("/inventario/recepciones/{id}")
    public String detail(Authentication auth,@PathVariable Long id,Model model) {
        model.addAttribute("titulo","Detalle de recepción");
        Long actor=actor(auth);
        model.addAttribute("detail",queries.detail(actor,id));
        model.addAttribute("canRevert",permissions.allowed(actor,INVENTARIO_REVERTIR_RECEPCION));
        return "inventario/recepciones/detalle";
    }
    @GetMapping("/inventario/recepciones/{id}/reverso/nuevo")
    public String newReversal(Authentication auth,@PathVariable Long id,Model model) {
        Long actor=actor(auth);
        var detail=queries.reversalPreview(actor,id);
        model.addAttribute("detail",detail);
        model.addAttribute("titulo","Reverso completo de recepción");
        model.addAttribute("actorContext",actor);
        model.addAttribute("requestId",detail.header().get("reversal_id")==null ? UUID.randomUUID() : null);
        return "inventario/recepciones/reverso-nuevo";
    }
    public record ReversalCapture(UUID requestId,String motivo) {}
    @PostMapping(value="/inventario/recepciones/{id}/reversos",consumes="application/json")
    @ResponseBody
    public Map<String,Object> reverse(Authentication auth,@PathVariable Long id,@RequestBody JsonNode body)
            throws JsonProcessingException {
        Long actor=actor(auth);
        permissions.require(actor,INVENTARIO_REVERTIR_RECEPCION);
        if (body!=null && body.isObject() &&
                ((body.hasNonNull("requestId") && !body.get("requestId").isTextual()) ||
                 (body.hasNonNull("motivo") && !body.get("motivo").isTextual()))) {
            throw new IllegalArgumentException("requestId y motivo deben ser texto");
        }
        var capture=parse(body,ReversalCapture.class);
        // Única autoridad de compensación. El proxy vuelve tras confirmar toda la transacción.
        var result=reversals.reverse(new ReceiptReversalService.Command(capture.requestId(),id,actor,capture.motivo()));
        return Map.of("estado","REVERTIDA","receiptId",result.receiptId(),"reversalId",result.reversalId(),
                "requestId",result.requestId(),"detailUrl","/inventario/recepciones/"+id+"/reversos/"+result.reversalId());
    }
    @GetMapping("/inventario/recepciones/{id}/reversos/{reversalId}")
    public String reversalDetail(Authentication auth,@PathVariable Long id,@PathVariable Long reversalId,Model model) {
        model.addAttribute("titulo","Detalle de reverso");
        model.addAttribute("reversal",queries.reversalDetail(actor(auth),id,reversalId));
        return "inventario/recepciones/reverso-detalle";
    }
    @GetMapping(value="/insumos/{insumoId}/presentaciones",params="captura=true")
    @ResponseBody
    public List<PurchasePresentationService.View> capturePresentations(Authentication auth,@PathVariable Long insumoId) {
        return presentations.list(actor(auth),insumoId,true);
    }
    @GetMapping(value="/insumos/{insumoId}/presentaciones",params="!captura")
    public String catalog(Authentication auth,@PathVariable Long insumoId,Model model) {
        Long actor=actor(auth);
        model.addAttribute("titulo","Presentaciones de compra");
        model.addAttribute("ingredient",queries.ingredient(actor,insumoId));
        model.addAttribute("presentations",presentations.list(actor,insumoId,false));
        return "inventario/recepciones/presentaciones";
    }
    @PostMapping("/insumos/{insumoId}/presentaciones")
    @ResponseBody
    public PurchasePresentationService.View create(Authentication auth,@PathVariable Long insumoId,
                                                  @RequestBody JsonNode body) throws JsonProcessingException {
        Long actor=actor(auth); permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        return presentations.create(actor,insumoId,parse(body,PurchasePresentationService.Create.class));
    }
    public record Rename(String nombre) {}
    public record State(InventoryPurchasePresentation.Status estado) {}
    @PostMapping("/insumos/{insumoId}/presentaciones/{id}/nombre")
    @ResponseBody
    public PurchasePresentationService.View rename(Authentication auth,@PathVariable Long insumoId,@PathVariable Long id,
                                                   @RequestBody JsonNode body) throws JsonProcessingException {
        Long actor=actor(auth); permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        return presentations.rename(actor,insumoId,id,parse(body,Rename.class).nombre());
    }
    @PostMapping("/insumos/{insumoId}/presentaciones/{id}/estado")
    @ResponseBody
    public PurchasePresentationService.View state(Authentication auth,@PathVariable Long insumoId,@PathVariable Long id,
                                                  @RequestBody JsonNode body) throws JsonProcessingException {
        Long actor=actor(auth); permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        return presentations.status(actor,insumoId,id,parse(body,State.class).estado());
    }
    private <T> T parse(JsonNode body,Class<T> type) throws JsonProcessingException {
        if (body==null || !body.isObject()) throw new IllegalArgumentException("Se requiere un objeto de captura");
        return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body.toString());
    }
    @ExceptionHandler(ReceiptReversalException.class)
    @ResponseBody
    public ResponseEntity<?> reversalError(ReceiptReversalException ex) {
        return error(409,ex.kind().name(),ex.getMessage(),true);
    }
    @ExceptionHandler(ReceiptException.class)
    @ResponseBody
    public ResponseEntity<?> receiptError(ReceiptException ex) {
        int status=ex.kind()==ReceiptException.Kind.MONEDA_NO_CONFIGURADA?503:409;
        return error(status,ex.kind().name(),ex.getMessage(),true);
    }
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseBody
    public ResponseEntity<?> denied(AccessDeniedException ex) {
        return error(403,"SIN_PERMISO","No tienes permiso para esta operación.",true);
    }
    @ExceptionHandler({JsonProcessingException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    @ResponseBody
    public ResponseEntity<?> binding(Exception ex) {
        return error(400,"CAPTURA_INVALIDA","Revisa el formato y los campos de la captura; no se admiten cambios de regla ni actor.",true);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<?> validation(IllegalArgumentException ex) {
        return error(422,"VALIDACION",ex.getMessage(),true);
    }
    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<?> technical(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(InventoryReceiptController.class).error("Fallo técnico en recepción/catálogo",ex);
        return error(500,"RESULTADO_DESCONOCIDO","No se pudo confirmar el resultado. Consulta el historial o reintenta el mismo envío.",false);
    }
    private ResponseEntity<?> error(int status,String code,String message,boolean noEffects) {
        return ResponseEntity.status(status).body(Map.of("codigo",code,"mensaje",message,"sinEfectos",noEffects));
    }
}
