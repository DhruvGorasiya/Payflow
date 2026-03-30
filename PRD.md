# PayFlow — Product Requirements Document

## Overview

PayFlow is a payment processing backend built with Java/Spring Boot. It uses Kafka-driven event sourcing across three microservices (ledger, transactions, notifications), with PostgreSQL for persistence, Redis for caching and idempotency, and Docker for containerization. The system is designed to handle high-throughput payment workloads (target: 2,000 transactions/min) with zero duplicate payments.

This is a backend-only project. There is no frontend UI. All interaction is through REST APIs.

---

## Tech Stack

| Layer                     | Technology               |
| ------------------------- | ------------------------ |
| Language                  | Java 17                  |
| Framework                 | Spring Boot 3.x          |
| Messaging                 | Apache Kafka             |
| Primary DB                | PostgreSQL 15            |
| Cache / Idempotency store | Redis 7                  |
| Migrations                | Flyway                   |
| Containerization          | Docker + Docker Compose  |
| Deployment                | AWS ECS (Fargate) + ECR  |
| Build tool                | Maven                    |
| Testing                   | JUnit 5 + Testcontainers |

---

## Repository Structure

```
payflow/
├── docker-compose.yml               # Local dev: Kafka, Zookeeper, PostgreSQL, Redis
├── docker-compose.override.yml      # Optional: local service overrides
├── .env.example
├── README.md
│
├── payflow-api/                     # Spring Boot API gateway service
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│
├── payflow-transactions/            # Transactions microservice
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│
├── payflow-ledger/                  # Ledger microservice
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│
├── payflow-notifications/           # Notifications microservice
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│
└── payflow-common/                  # Shared DTOs, Kafka event schemas, constants
    ├── pom.xml
    └── src/
```

Each service is a standalone Spring Boot application with its own `pom.xml`. The root `pom.xml` is a Maven parent POM tying them together.

---

## Service Responsibilities

### payflow-api

- Exposes REST endpoints to clients
- Validates incoming requests
- Checks idempotency key against Redis before processing
- Publishes `PaymentInitiatedEvent` to Kafka topic `payment.initiated`
- Returns synchronous HTTP response with payment ID and status PENDING

### payflow-transactions

- Consumes `payment.initiated` from Kafka
- Manages payment state machine: `INITIATED → PROCESSING → COMPLETED | FAILED`
- Writes transaction records to its own PostgreSQL schema
- Publishes `PaymentCompletedEvent` or `PaymentFailedEvent` to Kafka
- Handles retries with exponential backoff on transient failures

### payflow-ledger

- Consumes `payment.completed` from Kafka
- Writes double-entry accounting records (debit + credit) in a single DB transaction
- Schema: `ledger_entries` table with columns: `id`, `transaction_id`, `entry_type` (DEBIT/CREDIT), `account_id`, `amount`, `currency`, `created_at`
- Idempotent: checks `transaction_id` before writing to avoid duplicate ledger entries

### payflow-notifications

- Consumes `payment.completed` and `payment.failed` from Kafka
- Sends outbound notifications (stubbed HTTP webhook call or log output is acceptable for v1)
- Tracks notification delivery status in Redis with TTL

---

## Data Models

### Payment (payflow-api / payflow-transactions)

```sql
CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    sender_id       VARCHAR(255) NOT NULL,
    receiver_id     VARCHAR(255) NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_idempotency_key ON payments(idempotency_key);
CREATE INDEX idx_payments_sender_id ON payments(sender_id);
```

### Ledger Entry (payflow-ledger)

```sql
CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL,
    entry_type      VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    account_id      VARCHAR(255) NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_ledger_txn_type ON ledger_entries(transaction_id, entry_type);
```

---

## Kafka Topics and Events

| Topic               | Producer             | Consumers                             |
| ------------------- | -------------------- | ------------------------------------- |
| `payment.initiated` | payflow-api          | payflow-transactions                  |
| `payment.completed` | payflow-transactions | payflow-ledger, payflow-notifications |
| `payment.failed`    | payflow-transactions | payflow-notifications                 |

### PaymentInitiatedEvent

```json
{
  "eventId": "uuid",
  "eventType": "PAYMENT_INITIATED",
  "occurredAt": "ISO-8601 timestamp",
  "paymentId": "uuid",
  "idempotencyKey": "string",
  "senderId": "string",
  "receiverId": "string",
  "amount": "decimal",
  "currency": "string"
}
```

### PaymentCompletedEvent

```json
{
  "eventId": "uuid",
  "eventType": "PAYMENT_COMPLETED",
  "occurredAt": "ISO-8601 timestamp",
  "paymentId": "uuid",
  "senderId": "string",
  "receiverId": "string",
  "amount": "decimal",
  "currency": "string"
}
```

