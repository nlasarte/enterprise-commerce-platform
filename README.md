# Enterprise Commerce Platform

A production-style microservices portfolio project
built with Java, Spring Boot, PostgreSQL, Kafka,
and Docker.

## Architecture

This monorepo contains independently buildable services. The
[architecture overview](docs/architecture.md) describes service boundaries and
the rollout from synchronous REST to Kafka.

## Technology Stack

Java 21, Spring Boot, PostgreSQL, Kafka,
Docker, Maven, GitHub Actions.

## Current Status

Milestone 1 implements the Customer Service API, PostgreSQL schema, and tests.

## Run the Customer Service

Install Java 21, Maven 3.6.3 or later, and Docker with Compose. From the
repository root, start PostgreSQL and launch the service.

```sh
docker compose -f infrastructure/docker-compose.yml up -d --wait customer-db
mvn -f services/customer-service/pom.xml spring-boot:run
```

Flyway applies the schema on startup. The service listens on
`http://127.0.0.1:8080` with a health endpoint at `/actuator/health`. The MVP API
has no authentication and binds to loopback by default.

| Environment variable | Local default |
| --- | --- |
| `CUSTOMER_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/customers` |
| `CUSTOMER_DB_USER` | `customer_app` |
| `CUSTOMER_DB_PASSWORD` | `customer_local` |
| `CUSTOMER_SERVICE_BIND_ADDRESS` | `127.0.0.1` |
| `CUSTOMER_SERVICE_PORT` | `8080` |

Compose also reads `CUSTOMER_DB_USER` and `CUSTOMER_DB_PASSWORD` when initializing
the database volume. If you set `CUSTOMER_DB_PORT` for Compose, use the same
port in `CUSTOMER_DB_URL`.

### API

| Method | Path | Success |
| --- | --- | --- |
| POST | `/api/v1/customers` | `201 Created` with the customer and a `Location` header |
| GET | `/api/v1/customers` | `200 OK` with an array ordered by ID |
| GET | `/api/v1/customers/{id}` | `200 OK` with the customer |
| PUT | `/api/v1/customers/{id}` | `200 OK` with the updated customer |
| DELETE | `/api/v1/customers/{id}` | `204 No Content` |

POST and PUT accept a JSON body with all three fields.

```json
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com"
}
```

Customer responses include a server-generated UUID as `id`. Both names must be
nonblank and at most 100 characters. Email must be valid, at most 254
characters, and unique case-insensitively. Error responses use
`application/problem+json` with `400` for invalid input, `404` for missing
customers, or `409` for duplicate emails. Field validation errors include an
`errors` array with field names and messages.

### Test and build

```sh
mvn -f services/customer-service/pom.xml test
mvn -f services/customer-service/pom.xml verify
```

`test` runs MVC tests without a database. `verify` also runs the integration
tests against PostgreSQL 17 through Testcontainers and requires Docker.
Testcontainers provisions its own database, so Compose can stay stopped for
both commands. GitHub Actions runs `verify` for Customer Service changes.

## Inventory Service

The Inventory Service manages product information, stock availability, and
reservations. It normalizes new SKUs to uppercase and validates requests
with Jakarta Bean Validation. Errors use RFC 9457 Problem Details. Domain
unit tests cover zero stock, restocking, invalid quantities, and overflow.

### Local configuration

Start the database and service from the repository root.

```powershell
docker compose -f infrastructure/docker-compose.yml up -d --wait inventory-db
Set-Location services/inventory-service
.\mvnw.cmd spring-boot:run
```

The API listens on port `8082`. Its PostgreSQL database is `inventory_db`
on local port `5433`.

### REST API

| Method | Path | Request body | Success |
| --- | --- | --- | --- |
| POST | `/api/v1/products` | `sku`, `name`, optional `description` | `201` with the product |
| GET | `/api/v1/products` | | `200` with products sorted by SKU |
| GET | `/api/v1/products/{id}` | | `200` with the product |
| GET | `/api/v1/inventory/{productId}` | | `200` with the SKU and available and reserved quantities |
| POST | `/api/v1/inventory/{productId}/restock` | `quantity` | `200` with the updated stock |

### Stock Reservation Management

| Method | Path | Request body | Success |
| --- | --- | --- | --- |
| POST | `/api/v1/reservations` | `productId`, `orderId`, `quantity` | `201` with the reservation and a `Location` header |
| GET | `/api/v1/reservations/{id}` | | `200` with the reservation |
| POST | `/api/v1/reservations/{id}/confirm` | | `200` with the confirmed reservation |
| POST | `/api/v1/reservations/{id}/release` | | `200` with the released reservation |

