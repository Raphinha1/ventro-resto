-- La clave y el resultado se escriben en la misma transacción que la venta.
CREATE TABLE public.pos_sale_request (
    idempotency_key uuid PRIMARY KEY,
    request_hash character varying(64) NOT NULL,
    venta_id bigint UNIQUE REFERENCES public.ventas(id),
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- NOT VALID preserva instalaciones históricas desconocidas; las escrituras nuevas se comprueban.
ALTER TABLE public.clientes ADD CONSTRAINT slice3_cliente_saldos_check
    CHECK (puntos >= 0 AND saldo_credito >= 0 AND limite_credito >= 0
           AND saldo_credito <= limite_credito AND total_gastado >= 0) NOT VALID;
ALTER TABLE public.ventas ADD CONSTRAINT slice3_venta_importes_check
    CHECK (total >= 0 AND coalesce(monto_efectivo, 0) >= 0
           AND coalesce(monto_tarjeta, 0) >= 0 AND coalesce(cambio, 0) >= 0
           AND coalesce(puntos_ganados, 0) >= 0 AND coalesce(puntos_canjeados, 0) >= 0) NOT VALID;
ALTER TABLE public.venta_detalles ADD CONSTRAINT slice3_detalle_cantidad_check
    CHECK (cantidad > 0 AND precio_unidad >= 0 AND subtotal >= 0) NOT VALID;
