-- Esquema heredado capturado de Hibernate 6.4.4 / PostgreSQL 15.19.
-- Sin datos, usuarios, secretos ni objetos Flyway. No editar una vez aplicado.
CREATE TABLE public.asistencia (
    id bigint NOT NULL,
    hora_entrada timestamp(6) without time zone NOT NULL,
    hora_salida timestamp(6) without time zone,
    horas_trabajadas double precision,
    usuario_id bigint NOT NULL
);
CREATE SEQUENCE public.asistencia_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.asistencia_id_seq OWNED BY public.asistencia.id;
CREATE TABLE public.auditoria (
    id bigint NOT NULL,
    accion character varying(255) NOT NULL,
    descripcion character varying(2000),
    fecha timestamp(6) without time zone NOT NULL,
    modulo character varying(255),
    usuario character varying(255) NOT NULL
);
CREATE SEQUENCE public.auditoria_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.auditoria_id_seq OWNED BY public.auditoria.id;
CREATE TABLE public.categoria (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    descripcion text,
    nombre character varying(100) NOT NULL,
    tipo character varying(20) DEFAULT 'MENU'::character varying NOT NULL
);
CREATE SEQUENCE public.categoria_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.categoria_id_seq OWNED BY public.categoria.id;
CREATE TABLE public.clientes (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    direccion character varying(500),
    email character varying(255),
    fecha_registro timestamp(6) without time zone NOT NULL,
    limite_credito numeric(10,2) NOT NULL,
    mapa_url character varying(1000),
    nombre character varying(255) NOT NULL,
    puntos integer NOT NULL,
    referencias character varying(500),
    saldo_credito numeric(10,2) NOT NULL,
    telefono character varying(255),
    tipo character varying(255) NOT NULL,
    total_gastado numeric(12,2) NOT NULL
);
CREATE SEQUENCE public.clientes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.clientes_id_seq OWNED BY public.clientes.id;
CREATE TABLE public.configuracion_sistema (
    id bigint NOT NULL,
    direccion character varying(500),
    email character varying(255),
    fecha_expiracion character varying(255),
    iva_incluido boolean,
    limite_dispositivos integer,
    limite_productos integer,
    limite_usuarios integer,
    mensaje_ticket character varying(1000),
    mod_asistencia boolean NOT NULL,
    mod_caja boolean NOT NULL,
    mod_clientes boolean NOT NULL,
    mod_gastos boolean NOT NULL,
    mod_historial boolean NOT NULL,
    mod_ia boolean NOT NULL,
    mod_kds boolean NOT NULL,
    mod_mesas boolean NOT NULL,
    mod_reportes boolean NOT NULL,
    mod_reservas boolean NOT NULL,
    moneda character varying(255),
    moneda_fecha character varying(255),
    nivel_plan character varying(20) NOT NULL,
    nombre_negocio character varying(255),
    notas_maestras character varying(2000),
    permitir_descuentos boolean,
    porcentaje_iva numeric(5,2),
    precio_personalizado numeric(38,2),
    rfc character varying(255),
    simbolo_moneda character varying(255),
    slogan character varying(255),
    telefono character varying(255),
    zona_horaria character varying(255)
);
CREATE TABLE public.corte_caja (
    id bigint NOT NULL,
    diferencia numeric(12,2),
    estado character varying(255) NOT NULL,
    fecha_apertura timestamp(6) without time zone NOT NULL,
    fecha_cierre timestamp(6) without time zone,
    monto_contado numeric(12,2),
    monto_inicial numeric(12,2) NOT NULL,
    notas_cierre character varying(500),
    total_efectivo numeric(12,2),
    total_retiros numeric(12,2),
    total_tarjeta numeric(12,2),
    total_ventas numeric(12,2),
    usuario_id bigint
);
CREATE SEQUENCE public.corte_caja_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.corte_caja_id_seq OWNED BY public.corte_caja.id;
CREATE TABLE public.gastos (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    categoria character varying(255) NOT NULL,
    descripcion character varying(255) NOT NULL,
    fecha timestamp(6) without time zone NOT NULL,
    monto numeric(38,2) NOT NULL,
    usuario_id bigint
);
CREATE SEQUENCE public.gastos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.gastos_id_seq OWNED BY public.gastos.id;
CREATE TABLE public.insumo (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    costo_unitario numeric(10,2) NOT NULL,
    nombre character varying(150) NOT NULL,
    stock_actual numeric(10,2) NOT NULL,
    stock_minimo numeric(10,2) NOT NULL,
    unidad_medida character varying(50) NOT NULL,
    categoria_id bigint,
    proveedor_id bigint,
    CONSTRAINT insumo_costo_unitario_check CHECK ((costo_unitario >= (0)::numeric)),
    CONSTRAINT insumo_stock_actual_check CHECK ((stock_actual >= (0)::numeric)),
    CONSTRAINT insumo_stock_minimo_check CHECK ((stock_minimo >= (0)::numeric))
);
CREATE SEQUENCE public.insumo_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.insumo_id_seq OWNED BY public.insumo.id;
CREATE TABLE public.mesas (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    capacidad integer NOT NULL,
    estado character varying(255) NOT NULL,
    hora_apertura timestamp(6) without time zone,
    mozo character varying(255),
    numero integer NOT NULL,
    zona character varying(255),
    venta_id bigint
);
CREATE SEQUENCE public.mesas_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.mesas_id_seq OWNED BY public.mesas.id;
CREATE TABLE public.movimiento_inventario (
    id bigint NOT NULL,
    cantidad numeric(10,2) NOT NULL,
    comentario text,
    fecha timestamp(6) without time zone NOT NULL,
    tipo_movimiento character varying(50) NOT NULL,
    insumo_id bigint NOT NULL,
    usuario_id bigint NOT NULL
);
CREATE SEQUENCE public.movimiento_inventario_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.movimiento_inventario_id_seq OWNED BY public.movimiento_inventario.id;
CREATE TABLE public.producto (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    descripcion text,
    es_reventa boolean DEFAULT false NOT NULL,
    imagen_url character varying(255),
    nombre character varying(150) NOT NULL,
    precio_venta numeric(10,2) NOT NULL,
    puntos_otorgados integer NOT NULL,
    puntos_precio integer NOT NULL,
    categoria_id bigint,
    insumo_reventa_id bigint,
    CONSTRAINT producto_precio_venta_check CHECK ((precio_venta >= (0)::numeric))
);
CREATE SEQUENCE public.producto_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.producto_id_seq OWNED BY public.producto.id;
CREATE TABLE public.proveedor (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    contacto_nombre character varying(150),
    email character varying(100),
    nombre_empresa character varying(150) NOT NULL,
    telefono character varying(20)
);
CREATE SEQUENCE public.proveedor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.proveedor_id_seq OWNED BY public.proveedor.id;
CREATE TABLE public.receta (
    id bigint NOT NULL,
    cantidad numeric(10,4) NOT NULL,
    insumo_id bigint NOT NULL,
    producto_id bigint NOT NULL
);
CREATE SEQUENCE public.receta_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.receta_id_seq OWNED BY public.receta.id;
CREATE TABLE public.reservas (
    id bigint NOT NULL,
    email_cliente character varying(255),
    estado character varying(255) NOT NULL,
    fecha_creacion timestamp(6) without time zone NOT NULL,
    fecha_hora timestamp(6) without time zone NOT NULL,
    nombre_cliente character varying(255),
    notas character varying(500),
    numero_personas integer NOT NULL,
    origen character varying(255) NOT NULL,
    telefono_cliente character varying(255),
    cliente_id bigint,
    mesa_id bigint
);
CREATE SEQUENCE public.reservas_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.reservas_id_seq OWNED BY public.reservas.id;
CREATE TABLE public.sub_receta (
    id bigint NOT NULL,
    cantidad numeric(10,4) NOT NULL,
    ingrediente_id bigint NOT NULL,
    insumo_destino_id bigint NOT NULL
);
CREATE SEQUENCE public.sub_receta_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.sub_receta_id_seq OWNED BY public.sub_receta.id;
CREATE TABLE public.usuario (
    id bigint NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    nombre_completo character varying(150) NOT NULL,
    password character varying(255) NOT NULL,
    pin character varying(6),
    rol character varying(50) NOT NULL,
    username character varying(50) NOT NULL
);
CREATE SEQUENCE public.usuario_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.usuario_id_seq OWNED BY public.usuario.id;
CREATE TABLE public.venta_detalles (
    id bigint NOT NULL,
    canjeado boolean NOT NULL,
    cantidad integer NOT NULL,
    estado character varying(50) NOT NULL,
    notas character varying(255),
    precio_unidad numeric(38,2) NOT NULL,
    subtotal numeric(38,2) NOT NULL,
    producto_id bigint NOT NULL,
    venta_id bigint NOT NULL
);
CREATE SEQUENCE public.venta_detalles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.venta_detalles_id_seq OWNED BY public.venta_detalles.id;
CREATE TABLE public.ventas (
    id bigint NOT NULL,
    cambio numeric(10,2),
    fecha timestamp(6) without time zone NOT NULL,
    metodo_pago character varying(255),
    monto_efectivo numeric(10,2),
    monto_tarjeta numeric(10,2),
    puntos_canjeados integer,
    puntos_ganados integer,
    total numeric(38,2) NOT NULL,
    cliente_id bigint,
    mesa_id bigint,
    usuario_id bigint NOT NULL
);
CREATE SEQUENCE public.ventas_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.ventas_id_seq OWNED BY public.ventas.id;
ALTER TABLE ONLY public.asistencia ALTER COLUMN id SET DEFAULT nextval('public.asistencia_id_seq'::regclass);
ALTER TABLE ONLY public.auditoria ALTER COLUMN id SET DEFAULT nextval('public.auditoria_id_seq'::regclass);
ALTER TABLE ONLY public.categoria ALTER COLUMN id SET DEFAULT nextval('public.categoria_id_seq'::regclass);
ALTER TABLE ONLY public.clientes ALTER COLUMN id SET DEFAULT nextval('public.clientes_id_seq'::regclass);
ALTER TABLE ONLY public.corte_caja ALTER COLUMN id SET DEFAULT nextval('public.corte_caja_id_seq'::regclass);
ALTER TABLE ONLY public.gastos ALTER COLUMN id SET DEFAULT nextval('public.gastos_id_seq'::regclass);
ALTER TABLE ONLY public.insumo ALTER COLUMN id SET DEFAULT nextval('public.insumo_id_seq'::regclass);
ALTER TABLE ONLY public.mesas ALTER COLUMN id SET DEFAULT nextval('public.mesas_id_seq'::regclass);
ALTER TABLE ONLY public.movimiento_inventario ALTER COLUMN id SET DEFAULT nextval('public.movimiento_inventario_id_seq'::regclass);
ALTER TABLE ONLY public.producto ALTER COLUMN id SET DEFAULT nextval('public.producto_id_seq'::regclass);
ALTER TABLE ONLY public.proveedor ALTER COLUMN id SET DEFAULT nextval('public.proveedor_id_seq'::regclass);
ALTER TABLE ONLY public.receta ALTER COLUMN id SET DEFAULT nextval('public.receta_id_seq'::regclass);
ALTER TABLE ONLY public.reservas ALTER COLUMN id SET DEFAULT nextval('public.reservas_id_seq'::regclass);
ALTER TABLE ONLY public.sub_receta ALTER COLUMN id SET DEFAULT nextval('public.sub_receta_id_seq'::regclass);
ALTER TABLE ONLY public.usuario ALTER COLUMN id SET DEFAULT nextval('public.usuario_id_seq'::regclass);
ALTER TABLE ONLY public.venta_detalles ALTER COLUMN id SET DEFAULT nextval('public.venta_detalles_id_seq'::regclass);
ALTER TABLE ONLY public.ventas ALTER COLUMN id SET DEFAULT nextval('public.ventas_id_seq'::regclass);
ALTER TABLE ONLY public.asistencia
    ADD CONSTRAINT asistencia_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.auditoria
    ADD CONSTRAINT auditoria_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.categoria
    ADD CONSTRAINT categoria_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.clientes
    ADD CONSTRAINT clientes_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.configuracion_sistema
    ADD CONSTRAINT configuracion_sistema_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.corte_caja
    ADD CONSTRAINT corte_caja_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.gastos
    ADD CONSTRAINT gastos_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.insumo
    ADD CONSTRAINT insumo_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.mesas
    ADD CONSTRAINT mesas_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.movimiento_inventario
    ADD CONSTRAINT movimiento_inventario_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.producto
    ADD CONSTRAINT producto_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.proveedor
    ADD CONSTRAINT proveedor_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.receta
    ADD CONSTRAINT receta_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.reservas
    ADD CONSTRAINT reservas_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sub_receta
    ADD CONSTRAINT sub_receta_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.usuario
    ADD CONSTRAINT uk_863n1y3x0jalatoir4325ehal UNIQUE (username);
