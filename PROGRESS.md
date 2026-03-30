# PayFlow — Implementation Progress & Next Steps

## Status: Phase 4 Complete

All four microservices are implemented, tested, Dockerized, and wired into CI/CD pipelines.

---

## What Has Been Built

### Phase 1 — Foundation

- **Root `pom.xml`**: Maven multi-module parent POM wiring all five modules (`payflow-common`, `payflow-api`, `payflow-transactions`, `payflow-ledger`, `payflow-notifications`). Manages Spring Boot 3.2.5 BOM, Testcontainers BOM, and common dependencies. Includes `maven-failsafe-plugin` for `*IT` integration test classes.
- **`docker-compose.yml`**: Infra-only compose file (zookeeper, kafka, postgres, redis) with healthchecks. App services excluded per architecture decision.
- **`.env.example`**: Template for all required environment variables.

### Phase 2 — Core Services

#### `payflow-common`
Shared library JAR with no Spring Boot dependency.

| File | Purpose |
|---|---|
| `events/PaymentInitiatedEvent.java` | Kafka event record — sent when API accepts a payment |
| `events/PaymentCompletedEvent.java` | Kafka event record — sent when transaction processing succeeds |
| `events/PaymentFailedEvent.java` | Kafka event record — sent when transaction processing fails |
| `dto/PaymentRequest.java` | Incoming REST request record |
| `dto/PaymentResponse.java` | Outgoing REST response record |
| `dto/PaymentStatusResponse.java` | GET /payments/{id} response record |
| `dto/ErrorResponse.java` | Uniform error envelope record |
| `exception/PayflowException.java` | Base unchecked exception |
| `exception/ValidationException.java` | → HTTP 400 |
| `exception/PaymentNotFoundException.java` | → HTTP 404 |
| `exception/PaymentProcessingException.java` | → HTTP 422 |

#### `payflow-api` (port 8080)
REST gateway with idempotency and Kafka publishing.

| File | Purpose |
|---|---|
| `domain/Payment.java` | JPA entity for `payments` table |
| `repository/PaymentRepository.java` | `findByIdempotencyKey` query method |
| `service/PaymentService.java` | Redis check → DB check → create → Kafka → cache |
| `kafka/PaymentEventProducer.java` | Async `KafkaTemplate.send()` with completion logging |
| `api/PaymentController.java` | `POST /api/v1/payments`, `GET /api/v1/payments/{id}`, `GET /api/v1/health` |
| `api/GlobalExceptionHandler.java` | `@RestControllerAdvice` → uniform JSON error envelope |
| `config/KafkaConfig.java` | Producer factory, `DefaultErrorHandler` with DLQ |
| `resources/application.yml` | All configuration via env var references |
| `resources/db/migration/V1__create_payments_table.sql` | Creates `payments` table with indexes |

Idempotency flow:
1. Check Redis (`idempotency:{key}`) — return HTTP 200 on hit
2. Check DB unique constraint — return HTTP 200 on hit
3. Create payment row, publish `payment.initiated` to Kafka, cache response with 24h TTL
4. Return HTTP 202 Accepted

#### `payflow-transactions`
Payment state machine — consumes `payment.initiated`, publishes `payment.completed` or `payment.failed`.

