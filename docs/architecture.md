# Architecture

This document explains how the Commodity Order Management application is put
together, why each component exists, and what happens on the wire for the flows
the evaluator is likely to exercise.

## 1. System context

```text
┌──────────────────────────────────────────────┐
│ Angular 20 SPA (standalone components)       │
│ login · dashboard · instruments · orders     │
└──────────────────────┬───────────────────────┘
                       │ /api/** (JWT bearer)
┌──────────────────────▼───────────────────────┐
│ Spring Cloud Gateway :8080                   │
│ explicit routes · CORS · X-Request-Id filter │
└──────────────────────┬───────────────────────┘
                       │ lb:// (Eureka discovery)
        ┌──────────────┴───────────────┐
        ▼                              ▼
┌───────────────────────┐      ┌────────────────────────┐
│ order-service  :8081  │      │ instrument-service :8082│
│ Spring MVC + JPA      │      │ Spring MVC + Mongo      │
│ PostgreSQL            │      │ MongoDB                 │
│ JWT auth + BCrypt     │      │ Redis cache-aside       │
│ Feign client          │─────▶│ GET /api/instruments/{id}│
│ Kafka producer        │      │ Kafka consumer          │
│ document storage      │      │ JWT validation          │
└─────────┬─────────────┘      └───────────┬─────────────┘
          │ order-events                    │
          └──────────────▶ Kafka ◀──────────┘
                   PostgreSQL · MongoDB · Redis · local/S3 documents
```

Eureka Server (`:8761`) is the registry. All four Spring applications register
with it. The gateway resolves `ORDER-SERVICE` and `INSTRUMENT-SERVICE` through
Eureka and load-balances requests; it does not call fixed hostnames.

### Responsibilities

| Component | Owns | Does not own |
| --- | --- | --- |
| `order-service` | users, orders, order documents metadata, order lifecycle, JWT issuance | instrument catalogue |
| `instrument-service` | instrument catalogue, activity counters, Redis cache | orders or users |
| `api-gateway` | routing, CORS, request-id propagation | business rules |
| `eureka-server` | service registry | configuration or traffic |
| Angular SPA | UX, client-side validation, token storage | authoritative authorization or calculations |

Each service has its own database. `order-service` never queries MongoDB, and
`instrument-service` never queries PostgreSQL. Cross-service data is obtained
through HTTP (Feign) or Kafka events.

## 2. Technology decisions

| Concern | Choice | Reason |
| --- | --- | --- |
| Backend | Spring Boot 3.4.2, Java 17 | Matches the challenge; records, `Instant`, modern Spring Security |
| Cloud | Spring Cloud 2024.0.1 | Compatible release train for Boot 3.4 |
| Gateway | Spring Cloud Gateway (reactive) | Required by the brief; explicit `Path` routes |
| Discovery | Eureka Server | Required by the brief; gateway uses `lb://` URIs |
| Sync calls | OpenFeign + load balancer | Declarative client, timeouts, JWT propagation |
| Messaging | Kafka (KRaft, no ZooKeeper) | Required event flow; simpler local operation |
| Cache | Redis, explicit cache-aside | Demonstrates TTL and eviction rather than hiding it behind annotations |
| Databases | PostgreSQL 16, MongoDB 7 | Relational order data vs. flexible catalogue documents |
| Auth | Spring Security + JJWT 0.12.5, HS256, BCrypt | Stateless JWT, demonstrable role rules |
| API docs | springdoc-openapi 2.8.5 | Swagger UI per service |
| Frontend | Angular 20 standalone + RxJS | Satisfies “Angular 16+”; no NgRx/Material to keep the code explainable |
| Styling | Hand-written CSS design system | No UI framework dependency; consistent look |
| Containers | Multi-stage Dockerfiles + Compose | One command for the full stack |

## 3. Order lifecycle

```text
NEW ──submit──▶ SUBMITTED ──approve──▶ APPROVED (terminal)
 │                  │
 │                  ├──reject──▶ REJECTED (terminal)
 │                  │
 └──────cancel──────┴─────────▶ CANCELLED (terminal)
```

