-- 5A.5f: recibo de una activación individual; no es un saldo ni un movimiento.
CREATE TABLE public.inventory_activation (
    request_id uuid PRIMARY KEY,
    insumo_id bigint NOT NULL UNIQUE REFERENCES public.insumo(id),
    reconciliacion_id bigint NOT NULL,
    actor_usuario_id bigint NOT NULL REFERENCES public.usuario(id),
    activated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_inventory_activation_anchor
        FOREIGN KEY (insumo_id, reconciliacion_id)
        REFERENCES public.inventario_reconciliacion(insumo_id, id)
);

-- El recibo es la respuesta durable para reintentos del mismo comando.
CREATE FUNCTION public.proteger_inventory_activation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inventory_activation is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER inventory_activation_immutable
    BEFORE UPDATE OR DELETE ON public.inventory_activation
    FOR EACH ROW EXECUTE FUNCTION public.proteger_inventory_activation();

-- Extiende la defensa V8 a toda la aprobación seleccionada. No reescribe ni
-- exige campos nuevos a anclas históricas V7/V8; permite UPDATE sin cambios.
CREATE OR REPLACE FUNCTION public.validar_revision_ancla_inventario_5a()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        PERFORM 1 FROM public.insumo
        WHERE reconciliacion_actual_id = OLD.id
          AND estado_inventario_5a <> 'PENDIENTE'
        FOR SHARE;
        IF FOUND THEN
            IF NEW.revisado_en IS NULL OR NEW.revisado_por_usuario_id IS NULL THEN
                RAISE EXCEPTION 'No se puede retirar la revisión de un ancla activa'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.unidad_historica_familia IS DISTINCT FROM OLD.unidad_historica_familia
                    OR NEW.unidad_historica_codigo IS DISTINCT FROM OLD.unidad_historica_codigo
                    OR NEW.huella_comando IS DISTINCT FROM OLD.huella_comando THEN
                RAISE EXCEPTION 'No se puede cambiar la evidencia V8 de un ancla activa'
                    USING ERRCODE = '23514';
            END IF;
            RAISE EXCEPTION 'No se puede cambiar la aprobación de un ancla actual'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER validar_revision_ancla_inventario_5a ON public.inventario_reconciliacion;
CREATE TRIGGER validar_revision_ancla_inventario_5a
    BEFORE UPDATE ON public.inventario_reconciliacion
    FOR EACH ROW EXECUTE FUNCTION public.validar_revision_ancla_inventario_5a();
