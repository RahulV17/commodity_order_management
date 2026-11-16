# Known issues and production follow-ups

This list is deliberately blunt. Every item describes behaviour that exists in
the submitted code, why it was accepted for a time-boxed challenge, and what a
production implementation should do instead. Nothing here is presented as
"already solved" unless the verification is stated.

Severity scale: **High** affects correctness or security in production;
**Medium** affects operability or consistency; **Low** is cosmetic or a
documentation concern.

## 1. Kafka events can survive a rolled-back transaction (ghost events)

**Severity: Medium — data consistency**

`OrderService.create` is `@Transactional`. It saves the order and then calls
`eventProducer.publishOrderCreated(saved)` before the transaction commits.
`KafkaTemplate.send` is asynchronous and the producer has no transaction
synchronisation. If the transaction later rolls back (for example, a database
constraint failure at flush time), the event may already be in Kafka and the
Instrument Service will count an order that does not exist.

The reverse failure is also accepted: if the broker is unavailable, publishing
fails after the order is saved. The exception is logged and swallowed, so order
creation still succeeds but the activity counter is not updated.

**Why accepted:** the challenge requires a working event flow without
overengineering. Publishing inside the request keeps the demo simple.

**Production fix:** transactional outbox. Persist an `outbox_event` row in the
same transaction as the order, then run a relay (scheduler or Debezium) that
publishes and marks rows as sent. Consumers must be idempotent (see item 2).

**Where:** `backend/order-service/.../service/OrderService.java` (`create`),
`.../kafka/OrderEventProducer.java`. The trade-off is also recorded in
`docs/architecture.md`.

## 2. Kafka consumer is at-least-once but not idempotent

**Severity: Medium — data consistency**

`OrderEventConsumer.onOrderEvent` increments the instrument's activity count
for every `ORDER_CREATED` event. Kafka guarantees at-least-once delivery. If the
consumer processes an event, updates MongoDB, and then crashes before the offset
is committed, the event is redelivered and the counter is incremented twice.
There is no processed-event table or `eventId` uniqueness check.

Poison messages are caught and logged, which prevents a stuck partition but
means the event is silently dropped. There is no retry topic or dead-letter
topic.

**Production fix:** store processed `eventId` values with a unique index and
write the counter update and dedupe record in one MongoDB transaction (or make
the counter update idempotent by deriving it from orders). Add
`DefaultErrorHandler` with exponential backoff and a dead-letter topic.

**Where:** `backend/instrument-service/.../kafka/OrderEventConsumer.java`,
`.../service/InstrumentService.java` (`incrementOrderActivity`).

## 3. S3 storage is implemented but not integration-tested

**Severity: Medium — unverified feature**

`S3DocumentStorage` uses AWS SDK v2, the default credential provider chain and
an optional endpoint override. It compiles and is selected with
`STORAGE_TYPE=s3`, but no real bucket, MinIO instance or LocalStack was
available during development. Upload/download through S3 has therefore **not**
been proven. Local filesystem storage is the tested path.

**Production fix / verification recipe:**

```bash
export STORAGE_TYPE=s3
export AWS_S3_BUCKET=my-bucket
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
# optional: AWS_S3_ENDPOINT=http://localhost:9000 for MinIO/LocalStack
```

Then upload a PDF through the UI and confirm the object key
`orders/{orderId}/{uuid}-{filename}` exists and downloads correctly. Add an
integration test with Testcontainers LocalStack so this is automated.

**Where:** `backend/order-service/.../storage/S3DocumentStorage.java`,
`.../config/StorageConfig.java`.

## 4. Default JWT secret and demo passwords are in configuration

**Severity: High if deployed unchanged**

`application.yml` contains a shared development secret and the seeded demo
passwords `Admin@123` / `Operator@123`. Compose passes the same defaults through
environment variables. Anyone with the repository can mint a valid admin token
if the defaults are used.

**Why accepted:** the evaluator must be able to sign in without extra setup.

**Production fix:** require `JWT_SECRET` with no fallback (fail fast at
startup), generate at least 256 bits of entropy, rotate it, and never seed
users. Store password hashes in a managed identity store and add refresh-token
rotation. Keep the demo values only in `.env.example`/documentation.

**Where:** both `application.yml` files, `docker-compose.yml`,
`UserDataInitializer.java`.

## 5. JWT is stored in `localStorage`

**Severity: Medium — security trade-off**

`TokenStorageService` keeps the access token and user object in
`localStorage`. This survives reloads and works with the interceptor, but any
XSS can read the token. There is no refresh token; when the two-hour token
expires the user is redirected to login.

**Production fix:** store the token in an `HttpOnly`, `Secure`, `SameSite`
cookie and add CSRF protection for cookie-based auth, or keep short-lived
in-memory access tokens with a refresh token in an HttpOnly cookie. Add a strict
Content-Security-Policy. Document the chosen trade-off.