| File | Purpose |
|---|---|
| `domain/Payment.java` | JPA entity (read + status update only) |
| `repository/PaymentRepository.java` | `findById` |
| `service/PaymentTransactionService.java` | State machine: INITIATED→PROCESSING→COMPLETED\|FAILED |
| `kafka/PaymentEventConsumer.java` | `@KafkaListener(topics = "payment.initiated")` |
| `kafka/PaymentEventProducer.java` | Publishes to `payment.completed` and `payment.failed` |
| `config/KafkaConfig.java` | `DefaultErrorHandler` (3 retries, 3s backoff) → DLQ |
| `resources/application.yml` | `spring.flyway.enabled=false` (doesn't own schema) |

#### `payflow-ledger`
Double-entry accounting — consumes `payment.completed`.

| File | Purpose |
|---|---|
| `domain/LedgerEntry.java` | JPA entity for `ledger_entries` |
| `repository/LedgerEntryRepository.java` | `existsByTransactionId` for idempotency guard |
| `service/LedgerService.java` | `@Transactional` DEBIT + CREDIT in one transaction |
| `kafka/PaymentEventConsumer.java` | `@KafkaListener(topics = "payment.completed")` |
| `config/KafkaConfig.java` | `DefaultErrorHandler` → DLQ |
| `resources/application.yml` | `flyway.table=flyway_schema_history_ledger` (avoids collision with api) |
| `resources/db/migration/V1__create_ledger_entries_table.sql` | `UNIQUE INDEX (transaction_id, entry_type)` as idempotency guard |

#### `payflow-notifications`
Outbound notification stub — consumes `payment.completed` and `payment.failed`.

| File | Purpose |
|---|---|
| `service/NotificationService.java` | Logs notification; tracks delivery in Redis (fail-open, 24h TTL) |
| `kafka/PaymentEventConsumer.java` | Two `@KafkaListener` methods for both topics |
| `config/KafkaConfig.java` | `DefaultErrorHandler` → DLQ |
| `resources/application.yml` | No datasource; Redis only |

### Phase 3 — Dockerfiles, Compose Override & Unit Tests

- **Dockerfiles** (all 4 services): Multi-stage builds — `maven:3.9-eclipse-temurin-17` build stage, `eclipse-temurin:17-jre-alpine` runtime. Build context is project root so all modules are available.
- **`docker-compose.override.yml`**: Brings up all 4 app services. Transactions and ledger `depends_on: payflow-api: condition: service_healthy` so Flyway runs before Hibernate validation.
- **Unit tests** (all in `src/test`, run with `mvn test`):
  - `PaymentServiceTest` — 11 tests covering idempotency paths, validation, Kafka publish
  - `PaymentTransactionServiceTest` — 5 tests covering state machine transitions and idempotent skip
  - `LedgerServiceTest` — 3 tests covering double-entry write and duplicate guard
  - `NotificationServiceTest` — 4 tests covering completed/failed notifications and Redis fail-open

### Phase 4 — Integration Tests, CI/CD & ECS

- **Integration tests** (run with `mvn verify`, require Docker):
  - `PaymentControllerIT` — Full HTTP round-trip via `TestRestTemplate`. Testcontainers PostgreSQL + Redis. `@EmbeddedKafka`. Tests: create payment, duplicate idempotency key → 200, get payment status, get non-existent → 404.
  - `LedgerServiceIT` — Verifies double-entry write and idempotent re-delivery skip. Testcontainers PostgreSQL + `@EmbeddedKafka`.
  - `PaymentTransactionServiceIT` — Publishes `payment.initiated` to embedded Kafka; spy consumers verify `payment.completed` or `payment.failed` emitted. Seeds data via `JdbcTemplate`. Flyway enabled via `@TestPropertySource` with test-scope migration at `src/test/resources/db/migration/V1__create_payments_table.sql`.
- **GitHub Actions CI** (`.github/workflows/ci.yml`): Every push/PR to `main` → compile → unit tests → integration tests.
- **GitHub Actions CD** (`.github/workflows/cd.yml`): Push to `main` → matrix Docker build for all 4 services → push to Amazon ECR with `{short-sha}` and `latest` tags.
- **ECS task definitions** (`ecs/*.json`): Fargate, 512 CPU / 1024 MB. API has ALB + HTTP healthcheck. Other three services are internal. Secrets from AWS Secrets Manager.
- **`README.md`**: Architecture diagram, quick start, curl examples, test commands, environment variable reference, CI/CD table, idempotency design, AWS deployment notes.
- **`.gitignore`**: Excludes `target/`, `*.jar`, `.env`, IDE metadata.

---

## Known Issues / Technical Debt

| # | Area | Issue |
|---|---|---|
| 1 | `PaymentTransactionService` | `simulateProcessing()` only validates non-null amount. No real payment processing logic. |
| 2 | `NotificationService` | Webhook delivery is a stub (logs only). No real HTTP webhook calls. |
| 3 | Integration tests | Not verified at runtime — Docker was not available locally during development. Tests compile cleanly; runtime validation pending. |
| 4 | `payflow-notifications` | No integration test written yet (see Next Steps). |
| 5 | Security | No authentication/authorization on any endpoint. |
| 6 | Observability | No distributed tracing (no Micrometer/OTEL setup). |

---

## Branch History

| Branch | Contents |
|---|---|
| `main` | Stable base — `payflow-common`, root POM, `.gitignore` |
| `phase3` | All four services + Dockerfiles + docker-compose.override.yml + unit tests |
| `phase4` | Integration tests + CI/CD workflows + ECS task definitions + README |

---

## Next Steps

### Immediate (Phase 5 — Validation & Hardening)

- [ ] **`NotificationServiceIT`** — Write integration test for `payflow-notifications`. Verify `payment.completed` and `payment.failed` listeners trigger notification logic. Use `@EmbeddedKafka` + spy on `NotificationService` or check Redis key written.
- [ ] **Run all tests with Docker available** — Execute `mvn verify` end-to-end to confirm Testcontainers-based IT tests pass at runtime.
- [ ] **Input validation** — Add `@Valid` + Bean Validation (`@NotNull`, `@DecimalMin`, `@Size`) to `PaymentRequest`. Return HTTP 400 on violation via `GlobalExceptionHandler`.
- [ ] **Currency validation** — Validate `currency` is a valid ISO 4217 code (e.g. reject `"XYZ"`).
- [ ] **Amount range** — Enforce minimum (`0.01`) and maximum (`1,000,000.00`) amounts.

### Near-term (Phase 6 — Observability & Security)

- [ ] **Actuator + Micrometer** — Add `spring-boot-starter-actuator` to all services. Expose `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.
- [ ] **Distributed tracing** — Add `micrometer-tracing-bridge-otel` + OTLP exporter. Propagate `traceId` across Kafka messages via headers.
- [ ] **Structured logging** — Add `logstash-logback-encoder` to emit JSON logs. Include `traceId`, `paymentId`, `service` in every log line.
- [ ] **API authentication** — Add Spring Security with JWT validation on `POST /api/v1/payments`. Public endpoint: `GET /api/v1/health`.
- [ ] **Rate limiting** — Add Redis-backed rate limiting per sender (`senderId`) to prevent abuse.
- [ ] **Correlation ID** — Generate and propagate `X-Correlation-ID` header through all services for end-to-end tracing.

### Future (Phase 7 — Production Readiness)

- [ ] **Real payment processor integration** — Replace `simulateProcessing()` in `PaymentTransactionService` with a call to a payment processor (e.g. Stripe, Braintree). Add circuit breaker (Resilience4j).
- [ ] **Real webhook delivery** — Implement HTTP webhook POST in `NotificationService` with retry logic and delivery receipts.
- [ ] **Webhook registration** — Allow callers to register a webhook URL per `senderId`; store in a new `webhooks` table.
- [ ] **Pagination** — Add `GET /api/v1/payments?page=0&size=20` endpoint for transaction history.
- [ ] **Multi-currency** — Add FX rate lookup before processing. Store `exchangeRate` and `settledAmount` in `payments` table.
- [ ] **Refunds** — Add `POST /api/v1/payments/{id}/refund` endpoint. New state: `REFUNDED`. New Kafka topic: `payment.refunded`.
- [ ] **Admin API** — Internal endpoints for support (e.g. manually fail/retry a payment).
- [ ] **AWS infrastructure as code** — Terraform or CDK for VPC, ECS cluster, RDS, ElastiCache, ALB, IAM roles.
- [ ] **Load testing** — Validate 2,000 transactions/min target with k6 or Gatling. Tune HikariCP pool size and Kafka partition count.
- [ ] **Chaos testing** — Simulate Redis down, Kafka partition leader failure, DB connection exhaustion. Verify fail-open behavior and DLQ routing.
