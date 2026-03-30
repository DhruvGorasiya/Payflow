# PayFlow — CLAUDE.md

Read the full spec in `PRD.md` before writing any code. This file governs all decisions not already answered by the PRD.

---

## Project Layout

```
payflow/
├── pom.xml                    # Maven parent POM (packaging=pom, lists all modules)
├── docker-compose.yml
├── .env.example
├── payflow-common/            # Shared events, DTOs, constants
├── payflow-api/               # REST gateway
├── payflow-transactions/      # Payment state machine
├── payflow-ledger/            # Double-entry accounting
└── payflow-notifications/     # Outbound notifications
```

Every module is listed under `<modules>` in the root `pom.xml`. Each child `pom.xml` declares `<parent>` pointing to `com.payflow:payflow-parent`. `payflow-common` is a plain JAR dependency; the other four are Spring Boot applications.

---

## Non-Negotiable Rules

- Never write code outside the established module structure. Every class belongs in exactly one service.
- Never use field injection (`@Autowired` on fields). Constructor injection only.
- Never use `var` for ambiguous types. Be explicit with generics.
- Never swallow exceptions silently. Every catch block must rethrow, log, or handle deliberately.
- Never hardcode configuration values. All secrets, URLs, ports, and credentials go in `application.yml` as `${ENV_VAR:default}` references.
- Never write a Kafka consumer without a configured `DefaultErrorHandler` and dead letter topic.
- Never write a DB migration after the fact. Schema changes always come first as a new Flyway file.
- Never use `spring.jpa.hibernate.ddl-auto=create` or `update`. Flyway manages all schema changes.
- Never use Lombok. Java records and explicit constructors only.
- Never use Spring Data REST (`@RepositoryRestResource`). Expose endpoints manually through controllers.
- Never use `@Scheduled` polling as a substitute for Kafka consumers.
- Never put business logic in Kafka listener methods. Delegate to a service class immediately.
- Never use `Thread.sleep()` in production code.
- Never commit `.env` files or files containing real credentials.

---

## Code Style

- Java 17. Use records for immutable DTOs. Use sealed classes where appropriate for event hierarchies.
- Package structure: `com.payflow.{service}.{layer}` where `{service}` is `api`, `transactions`, `ledger`, `notifications`, or `common`, and `{layer}` is one of: `api`, `service`, `domain`, `repository`, `kafka`, `config`.
- Class names: PascalCase. Methods and variables: camelCase. Constants: SCREAMING_SNAKE_CASE.
- No abbreviations. `paymentId` not `pmtId`. `idempotencyKey` not `iKey`.
- Every public method gets a single-line Javadoc if its purpose isn't immediately obvious from the name.
- Max method length: 40 lines. Extract a private method if longer.
- Max class length: 300 lines. Split responsibilities if longer.

---

## Spring Boot Conventions

- Controllers are `@RestController` only. No `@Controller` with view resolution.
- All controllers use `/api/v1/` prefix applied at the class level with `@RequestMapping`.
- All controller methods return `ResponseEntity<T>` so HTTP status is explicit.
- Services are `@Service`. Business logic only — no HTTP or Kafka concerns.
- Repositories are `@Repository` and extend `JpaRepository<T, UUID>` or use `JdbcTemplate` for complex queries.
- `@Transactional` belongs at the service layer, not the repository or controller layer.
- One `@Configuration` class per concern: `KafkaConfig`, `RedisConfig`, etc.

---

## Kafka Rules

- All Kafka event classes live in `payflow-common` under `com.payflow.common.events`.
- Every event has: `eventId` (UUID), `eventType` (String), `occurredAt` (Instant), plus payload fields.
- Producer methods are `void` and async. Do not block on `CompletableFuture` in the hot path.
- Consumer methods: `@KafkaListener(topics = "...", groupId = "${spring.application.name}")`.
- Consumer classes: `@Component`, named `{Domain}EventConsumer` (e.g. `PaymentEventConsumer`).
- Always set `spring.kafka.consumer.auto-offset-reset=earliest`.
- Dead letter topics follow the pattern `{original-topic}.dlt`.
- Error handler config in `KafkaConfig`:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaOperations<?, ?> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    FixedBackOff backOff = new FixedBackOff(3000L, 3);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

---

## Redis / Idempotency Rules

