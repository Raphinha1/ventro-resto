-- Persistencia sintética 5B.1 para restore; NO es recepción operativa:
-- no crea movimientos ni modifica stock. Solo BD descartable Slice 1.
DO $$
DECLARE supply bigint; actor bigint; presentation bigint; receipt bigint;
BEGIN
    IF current_database() <> 'ventro_legacy' THEN
        RAISE EXCEPTION 'Fixture exclusivo de slice1 descartable';
    END IF;
    SELECT id INTO STRICT supply FROM insumo WHERE nombre='Activo backup sintetico';
    SELECT id INTO STRICT actor FROM usuario WHERE username='admin';
    INSERT INTO inventory_purchase_presentation(insumo_id,nombre,unidad_compra_codigo,modo,
        familia,unidad_base_codigo,factor_base,creado_por_usuario_id)
        VALUES (supply,'Caja 24 sintética','caja','FIJA','COUNT','unidad',24,actor)
        RETURNING id INTO presentation;
    INSERT INTO inventory_receipt(request_id,command_fingerprint,fingerprint_version,moneda,
        actor_usuario_id,documento_externo)
        VALUES ('00000000-0000-4000-8000-000000000512',repeat('a',64),'fixture-v1','MXN',actor,'DOC-SINTETICO')
        RETURNING id INTO receipt;
    INSERT INTO inventory_receipt_line(receipt_id,line_no,insumo_id,presentation_id,cantidad_comprada,
        cantidad_base_efectiva,costo_total_real,presentation_nombre,unidad_compra_codigo,modo,
        familia,unidad_base_codigo,factor_base,calculation_version,operation_id)
        VALUES (receipt,1,supply,presentation,1.000001,24.000024,25.23,'Caja 24 sintética','caja',
            'FIJA','COUNT','unidad',24,'receipt-conversion-v1','00000000-0000-4000-8000-000000000513');
END;
$$;
