# Architecture

The platform is a monorepo with independently buildable Maven services. Each
service owns the data for its responsibility and exposes operations through its
API or events.

| Service | Responsibility | Communication |
| --- | --- | --- |
| API Gateway | Routing and authentication | HTTP |
| Customer Service | Customer management | REST |
| Order Service | Order lifecycle | REST, Kafka |
| Inventory Service | Stock management and reservations | REST, Kafka |
| Notification Service | Order notifications | Kafka |

The Customer Service is the first milestone. It runs on Java 21 with Spring
Boot, Spring Web, Spring Data JPA, and PostgreSQL. Flyway manages the database
schema. Actuator exposes the service health endpoint. Docker Compose runs the
local database, and GitHub Actions runs the Maven build with JUnit, Mockito, and
Testcontainers.

When Order Service is introduced, it will call Customer Service and Inventory
Service synchronously over REST for customer checks and stock reservations.
Later milestones will publish Kafka events through a transactional outbox,
deduplicate event processing in consumers, and compensate failed order steps,
such as releasing a stock reservation when an order cannot complete.

The gateway will use Spring Cloud Gateway and Spring Security OAuth2 Resource
Server for JWT validation. Spring Boot 3.5.x maps to Spring Cloud 2025.0.x.
Recheck the support and compatibility matrix when adding the gateway.
[Spring Cloud release train compatibility](https://spring.io/projects/spring-cloud#overview)

The project uses Spring Boot 3.5.16 for the Java 21 and Boot 3 baseline.
Spring identifies this as the final open-source release of the 3.5 line.
[Spring Boot 3.5.16 release notes](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

The observability milestone will extend health monitoring with Micrometer
metrics and OpenTelemetry tracing.
