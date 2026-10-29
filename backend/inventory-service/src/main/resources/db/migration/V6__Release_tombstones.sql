CREATE TABLE reservation_guard (
    order_id UUID PRIMARY KEY,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO reservation_guard(order_id,cancelled) SELECT order_id,status='RELEASED' FROM reservation;
CREATE TABLE inventory_release (
    order_id UUID PRIMARY KEY,
    request_event_id UUID NOT NULL UNIQUE,
    correlation_id UUID NOT NULL,
    request_json TEXT NOT NULL,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('RELEASED','ALREADY_RELEASED','ABSENT_PREVENTED'))
);
CREATE TABLE compensation_error (
    event_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    error_code VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE processed_event ADD COLUMN event_json TEXT;
ALTER TABLE event_outbox DROP CONSTRAINT event_outbox_logical_key_check;
ALTER TABLE event_outbox ADD CONSTRAINT event_outbox_logical_key_check CHECK (
    CASE WHEN event_type IN ('InventoryReserved','InventoryRejected')
        THEN logical_event_key IS NOT NULL AND logical_event_key='InventoryDecision'
        WHEN event_type='InventoryReleased'
        THEN logical_event_key IS NOT NULL AND logical_event_key='InventoryRelease'
        ELSE logical_event_key IS NULL END
);
CREATE INDEX event_outbox_ready_idx ON event_outbox(published_at,publication_sequence);