The rule lives in one place: `OrderStatus.canTransitionTo`. The entity methods
`submit/approve/reject/cancel` call `transitionTo`, which throws
`InvalidOrderStateException` (HTTP 409) for anything not in the table. A direct
`PATCH .../approve` on a `NEW` order therefore fails even if the UI is bypassed.

Creation deliberately produces `NEW`. The Angular form then calls
`PATCH /api/orders/{id}/submit` in the same RxJS chain. Keeping these as two API
calls makes the lifecycle explicit and testable; the UI still feels like a
single “create and submit” action.

## 4. Request flows

### 4.1 Login

1. Angular posts `{username, password}` to `/api/auth/login` (relative URL).
2. The dev-server proxy or nginx forwards `/api` to the gateway, which routes
   `/api/auth/**` to `ORDER-SERVICE`.
3. `AuthService` loads the user by username, compares the password with the
   BCrypt hash, and asks `JwtService` for a token.
4. The token contains `sub` (username), `uid`, `role`, `iat`, `exp`, signed with
   HS256 using the shared `JWT_SECRET`.
5. Angular stores the token and user in `localStorage` through
   `TokenStorageService`, then navigates to `/dashboard` (admin) or `/orders`.
6. `authInterceptor` adds `Authorization: Bearer <token>` to later API calls.
   `errorInterceptor` clears the session and redirects on 401.

The gateway does not validate JWTs; each service validates the signature and
expiry locally. This keeps services independently deployable and avoids a
single security bottleneck.

### 4.2 Order list

1. `OrderListComponent` holds the full query in one `BehaviorSubject`
   (`search`, `status`, `instrumentId`, `sort`, `page`, `size`).
2. Search input is debounced and distinct; the other controls patch the same
   subject and reset to page 0.
3. `switchMap` cancels any in-flight request when the query changes.
4. The gateway routes `/api/orders` to `ORDER-SERVICE`.
5. `OrderService.findAll` builds a JPA `Specification`:
   instrument, status, case-insensitive search over order number / symbol /
   name, and — for non-admins — `createdBy = current username`.
6. Sort fields are allow-listed (`createdAt`, `updatedAt`, `orderNumber`,
   `price`, `quantity`, `status`); page size is clamped to 1–100.
7. `PageResponse` returns content plus page metadata, which drives the
   paginator.

The ownership filter is part of the SQL query, not a post-filter in Angular.

### 4.3 Create order + Feign + Kafka

```text
Angular             Gateway            order-service              instrument-service
   │ POST /api/orders  │                    │                            │
   ├──────────────────▶├───────────────────▶│ validate DTO               │
   │                   │                    │ Feign GET /instruments/id  │
   │                   │                    ├───────────────────────────▶│
   │                   │                    │◀── 200 / 404 ──────────────┤
   │                   │                    │ reject inactive (422)       │
   │                   │                    │ order.status = NEW          │
   │                   │                    │ INSERT PostgreSQL           │
   │                   │                    │ Kafka ORDER_CREATED         │
   │                   │◀── 201 Order ──────┤                            │
   │◀──────────────────┤                    │                            │
   │ PATCH /submit     │                    │                            │
   ├──────────────────▶├───────────────────▶│ NEW → SUBMITTED            │
```

The Feign client propagates the caller's bearer token through a
`RequestInterceptor`, so the instrument-service applies the same authentication
and role rules as a direct call. Timeouts are 2 s connect / 3 s read.

Failure mapping in `OrderService.loadInstrument`:

| Upstream result | Order-service response |
| --- | --- |
| 404 | 404 `Instrument not found` |
| 401/403 | 503 `Instrument service rejected the request` |
| connection/timeout/other | 503 `Instrument service is unavailable` |
| 200 but `active=false` | 422 `Instrument is inactive` |

The instrument snapshots (`symbol`, `name`) are copied onto the order so old
orders remain readable if the catalogue changes later.

### 4.4 Kafka consumer

`order-service` publishes to `order-events` with the instrument id as the
message key, so events for one instrument keep their order within a partition.
`instrument-service` consumes with `group-id=instrument-service` and:

1. ignores anything that is not `ORDER_CREATED`;
2. atomically increments `activityCount` and sets `lastOrderAt` with a Mongo
   `$inc`/`$set` (`MongoTemplate.updateFirst`);
