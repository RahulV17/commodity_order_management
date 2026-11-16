# Learning guide

**How this application was built, feature by feature — and how to explain it
under questioning.**

This guide is written for the person who has to stand in front of an evaluator,
open the code and answer "why did you do that?" without notes. It is not an
API reference (that is `docs/api-documentation.md`) and not a marketing
overview. It records the decisions, the code paths, the mistakes found while
building, and the words to use when explaining them.

Read it in this order if you have limited time:

1. **Section 1** — the brief mapped to the implementation (10 minutes).
2. **Section 2** — the build log, including the mistakes (10 minutes).
3. **Section 5** — the feature walkthrough, frontend and backend flow by flow
   (30 minutes).
4. **Section 6** — the 15 live evaluation questions with spoken-style answers
   (45 minutes). Rehearse the short answers aloud.
5. **Section 7** — live modification drills (30 minutes). Do at least two in
   the IDE, not just on paper.
6. **Section 8** — troubleshooting playbook (10 minutes).

Everything else is depth you can dip into when a question goes further.

> **Honesty rule used throughout:** a feature is described as working only if
> it was executed. The S3 path and a few operational gaps are explicitly marked
> as unverified or accepted trade-offs. Do not upgrade those claims during the
> evaluation.

---

## 1. The brief in one page

### 1.1 What the assignment asked for

| Requirement | Where it is implemented | How to prove it |
| --- | --- | --- |
| Angular 16+ frontend | `frontend/` — Angular 20 standalone | `npm run build`; UI walkthrough |
| Two Spring Boot microservices | `order-service`, `instrument-service` | Swagger UIs on 8081/8082 |
| PostgreSQL for Order Service | JPA entities, `orderdb` | `psql` query; `docker compose ps` |
| MongoDB for Instrument Service | `Instrument` document, `instrumentdb` | `mongosh` query |
| Spring Cloud Gateway | `api-gateway` with explicit routes | `GET :8080/api/...`; gateway actuator |
| Eureka discovery | `eureka-server`; all apps register | Eureka dashboard on 8761 |
| OpenFeign between services | `InstrumentClient` + JWT propagation | Create order while watching logs |
| JWT + BCrypt authentication | `JwtService`, `JwtAuthenticationFilter`, `AuthService` | Login, decode token, 401/403 checks |
| Demo users admin/operator | `UserDataInitializer` | Sign in with both |
| Kafka `order-events` | Producer in order, consumer in instrument | Console consumer; activity count |
| Redis instrument cache | `InstrumentCacheService`, cache-aside | `redis-cli` keys + TTL |
| Order lifecycle and valid transitions | `OrderStatus.canTransitionTo`, `Order` methods | Approve an approved order → 409 |
| Role-based authorization | `@PreAuthorize` + service ownership checks | Operator approve → 403 |
| Dashboard stats from backend | `DashboardService`, JPQL aggregate | Dashboard as admin |
| Pagination/filter/sort | JPA `Specification` + page/size/sort | Network tab + UI |
| JUnit/Mockito tests | 31 backend tests | `mvn -B test` |
| Jasmine/Karma tests | 22 frontend specs | `npm run test:ci` |
| Swagger/Actuator | springdoc + actuator in both services | `/swagger-ui.html`, `/actuator/health` |
| Dockerfiles + Compose | Shared backend Dockerfile, nginx frontend, Compose | `docker compose up -d` |
| AWS S3 integration | `S3DocumentStorage` behind `DocumentStorage` | Code + configuration only; **not exercised** |

### 1.2 How the marks map

The rubric is 100 points: backend 25, microservices/communication 15, Angular
15, databases 10, security 10, Kafka/Redis 10, testing/observability 10,
Docker/AWS/deployment 5.

The implementation deliberately spends its complexity where the marks are:

- **Backend (25):** a real domain model with an explicit state machine,
  validation, ownership rules, transactional services, global error handling
  and meaningful tests — not CRUD controllers with logic in them.
- **Microservices (15):** separate databases, discovery, gateway, Feign with
  timeout/failure mapping, Kafka. Each has a visible failure mode.
- **Angular (15):** routed, guarded, lazy-loaded feature modules; typed services;
  interceptors; reactive forms; a consistent design system; no dead screens.
- **Databases (10):** PostgreSQL schema with indexes and optimistic locking;
  MongoDB document model with `$inc`; Redis keys with TTL and eviction.
- **Security (10):** JWT issuance/validation, BCrypt, route-level roles,
  row-level ownership, verified negative cases.
- **Kafka/Redis (10):** a real produce/consume path and explicit cache-aside
  with failure fallback, plus honest consistency trade-offs.
- **Testing/observability (10):** unit and slice tests, actuator, request ids,
  structured logs, Swagger.
- **Docker/AWS (5):** full Compose stack; S3 adapter present but unverified.

### 1.3 What was deliberately not built

CI/CD, Kubernetes, Keycloak/OAuth2, NgRx, Prometheus/Grafana, S3 integration
testing, refresh tokens, a transactional outbox, an audit-history table. The
brief lists these as extensions. Adding them would have increased surface area
without increasing understanding, and several would have made the core flow
harder to explain. Their absence is documented in `docs/known-issues.md` with a
production fix for each.

---

## 2. Build log — the real chronology

This section is useful because evaluators often ask "what was hard?" A
sanitised answer ("nothing, it worked first time") is not credible. These are
the actual decision points and fixes.

### Phase 1 — Read the brief, choose versions

- Extracted the assignment to text and read the full 15 live questions before
  writing code. That changed the design: the questions define what must be
  *explainable*, not just runnable.
- Chose Spring Boot **3.4.2** with Spring Cloud **2024.0.1** and Java 17. The
  versions must come from the same compatibility train; mixing Boot 3.4 with an
  older Cloud release is a common startup failure.
- Chose **Angular 20** rather than 16. The brief says "Angular 16 or above";
  Angular 20 installs cleanly on the available Node 24 toolchain and uses
  standalone APIs throughout. This is a deliberate, defensible deviation — be
  ready to say why.
- Chose **JJWT 0.12.5** (the current API uses `Jwts.parser().verifyWith(...)`,
  not the deprecated `setSigningKey`).
- Chose **KRaft Kafka** (no ZooKeeper) to reduce Compose complexity.
- Created the Maven multi-module layout (`backend/pom.xml` with four modules).
  A shared parent makes versions consistent and lets `mvn -pl <module> -am`
  build one service with its dependencies.

### Phase 2 — Backend skeleton, then business logic

Order of implementation, deliberately:

1. Eureka and gateway first, so routing problems surface early.
2. Instrument Service next: it has no dependency on orders, so it can be tested
   standalone.
3. Order Service last: it needs the instrument API for Feign and is the most
   complex service.

Notable decisions made during this phase:

- **Order creation returns `NEW`; a separate `PATCH /submit` moves it to
  `SUBMITTED`.** The acceptance scenario says an admin approves an order the
  operator just created. If creation jumped straight to `SUBMITTED`, the
  lifecycle would be less explicit and the evaluator could not see the submit
  transition. The Angular form chains create then submit with `switchMap`, so
  the user experience is still one click.
- **Redis cache-aside is explicit**, not `@Cacheable`. The brief asks to
  demonstrate TTL and eviction; hiding it behind an annotation makes it harder
  to point at. It also lets Redis failures degrade gracefully.
- **Kafka publish is best-effort** after the database save. This keeps order
  creation working during a broker outage. The cost is the ghost-event risk;
  it is documented rather than hand-waved.
