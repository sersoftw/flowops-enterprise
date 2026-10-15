CREATE TABLE purchase_order (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (char_length(currency) = 3),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    total NUMERIC(19,2) NOT NULL CHECK (total > 0),
    idempotency_key VARCHAR(128) NOT NULL,
    CONSTRAINT uk_order_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE order_item (
    order_id UUID NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    item_index INTEGER NOT NULL CHECK (item_index >= 0 AND item_index < 100),
    product_id VARCHAR(100) NOT NULL CHECK (char_length(trim(product_id)) > 0),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    unit_price NUMERIC(11,2) NOT NULL CHECK (unit_price > 0),
    PRIMARY KEY (order_id, item_index)
);

CREATE INDEX idx_order_created ON purchase_order(created_at DESC, id DESC);
CREATE INDEX idx_order_customer_created ON purchase_order(customer_id, created_at DESC, id DESC);
CREATE INDEX idx_order_status_created ON purchase_order(status, created_at DESC, id DESC);
