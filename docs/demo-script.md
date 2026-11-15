# Demonstration script

A 12–15 minute walkthrough that proves the required integrations without
pretending anything is mocked. Adjust timings to the evaluator's questions; the
"Evidence" column is what you point at on screen.

## Before the evaluator arrives

Run these once and keep terminals open. Do not improvise during the demo.

```bash
cd commodity-order-management
docker compose up -d
docker compose ps                     # every service healthy
```

Pre-open browser tabs:

1. http://localhost:4200 — application
2. http://localhost:8761 — Eureka dashboard
3. http://localhost:8080/actuator/health — gateway
4. http://localhost:8081/actuator/health — order service
5. http://localhost:8082/actuator/health — instrument service
6. http://localhost:8081/swagger-ui.html — order API
7. A terminal in the project root for Kafka/Redis/PostgreSQL evidence

Confirm the demo data exists. If the database volume is fresh, the seeders
insert four instruments and two users on startup. If orders are empty, create one
during the demo (that is part of the script).

**Fallback if Docker is unhealthy:** run the four Spring applications with
`mvn -pl <module> spring-boot:run` and Angular with `npm start`. Infrastructure
containers (`postgres`, `mongo`, `kafka`) must still be up. Do not claim a
component works if its health endpoint is down.

## 0. Framing (30 seconds)

> "This is a commodity order desk. Angular talks only to Spring Cloud Gateway.
> The gateway discovers two services through Eureka: Order Service on
> PostgreSQL and Instrument Service on MongoDB. Order creation validates the
> instrument synchronously through Feign, then emits a Kafka event that updates
> the instrument's activity counter asynchronously. Instrument reads are cached
> in Redis. Authentication is stateless JWT with role checks in the service
> layer."

Show the architecture diagram in `docs/architecture.md` or the README. Do not
read the diagram line by line.

## 1. Service discovery and health (1 minute)

**Show:** Eureka dashboard.

Expected: `ORDER-SERVICE`, `INSTRUMENT-SERVICE`, `API-GATEWAY` registered with
status `UP`. Eureka itself is the registry and does not register.

**Say:** "The gateway routes use `lb://ORDER-SERVICE`, not a hostname. Eureka
supplies the instance list; Spring Cloud LoadBalancer picks one. If a second
instance starts, it receives traffic without a configuration change."

**Show:** `/actuator/health` on 8080, 8081, 8082. Point out that health is
public but business endpoints are not.

## 2. Login and security (1 minute)

1. Open http://localhost:4200. You are redirected to `/login` by `authGuard`.
2. Sign in as `operator` / `Operator@123`.

**Show:** DevTools → Network → `POST /api/auth/login` returns
`accessToken`, `tokenType`, `expiresInSeconds`, `user`. The next request carries
`Authorization: Bearer …` (the functional `authInterceptor`).

**Say:** "Passwords are BCrypt hashes in PostgreSQL. The JWT is HS256-signed
with a shared secret; both services validate signature and expiry
independently. There is no server session — `SessionCreationPolicy.STATELESS`.
The token is stored in `localStorage` for this demo; the production trade-off is
in `known-issues.md`."

**Prove the negative:** open a private window, go straight to
http://localhost:4200/orders. `authGuard` redirects to login. Then call the API
without a token:

```bash
curl -i http://localhost:8080/api/orders
```

Expected: `401` with a JSON `ApiError`, not an HTML login page
(`RestAuthenticationEntryPoint`).

## 3. Instrument catalogue and Redis cache (2 minutes)

1. Click **Instruments**.
2. Point out symbol, category, indicative price, active flag and activity count.
3. Open DevTools → Network and refresh. `GET /api/instruments` is a list query
   (not cached by the by-id cache).

**Show the cache working:**

```bash
docker compose exec redis redis-cli --scan --pattern 'instrument:*'
```

Initially empty. In the UI, create an order (next step) or call by id:

```bash
curl -s http://localhost:8080/api/instruments/GOLD-001 \
  -H "Authorization: Bearer $TOKEN" > /dev/null
docker compose exec redis redis-cli --scan --pattern 'instrument:*'
docker compose exec redis redis-cli ttl instrument:GOLD-001
```

Expected: key `instrument:GOLD-001`, TTL below 300 seconds. A second call is a
cache hit; the service logs no MongoDB query for it. Updating or deleting an
instrument evicts the key (`InstrumentService` calls `cache.evict`).

**Say:** "This is explicit cache-aside, not `@Cacheable`, so the TTL and
invalidation are visible in code. Redis failures are caught: the service falls
back to MongoDB and logs a warning. The cache is an optimisation, never the
source of truth."

