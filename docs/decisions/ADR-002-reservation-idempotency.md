# ADR-002 Reservation creation idempotency

## Status

Accepted

## Context

An Order Service client can lose the response to a reservation request after
the Inventory Service commits. The unique order and product constraint
prevents duplicate reservations. A separate record is needed to replay the
original successful response and let the client recover the outcome.

## Decision

Accept an optional `Idempotency-Key` header on
`POST /api/v1/reservations`. Scope keys to the creation operation and
compare a versioned SHA-256 fingerprint of the validated order ID, product
ID, and quantity.

Use PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` to acquire key ownership
under READ COMMITTED. A competing insert waits for the owner to finish.
After a commit, a separate query can read the completed record and replay the
stored response or return HTTP 409 with `IDEMPOTENCY_KEY_CONFLICT` for a
changed payload. After a rollback, a waiting request can claim the key.

Reservation creation and idempotency writes share a DataSource and
transaction manager across JPA and JdbcTemplate. The key claim, stock
update, reservation, status, and serialized response commit or roll back
together. Only successful committed requests consume a key. A replay
preserves the original body even after the reservation changes state.

## Alternatives considered

- An in-memory cache loses records on restart and cannot coordinate service
  instances.
- Redis can coordinate instances, but it adds infrastructure and requires
  coordination between its writes and PostgreSQL commits.
- The business unique constraint remains an integrity guarantee. It cannot
  reconstruct the original response.

## Consequences

Concurrent requests with the same key can wait on a PostgreSQL lock.
Inventory database sessions use a five-second lock timeout. Lock failures
translated by Spring's pessimistic locking exception hierarchy return HTTP
503 with `Retry-After`. The service delegates retry decisions to clients.
Clients must persist and reuse the key across retries of a logical operation.

Completed records are retained indefinitely. A future retention policy must
define a supported retry window and what happens after it expires.

## Validation

Unit tests cover key syntax and fingerprinting. PostgreSQL integration tests
cover replay, payload conflicts, business uniqueness, rollback, and key
ownership transfer. The concurrent tests observe an actual lock wait before
allowing the original transaction to commit or roll back.

## Limitations and future work

The guarantee covers reservation creation effects within Inventory
PostgreSQL. Authenticated caller or tenant scoping and authorization for
replays are required before exposing the endpoint to untrusted tenants.
Retention, contention metrics, further client integrations, transactional
outbox, Kafka deduplication, and Saga orchestration remain future work.
