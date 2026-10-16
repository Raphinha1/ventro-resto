package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.InventoryPurchasePresentation;
import com.tuapp.restaurante.models.InventoryReceipt;
import com.tuapp.restaurante.repositories.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Caso de uso interno; el futuro adaptador debe obtener actor de contexto confiable. */
@Service
public class ReceiptService {
    private final JdbcTemplate db;
    private final InventoryPermissionService permissions;
    private final InventoryChangeApplier applier;
    private final InventoryReceiptRepository receipts;
    private final InventoryPurchasePresentationRepository presentations;
    private final InsumoRepository insumos;
    private final UsuarioRepository users;
    private final ProveedorRepository suppliers;
    private final MovimientoInventarioRepository movements;
    private final String configuredCurrency;

    public ReceiptService(JdbcTemplate db, InventoryPermissionService permissions,
                          InventoryChangeApplier applier, InventoryReceiptRepository receipts,
                          InventoryPurchasePresentationRepository presentations, InsumoRepository insumos,
                          UsuarioRepository users, ProveedorRepository suppliers,
                          MovimientoInventarioRepository movements,
                          @Value("${ventro.inventory.currency:}") String configuredCurrency) {
        this.db=db; this.permissions=permissions; this.applier=applier; this.receipts=receipts;
        this.presentations=presentations; this.insumos=insumos; this.users=users;
        this.suppliers=suppliers; this.movements=movements; this.configuredCurrency=configuredCurrency;
    }