- **Documents use a `DocumentStorage` interface** with local and S3
  implementations selected by `app.storage.type`. This is the S3 requirement
  without forcing AWS credentials into the demo.

### Phase 3 — Test the backend while it is small

`mvn -B test` was run repeatedly. The final suite is 31 tests. The valuable
ones are not "controller returns 200"; they pin down rules:

- `OrderStatusTest` — every allowed and forbidden transition, including the
  rejection-reason rule.
- `OrderControllerSecurityTest` — 401 without a token, 403 for operator approve,
  200 for admin approve, 400 for an invalid payload. Uses `@WebMvcTest` with
  mocked services and security filters.
- `OrderServiceTest` — Feign 404 → `ResourceNotFoundException`, Feign 5xx →
  `InstrumentUnavailableException`, ownership checks, event publication.
- `InstrumentServiceTest` — cache hit avoids Mongo, cache miss populates it,
  status change evicts, `$inc` is used for the counter.
- `InstrumentCacheServiceTest` — JSON + TTL, hit, evict, Redis exception
  treated as a miss.

### Phase 4 — Frontend

Built core first (models, token storage, auth service, interceptors, guards),
then the shell, then features in order of dependency: login → instruments →
orders list → create → detail → dashboard.

Decisions worth defending:

- **Relative `/api` base URL plus a dev-server proxy.** The browser only talks
  to `localhost:4200`; the proxy forwards to the gateway. In Docker, nginx does
  the same. This avoids CORS in the normal flow and means no environment switch
  between local and container runs.
- **No Angular Material or NgRx.** A small hand-written CSS system and
  `BehaviorSubject` state keep every line explainable. The brief values
  understanding over library familiarity.