3. evicts the Redis entry for that instrument;
4. logs and skips unknown instruments without poisoning the consumer.

The consumer's `try/catch` is deliberate: a bad message must not block the
partition. Production would add retry topics and a dead-letter topic.

### 4.5 Redis cache-aside

`instrument-service` only caches single-instrument reads:

```text
GET /api/instruments/GOLD-001
        │
        ▼
  Redis GET instrument:GOLD-001 ── hit ──▶ return cached JSON
        │ miss
        ▼
  MongoDB findById ──▶ build DTO ──▶ Redis SET with TTL (default 300 s)
```

Writes and status changes evict the key. The Kafka consumer evicts it after
updating activity. Redis failures are logged and swallowed: the cache is an
optimisation, never the source of truth. Redis is intentionally not a
distributed lock or session store here, because the JWT is stateless.

### 4.6 Dashboard

`GET /api/orders/dashboard` is ADMIN-only (`@PreAuthorize`). Counts come from a
JPQL `GROUP BY status` query plus `findTop5ByOrderByCreatedAtDesc`. The Angular
dashboard only renders values returned by the API; it never derives totals from
a loaded page of orders.

`pendingOrders` means `SUBMITTED`; `newOrders` is reported separately.

### 4.7 Documents

1. Angular sends `multipart/form-data` to
   `POST /api/orders/{id}/documents`.
2. `DocumentService` verifies ownership/admin, size (≤ 5 MB) and MIME type
   (PDF, PNG, JPEG, DOCX, XLSX).
3. The file is stored through `DocumentStorage`:
   - `local` (default): `./uploads/orders/{orderId}/{uuid}-{name}`;
   - `s3`: `PutObject` with the default AWS credential chain. No credentials
     are committed.
4. Metadata (name, content type, bytes, uploader, storage key) is stored in
   PostgreSQL.
5. Downloads go through `GET /api/orders/{orderId}/documents/{documentId}/download`,
   which checks access and streams the bytes with `Content-Disposition`.
   There are no public bucket URLs in the demo.

The browser normalises an empty MIME type from the file extension before upload,
because some browsers report DOCX/XLSX as an empty `type`.

## 5. Data model

### PostgreSQL (`orderdb`)

```text
users                          orders
-----                          ------
id BIGSERIAL PK                id BIGSERIAL PK
username UNIQUE                order_number UNIQUE (ORD-yyyyMMdd-####)
password (BCrypt)              instrument_id / symbol / name (snapshots)
role (ADMIN|OPERATIONS)        order_type (BUY|SELL)
enabled                        quantity NUMERIC(19,4)
created_at                     price NUMERIC(19,4)
                               status VARCHAR(20)
                               remarks / rejection_reason
                               created_by
                               created_at / updated_at
                               version (optimistic lock)

order_documents
---------------
id, order_id FK, file_name, content_type, size_bytes,
storage_key, uploaded_by, uploaded_at
```

Indexes cover order number, status, instrument, creator and creation time. The
`@Version` column prevents lost updates when two approvers act concurrently.

### MongoDB (`instrumentdb.instruments`)

```json
{
  "_id": "GOLD-001",
  "symbol": "GOLD",
  "name": "Gold",
  "category": "PRECIOUS_METAL",
  "indicativePrice": 15000,
  "active": true,
  "activityCount": 3,
  "lastOrderAt": "2026-01-01T10:00:00Z",
  "createdAt": "...",
  "updatedAt": "..."
}
```

The business id is the Mongo `_id`, which makes Feign lookups direct and keeps
the Redis key deterministic (`instrument:GOLD-001`).

### Redis

| Key | Value | TTL | Invalidation |
| --- | --- | --- | --- |
| `instrument:{id}` | JSON `InstrumentResponse` | 300 s (configurable) | update, status change, Kafka activity event |

## 6. Security model

- **Stateless**: no HTTP session; `SessionCreationPolicy.STATELESS`.
- **Passwords**: BCrypt; demo users seeded only when missing, with env-overridable
  passwords.
