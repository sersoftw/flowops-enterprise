ALTER TABLE event_outbox ADD COLUMN publication_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX event_outbox_sequence_idx ON event_outbox(publication_sequence);
CREATE INDEX event_outbox_aggregate_pending_idx ON event_outbox(aggregate_id, published_at, publication_sequence);

ALTER TABLE purchase_order ALTER COLUMN status TYPE VARCHAR(40);
ALTER TABLE purchase_order DROP CONSTRAINT order_workflow_status;
ALTER TABLE purchase_order ADD CONSTRAINT order_workflow_status CHECK
    (status IN ('PENDING','INVENTORY_RESERVED','INVENTORY_REJECTED','COMPLETED','PAYMENT_FAILED_PENDING_COMPENSATION'));
ALTER TABLE event_outbox DROP CONSTRAINT event_outbox_logical_key_check;
ALTER TABLE event_outbox ADD CONSTRAINT event_outbox_logical_key_check CHECK (
    CASE WHEN event_type IN ('OrderCreated','PaymentRequested')
        THEN logical_event_key IS NOT NULL AND logical_event_key = event_type
        ELSE logical_event_key IS NULL END
);
