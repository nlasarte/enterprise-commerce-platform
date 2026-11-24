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

## Roadmap

1. Customer Service
2. Inventory Service
3. Order Service
4. Kafka event integration
5. API Gateway and security
6. Distributed tracing and delivery automation