- **Functional interceptors and guards** (Angular's modern API): `auth`,
  `error`, `loading`, plus `authGuard`, `adminGuard`, `guestGuard`.
- **One query state object** in the order list. Search is debounced, every
  filter resets the page to zero, and one method builds the HTTP params. This
  prevents the classic bug where changing a filter keeps page 4 and shows an
  empty table.

### Phase 5 — The frontend test failure (a useful debugging story)

The first full `npm run test:ci` runs passed individual assertions but ended
with:

```text
An error was thrown in afterAll
[object Object] thrown
Chrome Headless ... DISCONNECTED
```

Isolating specs with `ng test --include=...` showed all four order-form tests
passing, then failing in `afterAll`. The component's error path did this:

```ts
catchError((error) => {
  this.handleSaveError(error);      // sets a user-facing message
  return throwError(() => error);   // rethrows into a subscription with no error handler
})
```

The subscription only had a `next` handler. RxJS 7 reports an unhandled error
asynchronously via a timer, which surfaces after the spec as an `afterAll`
failure and confuses Karma's teardown. The fix was to complete the stream after
handling the error (`return EMPTY`) and to mock `NotificationService` in the
spec so toast timers do not outlive the test. The lesson: an error that is
already presented to the user should not be rethrown into a handler that does
not exist.

### Phase 6 — Containers and documentation

- One backend Dockerfile with `ARG SERVICE` builds any module; Compose passes a
  different argument per image. This avoids four near-identical Dockerfiles.
- The frontend Dockerfile builds with Node and serves static files with nginx;
  `nginx.conf` provides SPA fallback and proxies `/api` to the gateway.
- Compose health checks gate startup: Eureka → infrastructure → instrument →
  order → gateway → frontend. `depends_on` alone waits for *start*, not
  *readiness*, so health conditions are used.
- The first parallel `docker compose build` exhausted the Docker daemon
  (`failed to receive status: ... EOF`) while five Maven builds downloaded
  dependencies. Building sequentially with `COMPOSE_PARALLEL_LIMIT=1` is the
  documented workaround.
- Redis is not published to the host because a local Redis already occupies
  6379. Containerised services use `redis:6379`; host-run services need a local
  Redis or a temporary port mapping.

### Phase 7 — Documentation as a deliverable

Architecture, API reference, this guide, the demo script and known issues were
written after the code stabilised, by reading the code again. That pass found
small inconsistencies (for example, assuming six seeded instruments when there
are four). Writing documentation from the source, not from memory, is part of
the quality process.

---

## 3. Architecture: the mental model

### 3.1 The picture

```text
Browser (Angular)
  │  same-origin /api/**  +  Authorization: Bearer <JWT>
  ▼
nginx (Docker) or Angular dev-server proxy
  │  http://api-gateway:8080  /  http://localhost:8080
  ▼
Spring Cloud Gateway
  │  route match → lb://ORDER-SERVICE or lb://INSTRUMENT-SERVICE
  │  (service list from Eureka, instance chosen by Spring Cloud LoadBalancer)
  ├──────────────▶ order-service :8081
  │                  ├── PostgreSQL (orders, users, document metadata)
  │                  ├── Feign ──▶ instrument-service (validate instrument)
  │                  └── Kafka producer ──▶ order-events
  └──────────────▶ instrument-service :8082
                     ├── MongoDB (catalogue)
                     ├── Redis (instrument:{id}, TTL 300 s)
                     └── Kafka consumer ◀── order-events
```

Eureka Server (:8761) is a registry, not a proxy. It stores service name →
instance address mappings. No business request flows through it.

### 3.2 Why each component exists

| Component | Problem it solves | What would break without it |
| --- | --- | --- |
| Gateway | One browser-facing entry point, routing, CORS, request ids | The SPA would need two URLs and CORS config; services would be exposed directly |
| Eureka | Dynamic instance locations | Every service URL would be hard-coded; scaling would require config edits |
| Feign | Declarative synchronous call | Manual `RestTemplate`/`WebClient` boilerplate, duplicated error handling |
| Kafka | Asynchronous side effects | Instrument activity updates would require a synchronous call in the order path, increasing coupling and latency |
| Redis | Low-latency repeated reads | Every order creation would hit MongoDB for the same instrument |
| Separate DBs | Independent models and failure isolation | A shared schema would couple deployments and mix relational and document needs |
| JWT | Stateless authentication across services | Sessions would need sticky routing or a shared session store |

### 3.3 The request path, hop by hop

Take `GET /api/orders?status=SUBMITTED&page=0&size=10&sort=createdAt,desc`.

1. **Angular service** builds `HttpParams` and calls `${apiBaseUrl}/orders`.
   `apiBaseUrl` is `/api`, so the browser requests
   `http://localhost:4200/api/orders?...`.
2. **Interceptors** run in registration order: `loadingInterceptor` starts the
   progress bar, `authInterceptor` clones the request with
   `Authorization: Bearer …`, `errorInterceptor` installs a `catchError`.
3. **Dev proxy or nginx** forwards `/api/**` to `http://localhost:8080` (or
   `http://api-gateway:8080` in Compose). The browser origin does not change.
4. **Gateway** matches `Path=/api/orders/**`, rewrites nothing, resolves
   `lb://ORDER-SERVICE` through Eureka and forwards the request, preserving
   headers. Its `RequestIdGlobalFilter` ensures `X-Request-Id` exists.
5. **Order Service's `RequestIdFilter`** puts the id into SLF4J MDC. The
   `JwtAuthenticationFilter` parses the token, extracts `sub` and `role`,
   creates a `UsernamePasswordAuthenticationToken` with `ROLE_ADMIN` or
   `ROLE_OPERATIONS`, and stores it in the `SecurityContext`.
6. **`SecurityConfig`** allows the request because the path is not public and
   the context is authenticated. Method security checks `@PreAuthorize` only on
   dashboard/approve/reject.
7. **`OrderController.list`** binds query parameters and injects
   `Authentication`.
8. **`OrderService.findAll`** reads the role. For a non-admin it adds
   `created_by = :username` to the specification. It clamps page/size, validates
   the sort field against an allow-list, and calls the repository.
9. **Spring Data JPA** turns the `Specification` into a `where` clause, applies
   `order by`, `limit` and `offset`, and executes against PostgreSQL.
10. **`PageResponse.from`** maps entities to DTOs and adds `page`, `size`,
    `totalElements`, `totalPages`, `first`, `last`.
11. **The response** travels back through the gateway (which adds CORS headers)
    and the proxy to Angular. `errorInterceptor` sees no error;
    `loadingInterceptor` stops the bar; the component assigns `orders` and
    `pageInfo`.

The same path applies to writes, with `@Transactional` around the service
method and validation before the repository call.

### 3.4 Synchronous vs asynchronous communication

The two integration styles are not interchangeable; the choice is a design
statement.

| | Feign (sync) | Kafka (async) |
| --- | --- | --- |
| Used for | Validating the instrument before creating an order | Updating instrument activity after an order exists |
| Correctness requirement | Must succeed before the order is stored | May lag; the order is still valid |
| Failure behaviour | 503, order not created | Logged; counter eventually updates |
| Coupling | Temporal coupling: both services must be up | No temporal coupling; broker buffers |
| Latency | Added to the request | None added to the request |
| Data consistency | Strong within the request | Eventual |

The rule of thumb: **if the answer changes whether the write is allowed, it is
synchronous. If it is a side effect, it is asynchronous.**

### 3.5 Why two databases?

- **Shape:** orders are highly relational (user, status transitions,
  constraints, pagination with filters, aggregate counts). PostgreSQL with JPA
  fits. Instruments are catalogue documents with optional/variable attributes;
  MongoDB fits without migrations.
- **Ownership:** each service owns its schema. Order Service never queries
  MongoDB; Instrument Service never queries PostgreSQL. This is the
  microservice boundary, not just a deployment detail.
- **Failure isolation:** a Mongo outage stops instrument reads and therefore
  order creation (via Feign), but existing orders remain readable. A Postgres
  outage stops orders but not catalogue browsing.
- **Independent evolution:** the instrument document can gain fields without a
  relational migration; the order schema can add constraints without touching
  the catalogue.

The cost is **no cross-database joins and no distributed transaction**. The
system accepts eventual consistency for the activity counter, and validates
cross-service references at write time rather than enforcing a foreign key.

---

## 4. Repository tour

### 4.1 Backend modules

```text
backend/
├── pom.xml                     # parent: Boot 3.4.2, Cloud 2024.0.1, Java 17, JJWT, springdoc, AWS SDK
├── eureka-server/
│   └── EurekaServerApplication + application.yml (standalone registry)
├── api-gateway/
│   ├── ApiGatewayApplication
│   ├── filter/RequestIdGlobalFilter.java
│   └── application.yml (routes, CORS, actuator)
├── instrument-service/
│   ├── document/Instrument.java, InstrumentCategory.java
│   ├── repository/InstrumentRepository.java
│   ├── service/InstrumentService.java
│   ├── cache/InstrumentCacheService.java
│   ├── kafka/OrderEventConsumer.java
│   ├── security/ (JwtService, filter, config, entry point, denied handler)
│   ├── controller/InstrumentController.java
│   ├── config/ (seed data, OpenAPI)
│   ├── exception/ (not found, duplicate, global handler)
│   └── util/RequestIdFilter.java
└── order-service/
    ├── entity/ (Order, OrderStatus, OrderType, User, Role, OrderDocument)
    ├── repository/ (OrderRepository, UserRepository, OrderDocumentRepository)
    ├── service/ (OrderService, AuthService, DashboardService, DocumentService)
    ├── controller/ (OrderController, AuthController, DocumentController)
    ├── security/ (JwtService, filter, config, entry point, denied handler)
    ├── client/ (InstrumentClient, InstrumentDto, FeignConfig)
    ├── kafka/ (OrderCreatedEvent, OrderEventProducer, KafkaTopicConfig)
    ├── storage/ (DocumentStorage, LocalDocumentStorage, S3DocumentStorage, properties)
    ├── mapper/OrderMapper.java
    ├── config/ (UserDataInitializer, StorageConfig, OpenAPI)
    ├── exception/ (domain exceptions + GlobalExceptionHandler)
    └── util/ (OrderNumberGenerator, RequestIdFilter)
```

### 4.2 Frontend structure

```text
frontend/src/app/
├── app.ts / app.html / app.css          # root shell with RouterOutlet
├── app.config.ts                        # providers: router, HttpClient + interceptors
├── app.routes.ts                        # lazy routes, guards, titles
├── core/
│   ├── models/                          # auth, order, instrument, dashboard, api-error
│   ├── services/                        # auth, token storage, order, instrument,
│   │                                    # dashboard, notification, loading
│   ├── interceptors/                    # auth, error, loading
│   └── guards/                          # auth, admin, guest
├── shared/
│   ├── components/                      # page header, status badge, modal, toast,
│   │                                    # loading bar, empty state
│   └── pipes/status-label.pipe.ts
├── layouts/main-layout/                 # sidebar/topbar for authenticated pages
└── features/
    ├── auth/login/
    ├── dashboard/dashboard/
    ├── instruments/instrument-list/
    └── orders/{order-list, order-form, order-detail}/
```

### 4.3 Where to look for each concern

| Concern | File |
| --- | --- |
| Lifecycle rules | `backend/order-service/.../entity/OrderStatus.java`, `Order.java` |
| Ownership and filtering | `.../service/OrderService.java` |
| Role rules at the edge of the API | `.../controller/OrderController.java` |
| JWT creation | `.../security/JwtService.java` |
| JWT validation | `.../security/JwtAuthenticationFilter.java` |
| Feign + header propagation | `.../client/InstrumentClient.java`, `FeignConfig.java` |
| Feign failure mapping | `OrderService.loadInstrument` |
| Kafka produce | `.../kafka/OrderEventProducer.java` |
| Kafka consume | `instrument-service/.../kafka/OrderEventConsumer.java` |
| Cache-aside | `instrument-service/.../cache/InstrumentCacheService.java` |
| Cache use and eviction | `instrument-service/.../service/InstrumentService.java` |
| Global errors | both services' `GlobalExceptionHandler.java` |
| Gateway routes/CORS | `backend/api-gateway/src/main/resources/application.yml` |
| Compose topology | `docker-compose.yml` |
| Frontend auth state | `core/services/token-storage.service.ts` |
| Frontend order queries | `core/services/order.service.ts`, `order-list.component.ts` |
| Create→submit chain | `features/orders/order-form/order-form.component.ts` |
| Lifecycle UI | `features/orders/order-detail/order-detail.component.{ts,html}` |

---

## 5. Feature walkthrough

Each feature is described from three angles: what the user sees, what the
frontend does, and what the backend and data stores do. The final line of each
feature is a question an evaluator is likely to ask.

### 5.1 Sign in and session handling

**What the user sees.** A branded split-screen login page. Validation appears
after the first submit attempt. Wrong credentials show "Invalid username or
password." A successful login redirects to `/orders` (or to the `returnUrl`
saved by `authGuard`). The shell shows the username and role, and a sign-out
action.

**Frontend flow.**

1. `LoginComponent` uses a non-nullable `FormGroup` with `required` and
   `minLength(3)` on the username. It does not send an invalid form.
2. `AuthService.login` posts to `/api/auth/login`, then `tap`s the response into
   `TokenStorageService.setSession`.
