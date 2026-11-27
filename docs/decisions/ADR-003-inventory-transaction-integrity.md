# ADR-003 Inventory transaction integrity and database constraints

## Status

Accepted

## Context

Stock changes, reservation state, and stored idempotency responses must stay
consistent across concurrent requests and failed transactions. Direct SQL can
bypass REST validation, service checks, and domain invariants.

## Decision

Keep validation in the REST, application, and domain layers. Use PostgreSQL
primary keys, foreign keys, uniqueness, check constraints, and required columns
to protect persisted data. Retain optimistic version checks for both inventory
and reservation updates.

Reservation creation and successful idempotency completion use REQUIRED
propagation and share one local transaction across JPA and JDBC. A successful
commit publishes the stock update, reservation, and stored response together.
If the transaction fails, all three effects roll back and the client can
reuse the key.

Flushing executes SQL while the transaction remains open. Under READ
COMMITTED, the owning transaction can read its flushed changes and an
independent transaction sees only committed data.

## Alternatives considered

- Application validation alone leaves direct database writes unprotected.
- Independent commits for stock, reservations, and idempotency can leave
  partial effects after a failure.
- Triggers for cross-table rules could enforce additional invariants at
  the cost of more database-specific logic and lock coordination.

## Consequences

Database constraints enforce row-level rules. Transactional application
operations maintain the relationship between reserved stock and active
reservation quantities, valid state transitions, and the original response
associated with an idempotency key. Integration tests verify those
relationships through direct SQL.

Tests that suspend a transaction and start an independent one require another
pool connection. PostgreSQL and Docker are required for these tests.

## Validation

`DatabaseConstraintsIT` checks PostgreSQL SQLSTATE and persisted record
integrity. `ReservationTransactionIT` checks injected failures after SQL
execution, atomic commit, key reuse, read visibility, rollback-only
propagation, stale versions, and rollback of terminal transitions.

Related decisions are documented in [ADR-001](ADR-001-inventory-concurrency-control.md)
and [ADR-002](ADR-002-reservation-idempotency.md).

## Future considerations

Production integrity monitoring, retention policy, a transactional outbox,
Kafka deduplication, and Saga compensation.
