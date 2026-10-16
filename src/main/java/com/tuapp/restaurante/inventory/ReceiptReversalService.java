package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.models.*;
import com.tuapp.restaurante.repositories.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.tuapp.restaurante.inventory.ReceiptReversalException.Kind.*;

/** Caso de uso interno: el caller debe obtener actorId de identidad confiable. */
@Service
public class ReceiptReversalService {
    private final JdbcTemplate db;
    private final InventoryPermissionService permissions;
    private final InventoryChangeApplier applier;
    private final ReceiptService receiving;
    private final InventoryReceiptRepository receipts;
    private final InventoryReceiptReversalRepository reversals;
    private final InsumoRepository insumos;
    private final UsuarioRepository users;
    private final MovimientoInventarioRepository movements;
    public ReceiptReversalService(JdbcTemplate db,InventoryPermissionService permissions,InventoryChangeApplier applier,
            ReceiptService receiving,InventoryReceiptRepository receipts,InventoryReceiptReversalRepository reversals,
            InsumoRepository insumos,UsuarioRepository users,MovimientoInventarioRepository movements) {
        this.db=db; this.permissions=permissions; this.applier=applier; this.receiving=receiving;
        this.receipts=receipts; this.reversals=reversals; this.insumos=insumos; this.users=users; this.movements=movements;
    }
    public record Command(UUID requestId,Long receiptId,Long actorId,String motivo) {}
    public record ReversedLine(int lineNo,Long insumoId,BigDecimal baseQuantity,UUID operationId,Long movementId) {}
    public record Result(Long reversalId,Long receiptId,UUID requestId,List<ReversedLine> lines) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result reverse(Command input) {
        Objects.requireNonNull(input,"Comando obligatorio");
        permissions.require(input.actorId(),InventoryPermissionService.Permission.INVENTARIO_REVERTIR_RECEPCION);
        Command command=normalize(input);
        String hash=ReceiptReversalFingerprint.calculate(command);
        db.queryForObject("select pg_advisory_xact_lock(?,?) is null",Boolean.class,0x52565253,command.requestId().hashCode());
        var previous=reversals.findByRequestId(command.requestId());
        if (previous.isPresent()) {
            var reversal=previous.get();
            if (!ReceiptReversalFingerprint.VERSION.equals(reversal.getFingerprintVersion()) ||
                    !hash.equals(reversal.getCommandFingerprint())) {
                throw new ReceiptReversalException(CONFLICTO_REQUEST_ID,"requestId reutilizado con otro comando de reverso");
            }
            return confirmedResult(reversal);
        }
        // Bloquear solo cabecera; no FOR UPDATE sobre un fetch join de sus líneas.
        List<Long> ids=db.queryForList("select id from inventory_receipt where id=? for update",Long.class,command.receiptId());
        if (ids.isEmpty()) throw new IllegalArgumentException("Recepción no encontrada");
        if (reversals.existsByReceiptId(command.receiptId())) {
            throw new ReceiptReversalException(YA_REVERTIDA,"Recepción ya revertida");
        }
        InventoryReceipt receipt=receipts.findById(command.receiptId()).orElseThrow();
        verifyOriginal(receipt);
        applier.reserveOperations(receipt.getLines().stream().map(l -> operationId(command.requestId(),l.getLineNo())).toList());
        Map<Long,BigDecimal> totals=new TreeMap<>();
        for (var line : receipt.getLines()) {
            totals.merge(line.getInsumo().getId(),ReceiptNumbers.quantity(line.getCantidadBaseEfectiva()),BigDecimal::add);
        }
        Map<Long,Insumo> locked=new HashMap<>();
        for (var total : totals.entrySet()) {
            Insumo insumo=insumos.lockById(total.getKey()).orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
            if (InventoryWritePolicy.resolve(insumo.getEstadoInventario5a())!=InventoryWritePolicy.Mode.APPLIER) {
                throw new ReceiptReversalException(NO_SELECCIONABLE,"Reverso requiere insumo ACTIVO_5A");
            }
            BigDecimal required=ReceiptNumbers.quantity(total.getValue());
            if (InventoryNumbers.base(insumo.getStockActual()).compareTo(required)<0) {
                throw new ReceiptReversalException(SALDO_INSUFICIENTE,"Saldo insuficiente para reverso completo: insumo "+insumo.getId());
            }
            locked.put(insumo.getId(),insumo);
        }
        for (var line : receipt.getLines()) {
            Insumo insumo=locked.get(line.getInsumo().getId());
            if (BaseUnit.require(line.getFamilia(),line.getUnidadBaseCodigo())!=
                    BaseUnit.require(insumo.getUnidadBaseFamilia(),insumo.getUnidadBaseCodigo())) {
                throw new ReceiptReversalException(NO_SELECCIONABLE,"Unidad histórica incompatible con insumo "+insumo.getId());
            }
        }
        var reversal=new InventoryReceiptReversal(receipt,command.requestId(),hash,ReceiptReversalFingerprint.VERSION,
                users.getReferenceById(command.actorId()),command.motivo());
        receipt.getLines().forEach(l -> reversal.addLine(l,operationId(command.requestId(),l.getLineNo())));
        reversals.saveAndFlush(reversal);
        applier.applyAll(reversal.getLines().stream().map(l -> new InventoryChangeApplier.Command(l.getOperationId(),
                l.getInsumo().getId(),InventoryChangeApplier.Type.SALIDA,l.getCantidadBaseCompensada(),
                BaseUnit.require(l.getFamilia(),l.getUnidadBaseCodigo()),command.actorId(),"REVERSO_RECEPCION_RAPIDA",
                reference(receipt.getId(),reversal.getId(),l.getLineNo()),command.motivo())).toList());
        return confirmedResult(reversal);
    }
    private void verifyOriginal(InventoryReceipt receipt) {
        try { receiving.confirmedResult(receipt); }
        catch (IllegalStateException ex) {
            throw new ReceiptReversalException(INTEGRIDAD_CAUSAL,"Recepción sin integridad causal: "+ex.getMessage());
        }
        if (receipt.getLines().isEmpty()) throw new ReceiptReversalException(INTEGRIDAD_CAUSAL,"Recepción sin líneas");
    }
    private Result confirmedResult(InventoryReceiptReversal reversal) {
        Command persisted=normalize(new Command(reversal.getRequestId(),reversal.getReceipt().getId(),
                reversal.getActor().getId(),reversal.getMotivo()));
        if (!ReceiptReversalFingerprint.VERSION.equals(reversal.getFingerprintVersion()) ||
                !reversal.getCommandFingerprint().equals(ReceiptReversalFingerprint.calculate(persisted))) {
            throw new IllegalStateException("Huella distinta del reverso persistido");
        }
        verifyOriginal(reversal.getReceipt());
        var causal=movements.findByOperationIdIn(reversal.getLines().stream().map(l -> l.getOperationId()).toList())
                .stream().collect(Collectors.toMap(MovimientoInventario::getOperationId,Function.identity()));
        Set<Long> originalIds=new HashSet<>();
        List<ReversedLine> results=new ArrayList<>();
        for (var line : reversal.getLines()) {
            var original=line.getOriginalLine(); var movement=causal.get(line.getOperationId());
            if (!originalIds.add(original.getId()) || !original.getReceipt().getId().equals(reversal.getReceipt().getId()) ||
                    original.getLineNo()!=line.getLineNo() || !original.getInsumo().getId().equals(line.getInsumo().getId()) ||
                    original.getCantidadBaseEfectiva().compareTo(line.getCantidadBaseCompensada())!=0 ||
                    original.getFamilia()!=line.getFamilia() || !original.getUnidadBaseCodigo().equals(line.getUnidadBaseCodigo()) ||
                    !operationId(reversal.getRequestId(),line.getLineNo()).equals(line.getOperationId()) || movement==null ||
                    !"SALIDA".equals(movement.getTipoMovimiento()) || !"REVERSO_RECEPCION_RAPIDA".equals(movement.getCausalOrigin()) ||
                    !reference(reversal.getReceipt().getId(),reversal.getId(),line.getLineNo()).equals(movement.getCausalReference()) ||
                    !line.getInsumo().getId().equals(movement.getInsumo().getId()) ||
                    !reversal.getActor().getId().equals(movement.getUsuario().getId()) ||
                    !reversal.getMotivo().equals(movement.getComentario()) ||
                    line.getCantidadBaseCompensada().compareTo(movement.getCantidad())!=0) {
                throw new IllegalStateException("Reverso sin efecto causal íntegro: línea "+line.getLineNo());
            }
            results.add(new ReversedLine(line.getLineNo(),line.getInsumo().getId(),line.getCantidadBaseCompensada(),
                    line.getOperationId(),movement.getId()));
        }
        if (originalIds.size()!=reversal.getReceipt().getLines().size()) throw new IllegalStateException("Reverso incompleto");
        return new Result(reversal.getId(),reversal.getReceipt().getId(),reversal.getRequestId(),List.copyOf(results));
    }
    private static Command normalize(Command c) {
        if (c.requestId()==null) throw new IllegalArgumentException("requestId obligatorio");
        if (c.receiptId()==null || c.receiptId()<=0) throw new IllegalArgumentException("Recepción obligatoria");
        String reason=c.motivo()==null?null:c.motivo().strip();
        if (reason==null || reason.isBlank() || reason.length()>1000) throw new IllegalArgumentException("Motivo obligatorio, máximo 1000 caracteres");
        return new Command(c.requestId(),c.receiptId(),c.actorId(),reason);
    }
    static UUID operationId(UUID requestId,int lineNo) {
        return UUID.nameUUIDFromBytes(("REVERSO_RECEPCION_RAPIDA|v1|"+requestId+"|"+lineNo).getBytes(StandardCharsets.UTF_8));
    }
    static String reference(Long receiptId,Long reversalId,int lineNo) {
        return "receipt:"+receiptId+"/reversal:"+reversalId+"/line:"+lineNo;
    }
}
