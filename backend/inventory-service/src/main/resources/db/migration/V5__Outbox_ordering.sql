ALTER TABLE event_outbox ADD COLUMN publication_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX event_outbox_sequence_idx ON event_outbox(publication_sequence);
CREATE INDEX event_outbox_aggregate_pending_idx ON event_outbox(aggregate_id, published_at, publication_sequence);
