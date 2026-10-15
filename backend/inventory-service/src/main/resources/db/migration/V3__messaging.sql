CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL UNIQUE,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    event_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX event_outbox_pending_idx ON event_outbox(published_at, created_at);
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