- **JWT**: HS256, shared secret of at least 32 bytes, 120-minute default.
- **Roles**: `ADMIN` and `OPERATIONS`.
  - instrument create/update/status: ADMIN;
  - order approve/reject: ADMIN;
  - dashboard: ADMIN;
  - order submit/cancel/view: owner or ADMIN;
  - documents: owner or ADMIN.
- **Defence in depth**: the Angular guards and `*ngIf` checks improve UX, but the
  service layer repeats every rule. A forged request to `/approve` as an
  operator returns 403/409 regardless of the UI.
- **Consistent errors**: `RestAuthenticationEntryPoint` returns JSON 401,
  `RestAccessDeniedHandler` returns JSON 403, and `GlobalExceptionHandler`
  returns `ApiError` for validation, not-found, conflict, 422 and 503 cases.
- **Token storage**: `localStorage` is a demo trade-off (XSS exposure). A
  production design would use an httpOnly refresh cookie plus a short-lived
  in-memory access token.

## 7. Consistency and failure trade-offs

| Scenario | Current behaviour | Production direction |
| --- | --- | --- |
| Kafka down during create | Order is saved; publish failure is logged and the request still succeeds | Transactional outbox + relay |
| Kafka publish succeeds, DB commit later fails | Event may reference an order that rolled back | Publish only after commit via outbox |
| Redis down | Reads fall back to MongoDB | Alert on cache failures; circuit breaker |
| Instrument-service down | Create returns 503; no unvalidated order is stored | Same, plus retry budget |
| Concurrent approvals | `@Version` optimistic lock; second update conflicts | Surface 409 and refresh |
| Poison Kafka message | Logged and skipped | Retry + DLQ |
| Duplicate consumer delivery | `$inc` is not idempotent; counter could double | Store processed event ids / idempotency key |

These points are called out in `docs/known-issues.md`; they are intentional
choices for the time-boxed challenge, not hidden claims.

## 8. Frontend architecture

```text
src/app
├── core/          models, services, interceptors, guards, token storage
├── shared/        page header, modal, badges, empty state, toast, loading bar
├── layouts/       sidebar + topbar shell
└── features/
    ├── auth/login
    ├── dashboard
    ├── instruments/instrument-list
    └── orders/
        ├── order-list      filters, pagination, role actions, reject modal
        ├── order-form      reactive form, price prefill, create→submit chain
        └── order-detail    summary, timeline, documents
```

- Every feature route is lazy-loaded with `loadComponent`.
- Functional interceptors add the JWT, show the loading bar, and centralise
  401/403/0/5xx handling.
- `authGuard`, `adminGuard` and `guestGuard` protect routes.
- State is local to components with RxJS; `BehaviorSubject` holds the server
  query in the order list. There is no global store, which is appropriate for
  this size and keeps the data flow inspectable.
- The order list uses `combine`-style patching through one subject and
  `switchMap`, so fast filter changes never race.
- Templates use the shared design system (`styles.css`) and the
  `statusLabel` pipe for enum labels.

## 9. Deployment views

### Local development

```text
ng serve :4200 ──proxy /api──▶ gateway :8080 ──Eureka──▶ services
PostgreSQL :5432, MongoDB :27017, Redis :6379, Kafka :9092 on the host
```

### Docker Compose

```text
frontend nginx :4200 ──/api──▶ api-gateway :8080 ──▶ services
postgres, mongo, redis, kafka (KRaft), eureka, order, instrument, gateway
```

Containers use service DNS names; the browser still only calls
`http://localhost:4200`. Kafka exposes an internal listener on `kafka:29092`
and an external listener on `localhost:9092` for a host-run backend.

### AWS sketch

- RDS for PostgreSQL, DocumentDB or MongoDB Atlas for the catalogue.
- ElastiCache for Redis, MSK for Kafka.
- ECS Fargate or EKS for the four Spring images; ALB in front of the gateway.
- S3 private bucket with an IAM task role for documents.
- Secrets Manager/SSM for `JWT_SECRET`, DB credentials and S3 settings.
- CloudWatch for logs/metrics; Actuator health for target-group checks.

The application is stateless, which is what allows horizontal scaling behind a
load balancer.
