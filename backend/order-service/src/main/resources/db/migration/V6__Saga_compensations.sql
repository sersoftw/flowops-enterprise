ALTER TABLE purchase_order DROP CONSTRAINT order_workflow_status;
ALTER TABLE purchase_order ADD CONSTRAINT order_workflow_status CHECK
    (status IN ('PENDING','INVENTORY_RESERVED','INVENTORY_REJECTED','COMPLETED','PAYMENT_FAILED_PENDING_COMPENSATION','RELEASING_INVENTORY','CANCELLED','MANUAL_REVIEW'));
ALTER TABLE event_outbox DROP CONSTRAINT event_outbox_logical_key_check;
ALTER TABLE event_outbox ADD CONSTRAINT event_outbox_logical_key_check CHECK (
    CASE WHEN event_type IN ('OrderCreated','PaymentRequested','InventoryReleaseRequested')
        THEN logical_event_key IS NOT NULL AND logical_event_key=event_type
        ELSE logical_event_key IS NULL END
);
ALTER TABLE event_outbox ADD COLUMN abandoned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE processed_event ADD COLUMN event_json TEXT;
CREATE TABLE saga_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES purchase_order(id),
    event_id UUID,
    from_state VARCHAR(40),
    to_state VARCHAR(40) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX saga_history_order_idx ON saga_history(order_id,id);
INSERT INTO saga_history(order_id,to_state,reason) SELECT id,status,'MIGRATED_BASELINE' FROM purchase_order;
CREATE TABLE saga_compensation (
    order_id UUID PRIMARY KEY REFERENCES purchase_order(id),
    request_event_id UUID NOT NULL UNIQUE,
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 3),
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','CONFIRMED','MANUAL_REVIEW')),
    last_error VARCHAR(100)
);
CREATE INDEX saga_compensation_due_idx ON saga_compensation(status,next_retry_at);
CREATE TABLE saga_error (
    event_id UUID NOT NULL,
    order_id UUID NOT NULL,
    error_code VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(event_id,error_code)
);
CREATE INDEX event_outbox_ready_idx ON event_outbox(published_at,abandoned_at,publication_sequence);