## 4. Create and submit an order (2–3 minutes)

1. Click **New order**.
2. Choose `GOLD` (price prefills from the catalogue), `BUY`, quantity `2`,
   price `6500.5`, remarks `Evaluator demo`.
3. Point out client-side validation: positive quantity, max four decimals,
   remarks limit. Submit.

**Show:** Network contains `POST /api/orders` → `201`, then
`PATCH /api/orders/{id}/submit` → `200`. The UI navigates to the detail page.
This two-step flow is intentional: creation produces `NEW`; submit moves it to
`SUBMITTED` where an admin can act. The form chains the calls with `switchMap`
and, if submit fails, keeps the created order id and offers a link instead of
losing it.

**Say:** "Order Service does not trust the browser. It calls
`GET /api/instruments/{id}` through Feign, propagating the caller's JWT, and
rejects missing or inactive instruments. It stores a snapshot of the symbol and
name so the order stays readable if the catalogue changes."

**Show the PostgreSQL row:**

```bash
docker compose exec postgres psql -U orderuser -d orderdb \
  -c "select id, order_number, instrument_symbol, quantity, price, status, created_by from orders order by id desc limit 3;"
```

Expected: the new row is `SUBMITTED`, `created_by = operator`, and the number
matches `ORD-YYYYMMDD-NNNN`.

**Show the Kafka event:**

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order-events --from-beginning --max-messages 5
```

Expected: JSON with `eventId`, `eventType: ORDER_CREATED`, `orderId`,
`instrumentId`, `quantity`, `orderType`, `createdBy`, `occurredAt`.

**Say:** "The key is the instrument id, so events for one instrument stay in
order within a partition. The Instrument Service consumes with group
`instrument-service` and increments `activityCount`. This is at-least-once, not
exactly-once; the idempotency gap is documented."

Refresh **Instruments** and show the GOLD activity count increased. If it has
not yet, wait a second and refresh; explain eventual consistency.

## 5. Role enforcement (2 minutes)

Still signed in as `operator`:

- The order list shows only this user's orders (the query adds
  `created_by = :username` for non-admins).
- There is **no** Approve/Reject action on any row.
- **Dashboard** is not in the navigation; navigating to `/dashboard` is blocked
  by `adminGuard`.

**Prove the backend is the authority** (the UI is not the security boundary):

```bash
curl -i -X PATCH http://localhost:8080/api/orders/<id>/approve \
  -H "Authorization: Bearer $OPERATOR_TOKEN"
```

Expected: `403` from `@PreAuthorize("hasRole('ADMIN')")` on
`OrderController.approve`.

Then try to read another user's order as the operator (use an admin-created id,
or explain the rule if none exists):

```bash
curl -i http://localhost:8080/api/orders/<other-id> \
  -H "Authorization: Bearer $OPERATOR_TOKEN"
```

Expected: `403` with "You can only view your own orders" from
`OrderService.assertOwnerOrAdmin`. The ownership rule is in the service, not
only in Angular.

**Say:** "Two layers: URL-level role rules with `@PreAuthorize`, and
row-level ownership checks in the service. Listing filters in the database
query. The frontend hides actions for usability, but every rule is re-checked
server-side."

## 6. Approval, rejection, cancellation (2 minutes)

Log out, sign in as `admin` / `Admin@123`.

1. **Dashboard** loads totals by status and the five most recent orders. These
   are backend aggregates (`GET /api/orders/dashboard`), not calculations in
   Angular.
2. Open the submitted order. Click **Approve**.

**Show:** `PATCH /api/orders/{id}/approve` → `200`, status `APPROVED`, the
lifecycle timeline gains the approved step, and actions disappear because
`APPROVED` is terminal.

**Reject path:** open another `SUBMITTED` order (or create/submit one as the
operator, then return to admin). Click **Reject**, submit an empty reason first
to show validation, then enter "Price outside the approved band". Expected:
`PATCH /api/orders/{id}/reject` → `200`; the reason is persisted and displayed.

**Cancellation:** as operator, cancel one of your own `NEW` or `SUBMITTED`
orders. Expected: `PATCH /api/orders/{id}/cancel` → `200`. Attempting to cancel
an `APPROVED` order returns `409` with "Invalid state transition" — demonstrate
with curl if there is time.

**State machine to say out loud:**

```text
NEW ──submit──▶ SUBMITTED ──approve──▶ APPROVED (terminal)
 │                  │
 │                  ├──reject──▶ REJECTED (terminal)
 └──────cancel──────┴─────────▶ CANCELLED (terminal)
