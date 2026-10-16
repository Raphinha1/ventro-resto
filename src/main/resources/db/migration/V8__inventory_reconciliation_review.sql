-- Slice 5A.4: evidencia necesaria para auditar la unidad y el comando aprobado.
-- Filas V7 existentes permanecen legibles; solo el nuevo caso de uso llena estos campos.
ALTER TABLE public.inventario_reconciliacion
    ADD COLUMN unidad_historica_familia varchar(8),
    ADD COLUMN unidad_historica_codigo varchar(8),
    ADD COLUMN punto_inicial_en timestamp(6) without time zone,
    ADD COLUMN punto_inicial_saldo numeric(19,6),
    ADD COLUMN huella_comando varchar(64);

ALTER TABLE public.inventario_reconciliacion
    ADD CONSTRAINT inventario_reconciliacion_unidad_historica_check CHECK (
        (unidad_historica_familia IS NULL AND unidad_historica_codigo IS NULL)
        OR (unidad_historica_familia IS NOT NULL AND unidad_historica_codigo IS NOT NULL AND (
            (unidad_historica_familia = 'MASS' AND unidad_historica_codigo = 'g')
            OR (unidad_historica_familia = 'VOLUME' AND unidad_historica_codigo = 'ml')
            OR (unidad_historica_familia = 'COUNT' AND unidad_historica_codigo = 'unidad')
        ))
    ),
    ADD CONSTRAINT inventario_reconciliacion_punto_inicial_check CHECK (
        (punto_inicial_en IS NULL AND punto_inicial_saldo IS NULL)
        OR (punto_inicial_en IS NOT NULL AND punto_inicial_saldo IS NOT NULL
            AND punto_inicial_saldo >= 0)
    ),
    ADD CONSTRAINT inventario_reconciliacion_huella_check CHECK (
        huella_comando IS NULL OR huella_comando ~ '^[0-9a-f]{64}$'
    );

-- Sustituye la validación V7 para promociones futuras. Una fila ya promovida
-- en V7 conserva sus NULL históricos mientras estado/ancla/unidad no cambien.
CREATE OR REPLACE FUNCTION public.validar_ancla_inventario_5a()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    validar_nueva_promocion boolean;
    ancla record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        validar_nueva_promocion := true;
    ELSE
        validar_nueva_promocion :=
            NEW.estado_inventario_5a IS DISTINCT FROM OLD.estado_inventario_5a
            OR NEW.reconciliacion_actual_id IS DISTINCT FROM OLD.reconciliacion_actual_id
            OR NEW.unidad_base_familia IS DISTINCT FROM OLD.unidad_base_familia
            OR NEW.unidad_base_codigo IS DISTINCT FROM OLD.unidad_base_codigo;
    END IF;

    IF NEW.estado_inventario_5a <> 'PENDIENTE' AND validar_nueva_promocion THEN
        SELECT revisado_en, revisado_por_usuario_id, unidad_historica_familia,
               unidad_historica_codigo, huella_comando INTO ancla
        FROM public.inventario_reconciliacion
        WHERE id = NEW.reconciliacion_actual_id AND insumo_id = NEW.id
        FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'RECONCILIADO/ACTIVO_5A requiere ancla revisada del mismo insumo'
                USING ERRCODE = '23514';
        END IF;
        IF ancla.revisado_en IS NULL OR ancla.revisado_por_usuario_id IS NULL THEN
            RAISE EXCEPTION 'RECONCILIADO/ACTIVO_5A requiere ancla revisada del mismo insumo'
                USING ERRCODE = '23514';
        END IF;
        IF ancla.unidad_historica_familia IS NULL OR ancla.unidad_historica_codigo IS NULL
                OR ancla.huella_comando IS NULL
                OR ancla.huella_comando !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'RECONCILIADO/ACTIVO_5A requiere evidencia V8 completa'
                USING ERRCODE = '23514';
        END IF;
        IF (ancla.unidad_historica_familia, ancla.unidad_historica_codigo)
                IS DISTINCT FROM (NEW.unidad_base_familia, NEW.unidad_base_codigo) THEN
            RAISE EXCEPTION 'unidad histórica distinta de la unidad base del insumo'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- El trigger V7 no observaba cambios de unidad. Se amplía su lista de columnas;
-- la función anterior evita volver a validar una fila V7 sin cambios relevantes.
DROP TRIGGER validar_ancla_inventario_5a ON public.insumo;
CREATE TRIGGER validar_ancla_inventario_5a
    BEFORE INSERT OR UPDATE OF estado_inventario_5a, reconciliacion_actual_id,
                               unidad_base_familia, unidad_base_codigo
    ON public.insumo FOR EACH ROW
    EXECUTE FUNCTION public.validar_ancla_inventario_5a();

-- Una ancla ya seleccionada no puede perder la revisión ni mutar la unidad o
-- huella que justificaron su promoción. Las anclas V7 existentes no se reescriben.
CREATE OR REPLACE FUNCTION public.validar_revision_ancla_inventario_5a()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revisado_en IS NULL OR NEW.revisado_por_usuario_id IS NULL
            OR NEW.unidad_historica_familia IS DISTINCT FROM OLD.unidad_historica_familia
            OR NEW.unidad_historica_codigo IS DISTINCT FROM OLD.unidad_historica_codigo
            OR NEW.huella_comando IS DISTINCT FROM OLD.huella_comando THEN
        PERFORM 1 FROM public.insumo
        WHERE reconciliacion_actual_id = NEW.id
          AND estado_inventario_5a <> 'PENDIENTE'
        FOR SHARE;
        IF FOUND THEN
            IF NEW.revisado_en IS NULL OR NEW.revisado_por_usuario_id IS NULL THEN
                RAISE EXCEPTION 'No se puede retirar la revisión de un ancla activa'
                    USING ERRCODE = '23514';
            END IF;
            RAISE EXCEPTION 'No se puede cambiar la evidencia V8 de un ancla activa'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER validar_revision_ancla_inventario_5a ON public.inventario_reconciliacion;
CREATE TRIGGER validar_revision_ancla_inventario_5a
    BEFORE UPDATE OF revisado_en, revisado_por_usuario_id, unidad_historica_familia,
                     unidad_historica_codigo, huella_comando
    ON public.inventario_reconciliacion FOR EACH ROW
    EXECUTE FUNCTION public.validar_revision_ancla_inventario_5a();
