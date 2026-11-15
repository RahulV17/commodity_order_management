# Commodity Order Management

A working reference implementation of the NCDEX full-stack engineering
challenge: an Angular single-page application, two Spring Boot microservices,
service discovery, an API gateway, JWT security, Kafka events, Redis caching,
PostgreSQL, MongoDB and Docker Compose.

The application manages commodity **orders** and the **instrument catalogue**
they refer to. It is a learning/demo system, not a trading exchange: there is
no settlement, exchange connectivity or live market data.

![status](https://img.shields.io/badge/build-Maven%20%2B%20Angular-informational)
![java](https://img.shields.io/badge/Java-17-orange)
![spring](https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen)
![angular](https://img.shields.io/badge/Angular-20-red)

## What it demonstrates

| Area | Implementation |
| --- | --- |
| Microservices | `order-service` (PostgreSQL/JPA) and `instrument-service` (MongoDB) own separate data stores |
| Discovery | Eureka Server; all Spring applications register themselves |
| Gateway | Spring Cloud Gateway with explicit `/api/**` routes, CORS and `X-Request-Id` propagation |
| Sync communication | OpenFeign call from Order Service to Instrument Service, with the caller's JWT forwarded |
| Async communication | Kafka topic `order-events`; Order Service publishes `ORDER_CREATED`, Instrument Service consumes it and updates activity counters |
| Cache | Redis cache-aside for instrument-by-id, 300 s TTL, explicit eviction on update/delete, graceful fallback when Redis is down |
| Security | Stateless JWT (HS256), BCrypt password hashes, `ADMIN` and `OPERATIONS` roles, ownership checks in the service layer |
| Order lifecycle | `NEW → SUBMITTED → APPROVED / REJECTED / CANCELLED`, enforced by an explicit state machine |
| Documents | Authenticated upload/download, local filesystem by default, S3-compatible storage by configuration |
| Frontend | Angular 20 standalone components, lazy routes, functional guards/interceptors, custom CSS design system |
| Testing | 31 JUnit/Mockito tests, 22 Jasmine/Karma specs |
| Observability | Actuator health/info/metrics, structured request-id logging, Swagger UI per service |
| Deployment | Multi-stage Dockerfiles and one `docker compose up` for the full stack |

The assignment rubric, live questions and implementation notes are covered in
[`docs/LEARNING_GUIDE.md`](docs/LEARNING_GUIDE.md).

## Architecture at a glance

```text
Angular (:4200, nginx or dev server)
        │  /api/**  +  Authorization: Bearer <JWT>
        ▼
Spring Cloud Gateway (:8080)  ── Eureka lookup ──▶ Eureka Server (:8761)
        │
        ├── lb://ORDER-SERVICE (:8081) ── PostgreSQL
        │        │  Feign GET /api/instruments/{id}
        │        │  Kafka ORDER_CREATED
        │        ▼
        └── lb://INSTRUMENT-SERVICE (:8082) ── MongoDB + Redis
                 ▲
                 └── Kafka consumer (group: instrument-service)
```

No service calls another by a hard-coded hostname. The gateway and Feign use
logical service names resolved through Eureka.

## Repository layout

```text
commodity-order-management/
├── backend/
│   ├── pom.xml                  # Maven multi-module parent
│   ├── eureka-server/           # registry
│   ├── api-gateway/             # routes, CORS, request id
│   ├── instrument-service/      # MongoDB, Redis, Kafka consumer, JWT
│   ├── order-service/           # PostgreSQL, JWT, Feign, Kafka producer, documents
│   └── Dockerfile               # shared multi-stage build (SERVICE build arg)
├── frontend/
│   ├── src/app/core/            # guards, interceptors, models, services
│   ├── src/app/shared/          # reusable UI components and pipes
│   ├── src/app/features/        # login, dashboard, instruments, orders
│   ├── src/app/layouts/         # authenticated shell
│   ├── nginx.conf               # SPA fallback + /api proxy
│   └── Dockerfile               # Node build + nginx runtime
├── docs/                        # architecture, API, demo, learning guide, issues
├── docker-compose.yml
├── .env.example
└── README.md
```

## Quick start with Docker Compose

Prerequisites: Docker Desktop (or Docker Engine + Compose v2). Nothing else is
required on the host.

```bash
cd commodity-order-management
docker compose up -d --build
```

The first build downloads Maven and npm dependencies and can take several
minutes. On a memory-constrained machine build services sequentially:

```bash
COMPOSE_PARALLEL_LIMIT=1 docker compose build
docker compose up -d
```

Check status and follow startup:

```bash
docker compose ps
docker compose logs -f order-service instrument-service
```

All health checks must report `healthy` before the gateway and frontend start
(the Compose dependency chain enforces this).

| URL | Purpose |
| --- | --- |
| http://localhost:4200 | Angular application |
| http://localhost:8080/actuator/health | Gateway health |
| http://localhost:8761 | Eureka dashboard |
| http://localhost:8081/swagger-ui.html | Order Service Swagger UI |
| http://localhost:8082/swagger-ui.html | Instrument Service Swagger UI |
| http://localhost:8081/actuator/health | Order Service health |
| http://localhost:8082/actuator/health | Instrument Service health |

### Demo accounts

| Username | Password | Role | Can do |
| --- | --- | --- | --- |
| `admin` | `Admin@123` | `ADMIN` | Everything: dashboard, all orders, approve/reject/cancel, documents |
| `operator` | `Operator@123` | `OPERATIONS` | Create/submit/cancel **own** orders, browse instruments, upload/download own documents |

Seed users are created at startup only if they do not already exist. Override
the passwords with `DEMO_ADMIN_PASSWORD` / `DEMO_OPERATOR_PASSWORD`, or disable
seeding with `SEED_DATA=false`.

## Local development (without containerising the applications)

Prerequisites: Java 17, Maven 3.9+, Node.js 20+ (22 recommended), and Docker for
the infrastructure. Alternatively install PostgreSQL, MongoDB, Kafka and Redis
locally.

1. Start infrastructure only:

   ```bash
   docker compose up -d postgres mongo kafka
   ```

   Redis is intentionally **not published** by Compose because the development
   machine may already run Redis on 6379. Start a local Redis, or temporarily
   add `ports: ["6379:6379"]` to the `redis` service. A host-run Instrument
   Service uses `localhost:6379` by default.

2. Start the Spring applications in this order (separate terminals):

   ```bash
   cd backend
   mvn -pl eureka-server spring-boot:run
   mvn -pl instrument-service spring-boot:run
   mvn -pl order-service spring-boot:run
   mvn -pl api-gateway spring-boot:run
   ```

3. Start Angular:

   ```bash
   cd frontend
   npm install
   npm start
   ```

   The dev server listens on http://localhost:4200 and proxies `/api` to
   `http://localhost:8080` using `proxy.conf.json`, so the browser only ever
   talks to one origin. No CORS configuration is needed for the normal flow.

### Configuration

Every setting has a development default. Environment variables are the
supported override mechanism; see [`.env.example`](.env.example).

| Variable | Default | Used by |
| --- | --- | --- |
| `JWT_SECRET` | demo value in `application.yml` | order + instrument |
| `JWT_EXPIRATION_MINUTES` | `120` | order + instrument |
| `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `localhost:5432/orderdb` | order |
| `MONGODB_URI` | `mongodb://localhost:27017/instrumentdb` | instrument |
| `REDIS_HOST`, `REDIS_PORT` | `localhost:6379` | instrument |
| `INSTRUMENT_CACHE_TTL_SECONDS` | `300` | instrument |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | order + instrument |
| `ORDER_EVENTS_TOPIC` | `order-events` | order + instrument |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | all services |
| `SEED_DATA` | `true` | order + instrument |
| `STORAGE_TYPE` | `local` | order |
| `STORAGE_LOCAL_DIR` | `./uploads` (`/app/uploads` in Docker) | order |
| `AWS_S3_BUCKET`, `AWS_REGION`, `AWS_S3_ENDPOINT` | empty, `us-east-1`, empty | order (S3 mode) |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | empty | order (S3 mode) |

`java -jar` and `mvn spring-boot:run` both read environment variables; no
profile-specific file is required. Never commit a real `.env` or credentials.

## Tests

Backend:

```bash
cd backend
mvn -B test
```

Expected: 31 tests, 0 failures. The suite covers the order state machine,
ownership/role security, Feign failure mapping, dashboard aggregation, JWT
authentication and the Redis cache-aside helper.

Frontend:

```bash
cd frontend
npm run test:ci        # ng test --watch=false --browsers=ChromeHeadless
npm run build          # production build + budgets
```

Expected: 22 Jasmine specs and a production bundle with lazy-loaded feature
chunks. Chrome must be installed; the Karma launcher uses `ChromeHeadless`.

## Useful API calls

Obtain a token:

```bash
curl -s http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"operator","password":"Operator@123"}'
```

Create and submit an order:

```bash
TOKEN="<accessToken>"
curl -s http://localhost:8080/api/orders \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"instrumentId":"GOLD-001","orderType":"BUY","quantity":2,"price":6500.5,"remarks":"demo"}'
curl -s -X PATCH http://localhost:8080/api/orders/1/submit \
  -H "Authorization: Bearer $TOKEN"
```

Approve as admin:

```bash
curl -s -X PATCH http://localhost:8080/api/orders/1/approve \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The complete endpoint reference, payloads and error format is in
[`docs/api-documentation.md`](docs/api-documentation.md).

## Documentation

| Document | Contents |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | Components, decisions, lifecycle, request flows, data model, security and consistency trade-offs |
| [`docs/api-documentation.md`](docs/api-documentation.md) | Every endpoint, query parameter, sample request/response and status code |
| [`docs/LEARNING_GUIDE.md`](docs/LEARNING_GUIDE.md) | Build log, feature-by-feature walkthrough, concepts, all 15 live questions, live-modification drills |
| [`docs/demo-script.md`](docs/demo-script.md) | A timed demonstration script with verification steps |
| [`docs/known-issues.md`](docs/known-issues.md) | Honest limitations, risks and production follow-ups |

## Honest status

- Core flows (login, instrument browsing, create/submit/approve/reject/cancel,
  filtering/pagination/sorting, documents on local storage, Kafka event, Redis
  cache) are implemented and exercised locally.
- The S3 storage implementation exists and compiles, but it has **not** been
  integration-tested against a real bucket. Local storage is the demonstrated
  path.
- Kafka publishing is best-effort after the database write and occurs inside
  the transaction; there is no transactional outbox. A rollback can therefore
  leave a ghost event, and a broker outage does not fail order creation. This is
  a documented trade-off, not hidden behaviour.
- `spring.jpa.hibernate.ddl-auto=update` is used for demo convenience; a real
  deployment should use versioned migrations.
- Docker builds were verified on the development machine; the first build is
  network-heavy. See `docs/known-issues.md` for the parallel-build workaround.

## License

Educational project. No license is granted for production use.
