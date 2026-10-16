-- Los PIN heredados de baja entropía se invalidan. Su restablecimiento se hace fuera de Flyway.
-- No inventamos salidas para reconciliar historiales ambiguos.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.asistencia WHERE hora_salida IS NULL
        GROUP BY usuario_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V4: existen asistencias abiertas duplicadas; reconciliar el historial antes de migrar';
    END IF;
END $$;

ALTER TABLE public.usuario DROP COLUMN pin;
ALTER TABLE public.usuario ADD COLUMN pin_digest character varying(64);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_pin_digest_format
    CHECK (pin_digest IS NULL OR pin_digest ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX usuario_pin_digest_unique ON public.usuario (pin_digest)
    WHERE pin_digest IS NOT NULL;

CREATE UNIQUE INDEX asistencia_una_abierta_por_usuario ON public.asistencia (usuario_id)
    WHERE hora_salida IS NULL;

CREATE TABLE public.attendance_pin_attempt (
    source_digest character varying(64) PRIMARY KEY,
    attempt_count integer NOT NULL CHECK (attempt_count BETWEEN 0 AND 11),
    window_started_at timestamp with time zone NOT NULL
);

CREATE TABLE public.attendance_pin_config (
    singleton smallint PRIMARY KEY CHECK (singleton = 1),
    secret_check character varying(64) NOT NULL
);