3. `setSession` writes `com.accessToken` and `com.currentUser` to
   `localStorage` and pushes the user into a `BehaviorSubject`. The shell and
   guards observe `currentUser$` / read `currentUser`.
4. The component navigates to `returnUrl` or `/orders`.
5. On every later request, `authInterceptor` reads `tokenStorage.token`
   (which also checks expiry) and clones the request with the bearer header.

**Backend flow.**

1. Gateway routes `/api/auth/**` to `ORDER-SERVICE`.
2. `AuthController.login` validates the request body and calls `AuthService`.
3. `AuthService` loads the user by username, rejects missing/disabled users and
   uses `BCryptPasswordEncoder.matches` against the stored hash. It deliberately
   returns the same generic error for unknown user and wrong password.
4. `JwtService.generateToken` builds an HS256 token with:
   - `sub` = username
   - `uid` = database id
   - `role` = `ADMIN` or `OPERATIONS`
   - `iat`, `exp` (120 minutes by default)
5. The response contains the token, type, expiry seconds and a safe user DTO
   (never the password hash).
6. On subsequent requests, `JwtAuthenticationFilter` parses the token and
   creates an `Authentication` with authority `ROLE_<role>`. No database lookup
   is needed per request.

**Why this design.** Stateless authentication lets any service instance validate
any request without sticky sessions or a shared session store. BCrypt is
adaptive and salted per password. The token carries the role so both services
can authorize consistently.

**Verify.** Login, inspect the response, paste the token into jwt.io (or decode
the payload) and confirm `sub`, `role`, `exp`. Stop the order service and note
that login fails at the gateway — authentication is owned by Order Service.

**Likely question.** "Why not store the token in a cookie?" → XSS vs CSRF
trade-off; see `known-issues.md` item 5 and section 7.5 of this guide.

### 5.2 Application shell, routing and guards

**What the user sees.** A sidebar with Orders, Instruments and (admin only)
Dashboard; a top bar with page context, user identity and sign out. Directly
opening a protected URL while signed out sends the user to login.

**Frontend flow.**

- `app.routes.ts` lazy-loads every feature with `loadComponent`. The initial
  bundle contains only the shell and shared code; feature chunks load on demand.
- `MainLayoutComponent` is the authenticated parent route; its children render
  in a `router-outlet`.
- `authGuard` checks `AuthService.isAuthenticated()` (token exists, is not
  expired, and a user is stored) and preserves the attempted URL as
  `returnUrl`.
- `guestGuard` keeps a signed-in user away from `/login`.
- `adminGuard` checks the `ADMIN` role for `/dashboard`.
- Route definitions set document titles, and `withInMemoryScrolling` restores
  the top on navigation.

**Backend flow.** None for navigation. The guards are UX only; every protected
API call is independently authorized server-side.

**Why this design.** Lazy loading keeps the first paint small (the production
build reports a 340.41 kB initial bundle, 97.03 kB transfer). Guards prevent
pointless API calls and confusing empty screens. The three-guard split keeps
each rule single-purpose.

**Verify.** Sign in as operator: Dashboard is absent from navigation. Enter
`/dashboard` manually: you are redirected. Enter `/orders` signed out: login
with `returnUrl`.

**Likely question.** "Can a user bypass a guard?" → Yes, by calling the API
directly; that is why authorization is duplicated in the backend. A guard is not
a security control.

### 5.3 Instrument catalogue

**What the user sees.** A table/cards of instruments with symbol, name,
category, indicative price, active status and activity count. Search filters by
name or symbol. Inactive instruments are visually distinguishable. The list is
read-only for operations users; the API supports admin create/update/status.

**Frontend flow.** `InstrumentListComponent` calls
`InstrumentService.list(search, active?)`. Search is a simple form control with
a debounce; the API returns an array (the catalogue is small, so it is not
paginated). The component maps category and active state through shared pipes
and badges.

**Backend flow.**

1. `GET /api/instruments` → `InstrumentController.list`.
2. `InstrumentService.findAll`:
   - if `search` has text, uses
     `findByNameContainingIgnoreCaseOrSymbolContainingIgnoreCase`, sorted by
     symbol;
   - otherwise `findAll(Sort.by("symbol"))`;
   - applies the optional `active` filter in memory.
3. MongoDB returns `Instrument` documents; `InstrumentResponse.from` maps them
   to DTOs.

**Why this design.** The catalogue is small and read-heavy, so a simple list
endpoint is appropriate. MongoDB stores the document as-is, including optional
fields. Sorting by symbol gives a stable, human-friendly order.

**Verify.** Search `gold`; confirm one row. Deactivate an instrument via
Swagger and confirm it disappears from the order form's active list.

**Likely question.** "Why is `active` filtered in Java rather than Mongo?" →
For a tiny catalogue it keeps the repository method count down. At scale, push
it into the query and add a compound index; note this as a deliberate
simplification, not a belief that in-memory filtering scales.

### 5.4 Create and submit an order

**What the user sees.** A form with instrument select (active only), BUY/SELL
toggle, quantity, price, remarks and a live estimated value. Selecting an
instrument prefills the indicative price. Client validation blocks non-positive
values and more than four decimals. One button reads "Create & submit for
approval."

**Frontend flow.**

1. `ngOnInit` loads active instruments and subscribes to `instrumentId`
   changes. On selection it sets `price` to `indicativePrice` (still editable).
2. If the URL has `?instrumentId=...`, that instrument is preselected after the
   list arrives; otherwise the first is selected.
3. `save()` marks the form submitted, stops on invalid input, and builds a
   `CreateOrderRequest` with trimmed remarks (`null` when blank).
4. `OrderService.create` → `POST /api/orders`.
5. On success, `switchMap` calls `OrderService.submit(created.id)`. The created
   order is stored in `createdOrder` before the second call, so a submit failure
   can point the user at the existing order instead of inviting a duplicate.
6. On full success, a toast appears and the router navigates to
   `/orders/{id}`. On failure, `handleSaveError` distinguishes "created but
   submit failed", connectivity failure, and a server message.

The chain is visible in code:

```ts
this.orderService.create(request).pipe(
  switchMap((created) => {
    this.createdOrder = created;               // recoverable on failure
    return this.orderService.submit(created.id).pipe(
      map((submitted) => ({ created, submitted }))
    );
  }),
  catchError((error) => {
    this.handleSaveError(error);
    return EMPTY;                              // handled, do not rethrow
  }),
  finalize(() => (this.saving = false))
)
```

**Backend flow.**

1. `POST /api/orders` → `OrderController.create` with `@Valid`.
2. `CreateOrderRequest` enforces required instrument id, order type, positive
   quantity/price with at most 15 integer and 4 fraction digits, and remarks
   ≤ 500 characters.
3. `OrderService.create` calls `loadInstrument(instrumentId)`:
   - Feign `GET /api/instruments/{id}` on `INSTRUMENT-SERVICE`, resolved through
     Eureka;
   - `FeignConfig` copies the caller's `Authorization` header;
   - 404 → `ResourceNotFoundException` (404);
   - 401/403 → `InstrumentUnavailableException` with "sign in again";
   - any other failure/timeout → `InstrumentUnavailableException` (503).
4. If the instrument is inactive → `InstrumentInactiveException` (422).
5. An `Order` is built with a generated number, a **snapshot** of instrument
   symbol/name, the authenticated username as `createdBy`, and status `NEW`.
6. `orderRepository.save` inserts the row; `@CreationTimestamp` fills
   `createdAt`, `@Version` starts at 0.
7. `eventProducer.publishOrderCreated` sends an `ORDER_CREATED` event keyed by
   instrument id. Publishing is best-effort: failures are logged, not rethrown.
