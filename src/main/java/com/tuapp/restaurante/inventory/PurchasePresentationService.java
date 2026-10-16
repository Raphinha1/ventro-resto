package com.tuapp.restaurante.inventory;

import com.tuapp.restaurante.models.InventoryPurchasePresentation;
import com.tuapp.restaurante.repositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import static com.tuapp.restaurante.inventory.InventoryPermissionService.Permission.*;

/** Catálogo acotado; nunca escribe saldo ni modifica una regla existente. */
@Service
public class PurchasePresentationService {
    private final InventoryPermissionService permissions;
    private final InventoryPurchasePresentationRepository presentations;
    private final InsumoRepository insumos;
    private final UsuarioRepository users;
    public PurchasePresentationService(InventoryPermissionService permissions,
            InventoryPurchasePresentationRepository presentations, InsumoRepository insumos, UsuarioRepository users) {
        this.permissions=permissions; this.presentations=presentations; this.insumos=insumos; this.users=users;
    }
    public record View(Long id, String nombre, String unidadCompra, String modo, String familia,
                       String unidadBase, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal factor, String estado) {}
    public record Create(String nombre, String unidadCompra, InventoryPurchasePresentation.Mode modo, BigDecimal factor) {}
    private View view(InventoryPurchasePresentation p) {
        return new View(p.getId(),p.getNombre(),p.getUnidadCompraCodigo(),p.getModo().name(),
                p.getFamilia().name(),p.getUnidadBaseCodigo(),p.getFactorBase(),p.getEstado().name());
    }
    @Transactional(readOnly=true)
    public List<View> list(Long actor, Long insumoId, boolean capture) {
        permissions.require(actor,capture?INVENTARIO_RECIBIR:INVENTARIO_GESTIONAR_PRESENTACIONES);
        if (!insumos.existsById(insumoId)) throw new IllegalArgumentException("Insumo no encontrado");
        return (capture ? presentations.findByInsumoIdAndEstado(insumoId,InventoryPurchasePresentation.Status.ACTIVA)
                : presentations.findByInsumoIdOrderByIdAsc(insumoId)).stream().map(this::view).toList();
    }
    @Transactional
    public View create(Long actor, Long insumoId, Create c) {
        permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        var insumo=insumos.lockById(insumoId).orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
        if (InventoryWritePolicy.resolve(insumo.getEstadoInventario5a())!=InventoryWritePolicy.Mode.APPLIER) {
            throw new IllegalArgumentException("Crear presentación requiere insumo ACTIVO_5A");
        }
        if (c.modo()==null) throw new IllegalArgumentException("Modo obligatorio");
        var p=new InventoryPurchasePresentation(insumo,text(c.nombre(),150,"Nombre"),
                text(c.unidadCompra(),50,"Unidad de compra"),c.modo(),c.factor(),users.getReferenceById(actor));
        return view(presentations.saveAndFlush(p));
    }
    @Transactional
    public View rename(Long actor, Long insumoId, Long id, String name) {
        permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        var p=locked(insumoId,id);
        p.setNombre(text(name,150,"Nombre"));
        return view(presentations.saveAndFlush(p));
    }
    @Transactional
    public View status(Long actor, Long insumoId, Long id, InventoryPurchasePresentation.Status state) {
        permissions.require(actor,INVENTARIO_GESTIONAR_PRESENTACIONES);
        if (state==null) throw new IllegalArgumentException("Estado obligatorio");
        var p=locked(insumoId,id); p.setEstado(state);
        return view(presentations.saveAndFlush(p));
    }
    private InventoryPurchasePresentation locked(Long insumoId,Long id) {
        // Mismo orden que recepción: insumo antes de presentación.
        insumos.lockById(insumoId).orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
        return presentations.lockForManagement(id,insumoId)
                .orElseThrow(() -> new IllegalArgumentException("Presentación no pertenece al insumo"));
    }
    private String text(String s,int limit,String field) {
        if (s==null || s.isBlank() || s.strip().length()>limit) throw new IllegalArgumentException(field+" obligatorio, máximo "+limit+" caracteres");
        return s.strip();
    }
}
