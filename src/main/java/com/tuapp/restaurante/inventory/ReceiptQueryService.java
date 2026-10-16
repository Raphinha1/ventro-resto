package com.tuapp.restaurante.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.math.BigDecimal;
import java.util.*;
import static com.tuapp.restaurante.inventory.InventoryPermissionService.Permission.*;

/** Proyecciones web de solo lectura; no reparan historia ni confirman recepciones. */
@Service
@Transactional(readOnly=true)
public class ReceiptQueryService {
    private final JdbcTemplate db;
    private final InventoryPermissionService permissions;
    private final String currency;
    public ReceiptQueryService(JdbcTemplate db, InventoryPermissionService permissions,
            @Value("${ventro.inventory.currency:}") String currency) {
        this.db=db; this.permissions=permissions; this.currency=currency;
    }
    public String currency() { return currency!=null && currency.matches("[A-Z]{3}") ? currency : ""; }
    public List<Map<String,Object>> ingredients(Long actor) {
        permissions.require(actor,INVENTARIO_RECIBIR);
        return db.queryForList("select id,nombre,unidad_base_codigo from insumo where estado_inventario_5a='ACTIVO_5A' order by nombre,id");
    }
    public List<Map<String,Object>> suppliers(Long actor) {
        permissions.require(actor,INVENTARIO_RECIBIR);
        return db.queryForList("select id,nombre_empresa from proveedor where activo=true order by nombre_empresa,id");
    }
    public Map<String,Object> ingredient(Long actor,Long id) {
        permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        var rows=db.queryForList("select id,nombre,estado_inventario_5a,unidad_base_codigo from insumo where id=?",id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Insumo no encontrado");
        return rows.get(0);
    }
    private static final String HEADERS="""
        select r.id,r.request_id,r.registrado_en,r.moneda,r.documento_externo,
               v.id as reversal_id,case when v.id is null then 'CONFIRMADA' else 'REVERTIDA' end as estado,
               u.nombre_completo as actor,p.nombre_empresa as proveedor,
               (select sum(l.costo_total_real) from inventory_receipt_line l where l.receipt_id=r.id) as costo
        from inventory_receipt r join usuario u on u.id=r.actor_usuario_id
        left join proveedor p on p.id=r.proveedor_id
        left join inventory_receipt_reversal v on v.receipt_id=r.id
        """;
    public Page<Map<String,Object>> history(Long actor,int page,UUID requestId) {
        permissions.require(actor,INVENTARIO_RECIBIR);
        if (page<0 || page>1000000) throw new IllegalArgumentException("Página inválida");
        String where=requestId==null?"":" where r.request_id=?";
        var args=new ArrayList<Object>(); if (requestId!=null) args.add(requestId);
        Long count=db.queryForObject("select count(*) from inventory_receipt r"+where,Long.class,args.toArray());
        args.add(20); args.add(page*20);
        var rows=db.queryForList(HEADERS+where+" order by r.registrado_en desc,r.id desc limit ? offset ?",args.toArray());
        return new PageImpl<>(rows,PageRequest.of(page,20),count);
    }
    public record Detail(Map<String,Object> header,List<Map<String,Object>> lines,boolean causal) {}
    public Detail detail(Long actor,Long id) {
        permissions.require(actor,INVENTARIO_RECIBIR);
        return receiptDetail(id);
    }
    public Detail reversalPreview(Long actor,Long id) {
        permissions.require(actor,INVENTARIO_REVERTIR_RECEPCION);
        // Un intento con respuesta perdida debe poder recargar aun tras el commit.
        // La vista bloquea un intento nuevo; el servicio sigue rechazando otro reverso.
        return receiptDetail(id);
    }
    private Detail receiptDetail(Long id) {
        var headers=db.queryForList(HEADERS+" where r.id=?",id);
        if (headers.isEmpty()) throw new IllegalArgumentException("Recepción no encontrada");
        var lines=db.queryForList("""
            select l.*,i.nombre as insumo_nombre,m.id as movement_id,
                   m.causal_origin,m.causal_reference,
                   coalesce(m.tipo_movimiento='ENTRADA' and m.causal_origin='RECEPCION_RAPIDA'
                     and m.causal_reference='receipt:'||l.receipt_id||'/line:'||l.line_no
                     and m.insumo_id=l.insumo_id and m.usuario_id=r.actor_usuario_id
                     and m.cantidad=l.cantidad_base_efectiva,false) as causal_ok
            from inventory_receipt_line l join insumo i on i.id=l.insumo_id
            join inventory_receipt r on r.id=l.receipt_id
            left join movimiento_inventario m on m.operation_id=l.operation_id
            where l.receipt_id=? order by l.line_no
            """,id);
        return new Detail(headers.get(0),lines,!lines.isEmpty() && lines.stream().allMatch(l -> Boolean.TRUE.equals(l.get("causal_ok"))));
    }

    public record ReversalDetail(Map<String,Object> header,List<Map<String,Object>> lines,boolean causal) {}
    public ReversalDetail reversalDetail(Long actor,Long receiptId,Long reversalId) {
        permissions.require(actor,INVENTARIO_RECIBIR);
        var headers=db.queryForList("""
            select v.id,v.receipt_id,v.request_id,v.registrado_en,v.motivo,v.fingerprint_version,
                   u.nombre_completo as actor
            from inventory_receipt_reversal v join usuario u on u.id=v.actor_usuario_id
            where v.id=? and v.receipt_id=?
            """,reversalId,receiptId);
        if (headers.isEmpty()) throw new IllegalArgumentException("Reverso no encontrado para esta recepción");
        var lines=db.queryForList("""
            select l.line_no,l.original_receipt_line_id,l.insumo_id,i.nombre as insumo_nombre,
                   l.cantidad_base_compensada,l.unidad_base_codigo,l.operation_id,
                   o.operation_id as original_operation_id,m.id as movement_id,m.tipo_movimiento,
                   m.causal_origin,m.causal_reference,m.resulting_stock,
                   coalesce(m.tipo_movimiento='SALIDA' and m.causal_origin='REVERSO_RECEPCION_RAPIDA'
                     and m.causal_reference='receipt:'||v.receipt_id||'/reversal:'||v.id||'/line:'||l.line_no
                     and m.insumo_id=l.insumo_id and m.usuario_id=v.actor_usuario_id
                     and m.cantidad=l.cantidad_base_compensada,false) as causal_ok
            from inventory_receipt_reversal_line l
            join inventory_receipt_reversal v on v.id=l.reversal_id
            join inventory_receipt_line o on o.id=l.original_receipt_line_id
            join insumo i on i.id=l.insumo_id
            left join movimiento_inventario m on m.operation_id=l.operation_id
            where l.reversal_id=? order by l.line_no
            """,reversalId);
        return new ReversalDetail(headers.get(0),lines,!lines.isEmpty() && lines.stream().allMatch(l -> Boolean.TRUE.equals(l.get("causal_ok"))));
    }
}