8. The mapper returns `OrderResponse`. The controller replies `201`.

`PATCH /api/orders/{id}/submit` then loads the order, checks ownership
(owner or admin), calls `order.submit()`, which delegates to
`OrderStatus.canTransitionTo`. `NEW → SUBMITTED` is allowed; anything else is a
`409`.

**Why two calls instead of one.** The lifecycle is explicit in the API and the
database, and the evaluator can see the submit transition. The UI still offers
one action by chaining the calls. The alternative — creating directly as
`SUBMITTED` — would hide a state the rubric asks to demonstrate.

**Verify.** Network tab shows `POST` then `PATCH`; PostgreSQL row is `SUBMITTED`;
Kafka contains `ORDER_CREATED`; the detail page timeline shows Created and
Submitted.

**Likely question.** "Why store the symbol/name snapshot?" → The order must
remain readable even if the catalogue is renamed or the instrument is deleted.
The `instrumentId` remains the reference; the snapshot is denormalisation for
history, not a second source of truth.

### 5.5 Order list: filtering, sorting, pagination

**What the user sees.** A table with order number, instrument, side, quantity,
price, estimated value, status and role-appropriate actions. A toolbar has
search, status, instrument, sort and page-size controls. Pagination shows
"showing 1–10 of 57" and page buttons. A reset button restores defaults.

**Frontend flow.**

- One `BehaviorSubject<OrderQueryState>` holds `search`, `status`,
  `instrumentId`, `sort`, `page`, `size`.
- Each control patches the subject. Search is debounced 300 ms and
  `distinctUntilChanged`; all other controls are distinct-only. Every filter
  change sets `page: 0` — otherwise a filter applied while on page 4 would
  request a page that may not exist.
- `loadQuery()` pipes the subject through `switchMap` into `OrderService.list`.
  `switchMap` cancels the previous in-flight request, so a fast typist never
  sees an older response overwrite a newer one.
- Errors are caught locally to show an inline message; `finalize` clears the
  loading flag. `takeUntilDestroyed` prevents subscriptions outliving the
  component.
- `trackByOrderId` avoids re-rendering every row when one order changes.
- Row actions call the same endpoints as the detail page, then refresh the
  current query.

**Backend flow.**

`OrderService.findAll` builds a JPA `Specification`:

```java
if (instrumentId) equal(root.get("instrumentId"), instrumentId)
if (status)       equal(root.get("status"), status)
if (search)       lower(orderNumber) like %q% OR lower(symbol) like %q% OR lower(name) like %q%
if (!admin)       equal(root.get("createdBy"), authentication.getName())
```

It clamps `page` to ≥ 0 and `size` to 1–100, parses `sort` against the
allow-list `{createdAt, updatedAt, orderNumber, price, quantity, status}` and
defaults to `createdAt desc`. `PageResponse.from` exposes both the content and
the pagination metadata. The query count is one `count(*)` plus one page query.

**Why this design.** Filtering and pagination in the database avoid loading the
whole table. The allow-list prevents a client from sorting by an arbitrary
property (which would cause a 500 or leak schema details). Applying ownership
inside the same query — not after fetching — means an operator cannot infer
other users' orders from `totalElements`.

**Verify.** Filter `SUBMITTED`; change page size; sort by price; search a
symbol; inspect the request parameters. As operator, confirm totals only
include your orders.

**Likely question.** "Why a `Specification` instead of many repository
methods?" → Optional filter combinations would otherwise explode into methods
(`findByStatusAndInstrumentIdAndCreatedBy...`). A specification composes
predicates and keeps one query path.

### 5.6 Order detail and the lifecycle timeline

**What the user sees.** Summary card (order number, instrument, side, quantity,
price, estimated value, owner, timestamps, remarks), a rejection panel when
applicable, a vertical lifecycle timeline, role actions, and a documents panel.

**Frontend flow.**

- `route.paramMap` → parse id → `switchMap` into `loadOrder(id)`.
- `loadOrder` fetches the order, then its documents. A document failure does
  not hide the order: it sets `documentsError` and returns the order with an
  empty list.
- Invalid ids (`abc`, `0`, negative) are rejected before any HTTP call.
- `timeline` is computed from current status and the two timestamps. It
  **does not invent per-step times**: it shows `updatedAt` only for the current
  terminal/step and says that the schema stores current state, not an audit
  trail.
- Action visibility mirrors the backend rules: submit for `NEW` owner/admin,
  approve/reject for admin on `SUBMITTED`, cancel for `NEW`/`SUBMITTED`
  owner/admin.
- Actions replace the local order with the API response instead of refetching,
  so the UI reflects the authoritative new state immediately.

**Backend flow.** `GET /api/orders/{id}` → ownership check (owner or admin) →
mapper. `PATCH` endpoints apply the transition and return the updated order.

**Why this design.** One source of truth for the state machine (the entity) and
one authoritative response after each action. The honest timeline is better
than a fabricated audit log; the production fix is described in
`known-issues.md` item 8.

**Verify.** Approve as admin: timeline gains Approved, actions disappear.
Reject: reason panel appears. Cancel as operator: only cancellable states offer
the button.

**Likely question.** "How would you show who approved it?" → Add an
`order_status_history` table and write a row per transition in the same
transaction; expose a history endpoint. The current schema cannot answer it.

### 5.7 Approve, reject, cancel and ownership

**Approve.** `PATCH /api/orders/{id}/approve` is annotated
`@PreAuthorize("hasRole('ADMIN')")`. The service loads the order and calls
`approve()`, which allows only `SUBMITTED → APPROVED`. Terminal orders return
`409`.

**Reject.** Same role gate. The body is `{ "reason": "..." }`, validated as
required, 5–500 characters at the edge and required in the entity.
`Order.reject` transitions first, then stores the trimmed reason. A rejected
order cannot be approved or cancelled afterwards.

**Cancel.** No role annotation: owners and admins may cancel, but the service
checks ownership. Allowed from `NEW` and `SUBMITTED`. The frontend asks for
confirmation and shows the endpoint error if the state changed underneath the
user.

**Ownership.** Two layers:

- listing adds `created_by = username` for non-admins;
- single-resource reads/writes call `assertOwnerOrAdmin` (or the document
  service equivalent).

Approving is **not** owner-scoped: an admin can approve any submitted order,
which is the business rule. An operator is stopped by the role gate before
ownership is even considered.

**Concurrency.** `Order` has `@Version`. Two admins loading the same
`SUBMITTED` order and both approving: the first commits version *n+1*; the
second flush fails with an optimistic-lock exception. The global handler's
catch-all currently maps that to `500` rather than a friendlier `409`; this is a
known gap worth stating honestly. The transition cannot silently happen twice.

**Verify.** Operator approve → `403`; operator cancel another user's order →
`403`; cancel approved → `409`; duplicate approve in two tabs → one succeeds,
the other fails.

**Likely question.** "Where is the real authorization?" → The service and
method annotations. The Angular checks only decide which buttons to render.

### 5.8 Dashboard

**What the user sees.** Six counters (total, new, submitted, approved,
rejected, cancelled) and the five most recent orders. Values change after
actions elsewhere.

**Frontend flow.** `DashboardComponent` calls
`DashboardService.getStats()` once on load and exposes a refresh action. It
formats numbers and status labels with shared pipes; it does not compute totals
from a client-side order array.

**Backend flow.** `GET /api/orders/dashboard` is `ADMIN` only.
`DashboardService.getStats` seeds every enum status with zero, runs
`select o.status, count(o) from Order o group by o.status`, sums the total, and
fetches `findTop5ByOrderByCreatedAtDesc`. Missing statuses remain zero rather
than disappearing from the UI.