### PaymentFailedEvent

```json
{
  "eventId": "uuid",
  "eventType": "PAYMENT_FAILED",
  "occurredAt": "ISO-8601 timestamp",
  "paymentId": "uuid",
  "reason": "string"
}
```

All events are serialized as JSON. Use `JsonSerializer` / `JsonDeserializer` from `spring-kafka`.

---

## REST API Endpoints (payflow-api)

### POST /api/v1/payments

Initiate a payment.

**Request headers:**

```
Idempotency-Key: <client-generated UUID>
Content-Type: application/json
```

**Request body:**

```json
{
  "senderId": "user_123",
  "receiverId": "user_456",
  "amount": "150.00",
  "currency": "USD"
}
```

**Response 202 Accepted:**

```json
{
  "paymentId": "uuid",
  "status": "INITIATED",
  "createdAt": "2024-01-01T12:00:00Z"
}
```

**Response 200 OK (duplicate idempotency key):**
Returns the original response from cache. No new event published.

**Response 400:** Validation error (missing fields, invalid amount, unsupported currency).

**Response 422:** Business rule violation.

### GET /api/v1/payments/{paymentId}

Fetch payment status.

**Response 200:**

```json
{
  "paymentId": "uuid",
  "status": "COMPLETED",
  "senderId": "user_123",
  "receiverId": "user_456",
  "amount": "150.00",
  "currency": "USD",
  "createdAt": "...",
  "updatedAt": "..."
}
```

### GET /api/v1/health

Returns `{ "status": "UP" }`. Used by ECS health checks.

---

## Idempotency Design

1. Client sends `Idempotency-Key` header with every POST request.
2. API layer checks Redis: `GET idempotency:{key}`
3. If key exists in Redis, return the cached response immediately. Do not publish to Kafka.
4. If key does not exist, proceed with payment creation.
5. After publishing to Kafka, store `SET idempotency:{key} {responseJson} EX 86400` (24hr TTL).
6. Also store the idempotency key in the `payments` table with a UNIQUE constraint as a secondary safety net.

---

## Docker Compose (Local Dev)

The `docker-compose.yml` must bring up:

- Zookeeper on port 2181
- Kafka broker on port 9092
- PostgreSQL on port 5432 (single instance, multiple schemas or databases per service)
- Redis on port 6379

Each service connects to these via environment variables. Services should have `depends_on` with health checks so Kafka and Postgres are ready before the app starts.

Use `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` for local dev to avoid manual topic creation.

---

## AWS ECS Deployment

Each service gets:

- Its own ECR repository
- Its own ECS task definition (Fargate, 512 CPU / 1024 MB memory minimum)
- Its own ECS service with desired count 1 for staging

Environment variables are passed via ECS task definition environment or AWS Secrets Manager references:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `SPRING_REDIS_HOST`
- `SPRING_REDIS_PORT`

The API service gets an Application Load Balancer attached. The other three services are internal only.

---

## Error Handling

- All controllers return a consistent error envelope:

```json
{
  "error": "VALIDATION_ERROR",
  "message": "amount must be greater than 0",
  "timestamp": "...",
  "path": "/api/v1/payments"
}
```

- Use `@ControllerAdvice` with `@ExceptionHandler` for global error handling.
- Kafka consumers use a `DefaultErrorHandler` with `FixedBackOff(3000L, 3)` for retry (3 retries, 3s apart).
- After retries exhausted, send to a dead letter topic: `{topic}.dlt`

---

## Configuration (application.yml pattern)

Each service should follow this structure:

```yaml
spring:
  application:
    name: payflow-{service}
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080
```

---

## Performance Target

- Target throughput: 2,000 transactions/min (approx 33/sec)
- Redis cache hit on duplicate idempotency keys should return in under 10ms
- Kafka producer send is async (fire and forget for initiated event, ack not required for p99 latency)
- Use a HikariCP connection pool with `maximum-pool-size: 20` per service

---

## Out of Scope (v1)

- Authentication / JWT
- Multi-currency conversion
- Real payment gateway integration (Stripe, etc.)
- Frontend UI
- GraphQL
- gRPC between services
- Distributed tracing (Jaeger / Zipkin)
- Kubernetes

---

## Definition of Done

- [ ] All four services start with `docker-compose up`
- [ ] POST /api/v1/payments returns 202 with a paymentId
- [ ] Sending the same request twice with the same Idempotency-Key returns the cached response, no duplicate in DB
- [ ] Ledger service writes exactly two rows (debit + credit) per completed payment
- [ ] Notifications service logs the notification on payment.completed
- [ ] All services have Flyway migrations that run on boot
- [ ] Each service has a working Dockerfile
- [ ] ECS task definitions are written (even if not deployed)