- Redis operations are always wrapped in try-catch. A Redis failure must never block a payment — fail open, log the error.
- Idempotency key format: `idempotency:{key}` where `{key}` is the raw client-provided UUID.
- TTL for idempotency keys: 86400 seconds (24 hours).
- Use `StringRedisTemplate` for simple key-value ops. Use `RedisTemplate<String, Object>` only for complex objects.
- Cache the full response body as a JSON string so duplicate requests get byte-for-byte identical responses.
- On a duplicate idempotency key hit, return **HTTP 200** with the cached response. Do not publish to Kafka. This is not an error — it is expected behavior.

---

## Database / Flyway Rules

- Every schema change is a new Flyway migration file. Never modify an existing migration.
- Naming: `V{n}__{description}.sql` with sequential integers (V1, V2, V3...).
- All migration files go in `src/main/resources/db/migration/`.
- Each service manages its own schema. Never write migrations that touch another service's tables.
- Always include `IF NOT EXISTS` in `CREATE TABLE` statements.
- Use `UUID` primary keys with `gen_random_uuid()` as default.
- Use `TIMESTAMPTZ` for all timestamp columns, never bare `TIMESTAMP`.
- Add indexes on foreign keys and any column used in a `WHERE` clause.

### Ledger double-entry writes

The ledger service must write both the DEBIT and CREDIT rows in a single `@Transactional` method. The unique index on `(transaction_id, entry_type)` is the idempotency guard — check for existence before writing, or rely on the unique constraint and catch `DataIntegrityViolationException` as a no-op.

---

## Error Handling

- Global exception handler: `@ControllerAdvice` class named `GlobalExceptionHandler` in `payflow-api`.
- All error responses follow this exact shape:

```json
{
  "error": "ERROR_CODE",
  "message": "human readable message",
  "timestamp": "ISO-8601",
  "path": "/api/v1/..."
}
```

- Custom exception hierarchy:
  - `PayflowException` (base, unchecked)
    - `ValidationException` → HTTP 400
    - `PaymentNotFoundException` → HTTP 404
    - `PaymentProcessingException` → HTTP 422
    - All others → HTTP 500

- A duplicate idempotency key is **not** an exception — it returns 200 from the cache. Do not model it as an exception.
- Never expose stack traces in API responses. Log at ERROR level with a correlation ID.

---

## Docker Rules

- Each service has its own `Dockerfile` in its module root.
- Multi-stage builds: build stage uses `maven:3.9-eclipse-temurin-17`, runtime stage uses `eclipse-temurin:17-jre-alpine`.
- Copy the JAR from `/target/*.jar` in the build stage to `/app/app.jar` in the runtime stage.
- Expose port 8080 in all Dockerfiles.
- Entrypoint: `CMD ["java", "-jar", "/app/app.jar"]`. No shell wrapper scripts.
- `docker-compose.yml` brings up: zookeeper, kafka, postgres, redis only. Application services are not in the base compose file.
- Define `healthcheck` blocks for kafka and postgres so dependent services wait correctly.
- Use `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` for local dev.

---

## Testing Rules

- Use Testcontainers for integration tests that touch Postgres, Redis, or Kafka. Never mock the DB in integration tests.
- Unit tests: JUnit 5 + Mockito. No PowerMock.
- Naming: `{ClassUnderTest}Test` for unit tests, `{ClassUnderTest}IT` for integration tests.
- Every service method with business logic gets at least one happy-path unit test and one failure-path unit test.
- Do not write tests for getters/setters or Spring Boot auto-configuration.

---

## Build Order

1. `payflow-common` — event classes, shared DTOs, constants
2. `docker-compose.yml` — kafka, zookeeper, postgres, redis
3. Root `pom.xml` — parent POM wiring all modules
4. `payflow-api` — POST /payments, idempotency check, Kafka publish
5. `payflow-transactions` — consume payment.initiated, state machine, publish completed/failed
6. `payflow-ledger` — consume payment.completed, write double-entry
7. `payflow-notifications` — consume completed/failed, log/stub webhook
8. Dockerfiles for all four services
9. ECS task definition JSONs

---

## Environment Variables (all services)

| Variable                      | Description                        |
|-------------------------------|------------------------------------|
| `SPRING_DATASOURCE_URL`       | JDBC URL for PostgreSQL            |
| `SPRING_DATASOURCE_USERNAME`  | DB username                        |
| `SPRING_DATASOURCE_PASSWORD`  | DB password                        |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker(s)               |
| `SPRING_REDIS_HOST`           | Redis hostname                     |
| `SPRING_REDIS_PORT`           | Redis port                         |

HikariCP: `maximum-pool-size: 20` per service.