**Why this design.** Aggregating in SQL is correct and scales; computing in the
browser would only reflect the current page and would duplicate business logic.
Seeding zeros makes the contract stable.

**Verify.** Note counts, approve an order, refresh: submitted decreases and
approved increases by one.

**Likely question.** "Why not reuse the paginated list endpoint?" → The
dashboard needs aggregates over all orders, not one page. A count query is
cheaper and cannot be fooled by pagination.

### 5.9 Documents

**What the user sees.** A documents panel with upload, list, download, size,
uploader and timestamp. Upload rejects unsupported types and files over 5 MB
before sending. Download saves the original filename.

**Frontend flow.** The file input validates extension, non-empty size and the
5 MB cap. If the browser reports an empty MIME type (common for DOCX/XLSX), the
component rebuilds the `File` with the MIME derived from the extension, because
the server checks the content type. Upload posts `FormData`; the new document
is prepended. Download requests a `Blob`, creates an object URL, clicks a
temporary anchor and revokes the URL.

**Backend flow.**

1. Security requires authentication; `DocumentService` requires owner or admin.
2. Validation: non-empty, ≤ 5 MB, content type in
   `{pdf, png, jpeg, docx, xlsx}`.
3. `safeFileName` uses `StringUtils.cleanPath`, rejects `..`, strips
   directories and replaces unsafe characters.
4. Key: `orders/{orderId}/{uuid}-{filename}`. Bytes go to `DocumentStorage`
   (`LocalDocumentStorage` by default, `S3DocumentStorage` when
   `app.storage.type=s3`).
5. Metadata is saved in PostgreSQL (`order_documents`), not in Mongo.
6. Download loads metadata by document id **and** order id (prevents using an
   id from another order), reads the bytes, and returns them with the stored
   content type and filename.

**Why this design.** The interface isolates storage from business rules, so S3
can be enabled without changing the service. Metadata in PostgreSQL keeps
ownership joins and ordering simple. Controlled download means documents are not
public URLs; access is checked every time.

**Verify.** Upload a PDF, download it, compare size/open it. Try `.txt` and
6 MB: both rejected. As operator, request another user's document id: `403`.

**Likely question.** "Why not upload directly to S3 from Angular?" → A
presigned URL is the scalable production approach. Here, proxying through the
service keeps authorization and validation in one place and works without AWS
credentials. Both are defensible; state the trade-off.

### 5.10 Errors, notifications and loading state

**What the user sees.** A top loading bar during HTTP activity, toasts for
success and global failures, inline messages for form/validation errors, and a
full error panel when a page cannot load.

**Frontend flow.** Three functional interceptors:

- `loadingInterceptor` increments/decrements a counter in `LoadingService`;
  the bar shows while the count is positive. Using a counter, not a boolean,
  prevents parallel requests from clearing the bar early.
- `authInterceptor` adds the bearer token when one exists.
- `errorInterceptor` handles 401 (clear session, notify, redirect to login),
  403 (role message), 0 (gateway unreachable) and ≥ 500 (server message).
  It deliberately leaves 400/404/409/422 to the component so field-level and
  action-level messages can be specific.

Components follow the same pattern: `finalize` always clears local loading
flags; errors that were already surfaced globally are not duplicated (see
`reportActionError`).

**Backend flow.** `@RestControllerAdvice` maps domain exceptions to a
consistent `ApiError`:

```json
{
  "timestamp": "...",
  "status": 409,
  "error": "Conflict",
  "message": "Invalid state transition for order ORD-... from APPROVED to APPROVED",
  "path": "/api/orders/1/approve",
  "requestId": "…",
  "fieldErrors": null
}
```

Unexpected exceptions are logged with the stack trace and returned as a generic
`500` without leaking internals; the `requestId` is the bridge to the logs.

**Why this design.** One error shape lets the frontend parse messages without
per-endpoint special cases. Status codes carry meaning: 401 unauthenticated,
403 forbidden, 404 missing, 409 conflict/state, 422 semantically invalid, 503
dependency unavailable, 500 unexpected.

**Verify.** Stop Instrument Service and create an order: toast + `503` with a
useful message. Request a missing order: `404` with the id. Call an endpoint
with a malformed enum: `400`.

**Likely question.** "Why not show every error as a toast?" → Validation errors
belong next to the field; action conflicts belong near the button. Global
toasts are for failures the user cannot attach to a specific input.

### 5.11 Kafka activity updates

**What the user sees.** Instrument activity counts and "last order" timing
change after orders are created, without the order request waiting for it.

**Producer flow.** `OrderService.create` saves the order, then builds
`OrderCreatedEvent` (`eventId`, `eventType`, `orderId` - which carries the
human-readable order number such as `ORD-44D7E063` - `instrumentId`,
`quantity`, `orderType`, `createdBy`, `occurredAt`) and calls
`kafkaTemplate.send(topic, instrumentId, event)`. The instrument id is the key,
so all events for an instrument land in the same partition and preserve order.
The callback logs topic/partition/offset or the failure.

**Consumer flow.** `@KafkaListener(topics = "order-events", groupId =
"instrument-service")` deserialises into `OrderEvent`, ignores anything that is
not `ORDER_CREATED`, and calls `incrementOrderActivity`. That method uses a
Mongo `$inc` on `activityCount` plus `$set lastOrderAt`, so concurrent consumers
do not lose increments. It evicts `instrument:{id}` so the next read is fresh.
Unknown instruments log a warning instead of failing the partition.

**Why asynchronous.** The order is valid without the counter. Making the
counter synchronous would couple order creation to a second write path and make
the instrument service a hard dependency for a non-critical side effect.

**Verify.** Kafka console consumer; instrument activity count; Redis key gone
after consumption (evicted).

**Likely question.** "What if the same event arrives twice?" → The counter
increments twice. Delivery is at-least-once; idempotency needs a processed
event id. Documented as a gap, with the fix.

### 5.12 Redis cache-aside

**What the user sees.** Nothing directly — the benefit is latency and reduced
Mongo load. The evaluator sees keys and TTL in `redis-cli`.

**Flow.**

1. `InstrumentService.findById` asks `cacheService.get(id)`.
2. Hit → deserialise JSON to `InstrumentResponse`, log `Cache HIT`, return.
3. Miss → read MongoDB, map to response, `cacheService.put` with
   `SETEX instrument:{id} 300 <json>`, return.
4. Create/update/status change/activity increment → `cacheService.evict(id)`.
5. Redis exceptions are caught and treated as a miss or a no-op; the request
   still succeeds from Mongo.

**Why explicit rather than `@Cacheable`.** TTL and eviction are visible and
testable; the evaluator can be shown exactly where invalidation happens. It also
avoids proxy/self-invocation surprises and makes the failure policy explicit.

**Verify.** `redis-cli --scan --pattern 'instrument:*'`, `TTL`, then update the
instrument and watch the key disappear. Stop Redis and confirm reads still work
with a warning.

**Likely question.** "Why cache by id but not the list?" → The by-id read is the
hot path used by Feign before every order creation. The list is small, changes
rarely and is harder to invalidate correctly; caching it would add risk for
little gain.

---

## 6. The 15 live evaluation questions

These are the questions printed in the assignment brief (section 21.1). Each
answer below is written the way you should say it aloud: a direct sentence or
two, then one concrete detail from this codebase that proves you built it.

### Q6.1 "How does a request travel from Angular to Order Service?"

