CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    logical_event_key VARCHAR(40),
    event_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT event_outbox_logical_unique UNIQUE (aggregate_id, logical_event_key),
    CONSTRAINT event_outbox_logical_key_check CHECK (
        CASE WHEN event_type IN ('InventoryReserved', 'InventoryRejected')
            THEN logical_event_key IS NOT NULL AND logical_event_key = 'InventoryDecision'
            ELSE logical_event_key IS NULL END
    )
);
CREATE INDEX event_outbox_pending_idx ON event_outbox(published_at, created_at, event_id);
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
