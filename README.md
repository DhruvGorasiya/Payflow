# PayFlow

A payment processing backend built with Java 17 / Spring Boot 3. Uses Kafka-driven event sourcing across four microservices, PostgreSQL for persistence, Redis for caching and idempotency, and Docker for local development.

**Target throughput:** 2,000 transactions/min with zero duplicate payments.

---

## Architecture

```
┌─────────────┐   POST /payments    ┌──────────────────────┐
│   Client    │ ─────────────────►  │    payflow-api        │
└─────────────┘                     │  (REST gateway)       │
                                    └──────────┬────────────┘
                                               │ payment.initiated
                                               ▼
                              ┌────────────────────────────┐
                              │   payflow-transactions      │
                              │   (state machine)           │
                              └───────┬──────────┬─────────┘
                        payment.      │          │  payment.
                        completed     │          │  failed
                              ┌───────┘          └──────────┐
                              ▼                             ▼
                  ┌────────────────────┐    ┌──────────────────────┐
                  │  payflow-ledger     │    │ payflow-notifications │
                  │  (double-entry)     │    │ (webhooks / logging)  │
                  └────────────────────┘    └──────────────────────┘
```

### Services

| Service | Responsibility | Port |
|---|---|---|
| `payflow-api` | REST gateway, idempotency, Kafka producer | 8080 |
| `payflow-transactions` | Payment state machine, Kafka consumer + producer | — |
| `payflow-ledger` | Double-entry accounting, Kafka consumer | — |
| `payflow-notifications` | Outbound notifications (stub), Kafka consumer | — |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Messaging | Apache Kafka |
| Database | PostgreSQL 15 |
| Cache / Idempotency | Redis 7 |
| Migrations | Flyway |
| Containerization | Docker + Docker Compose |
| Deployment | AWS ECS Fargate |
| Build | Maven (multi-module) |
| Testing | JUnit 5 + Mockito + Testcontainers |

---

## Quick Start

### Prerequisites

- Docker + Docker Compose
- Java 17
- Maven 3.9+

### 1. Start infrastructure + all services

```bash
docker-compose up --build
```

This starts Zookeeper, Kafka, PostgreSQL, Redis, and all four Spring Boot services. The API is available at `http://localhost:8080`.

### 2. Initiate a payment

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

**Response 202 Accepted:**
```json
{
  "paymentId": "e4f3c2b1-...",
  "status": "INITIATED",
  "createdAt": "2024-01-01T12:00:00Z"
}
```

Sending the same request again with the **same `Idempotency-Key`** returns HTTP 200 with the cached response — no duplicate payment is created.

### 3. Check payment status

```bash
curl http://localhost:8080/api/v1/payments/{paymentId}
```

### 4. Health check

```bash
curl http://localhost:8080/api/v1/health
# {"status":"UP"}
```

---

## Running Tests

### Unit tests (fast, no Docker required)

```bash
mvn test
```

### Integration tests (requires Docker)

```bash
mvn verify
```

Integration tests use [Testcontainers](https://testcontainers.com/) to spin up real PostgreSQL and Redis containers. Kafka uses Spring's `EmbeddedKafka`.

---

## Project Structure

```
payflow/
├── docker-compose.yml               # Infra: Kafka, Zookeeper, PostgreSQL, Redis
├── docker-compose.override.yml      # App services (merged automatically by Docker Compose)
├── .env.example                     # Environment variable reference
├── pom.xml                          # Maven parent POM
│
├── payflow-common/                  # Shared events, DTOs, exceptions
├── payflow-api/                     # REST gateway (port 8080)
├── payflow-transactions/            # Payment state machine
├── payflow-ledger/                  # Double-entry ledger
├── payflow-notifications/           # Outbound notifications
│
├── ecs/                             # ECS Fargate task definitions
└── .github/workflows/               # CI (build + test) and CD (ECR push)
```

---

## Environment Variables

Copy `.env.example` to `.env` and fill in your values. Never commit `.env`.

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |

---

## CI/CD

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | Every push / PR to `main` | Compile → unit tests → integration tests |
| `cd.yml` | Push to `main` | Build all 4 Docker images → push to Amazon ECR |

**Required GitHub secrets for CD:** `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `ECR_REGISTRY`
**Required GitHub variable:** `AWS_REGION`

---

## Idempotency Design

1. Client sends `Idempotency-Key` header with every `POST /payments`
2. API checks Redis (`GET idempotency:{key}`) — returns cached 200 on hit
3. On miss, checks DB unique constraint as secondary safety net
4. Creates payment, publishes Kafka event, caches response in Redis with 24h TTL
5. Returns 202 Accepted

Redis failures are non-blocking (fail open) — the DB unique constraint prevents duplicates even if Redis is unavailable.

---

## AWS Deployment

ECS task definitions are in `ecs/`. Each service runs as a Fargate task (512 CPU / 1024 MB). The `payflow-api` service gets an Application Load Balancer; the other three are internal only.

Secrets (DB credentials) are sourced from AWS Secrets Manager. See `ecs/*.json` for the full configuration.