The browser never calls a service directly. It calls `/api/...` relative to its
own origin, so the dev proxy (local) or nginx (Docker) forwards to the gateway on
port 8080. The gateway matches the path, forwards to Eureka to resolve the
service name, and load-balances across healthy instances. For order APIs the
request lands on `OrderController` in the order service.

Say: *"Angular -> /api -> gateway -> Eureka lookup -> order-service instance."*
Point at `frontend/proxy.conf.json`, `frontend/nginx.conf`, the route table in
`backend/api-gateway/src/main/resources/application.yml`, and the controller.

### Q6.2 "Why are two separate databases used?"

Because the services have different data shapes and consistency needs. Orders
are transactional records with enforced relationships and state transitions, so
they fit PostgreSQL's relational model with JPA. Instruments are a small,
read-heavy catalogue with a flexible shape and a counter that must update
atomically, so MongoDB fits; `$inc` updates the activity count without a
read-modify-write race.

Say: *"PostgreSQL for the transactional order ledger, MongoDB for the flexible,
high-read instrument catalogue."* Point at `order-service` entities vs the
`Instrument` document.

### Q6.3 "How does Eureka service discovery work?"

Each service instance registers itself with Eureka at startup using its
application name and host/port, then sends heartbeats. A caller asks Eureka for
instances of a name and picks one. In this project the gateway resolves the
target service before forwarding, so clients use service names rather than
hardcoded URLs.

Say: *"register, heartbeat, lookup."* Point at the `eureka-server` module, the
registration config in each service's `application.yml`, and the Eureka
dashboard on 8761.

### Q6.4 "What is the role of Spring Cloud Gateway?"

It is the single entry point. It routes `/api/**` to the correct service,
applies CORS once, adds a request id, and hides the internal topology from the
browser. Without it every client would need to know each service address.

Say: *"one door, routing, cross-cutting concerns, no direct service access."*
Point at the route definitions and the CORS/request-id filters.

### Q6.5 "How does Feign communicate between services?"

Feign turns a Java interface into an HTTP client. The order service declares
`InstrumentClient`; at runtime Spring creates a proxy that calls the instrument
service through the gateway. `FeignConfig` copies the incoming JWT into the
outgoing request, so the downstream service sees the same authenticated user.

Say: *"interface -> proxy -> HTTP through the gateway, with the token forwarded."*
Point at `InstrumentClient`, `FeignConfig`, and the JWT propagation tests.

### Q6.6 "What happens if Instrument Service is unavailable?"

Order creation needs instrument data, so it fails fast: the Feign call throws,
the order service maps that to a clear error response instead of saving a bad
order, and the frontend shows the message. Other endpoints that do not need the
instrument service keep working. This is an explicit failure mode, not a silent
degradation.

Say: *"Feign error -> mapped response -> user sees a message; unrelated features
still work."* Point at `OrderService.loadInstrument` and the global exception
handler.

### Q6.7 "How does Kafka producer-consumer communication work?"

When an order is created the order service publishes an `ORDER_CREATED` event to
the `order-events` topic after the database save. The instrument service consumes
that event and increments the instrument activity counter. Producers and
consumers never call each other directly; they share only the topic.

Say: *"publish after save, consume asynchronously, shared topic, not a direct
call."* Point at `OrderEventProducer`, `OrderEventConsumer`, and the topic
configuration.

### Q6.8 "Why is Redis used in this application?"

To avoid hitting MongoDB for every instrument read on the hot path. The by-id
read is cached with a TTL and invalidated on change. If Redis is down the code
falls back to MongoDB, so a cache failure degrades performance but not
correctness.

Say: *"cache-aside with TTL and eviction, fallback to the database on error."*
Point at `InstrumentCacheService`, the cache call sites in `InstrumentService`,
and `redis-cli` evidence.

### Q6.9 "How is JWT generated and validated?"

On login the order service checks the credentials with BCrypt, then signs a JWT
with the configured secret and expiry. Every later request carries the token in
the `Authorization` header; `JwtAuthenticationFilter` validates the signature and
expiry, then puts the authentication in the security context. An invalid or
expired token is rejected before the controller runs.

Say: *"BCrypt check at login, signed token, validated per request by a filter."*
Point at `JwtService`, `JwtAuthenticationFilter`, and the auth flow tests.

### Q6.10 "How do you prevent an Operations User from approving an order?"

Two layers. First, `@PreAuthorize` on the endpoint requires the admin role for
approve/reject, so a normal operator gets 403 before the service logic runs.
Second, the service checks ownership and status before any transition, so even a
valid caller cannot act on an order they do not own or in the wrong state.

Say: *"role check at the edge, ownership and state check in the service."*
Point at `OrderController` annotations, `OrderService` ownership rules, and the
negative test.

### Q6.11 "How do you handle exceptions globally?"

Both services use a `@RestControllerAdvice` handler. Controllers throw typed
exceptions; the handler maps them to consistent error responses with status codes
and messages. Business errors such as invalid transitions are exceptions, not
return codes, so they cannot be forgotten at a call site.

Say: *"throw typed exceptions, map once in one place, consistent response
shape."* Point at the `GlobalExceptionHandler` classes and the error format in
the API docs.

### Q6.12 "How do you write unit tests using Mockito?"

Services are tested by mocking their collaborators. The test declares a mock
repository or client, stubs the method under test, calls the real service, and
verifies both the returned value and the interaction. Web-layer slice tests mock
the service and assert the HTTP contract without starting the full context.

Say: *"mock the boundary, exercise the real class, verify result and
interaction."* Point at the order-service and instrument-service test packages.

### Q6.13 "How do you troubleshoot an API returning HTTP 500?"

Read the structured log line and trace id first, because the gateway and both
services log a request id. Match that id across the gateway and service logs to
find which hop failed. Then look at the stack trace in the service log and the
global handler's response body. Check the dependencies (database, Mongo, Redis,
Kafka) with their health endpoints, then reproduce with a focused request.

Say: *"trace id -> service log -> stack trace -> dependency health -> repro."*
Point at the logging configuration and Actuator health endpoints.

### Q6.14 "How does Docker Compose start the application?"

`docker compose up -d` reads `docker-compose.yml`. Compose starts PostgreSQL,
MongoDB, Kafka, and Redis first, then the two services, then Eureka and the
gateway, then the frontend container. Healthchecks gate the dependents, so a
service is only considered ready when its `/actuator/health` responds.

Say: *"one file describes the topology; Compose starts dependencies in order and
uses healthchecks."* Point at `docker-compose.yml` and the healthcheck entries.

### Q6.15 "How would you deploy this application to AWS?"

The images already exist, so the natural path is containers on ECS or EKS with a
load balancer in front of the gateway. PostgreSQL becomes RDS, MongoDB becomes
DocumentDB or Atlas, Redis becomes ElastiCache, and Kafka becomes MSK or a
managed broker. Secrets move out of `.env` into Secrets Manager. The current S3
document storage is the one AWS-specific piece already written, but it has not
been exercised against a real bucket.

Say: *"containers to ECS/EKS, managed data stores, secrets manager; S3 code
exists but is unverified."* Be honest about the S3 gap.

---

## 7. Live modification drills

The evaluator may ask for a small live change. Practice these three until you can
do each without reading notes. Each drill names the exact files and the risk to
watch.

### Drill 1 — Add a new order status (e.g. PENDING_REVIEW)

**Files:** `backend/order-service/.../entity/OrderStatus.java`,
`.../entity/Order.java`.

**Steps.**

1. Add the enum constant in the correct position.
2. Update `canTransitionTo` so the transition table stays explicit: decide which
   statuses may move to `PENDING_REVIEW` and from it.
