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
| POST | `/api/v1/reservations` | `productId`, `orderId`, `quantity` | `201` for a new reservation, `200` for a retry |
| GET | `/api/v1/reservations/{id}` | | `200` with the reservation |
| POST | `/api/v1/reservations/{id}/confirm` | | `200` with the confirmed reservation |
| POST | `/api/v1/reservations/{id}/release` | | `200` with the released reservation |

Quantities are positive integers and IDs are UUIDs. A new product starts with
zero stock. Reserving stock moves units from available to reserved. Confirming
consumes the reserved units. Releasing returns them to available stock.

A reservation retry with the same order, product, and quantity returns the
existing reservation in its current state. Repeating the same final action
also returns the existing state. A changed reservation quantity, the opposite
final action, insufficient stock, or a duplicate SKU returns `409 Conflict`.
Invalid requests return `400 Bad Request` and missing resources return
`404 Not Found`.

### Architecture decisions

Product metadata and stock quantities live in separate tables to isolate their
update patterns. Product creation and zero-stock initialization share one
transaction. JPA `@Version` detects stale inventory updates. Reservation
operations also lock the inventory row while checking stock and updating the
reservation. PostgreSQL constraints enforce SKU uniqueness and nonnegative
stock. Inventory Service owns its database and applies schema changes through
Flyway.

Integration with Order Service through REST and Kafka is planned.

## Roadmap

1. Customer Service
2. Inventory Service
3. Order Service
4. Kafka event integration
5. API Gateway and security
6. Distributed tracing and delivery automation
