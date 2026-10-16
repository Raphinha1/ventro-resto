-- Cada request_id aceptado representa una sola transición de asistencia.
-- La fila se crea y completa en la misma transacción que la entrada/salida.
CREATE TABLE public.attendance_mark_request (
    request_id uuid PRIMARY KEY,
    pin_digest character varying(64) NOT NULL,
    result_message character varying(255),
    created_at timestamp with time zone NOT NULL DEFAULT current_timestamp
);