```

"The transition table is a method on the `OrderStatus` enum. The entity calls
it; the service cannot bypass it. Illegal transitions become `409 Conflict`,
not a silent update. `@Version` adds optimistic locking so two admins acting on
the same order cannot both succeed."

## 7. Filtering, pagination, sorting (1 minute)

On **Orders**:

1. Search `GOLD` — request contains `search=GOLD`; order number, symbol and
   name are searched case-insensitively.
2. Filter status `SUBMITTED` — request contains `status=SUBMITTED`.
3. Filter instrument, change sort to **Oldest first** (`createdAt,asc`), change
   page size.
4. Clear filters with **Reset**.

**Say:** "One `BehaviorSubject` holds the query state. Search is debounced; every
change resets to page 0 and triggers one HTTP request. The backend uses a JPA
`Specification` and an allow-list for sortable fields, so `sort` cannot inject
arbitrary properties. `size` is clamped to 1–100."

## 8. Documents (1–2 minutes)

On the detail page, upload a small PDF or PNG (≤ 5 MB).

**Show:** `POST /api/orders/{id}/documents` (multipart) → `201`; the document
appears with name, size, uploader and time. Download it — the browser receives
the original bytes through an authenticated endpoint.

**Say:** "Files are validated by size, content type and sanitised name.
Metadata is in PostgreSQL; bytes go through a `DocumentStorage` interface.
Local filesystem is the demonstrated backend. Setting `STORAGE_TYPE=s3` swaps
in the AWS SDK implementation without touching the service; S3 itself was not
exercised because no bucket was available."

**Show the negative:** try a `.txt` file. Expected: `400` with "Unsupported file
type". The client also blocks it before upload.

## 9. Tests and observability (1 minute)

Run the suites live only if time allows; otherwise show the commands and the
last recorded result.

```bash
cd backend && mvn -B test
cd ../frontend && npm run test:ci
```

**Say:** "Backend: 31 tests. The interesting ones are the state machine, the
ownership/role security test, Feign failure mapping, the dashboard aggregate and
the Redis cache helper. Frontend: 22 specs covering auth, order service
contract, login, order form and list actions."

**Observability:** point at the request id. Every response has `X-Request-Id`;
logs use `[%X{requestId}]`. A 500 response body includes the same id, so you can
grep one log line across gateway and service:

```bash
docker compose logs api-gateway order-service | Select-String "<request-id>"
```

Actuator exposes `health,info,metrics`; health includes component details.

## 10. Close (30 seconds)

> "The value here is the separation: each service owns its database; all
> cross-service reads are explicit (Feign) or asynchronous (Kafka); the gateway
> is the only browser entry point; authorization is enforced twice, in the route
> rules and in the row-level service logic. The known gaps are a non-transactional
> Kafka publish, S3 not exercised, and demo-grade secrets — all documented rather
> than hidden."

## Likely follow-up questions and where to point

| Question | Answer anchor |
| --- | --- |
| "What happens if Instrument Service is down?" | Feign timeout 2 s connect / 3 s read → `InstrumentUnavailableException` → `503`; order is not created. Circuit breaker is a documented extension. |
| "Why not one database?" | Different shapes and ownership: relational lifecycle/constraints vs flexible catalogue. Independent schema evolution and failure isolation. |
| "How does the gateway find a service?" | Eureka registration + `lb://` + Spring Cloud LoadBalancer; gateway has no fixed host/port. |
| "How is the JWT validated in Instrument Service?" | Same secret, `JwtAuthenticationFilter` builds the `SecurityContext`; stateless. Feign forwards the header. |
| "How would you debug a 500?" | Response `requestId` → grep gateway/order logs → exception stack → correlate SQL/Mongo/Feign → reproduce with curl/Swagger. |
| "How would you add a status?" | Enum + transition table + frontend badge/labels + tests; no schema migration because status is a string. Drill in the learning guide. |

## If something fails during the demo

- **Frontend blank / API unreachable:** check gateway health and the nginx (or
  dev-server) proxy. Do not restart everything blindly; read the failing
  container's log.
- **401 immediately after login:** confirm the token is in the request header
  and that all services share `JWT_SECRET`.
- **503 on order creation:** Instrument Service is down or not registered. Show
  the Feign error and explain the designed behaviour.
- **Kafka event missing:** check broker health, then `order-service` logs for
  "Failed to publish". The order still exists; this is the documented
  best-effort trade-off.
- **Redis key missing:** confirm the host/port and that a by-id read occurred;
  list endpoints intentionally do not populate the by-id cache.

Never claim a step passed if it did not. State the failure, the cause and the
fallback — that demonstrates engineering understanding better than a perfect
script.
