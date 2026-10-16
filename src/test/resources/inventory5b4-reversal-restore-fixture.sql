-- Solo Slice 1 descartable. Estado persistido sintético, no un caso de uso de stock SQL.
-- Mantiene el saldo del fixture 5A: entrada + reverso completo de una unidad.
CREATE FUNCTION pg_temp.reversal_fixture_hash(VARIADIC fields text[]) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE payload bytea := ''::bytea; field text; encoded bytea;
BEGIN
    FOREACH field IN ARRAY fields LOOP
        IF field IS NULL THEN payload := payload || int4send(-1);
        ELSE encoded := convert_to(field,'UTF8'); payload := payload || int4send(octet_length(encoded)) || encoded;
        END IF;
    END LOOP;
    RETURN encode(sha256(payload),'hex');
END;
$$;
DO $$
DECLARE supply bigint; actor bigint; presentation bigint; receipt bigint; line bigint; reversal bigint; stock numeric;
BEGIN
    IF current_database() <> 'ventro_legacy' THEN RAISE EXCEPTION 'Fixture exclusivo de slice1 descartable'; END IF;
    SELECT id,stock_actual INTO STRICT supply,stock FROM insumo WHERE nombre='Activo backup sintetico';
    SELECT id INTO STRICT actor FROM usuario WHERE username='admin';
    INSERT INTO inventory_purchase_presentation(insumo_id,nombre,unidad_compra_codigo,modo,familia,
        unidad_base_codigo,factor_base,creado_por_usuario_id)
        VALUES(supply,'Unidad sintética','unidad','FIJA','COUNT','unidad',1,actor) RETURNING id INTO presentation;
    INSERT INTO inventory_receipt(request_id,command_fingerprint,fingerprint_version,moneda,actor_usuario_id)
        VALUES('00000000-0000-4000-8000-000000000614',
            pg_temp.reversal_fixture_hash('receipt-command-v1','00000000-0000-4000-8000-000000000614',
                actor::text,'MXN',NULL,NULL,'1','1',supply::text,presentation::text,'1',NULL,'10'),
            'receipt-command-v1','MXN',actor) RETURNING id INTO receipt;
    INSERT INTO inventory_receipt_line(receipt_id,line_no,insumo_id,presentation_id,cantidad_comprada,
        cantidad_base_efectiva,costo_total_real,presentation_nombre,unidad_compra_codigo,modo,familia,
        unidad_base_codigo,factor_base,calculation_version,operation_id)
        VALUES(receipt,1,supply,presentation,1,1,10,'Unidad sintética','unidad','FIJA','COUNT','unidad',1,
            'receipt-conversion-v1','725e0bb6-b2a0-30e1-a339-3ca0185622db') RETURNING id INTO line;
    INSERT INTO movimiento_inventario(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha,
        operation_id,operation_fingerprint,causal_origin,causal_reference,resulting_stock)
        VALUES(supply,actor,'ENTRADA',1,localtimestamp,'725e0bb6-b2a0-30e1-a339-3ca0185622db',
            pg_temp.reversal_fixture_hash(supply::text,'ENTRADA','1','UNIT',actor::text,'RECEPCION_RAPIDA',
                'receipt:'||receipt||'/line:1',NULL),'RECEPCION_RAPIDA','receipt:'||receipt||'/line:1',stock+1);
    INSERT INTO inventory_receipt_reversal(receipt_id,request_id,command_fingerprint,fingerprint_version,actor_usuario_id,motivo)
        VALUES(receipt,'00000000-0000-4000-8000-000000000615',
            pg_temp.reversal_fixture_hash('receipt-reversal-command-v1','00000000-0000-4000-8000-000000000615',
                receipt::text,actor::text,'Restore sintético'),'receipt-reversal-command-v1',actor,'Restore sintético')
        RETURNING id INTO reversal;
    INSERT INTO inventory_receipt_reversal_line(reversal_id,original_receipt_line_id,line_no,insumo_id,familia,
        unidad_base_codigo,cantidad_base_compensada,operation_id)
        VALUES(reversal,line,1,supply,'COUNT','unidad',1,'d25f1e1b-a675-3897-b538-672c5770949c');
    INSERT INTO movimiento_inventario(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha,comentario,
        operation_id,operation_fingerprint,causal_origin,causal_reference,resulting_stock)
        VALUES(supply,actor,'SALIDA',1,localtimestamp,'Restore sintético','d25f1e1b-a675-3897-b538-672c5770949c',
            pg_temp.reversal_fixture_hash(supply::text,'SALIDA','1','UNIT',actor::text,'REVERSO_RECEPCION_RAPIDA',
                'receipt:'||receipt||'/reversal:'||reversal||'/line:1','Restore sintético'),
            'REVERSO_RECEPCION_RAPIDA','receipt:'||receipt||'/reversal:'||reversal||'/line:1',stock);
END;
$$;
