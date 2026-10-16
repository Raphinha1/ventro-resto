-- Una identidad de restaurante y sucursal por BD/despliegue. Se fija en el arranque.
CREATE TABLE deployment_identity (
    singleton smallint PRIMARY KEY DEFAULT 1 CHECK (singleton = 1),
    restaurant_id uuid NOT NULL,
    branch_id uuid NOT NULL
);