3. If the UI shows a badge per status, update the frontend status metadata
   (`order-detail.component.ts`, any status-label map).
4. Run `mvn -B test` in `backend/`; the lifecycle tests should catch an invalid
   transition you forgot.

**Risk:** a new status that nothing can reach, or one that bypasses approval.
Say so if asked why the transition table is a switch rather than a list of
allowed pairs: *"it is easier to read and harder to get wrong."*

### Drill 2 — Add a filter to the order list (e.g. by instrument code)

**Files:** `backend/order-service/.../service/OrderService.java`,
`.../controller/OrderController.java`, `OrderRepository` (Spring Data query or a
`Specification`), `frontend/src/app/features/orders/order-list/*`.

**Steps.**

1. Add a request parameter to the list endpoint (optional, default null).
2. Extend the repository query or `Specification` to filter by the instrument code.
3. Add the input to the frontend filter form and include it in the
   `OrderQueryState` that feeds the debounced search.
4. Verify with two orders on different instruments and a filtered list.

**Risk:** forgetting that the query state object is the single source of truth;
the UI must send the new field or the backend never sees it.

### Drill 3 — Add a validation rule (e.g. quantity must be at least 1)

**Files:** the create-order request DTO in `order-service`
(`@Min(1)`), and the Angular reactive form validator in `order-form.component.ts`.

**Steps.**

1. Annotate the DTO field and confirm the global handler returns a 400 with the
   field message.
2. Add the matching `Validators.min(1)` so the UI disables submit before a
   request is sent.
3. Test both layers: a direct API call with 0 (expect 400) and the UI form.

**Risk:** only validating on one side. Say: *"the backend is authoritative; the
frontend validator exists for feedback, not security."*

---

## 8. Troubleshooting playbook

Symptoms you are most likely to hit during a demo, and the first thing to check
for each.

| Symptom | First check | Likely cause | Fix |
| --- | --- | --- | --- |
| Login returns 401 with correct credentials | Seed users exist | `SEED_DATA=false` or the initializer ran before the schema was ready | Confirm the initializer log line; reseed |
| Gateway returns 503 for a service | Eureka registration | The service has not registered yet, or the healthcheck failed | Wait for health; check the Eureka dashboard for the instance |
| Order creation fails with an instrument error | Instrument service reachable | It is down, or Redis is unavailable and the fallback is logging | Check `/actuator/health` on 8082; check the cache-fallback log |
| Kafka event not consumed | Topic and consumer group | Wrong topic name, or the consumer started after the publish | Check the topic config; use a console consumer to see the backlog |
| Redis keys missing | Cache write happened | An eviction ran, or the key expired (TTL) | Read again and check the HIT/MISS log lines |
| 403 on approve as operator | Role rule | Expected behaviour | Explain that this is the ownership/role guard working |
| Swagger UI blank | Static resource path behind the gateway | Forwarded `/v3/api-docs` instead of the UI path | Use the direct service URL or the gateway route documented in the API docs |
| Frontend calls fail in Docker | Proxy target | nginx points at a hostname Docker cannot resolve | Confirm the nginx resolver/proxy configuration |
| Tests fail after a dependency bump | Bytecode/JDK mismatch | Java version or Spring Boot patch changed | Re-run with the pinned versions in the root `pom.xml` |

### A reusable method

1. Identify the hop: browser, gateway, service, database, message broker, cache.
2. Find the request id in the log and follow it across hops.
3. Read the exception type and the first stack frame in your code.
4. Check the dependency health endpoint.
5. Reproduce with the smallest possible request.

If a feature does not work, say which layer you have already ruled out. That is
usually worth more than the fix itself.

---

## 9. Concise answers and honest status

### 60-second answers

Keep these word-for-word ready; they are the highest-value sentences in the
guide.

1. **Request path:** Angular calls `/api`; the gateway routes and Eureka
   resolves; the controller handles it.
2. **Two databases:** PostgreSQL for transactional orders, MongoDB for the
   flexible instrument catalogue.
3. **Discovery:** services register with Eureka and heartbeat; callers look up
   instances by name.
4. **Gateway:** single entry point for routing, CORS, and request ids.
5. **Feign:** a Java interface backed by a proxy that calls the other service
   through the gateway, forwarding the JWT.
6. **Failure mode:** a missing instrument service makes order creation fail fast
   with a clear error; unrelated endpoints keep working.
7. **Kafka:** publish `ORDER_CREATED` after the save; the instrument service
   consumes it and increments activity.
8. **Redis:** cache-aside by instrument id with a TTL and eviction; falls back to
   Mongo on error.
9. **JWT:** BCrypt at login, signed token issued, validated per request by a
   filter.
10. **Authorization:** role check at the endpoint, ownership and state check in
    the service.
11. **Exceptions:** typed exceptions mapped once by `@RestControllerAdvice`.
12. **Tests:** mock the boundary, exercise the real class, verify result and
    interaction.
13. **Troubleshooting:** trace id, service log, stack trace, dependency health,
    repro.
14. **Compose:** one file describes the topology; healthchecks gate dependents.
15. **AWS:** containers on ECS/EKS, managed data stores, Secrets Manager; the S3
    document code exists but is not verified against a real bucket.

### Honest execution status

Claim only what has been executed. As of 2026-11-16:

- Backend compiles; 31 backend tests pass (`mvn -B test`).
- Frontend builds; 22 Jasmine/Karma specs pass (`npm run test:ci`).
- Five Docker images build, and the full Compose stack reported healthy: both
  services and the gateway registered with Eureka; login, create, submit,
  approve, reject and cancel worked through the gateway, including the expected
  `403` and `409` negative cases.
- Pagination, filtering, search, admin dashboard statistics, document
  upload/list/download (with a SHA-256 round-trip check), Kafka publication and
  consumption, and the Redis cache-aside/eviction cycle were exercised. The
  commands and outputs are in [`verification.md`](verification.md).
- Architecture, API, known-issues, verification and demo-script documents are
  written.

A Chrome DevTools smoke check of the production build covered the login guard,
both logins, the orders list, admin dashboard, order detail with a document, and
the instrument catalogue. Button-driven mutations were driven through the API;
their component logic is covered by the Jasmine specs.

**Not claimed:** a real AWS S3 upload (only the local storage path was
exercised), a production deployment, or an exhaustive scripted click-through of
every Angular screen.

### Glossary

| Term | One-line meaning |
| --- | --- |
| Discovery | Services find each other by name instead of fixed addresses |
| Gateway | Single entry point that routes and applies cross-cutting concerns |
| Feign | Declarative HTTP client over a Java interface |
| JWT | Signed token carrying identity, validated without server state |
| BCrypt | Salted password hash used instead of storing plaintext |
| Cache-aside | Application reads cache first, then the store, and fills the cache |
| Eviction | Removing a cache entry when the underlying data changes |
| State machine | Explicit allowed transitions instead of free status updates |
| Ownership check | Ensuring a user may act only on their own records |
| Global exception handler | One place mapping exceptions to responses |
| Slice test | A test that loads only one layer of the application |
| Healthcheck | A probe that reports whether a dependency is ready |

### Study plan (one evening)

1. **15 minutes** — read Section 1 and the build log.
2. **30 minutes** — walk Section 5 and open each named file as you read it.
3. **45 minutes** — rehearse the 15 answers aloud; time yourself.
4. **30 minutes** — do Drills 1 and 2 in the IDE.
5. **10 minutes** — read the troubleshooting playbook and the known-issues list.
6. **Final pass** — re-read the honest status and remove any claim you cannot
   demonstrate.

