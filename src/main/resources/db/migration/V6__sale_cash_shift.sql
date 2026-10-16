-- Las ventas anteriores permanecen sin atribución: ninguna fecha permite inferir su turno.
-- Un turno heredado abierto no puede cerrarse después con ventas previas sin vínculo.
-- Exigir su cierre con el código anterior conserva sus totales ya calculados.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.corte_caja WHERE estado = 'ABIERTO') THEN
        RAISE EXCEPTION 'Cierre los turnos de caja abiertos antes de migrar a V6';
    END IF;
END $$;

ALTER TABLE public.ventas
    ADD COLUMN corte_caja_id bigint;

-- Marca únicamente filas que ya existían antes de V6. Un dump restaurado conserva
-- esta exención; NOT VALID por sí solo rechazaría los INSERT del restore.
ALTER TABLE public.ventas
    ADD COLUMN legacy_sin_turno boolean NOT NULL DEFAULT true;
ALTER TABLE public.ventas
    ALTER COLUMN legacy_sin_turno SET DEFAULT false;

ALTER TABLE public.ventas
    ADD CONSTRAINT fk_ventas_corte_caja
    FOREIGN KEY (corte_caja_id) REFERENCES public.corte_caja(id);

-- NOT VALID conserva ventas históricas sin turno; PostgreSQL valida escrituras nuevas.
ALTER TABLE public.ventas
    ADD CONSTRAINT venta_monetaria_turno_check
    CHECK (total <= 0 OR corte_caja_id IS NOT NULL OR legacy_sin_turno) NOT VALID;

CREATE INDEX idx_ventas_corte_caja_id ON public.ventas(corte_caja_id);

-- Evita que dos aperturas concurrentes creen dos turnos activos.
-- Una instalación con turnos abiertos duplicados debe reconciliarlos antes de migrar.
CREATE UNIQUE INDEX uq_corte_caja_turno_abierto
    ON public.corte_caja(estado) WHERE estado = 'ABIERTO';