Quantities are positive integers and IDs are UUIDs. The order ID is an
external reference.

- `RESERVED` moves units from available stock to reserved stock.
- `CONFIRMED` consumes the reserved units.
- `RELEASED` returns the reserved units to available stock.

Stock and reservation changes commit or roll back together. PostgreSQL
enforces nonnegative quantities and one reservation per order and product.
Duplicate order and product reservations, insufficient stock, and attempts to
change a terminal state return `409 Conflict`. Matching idempotent retries
replay the original creation response. Repeating the same confirm or release
returns `200 OK` without changing stock. Invalid requests return
`400 Bad Request` and missing resources return `404 Not Found`.

Domain tests cover stock arithmetic and state transitions. Mockito tests
cover service orchestration. PostgreSQL Testcontainers tests cover
persistence, rollback after SQL execution, competing allocations, and
simultaneous confirmation and release.

Planned work includes automatic expiration and release, Saga coordination
with Order Service, and Kafka events.

### Architecture decisions

Product metadata and stock quantities live in separate tables to isolate their
update patterns. Product creation and zero-stock initialization share one
transaction. PostgreSQL constraints enforce SKU uniqueness and nonnegative
stock. Inventory Service owns its database and applies schema changes through
Flyway.

### Concurrency control

JPA `@Version` on both `Inventory` and `StockReservation` detects stale
modifications. Stock and reservation state commit or roll back in the same
transaction. An optimistic locking conflict returns `409 Conflict` with
`errorCode: CONCURRENT_MODIFICATION`. Repeating a completed confirm or release
is safe once the client has the current state.

Multithreaded PostgreSQL Testcontainers tests use JUnit 5, `ExecutorService`,
`CountDownLatch`, and Spring `TransactionTemplate` to force overlapping
transactions. They verify competing allocations, terminal transitions,
stale reservations with fresh inventory, restock races, and duplicate
creation. See [ADR-001](docs/decisions/ADR-001-inventory-concurrency-control.md)
for the alternatives and trade-offs.

Server-side automatic retries, production load testing, and multi-product
reservations remain future work.

### Reservation idempotency

Send an optional `Idempotency-Key` with `POST /api/v1/reservations`. Generate
the key once per logical reservation and reuse it with the same payload for
every retry.

```http
POST /api/v1/reservations
Content-Type: application/json
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

{
  "orderId": "b4ed7f7a-512a-4e27-98a7-34f93655d001",
  "productId": "b4ed7f7a-512a-4e27-98a7-34f93655d002",
  "quantity": 20
}
```

| Scenario | Result |
| --- | --- |
| New key and valid request | `201 Created` with `Idempotency-Replayed: false` |
| Same key and payload | Original status, `Location`, and JSON body with `Idempotency-Replayed: true` |
| Same key and different payload | `409 Conflict` with `IDEMPOTENCY_KEY_CONFLICT` |
| Invalid key | `400 Bad Request` with `INVALID_IDEMPOTENCY_KEY` |
| Different key for an existing order and product | `409 Conflict` |
| Rolled-back transaction | The key is available for another attempt |
| Missing key | Standard reservation creation |

Keys accept 1–128 characters using ASCII letters, digits, dots, underscores,
colons, and hyphens. A replay returns the original creation status in the
body. Use `GET /api/v1/reservations/{id}` for the current reservation state.

PostgreSQL coordinates concurrent claims with `INSERT ... ON CONFLICT DO NOTHING`.
The service hashes validated fields using SHA-256 and saves the original
response in the same transaction as the reservation and stock update.
Concurrent PostgreSQL tests exercise commits, replays, and rollbacks.

For network failures and transient concurrency errors, retry with the same
key, bounded exponential backoff, and jitter. The [PowerShell reference
client](scripts/Invoke-IdempotentReservation.ps1) implements this policy.
See [ADR-002](docs/decisions/ADR-002-reservation-idempotency.md) for design
trade-offs and the guarantee boundary.

The feature covers reservation creation only. Authenticated tenant scoping,
retention cleanup, production load testing, and distributed event
deduplication remain future work.


Integration with Order Service through REST and Kafka is planned.

## Roadmap

1. Customer Service
2. Inventory Service
3. Order Service
4. Kafka event integration
5. API Gateway and security
6. Distributed tracing and delivery automation