**Where:** `frontend/src/app/core/services/token-storage.service.ts`,
`auth.interceptor.ts`.

## 6. Redis is not published by Compose

**Severity: Low — developer experience**

The `redis` service has no host port mapping because the development machine
already runs Redis on 6379. Containerised services use `redis:6379` and work
correctly. A host-run Instrument Service expects `localhost:6379` and will log
cache warnings (then fall back to MongoDB) if no host Redis is running.

**Workaround:** run a local Redis, or temporarily add `ports: ["6379:6379"]`,
or set `REDIS_HOST`/`REDIS_PORT` to a reachable instance.

**Where:** `docker-compose.yml` (`redis`), `docs/architecture.md`.

## 7. Schema is managed by Hibernate `ddl-auto: update`

**Severity: Medium — operability**

`spring.jpa.hibernate.ddl-auto=update` creates and evolves tables
automatically. It is convenient for a demo but unsafe in production: no
reviewable migrations, no rollback, and destructive changes are not applied
predictably.

**Production fix:** Flyway or Liquibase with versioned SQL, `ddl-auto=validate`,
and a migration step in the deployment pipeline. Indexes currently declared on
the entity should be reproduced in the migration.

**Where:** `backend/order-service/src/main/resources/application.yml`,
`entity/Order.java`.

## 8. No order status history / audit trail

**Severity: Medium — auditability**

The `orders` table stores only the current status, `rejectionReason`,
`createdAt` and `updatedAt`. The detail page's timeline is an honest
interpretation of those fields, not a recorded history. It cannot show who
approved an order or when each transition happened.

**Production fix:** an `order_status_history` table (order id, from, to, actor,
reason, timestamp) written in the same transaction as each transition, exposed
through `GET /api/orders/{id}/history`.

**Where:** `entity/Order.java`, `OrderService.java`,
`frontend/.../order-detail.component.ts`.

## 9. Kafka topic creation is convenience-oriented

**Severity: Low — operability**

`KafkaTopicConfig` declares the topic and the broker also has
`auto_create_topics_enable=true`. Replication factor is 1 because the local
Compose cluster is a single broker. This is not a production topology.

**Production fix:** disable auto-creation, provision topics with replication
factor 3 and `min.insync.replicas=2`, manage them as infrastructure, and add
schema governance for event payloads.

**Where:** `backend/order-service/.../kafka/KafkaTopicConfig.java`,
`docker-compose.yml`.

## 10. Gateway trusts downstream services for authorization

**Severity: Low — defence in depth**

The gateway routes and applies CORS but does not validate JWTs. Each service
validates the token and enforces role rules. This is a valid design (the
services are the policy owners), but an unauthenticated request still reaches a
service before being rejected, and there is no rate limiting at the edge.

**Production fix:** keep service-side authorization, add gateway-level
authentication/rate limiting, and make services unreachable except through the
gateway (private network/security groups).

**Where:** `backend/api-gateway/src/main/resources/application.yml`.

## 11. Docker build can exhaust the daemon on a small machine

**Severity: Low — tooling**

`docker compose build` builds five images in parallel. Each Maven build
downloads dependencies into its own layer, and on a memory-constrained Docker
Desktop the daemon can return `EOF` or restart. The frontend image built
successfully; the parallel backend build failed once with
`failed to receive status ... EOF`.

**Workaround:** build sequentially:

```bash
COMPOSE_PARALLEL_LIMIT=1 docker compose build
```

or build one service at a time (`docker compose build order-service`). The
Docker CLI also needs `C:\Program Files\Docker\Docker\resources\bin` on `PATH`
on Windows so the credential helper resolves. Increase Docker Desktop memory if
the failure repeats.

## 12. No CI pipeline, cloud deployment or Kubernetes manifests

**Severity: Low — out of core scope**

The challenge lists CI/CD, EC2, Kubernetes, Prometheus/Grafana and Keycloak as
extension tasks. None were implemented. The repository contains Docker assets
and documented environment configuration only.

**Production fix:** GitHub Actions/GitLab CI running `mvn test`, `npm run
test:ci`, `npm run build`, image scanning and pushes; deployment to ECS/EKS or
EC2 with Terraform; Prometheus metrics via Micrometer and Grafana dashboards.

## 13. Single shared database credentials in Compose

**Severity: Low for local use**

PostgreSQL uses one application user (`orderuser`) with the database owner role;
MongoDB runs without authentication in the Compose file. This is acceptable only
for a local, non-routable demo network.

**Production fix:** separate migration/runtime users, least-privilege grants,
MongoDB authentication with per-service users, TLS, and secrets from a managed
secret store.

