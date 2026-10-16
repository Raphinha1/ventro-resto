-- 5A.5a: ampliar precisión sin interpretar unidades ni reescribir movimientos.
-- Los escritores legacy conservan la coerción NUMERIC(10,2) en sus servicios;
-- el aplicador usa escala 6 e identifica sus movimientos por operation_id.
ALTER TABLE public.insumo
    ALTER COLUMN stock_actual TYPE numeric(19,6) USING stock_actual::numeric(19,6);

ALTER TABLE public.movimiento_inventario
    ALTER COLUMN cantidad TYPE numeric(19,6) USING cantidad::numeric(19,6),
    ADD COLUMN operation_id uuid,
    ADD COLUMN operation_fingerprint varchar(64),
    ADD COLUMN causal_origin varchar(100),
    ADD COLUMN causal_reference varchar(200),
    ADD COLUMN resulting_stock numeric(19,6);

-- NULL identifica las filas anteriores y las escrituras legacy.
-- Las operaciones nuevas son completas, positivas y distinguibles por UUID.
ALTER TABLE public.movimiento_inventario
    ADD CONSTRAINT movimiento_aplicador_completo_check CHECK (
        (operation_id IS NULL AND operation_fingerprint IS NULL
            AND causal_origin IS NULL AND causal_reference IS NULL AND resulting_stock IS NULL)
        OR (operation_id IS NOT NULL AND operation_fingerprint IS NOT NULL
            AND operation_fingerprint ~ '^[0-9a-f]{64}$'
            AND causal_origin IS NOT NULL AND length(btrim(causal_origin)) > 0
            AND causal_reference IS NOT NULL AND length(btrim(causal_reference)) > 0
            AND resulting_stock IS NOT NULL AND resulting_stock >= 0
            AND cantidad > 0
            AND tipo_movimiento IN ('ENTRADA', 'SALIDA', 'MERMA', 'SALIDA_VENTA',
                                    'ENTRADA_PRODUCCION', 'SALIDA_PRODUCCION'))
    );

CREATE UNIQUE INDEX movimiento_aplicador_operation_unique
    ON public.movimiento_inventario(operation_id) WHERE operation_id IS NOT NULL;

-- La fila causal es el recibo persistido del aplicador: los reintentos leen
-- estos datos. Las filas heredadas (operation_id NULL) siguen siendo editables,
-- pero no pueden adquirir identidad causal por UPDATE.
CREATE FUNCTION public.proteger_movimiento_aplicador() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.operation_id IS NULL THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        IF NEW.operation_id IS NOT NULL THEN
            RAISE EXCEPTION 'no se puede convertir un movimiento legacy en causal por UPDATE'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'movimiento causal inmutable: no se puede eliminar'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.insumo_id, NEW.usuario_id, NEW.tipo_movimiento, NEW.cantidad,
           NEW.operation_id, NEW.operation_fingerprint, NEW.causal_origin,
           NEW.causal_reference, NEW.resulting_stock, NEW.comentario, NEW.fecha)
       IS DISTINCT FROM
       ROW(OLD.insumo_id, OLD.usuario_id, OLD.tipo_movimiento, OLD.cantidad,
           OLD.operation_id, OLD.operation_fingerprint, OLD.causal_origin,
           OLD.causal_reference, OLD.resulting_stock, OLD.comentario, OLD.fecha) THEN
        RAISE EXCEPTION 'movimiento causal inmutable: no se puede cambiar su identidad o efecto'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER movimiento_aplicador_inmutable
    BEFORE UPDATE OR DELETE ON public.movimiento_inventario
    FOR EACH ROW EXECUTE FUNCTION public.proteger_movimiento_aplicador();
