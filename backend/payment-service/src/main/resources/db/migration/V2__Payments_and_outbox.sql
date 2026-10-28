CREATE TABLE payment (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    request_event_id UUID NOT NULL UNIQUE,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_attempt (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE REFERENCES payment(id),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED','FAILED')),
    simulator_version INTEGER NOT NULL CHECK (simulator_version = 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    request_json TEXT NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    logical_event_key VARCHAR(40),
    event_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    publication_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    CONSTRAINT event_outbox_logical_unique UNIQUE (aggregate_id, logical_event_key),
    CONSTRAINT event_outbox_logical_key_check CHECK (
        CASE WHEN event_type IN ('PaymentSucceeded','PaymentFailed')
            THEN logical_event_key IS NOT NULL AND logical_event_key = 'PaymentDecision'
            ELSE logical_event_key IS NULL END
    )
);
CREATE UNIQUE INDEX event_outbox_sequence_idx ON event_outbox(publication_sequence);
CREATE INDEX event_outbox_aggregate_pending_idx ON event_outbox(aggregate_id,published_at,publication_sequence);
