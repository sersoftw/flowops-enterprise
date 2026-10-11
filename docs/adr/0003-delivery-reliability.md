# ADR-0003: At-least-once events and eventual saga consistency

**Status:** Proposed for Day 4 onward (not implemented Day 1)

Use an orchestrated saga. Order persistence and its outbox share a local DB transaction. Consumers store processed event IDs transactionally with side effects to handle duplicates. Compensation releases inventory after payment failure. Dead-letter handling, trace IDs and manual intervention are planned for later days. We will not advertise end-to-end exactly-once processing across database and Kafka.
