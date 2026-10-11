-- Day 1 migration only: each service owns its schema and Flyway metadata.
-- Domain tables are deliberately deferred until their implementation days.
CREATE TABLE bootstrap_marker (
    service_name VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
INSERT INTO bootstrap_marker (service_name) VALUES ('payment-service');