ALTER TABLE ONLY public.mesas
    ADD CONSTRAINT uk_j0e0hutvqcecq56ogqll8feqw UNIQUE (numero);
ALTER TABLE ONLY public.usuario
    ADD CONSTRAINT usuario_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.venta_detalles
    ADD CONSTRAINT venta_detalles_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.ventas
    ADD CONSTRAINT ventas_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.ventas
    ADD CONSTRAINT fk4dgjhccl2uuo8swmxlxb4ipb5 FOREIGN KEY (cliente_id) REFERENCES public.clientes(id);
ALTER TABLE ONLY public.corte_caja
    ADD CONSTRAINT fk6grf12ibwefsec9ed7qd53ad9 FOREIGN KEY (usuario_id) REFERENCES public.usuario(id);
ALTER TABLE ONLY public.venta_detalles
    ADD CONSTRAINT fk6oe5rpuq8yx6emqvsfb50nipb FOREIGN KEY (venta_id) REFERENCES public.ventas(id);
ALTER TABLE ONLY public.sub_receta
    ADD CONSTRAINT fk7enhp8fj5pa6ww5ktnncjrsk3 FOREIGN KEY (ingrediente_id) REFERENCES public.insumo(id);
ALTER TABLE ONLY public.insumo
    ADD CONSTRAINT fk7pfmw5wop41sal3595f572dkg FOREIGN KEY (categoria_id) REFERENCES public.categoria(id);