    public record Line(int lineNo, Long insumoId, Long presentationId,
                       BigDecimal purchased, BigDecimal receivedBase, BigDecimal cost) {}
    public record Command(UUID requestId, Long actorId, String currency, Long supplierId,
                          String externalDocument, List<Line> lines) {}
    public record ReceivedLine(int lineNo, Long insumoId, BigDecimal baseQuantity,
                               UUID operationId, Long movementId) {}
    public record Result(Long receiptId, UUID requestId, List<ReceivedLine> lines) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result confirm(Command input) {
        Objects.requireNonNull(input, "Comando obligatorio");
        permissions.require(input.actorId(), InventoryPermissionService.Permission.INVENTARIO_RECIBIR);
        Command command=normalize(input);
        String hash=ReceiptFingerprint.calculate(command);
        // Espacio PostgreSQL de dos int distinto del advisory bigint del aplicador.
        db.queryForObject("select pg_advisory_xact_lock(?,?) is null",Boolean.class,
                0x52454350,command.requestId().hashCode());
        var previous=receipts.findByRequestId(command.requestId());
        if (previous.isPresent()) {
            var receipt=previous.get();
            if (!ReceiptFingerprint.VERSION.equals(receipt.getFingerprintVersion()) ||
                    !hash.equals(receipt.getCommandFingerprint())) {
                throw new ReceiptException(ReceiptException.Kind.CONFLICTO_REQUEST_ID,"requestId reutilizado con otro comando de recepción");
            }
            // No consultar configuración/presentación actual para cambiar un retry histórico.
            return confirmedResult(receipt);
        }
        if (configuredCurrency==null || !configuredCurrency.matches("[A-Z]{3}")) {
            throw new ReceiptException(ReceiptException.Kind.MONEDA_NO_CONFIGURADA,"Moneda del restaurante no configurada para recepción");
        }
        if (!configuredCurrency.equals(command.currency())) {
            throw new IllegalArgumentException("La moneda debe coincidir con la del restaurante");
        }
        var supplier=command.supplierId()==null?null:suppliers.findById(command.supplierId())
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));

        applier.reserveOperations(command.lines().stream().map(l -> operationId(command.requestId(),l.lineNo())).toList());
        List<Long> presentationIds=command.lines().stream().map(Line::presentationId).distinct().sorted().toList();
        String placeholders=String.join(",",Collections.nCopies(presentationIds.size(),"?"));
        Map<Long,Long> owners=new HashMap<>();
        db.query("select id,insumo_id from inventory_purchase_presentation where id in ("+placeholders+")",
                rs -> { owners.put(rs.getLong(1),rs.getLong(2)); },presentationIds.toArray());
        for (Line line : command.lines()) {
            if (!line.insumoId().equals(owners.get(line.presentationId()))) {
                throw new IllegalArgumentException("Presentación inexistente o de otro insumo: línea "+line.lineNo());
            }
        }
        Map<Long,Insumo> locked=new HashMap<>();
        for (Long id : command.lines().stream().map(Line::insumoId).distinct().sorted().toList()) {
            Insumo insumo=insumos.lockById(id).orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
            if (InventoryWritePolicy.resolve(insumo.getEstadoInventario5a()) != InventoryWritePolicy.Mode.APPLIER) {
                throw new ReceiptException(ReceiptException.Kind.NO_SELECCIONABLE,
                        "El insumo todavía usa escritura LEGACY; recepción requiere ACTIVO_5A");
            }
            locked.put(id,insumo);
        }
        Map<Long,InventoryPurchasePresentation> selected=new HashMap<>();
        for (Long id : presentationIds) {
            var p=presentations.lockById(id).orElseThrow(() -> new IllegalArgumentException("Presentación no encontrada"));
            Insumo insumo=locked.get(owners.get(id));
            if (p.getEstado()!=InventoryPurchasePresentation.Status.ACTIVA ||
                    BaseUnit.require(p.getFamilia(),p.getUnidadBaseCodigo()) !=
                            BaseUnit.require(insumo.getUnidadBaseFamilia(),insumo.getUnidadBaseCodigo())) {
                throw new ReceiptException(ReceiptException.Kind.NO_SELECCIONABLE,"Presentación INACTIVA o unidad incompatible");
            }
            selected.put(id,p);
        }
        var receipt=new InventoryReceipt(command.requestId(),hash,ReceiptFingerprint.VERSION,configuredCurrency,
                users.getReferenceById(command.actorId()),supplier,command.externalDocument());
        for (Line line : command.lines()) {
            receipt.addLine(line.lineNo(),selected.get(line.presentationId()),line.purchased(),line.receivedBase(),
                    line.cost(),operationId(command.requestId(),line.lineNo()));
        }
        Map<Long,BigDecimal> totals=new HashMap<>();
        receipt.getLines().forEach(l -> totals.merge(l.getInsumo().getId(),l.getCantidadBaseEfectiva(),BigDecimal::add));
        totals.forEach((id,total) -> InventoryNumbers.base(locked.get(id).getStockActual().add(total)));
        receipts.saveAndFlush(receipt);
        applier.applyAll(receipt.getLines().stream().map(l -> new InventoryChangeApplier.Command(l.getOperationId(),
                l.getInsumo().getId(),InventoryChangeApplier.Type.ENTRADA,l.getCantidadBaseEfectiva(),
                BaseUnit.require(l.getFamilia(),l.getUnidadBaseCodigo()),command.actorId(),"RECEPCION_RAPIDA",
                reference(receipt.getId(),l.getLineNo()),null)).toList());
        return confirmedResult(receipt);
    }

    /** Verificación histórica compartida con reverso; no consulta catálogo ni modifica filas. */
    Result confirmedResult(InventoryReceipt receipt) {
        Command persisted=new Command(receipt.getRequestId(),receipt.getActor().getId(),receipt.getMoneda(),
                receipt.getProveedor()==null?null:receipt.getProveedor().getId(),receipt.getDocumentoExterno(),
                receipt.getLines().stream().map(l -> new Line(l.getLineNo(),l.getInsumo().getId(),
                    l.getPresentation().getId(),l.getCantidadComprada(),l.getCantidadBaseRecibida(),
                    l.getCostoTotalReal())).toList());
        if (!ReceiptFingerprint.VERSION.equals(receipt.getFingerprintVersion()) ||
                !receipt.getCommandFingerprint().equals(ReceiptFingerprint.calculate(normalize(persisted)))) {
            throw new IllegalStateException("La huella no coincide con la recepción persistida");
        }

        var causal=movements.findByOperationIdIn(receipt.getLines().stream().map(l -> l.getOperationId()).toList())
                .stream().collect(Collectors.toMap(m -> m.getOperationId(),Function.identity()));
        List<ReceivedLine> result=new ArrayList<>();
        for (var line : receipt.getLines()) {
            var movement=causal.get(line.getOperationId());
            if (!operationId(receipt.getRequestId(),line.getLineNo()).equals(line.getOperationId()) ||
                    movement==null || !"ENTRADA".equals(movement.getTipoMovimiento()) ||
                    !"RECEPCION_RAPIDA".equals(movement.getCausalOrigin()) ||
                    !reference(receipt.getId(),line.getLineNo()).equals(movement.getCausalReference()) ||
                    !line.getInsumo().getId().equals(movement.getInsumo().getId()) ||
                    !receipt.getActor().getId().equals(movement.getUsuario().getId()) ||
                    line.getCantidadBaseEfectiva().compareTo(movement.getCantidad())!=0) {
                throw new IllegalStateException("Recepción sin efecto causal íntegro: línea "+line.getLineNo());
            }
            result.add(new ReceivedLine(line.getLineNo(),line.getInsumo().getId(),line.getCantidadBaseEfectiva(),
                    line.getOperationId(),movement.getId()));
        }
        return new Result(receipt.getId(),receipt.getRequestId(),List.copyOf(result));
    }

    private static UUID operationId(UUID requestId,int lineNo) {
        return UUID.nameUUIDFromBytes(("RECEPCION_RAPIDA|v1|"+requestId+"|"+lineNo).getBytes(StandardCharsets.UTF_8));
    }

    private static String reference(Long receiptId,int lineNo) { return "receipt:"+receiptId+"/line:"+lineNo; }

    private static Command normalize(Command c) {
        if (c.requestId()==null) throw new IllegalArgumentException("requestId obligatorio");
        if (c.currency()==null || !c.currency().matches("[A-Z]{3}")) throw new IllegalArgumentException("Moneda inválida");
        if (c.supplierId()!=null && c.supplierId()<=0) throw new IllegalArgumentException("Proveedor inválido");
        String document=c.externalDocument()==null || c.externalDocument().isBlank()?null:c.externalDocument().strip();
        if (document!=null && document.length()>200) throw new IllegalArgumentException("Documento externo demasiado largo");
        if (c.lines()==null || c.lines().isEmpty()) throw new IllegalArgumentException("Recepción requiere líneas");
        List<Line> lines=new ArrayList<>();
        Set<Integer> numbers=new HashSet<>();
        for (Line l : c.lines()) {
            if (l==null || l.lineNo()<=0 || !numbers.add(l.lineNo())) throw new IllegalArgumentException("lineNo inválido o duplicado");
            if (l.insumoId()==null || l.insumoId()<=0 || l.presentationId()==null || l.presentationId()<=0) {
                throw new IllegalArgumentException("Insumo/presentación obligatorios: línea "+l.lineNo());
            }
            lines.add(new Line(l.lineNo(),l.insumoId(),l.presentationId(),ReceiptNumbers.quantity(l.purchased()),
                    l.receivedBase()==null?null:ReceiptNumbers.quantity(l.receivedBase()),ReceiptNumbers.cost(l.cost())));
        }
        lines.sort(Comparator.comparingInt(Line::lineNo));
        return new Command(c.requestId(),c.actorId(),c.currency(),c.supplierId(),document,List.copyOf(lines));
    }
}
