CREATE TABLE product (
    id VARCHAR(128) PRIMARY KEY CHECK (CHAR_LENGTH(TRIM(id)) > 0),
    name VARCHAR(200) NOT NULL CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE stock (
    product_id VARCHAR(128) PRIMARY KEY REFERENCES product(id),
    available INTEGER NOT NULL CHECK (available >= 0),
    reserved INTEGER NOT NULL CHECK (reserved >= 0),
    CHECK (CAST(available AS BIGINT) + reserved <= 2147483647)
);

-- Order IDs are opaque references, never foreign keys to the orders schema.
CREATE TABLE reservation (
    order_id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reservation_line (
    order_id UUID NOT NULL REFERENCES reservation(order_id),
    product_id VARCHAR(128) NOT NULL REFERENCES product(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, product_id)
);
CREATE INDEX reservation_line_product_idx ON reservation_line(product_id);