## 14. In-memory pagination limits are fixed in code

**Severity: Low**

The backend clamps `size` to 1–100 and the frontend offers 10/25/50. Search is a
case-insensitive `LIKE` over order number, instrument symbol and instrument
name. Large datasets would need full-text/trigram indexes and cursor
pagination.

**Where:** `OrderService.findAll`, `OrderRepository` specifications.

## 15. Frontend test suite has a history of headless-Chrome teardown noise

**Severity: Low — tooling**

Earlier runs produced `An error was thrown in afterAll` and a Chrome
disconnect. The cause was an unhandled RxJS error: the order-form component
handled a failed submit and then rethrew it into a subscription with no error
handler. Replacing the rethrow with `EMPTY` and mocking `NotificationService`
in that spec fixed it. Final verification: `npm run test:ci` executed **22 of
22 SUCCESS** and exited 0; Karma still prints a harmless "ChromeHeadless was not
killed in 2000 ms" warning during teardown. If the afterAll error reappears, run
one spec at a time with `ng test --include=...` and check for pending timers,
unhandled navigations or unhandled RxJS errors. Do not report the suite as green
unless `npm run test:ci` exits 0.

## 16. Uploaded files are read fully into memory on download

**Severity: Low**

`DocumentService.download` returns `byte[]`, so a 5 MB document is buffered in
the service and copied through the HTTP response. The 5 MB cap keeps this
bounded. Streaming (`StreamingResponseBody` or a presigned S3 URL) is the
production approach and avoids routing file bytes through the service.

**Where:** `DocumentService.download`, `DocumentController`.

## 17. Instrument activity counters are eventually consistent

**Severity: Low — by design**

The instrument list shows `activityCount` and `lastOrderAt` updated
asynchronously from Kafka. Immediately after order creation the number may be
stale until the consumer processes the event. This is intentional for the event
demonstration, not a bug; the UI does not depend on it for correctness.

## 18. CORS is broad for local development

**Severity: Low**

The gateway allows `http://localhost:*` and `http://127.0.0.1:*`. This supports
the dev server and Docker frontend without configuration changes. A production
deployment must restrict allowed origins to the real frontend hostnames.

## 19. Local Kafka container choice (Redpanda)

`apache/kafka:3.9.0` does not start on this host with the multi-listener
configuration required by `docker-compose.yml` (internal listener for
containers plus an external listener for host-run tools). Its entrypoint aborts
during `kafka-storage format` with:

```
java.lang.IllegalArgumentException: advertised.listeners cannot use the
nonroutable meta-address 0.0.0.0
```

`redpandadata/redpanda` is used instead. It speaks the Kafka wire protocol
(Kafka API 3.x), so the Spring Kafka producer/consumer configuration is
unchanged; only the broker image and the healthcheck command differ. If a
strict Apache Kafka broker is required for the evaluation, the original
environment-based configuration can be restored from git history and the same
listeners applied with a single-listener setup (`KAFKA_ADVERTISED_LISTENERS`
without the `EXTERNAL` pair).

## 20. Healthcheck timeouts on a loaded host

Two healthchecks were too tight on an 8 GB Windows host running Docker Desktop
with Mongo and Kafka both at high CPU:

- MongoDB's `mongosh --eval db.adminCommand('ping')` could exceed 5 s, marking a
  healthy database DOWN and blocking every dependent service. Timeout raised
  to 20 s with a 60 s start period.
- The order-service Feign call to the instrument service timed out under CPU
  contention ("Read timed out executing GET
  http://instrument-service/api/instruments/GOLD-001"), surfacing to the user as
  "Instrument service is unavailable". Feign connect/read timeouts raised from
  2 s/3 s to 10 s/20 s.

Both are infrastructure tuning issues rather than application defects, but they
are documented because a slow first demo can look like a functional failure.

The frontend healthcheck had a separate, deterministic bug: it probed
`http://localhost/healthz`, and busybox `wget` in the nginx image resolves
`localhost` to `::1` first. nginx listens on IPv4 by default, so the check
always failed with "connection refused" even though the site answered on port
4200. It now probes `http://127.0.0.1/healthz`.

## 21. Docker Desktop can drop during a long verification run

**Severity: Low — tooling**

On this 8 GB host the Docker Desktop engine went away twice: once during the
parallel image build and once after the browser smoke check. The CLI reported a
missing `dockerDesktopLinuxEngine` pipe; there was no application exception
before it. Named volumes survive, so PostgreSQL orders, MongoDB instruments and
Kafka events were still present after the restart.

**Recovery:** start Docker Desktop, wait for `docker info` to answer, then run
`docker compose up -d`. No rebuild is needed. The stack was brought back and
re-verified after the first drop; the second occurred after the verification
pass was complete.
