# ADR-001 Inventory concurrency control

## Status

Accepted

## Context

Requests can compete for the same available stock or attempt to confirm and
release the same reservation. Inventory and stock reservations are separate
mutable entities. A transaction can hold a stale reservation while reading
fresh inventory, so the inventory version alone cannot protect a reservation
transition.

## Decision

Use JPA optimistic locking through `@Version` on both `Inventory` and
`StockReservation`. The service changes stock and reservation state in one
local PostgreSQL transaction. Before adjusting stock for a terminal
transition, the service flushes the reservation change to check its version.
If a subsequent inventory update fails, the database rolls back the flushed
change as part of the same transaction.

PostgreSQL constraints enforce nonnegative quantities and one reservation per
order and product. A stale update returns HTTP `409 Conflict` with the
`CONCURRENT_MODIFICATION` error code. The service performs no automatic
retries.

## Alternatives considered

**Pessimistic locking** acquires a row lock when reading the entity. Competing
transactions wait and can evaluate the latest committed stock afterward.
Blocking can reduce throughput, and transactions that lock multiple rows
need a consistent lock order.

**Atomic SQL updates** combine the quantity check and stock change into a
conditional update. They can reduce round trips under contention. They
bypass JPA dirty checking, require explicit version and persistence-context
management, and still need a transaction to coordinate the reservation.

## Consequences

The selected approach retains the JPA domain operations and fits short
transactions without network calls. A request can fail with a version
conflict even when the database has sufficient stock for both requests.
Clients can retrieve the current state before deciding whether to retry.
Database writes still acquire locks, so deadlocks remain possible. Workload
and contention measurements should inform any change in strategy.

## Validation

JUnit 5 and Testcontainers run independent PostgreSQL transactions using
`ExecutorService`, `CountDownLatch`, and Spring `TransactionTemplate`.
The suite checks competing allocations, concurrent confirmation and release,
opposing terminal transitions, a stale reservation with fresh inventory,
restock versus reserve, and duplicate reservation creation.

## Future considerations

Safe retries, request idempotency, lock contention monitoring, deadlock
handling, production load testing, and multi-product reservations.
