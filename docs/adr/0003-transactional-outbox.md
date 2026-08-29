# ADR 0003: Use a transactional outbox

## Context

A `REQUIRE_APPROVAL` request changes PostgreSQL state and must also produce an SQS message. If the decision committed and the following publish failed, the approval work item would be lost. Publishing first has the inverse problem: a message could exist for database work that later rolled back.

## Decision

Create the decision, pending approval, audit records, and outbox row in one database transaction. A scheduled relay publishes unsent outbox rows after commit and records `sent_at` only after SQS accepts the message.

## Consequences

- A committed approval always has a durable message to retry.
- Publishing is at least once, so the consumer uses `processed_messages` in the same transaction as its effect.
- The relay can retry failed sends and multiple relay instances can claim batches with row locks.
- The outbox adds a table, a scheduler, backlog monitoring, and an operational retry path.
