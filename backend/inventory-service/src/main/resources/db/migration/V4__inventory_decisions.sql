CREATE TABLE inventory_decision (
    order_id UUID PRIMARY KEY,
    correlation_id UUID NOT NULL,
    request_json TEXT NOT NULL,
    result_type VARCHAR(40) NOT NULL CHECK (result_type IN ('InventoryReserved', 'InventoryRejected'))
);
