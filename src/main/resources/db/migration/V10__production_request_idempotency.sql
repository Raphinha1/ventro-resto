-- Una fila confirmada representa una producción completa. La inserción y todos
-- sus movimientos comparten transacción; un fallo no deja una clave consumida.
CREATE TABLE public.production_request (
    request_id uuid PRIMARY KEY,
    request_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT production_request_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION public.reject_production_request_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'production_request is immutable';
END;
$$;

CREATE TRIGGER production_request_immutable
BEFORE UPDATE OR DELETE ON public.production_request
FOR EACH ROW EXECUTE FUNCTION public.reject_production_request_mutation();