ALTER TABLE ONLY public.ventas
    ADD CONSTRAINT fk7q90tuwecmnx8c26jkx4vm9wt FOREIGN KEY (mesa_id) REFERENCES public.mesas(id);
ALTER TABLE ONLY public.movimiento_inventario
    ADD CONSTRAINT fkak0jo2lutflqsuxblfg9rlia4 FOREIGN KEY (insumo_id) REFERENCES public.insumo(id);
ALTER TABLE ONLY public.producto
    ADD CONSTRAINT fkbhaxjy3ysjchd9ol8m3lm0au5 FOREIGN KEY (insumo_reventa_id) REFERENCES public.insumo(id);
ALTER TABLE ONLY public.gastos
    ADD CONSTRAINT fkblvcrmk9ped3jdwfh4y4u8u5e FOREIGN KEY (usuario_id) REFERENCES public.usuario(id);
ALTER TABLE ONLY public.mesas
    ADD CONSTRAINT fkdcjhy2o0xe0sri4pc22y3l47c FOREIGN KEY (venta_id) REFERENCES public.ventas(id);
ALTER TABLE ONLY public.sub_receta
    ADD CONSTRAINT fkiog7t11b9khcsjwyaqrt1wxje FOREIGN KEY (insumo_destino_id) REFERENCES public.insumo(id);
