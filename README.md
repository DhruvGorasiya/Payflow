# PayFlow

A distributed payment processing backend built with Java 17 and Spring Boot 3. Four microservices communicate via Kafka events, share no databases, and are individually deployable. Designed for reliability: every payment is idempotent, every Kafka consumer has a dead-letter queue, and the double-entry ledger is ACID-safe.

**Target throughput:** 2,000 transactions/min with zero duplicate payments.

---

## Architecture

```
┌─────────────┐   POST /api/v1/payments   ┌───────────────────────┐
│   Client    │ ────────────────────────► │     payflow-api        │
└─────────────┘                           │   (REST gateway)       │
                                          └──────────┬─────────────┘
                                                     │ payment.initiated
                                                     ▼
                                        ┌────────────────────────────┐
                                        │    payflow-transactions     │
                                        │    (payment state machine)  │
                                        └──────────┬─────────────────┘
                                payment.completed  │   payment.failed
                              ┌────────────────────┘        │
                              ▼                             ▼
                ┌─────────────────────┐    ┌──────────────────────────┐
                │   payflow-ledger    │    │  payflow-notifications    │
                │  (double-entry      │    │  (delivery tracking)      │
                │   accounting)       │    └──────────────────────────┘
                └─────────────────────┘
```

### Services

| Service | Responsibility | Port |
|---|---|---|
| `payflow-api` | Accepts payments, enforces idempotency, publishes to Kafka | 8080 |
| `payflow-transactions` | Consumes `payment.initiated`, runs state machine, publishes outcome | — |
| `payflow-ledger` | Consumes `payment.completed`, writes DEBIT + CREDIT atomically | — |
| `payflow-notifications` | Consumes both outcomes, tracks delivery status in Redis | — |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Messaging | Apache Kafka |
| Database | PostgreSQL 15 (Flyway migrations) |
| Cache / Idempotency | Redis 7 |
| Containerisation | Docker + Docker Compose |
| Deployment | AWS ECS Fargate |
| Build | Maven (multi-module) |
| Metrics | Micrometer + Prometheus |
| Logging | Logstash Logback Encoder (structured JSON) |
| Testing | JUnit 5 + Mockito + Testcontainers |

---

## Quick Start

### Prerequisites

- Docker + Docker Compose
- Java 17
- Maven 3.9+

### 1. Start infrastructure

```bash
docker-compose up -d
```

Starts Zookeeper, Kafka, PostgreSQL, and Redis with healthchecks.

### 2. Start all four services

```bash
docker-compose -f docker-compose.yml -f docker-compose.override.yml up --build
```

The API is available at `http://localhost:8080`.

### 3. Initiate a payment

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "senderId": "user_123",
    "receiverId": "user_456",
    "amount": "150.00",
    "currency": "USD"
  }'
```

**202 Accepted:**
```json
{
  "paymentId": "e4f3c2b1-a1b2-4c3d-8e9f-000000000001",
  "status": "INITIATED",
  "createdAt": "2024-01-01T12:00:00Z"
}
```

Repeating the request with the **same `Idempotency-Key`** returns **200 OK** with the cached response — no duplicate payment is created.

### 4. Check payment status

```bash
curl http://localhost:8080/api/v1/payments/{paymentId}
```

**200 OK:**
```json
{
  "paymentId": "e4f3c2b1-a1b2-4c3d-8e9f-000000000001",
  "status": "COMPLETED",
  "senderId": "user_123",
  "receiverId": "user_456",
  "amount": 150.00,
  "currency": "USD",
  "createdAt": "2024-01-01T12:00:00Z",
  "updatedAt": "2024-01-01T12:00:01Z"
}
```

### 5. Health check

```bash
curl http://localhost:8080/api/v1/health
# {"status":"UP"}

curl http://localhost:8080/actuator/health
# Full Spring Boot health with component details
```

---

## API Reference

### `POST /api/v1/payments`

Initiates a payment. Requires an `Idempotency-Key` header.

| Field | Type | Constraints |
|---|---|---|
| `senderId` | string | Required, max 64 chars |
| `receiverId` | string | Required, max 64 chars |
| `amount` | decimal | Required, 0.01 – 1,000,000.00 |
| `currency` | string | Required, valid ISO 4217 code (e.g. `USD`, `EUR`, `GBP`) |

**Responses:**

| Status | Meaning |
|---|---|
| 202 Accepted | Payment created, event published to Kafka |
| 200 OK | Duplicate `Idempotency-Key` — cached response returned |
| 400 Bad Request | Missing header or invalid field |
| 500 Internal Server Error | Unexpected error |

### `GET /api/v1/payments/{paymentId}`

Returns current payment status.

| Status | Meaning |
|---|---|
| 200 OK | Payment found |
| 404 Not Found | Unknown payment ID |

### `GET /actuator/health` · `GET /actuator/metrics` · `GET /actuator/prometheus`

Operational endpoints exposed on all four services.

---

## Payment Lifecycle

```
INITIATED ──► PROCESSING ──► COMPLETED
                         └──► FAILED
