-- Solo prueba de recuperación: filas sintéticas equivalentes a estado 5A
-- persistido. No es un procedimiento para activar/restablecer datos reales.
CREATE FUNCTION pg_temp.fixture_hash(VARIADIC fields text[]) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE payload bytea := ''::bytea; field text; encoded bytea;
BEGIN
    FOREACH field IN ARRAY fields LOOP
        IF field IS NULL THEN
            payload := payload || int4send(-1);
        ELSE
            encoded := convert_to(field, 'UTF8');
            payload := payload || int4send(octet_length(encoded)) || encoded;
        END IF;
    END LOOP;
    RETURN encode(sha256(payload), 'hex');
END;
$$;
DO $$
DECLARE supply bigint; actor bigint; anchor bigint;
BEGIN
    IF current_database() <> 'ventro_legacy' THEN
        RAISE EXCEPTION 'Fixture exclusivo de slice1 descartable';
    END IF;
    SELECT id INTO STRICT actor FROM usuario WHERE username='admin';
    INSERT INTO insumo(nombre,unidad_medida,costo_unitario,stock_actual,stock_minimo,activo)
        VALUES ('Activo backup sintetico','texto legado',5,10.123456,0,true) RETURNING id INTO supply;
    INSERT INTO inventario_reconciliacion(insumo_id,request_id,saldo_observado,
        suma_movimientos_conocidos,delta_no_explicado,corte_en,version_calculo,clasificacion,
        creado_por_usuario_id,revisado_en,revisado_por_usuario_id,procedencia,evidencia,
        unidad_historica_familia,unidad_historica_codigo,huella_comando)
        VALUES (supply,'00000000-0000-4000-8000-000000000501',10.123456,0,10.123456,
            clock_timestamp(),'legacy-inventory-v1','ORIGEN_NO_VERIFICADO',actor,
            clock_timestamp(),actor,'fixture restore','conteo sintetico','COUNT','unidad',
            pg_temp.fixture_hash(supply::text,'UNIT','UNIT',NULL,NULL,actor::text,
                'fixture restore','conteo sintetico')) RETURNING id INTO anchor;
    UPDATE insumo SET unidad_base_familia='COUNT',unidad_base_codigo='unidad',
        reconciliacion_actual_id=anchor,estado_inventario_5a='RECONCILIADO' WHERE id=supply;
    UPDATE insumo SET estado_inventario_5a='ACTIVO_5A' WHERE id=supply;
    INSERT INTO inventory_activation(request_id,insumo_id,reconciliacion_id,actor_usuario_id)
        VALUES ('00000000-0000-4000-8000-000000000502',supply,anchor,actor);
    INSERT INTO movimiento_inventario(insumo_id,usuario_id,tipo_movimiento,cantidad,fecha,
        comentario,operation_id,operation_fingerprint,causal_origin,causal_reference,resulting_stock)
        VALUES (supply,actor,'SALIDA',2,localtimestamp,'Salida sintetica',
            '00000000-0000-4000-8000-000000000503',
            pg_temp.fixture_hash(supply::text,'SALIDA','2','UNIT',actor::text,
                'CHECKPOINT','RESTORE:1','Salida sintetica'),
            'CHECKPOINT','RESTORE:1',8.123456);
    UPDATE insumo SET stock_actual=8.123456 WHERE id=supply;
    INSERT INTO production_request(request_id,request_hash) VALUES (
        '00000000-0000-4000-8000-000000000504',
        encode(sha256(convert_to(supply::text || '|1|' || actor::text, 'UTF8')), 'hex'));
END;
$$;