ALTER TABLE ONLY public.venta_detalles
    ADD CONSTRAINT fkjsqctj8rb4ii6fkx7v1i947px FOREIGN KEY (producto_id) REFERENCES public.producto(id);
ALTER TABLE ONLY public.reservas
    ADD CONSTRAINT fkk33nidm20a0k0ssnyjfjmnnnp FOREIGN KEY (mesa_id) REFERENCES public.mesas(id);
ALTER TABLE ONLY public.insumo
    ADD CONSTRAINT fkmsmbrf64upncu8ieylvsb70r6 FOREIGN KEY (proveedor_id) REFERENCES public.proveedor(id);
ALTER TABLE ONLY public.receta
    ADD CONSTRAINT fkmxpgqwk8neoqbb3y97re8hj4w FOREIGN KEY (insumo_id) REFERENCES public.insumo(id);
ALTER TABLE ONLY public.ventas
    ADD CONSTRAINT fkn7rj3vt9pew8ahxt4f0qn9f6y FOREIGN KEY (usuario_id) REFERENCES public.usuario(id);
ALTER TABLE ONLY public.producto
    ADD CONSTRAINT fkodqr7965ok9rwquj1utiamt0m FOREIGN KEY (categoria_id) REFERENCES public.categoria(id);
ALTER TABLE ONLY public.reservas
    ADD CONSTRAINT fkpays115gahfu3ffuywded1xqt FOREIGN KEY (cliente_id) REFERENCES public.clientes(id);
ALTER TABLE ONLY public.asistencia
    ADD CONSTRAINT fkq04ajsgf4iqicxnc2s0ysneb3 FOREIGN KEY (usuario_id) REFERENCES public.usuario(id);
ALTER TABLE ONLY public.receta
    ADD CONSTRAINT fkqha3n6kdoytu44l80aljm5lur FOREIGN KEY (producto_id) REFERENCES public.producto(id);
ALTER TABLE ONLY public.movimiento_inventario
    ADD CONSTRAINT fkt6p6ejpbblav1phv0lgjpeg4r FOREIGN KEY (usuario_id) REFERENCES public.usuario(id);