```

1. `payflow-api` accepts the request → saves `INITIATED` row → publishes `payment.initiated`
2. `payflow-transactions` picks up the event → transitions to `PROCESSING` → runs payment logic → transitions to `COMPLETED` or `FAILED` → publishes outcome event
3. `payflow-ledger` picks up `payment.completed` → writes DEBIT + CREDIT in a single transaction
4. `payflow-notifications` picks up both outcomes → logs notification → records delivery status in Redis

---

## Idempotency Design

```
POST /payments
    │
    ├─► Redis HIT  ──► return cached 200 (no DB, no Kafka)
    │
    ├─► Redis MISS
    │       │
    │       ├─► DB record found ──► re-cache → return 200
    │       │
    │       └─► New payment ──► save to DB → publish Kafka → cache in Redis → return 202
```

- Idempotency key format: `idempotency:{uuid}` in Redis, TTL 24 hours
- Redis failure is **fail-open** — the DB unique constraint on `idempotency_key` is the safety net
- The ledger uses a `UNIQUE INDEX (transaction_id, entry_type)` as a second idempotency guard against duplicate Kafka delivery

---

## Observability

Every service exposes:

| Endpoint | Description |
|---|---|
| `/actuator/health` | Liveness + readiness with component details |
| `/actuator/metrics` | JVM, HTTP, Kafka, and custom metrics |
| `/actuator/prometheus` | Prometheus scrape endpoint |

All log lines are structured JSON (via `logstash-logback-encoder`) and include:

```json
{
  "timestamp": "2024-01-01T12:00:00.000Z",
  "level": "INFO",
  "service": "payflow-api",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "logger": "com.payflow.api.service.PaymentService",
  "message": "Payment created"
}
```

**Correlation ID** — `X-Correlation-ID` header is generated by `payflow-api` if absent, stored in MDC, forwarded as a Kafka message header, and restored in MDC by all downstream consumers. Every log line across all four services for a single payment shares the same `correlationId`.

---

## Error Responses

All errors follow a consistent envelope:

```json
{
  "error": "VALIDATION_ERROR",
  "message": "amount must be between 0.01 and 1000000.00",
  "timestamp": "2024-01-01T12:00:00Z",
  "path": "/api/v1/payments"
}
```

| Error code | HTTP status | Cause |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Invalid request field or missing `Idempotency-Key` header |
| `MISSING_HEADER` | 400 | `Idempotency-Key` header absent |
| `PAYMENT_NOT_FOUND` | 404 | Unknown payment ID |
| `PAYMENT_PROCESSING_ERROR` | 422 | Payment could not be processed |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

---

## Running Tests

### Unit tests (no Docker required)

```bash
mvn test
```

23 unit tests across all four services covering idempotency paths, state machine transitions, double-entry writes, notification delivery, and Redis fail-open behaviour.

### Integration tests (requires Docker)

```bash
mvn verify
```

Testcontainers spins up real PostgreSQL and Redis containers per test class. Kafka uses Spring's `@EmbeddedKafka`. Four integration test suites:

| Test class | What it covers |
|---|---|
| `PaymentControllerIT` | Full HTTP round-trip: create, duplicate key, status check, 404, validation errors |
| `PaymentTransactionServiceIT` | Publishes `payment.initiated` to embedded Kafka; asserts `payment.completed` / `payment.failed` emitted and DB status updated |
| `LedgerServiceIT` | Verifies double-entry write and idempotent re-delivery skip |
| `NotificationServiceIT` | Publishes events to embedded Kafka; asserts Redis delivery keys written |

---

## Project Structure

```
payflow/
├── pom.xml                          # Maven parent POM (manages all deps + versions)
├── docker-compose.yml               # Infra: Kafka, Zookeeper, PostgreSQL, Redis
├── docker-compose.override.yml      # All four app services
├── .env.example                     # Environment variable reference
│
├── payflow-common/                  # Shared: events, DTOs, exceptions, validation
│   └── src/main/java/com/payflow/common/
│       ├── dto/                     # PaymentRequest, PaymentResponse, etc.
│       ├── events/                  # PaymentInitiatedEvent, PaymentCompletedEvent, etc.
│       ├── exception/               # PayflowException hierarchy
│       ├── util/                    # Headers (X-Correlation-ID constant)
│       └── validation/              # @ValidCurrency + CurrencyValidator
│
├── payflow-api/                     # REST gateway (port 8080)
├── payflow-transactions/            # Payment state machine
├── payflow-ledger/                  # Double-entry ledger
├── payflow-notifications/           # Notification delivery tracking
│
└── ecs/                             # AWS ECS Fargate task definitions
```

---

## Environment Variables

Copy `.env.example` to `.env`. Never commit `.env`.

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker(s) |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |

---

## AWS Deployment

ECS task definitions are in `ecs/`. Each service runs as a Fargate task (512 CPU / 1024 MB).

- `payflow-api` is fronted by an Application Load Balancer
- The other three services are internal (no public ingress)
- DB credentials are sourced from AWS Secrets Manager (see `ecs/*.json`)

---

## Known Limitations

| Area | Detail |
|---|---|
| Payment processing | `simulateProcessing()` is a stub — no real payment processor integration |
| Webhook delivery | `NotificationService` logs only — no real HTTP webhook calls |
| Authentication | No auth on any endpoint — suitable for internal/demo use only |
