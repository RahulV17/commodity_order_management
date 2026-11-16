# Verification log

This file records what was actually executed against the running stack, with the
observed results. It exists so that no claim in the README or the learning guide
is stronger than the evidence. It is not a test report for the automated suites;
those are covered in the README and in the source tree.

- Date: 2026-11-16
- Host: Windows, Docker Desktop engine 29.5.2
- Compose project: `commodity-order-management`
- Images built from the committed tree (backend shared Dockerfile with
  `ARG SERVICE`, Angular production build behind nginx)

## 1. Build and automated tests

| Check | Command | Result |
| --- | --- | --- |
| Backend compile + tests | `mvn -B test` (in `backend/`) | 31 tests passed |
| Frontend production build | `npm run build` (in `frontend/`) | success; initial bundle 340.41 kB raw / 97.03 kB transfer |
| Frontend unit tests | `npm run test:ci` | 22 of 22 passed (ChromeHeadless) |
| Compose file validity | `docker compose config --quiet` | success |
| Images | `docker compose build` | five images built |

The first parallel image build exhausted the Docker daemon on this host
(`failed to receive status ... EOF`). Building with
`COMPOSE_PARALLEL_LIMIT=1` succeeded. This is documented in
[`known-issues.md`](known-issues.md).

## 2. Container health

`docker compose up -d` brought up nine containers. At the end of the run:

```
com-eureka             Up (healthy)
com-api-gateway        Up (healthy)
com-order-service      Up (healthy)
com-instrument-service Up (healthy)
com-frontend           Up (healthy)
com-postgres           Up (healthy)
com-mongo              Up (healthy)
com-redis              Up (healthy)
com-kafka              Up (healthy)
```

The frontend check initially failed on an IPv6/Linux quirk (busybox `wget`
resolving `localhost` to `::1` while nginx listened on IPv4); it now probes
`127.0.0.1` and reports healthy. This is recorded in `known-issues.md`
item 20.

Startup on this 8 GB host is slow (the JVM services took roughly two minutes to
become healthy while the image build was running). No application error was
observed once startup completed.

## 3. Service discovery

`GET http://localhost:8761/eureka/apps` (with `Accept: application/json`)
returned three applications, each with one instance in status `UP`:

```
API-GATEWAY         1 instance  api-gateway:8080
INSTRUMENT-SERVICE  1 instance  instrument-service:8082
ORDER-SERVICE       1 instance  order-service:8081
```

`GET http://localhost:8080/actuator/health` returned `{"status":"UP"}` and
reported the discovery client as `UP`.

## 4. End-to-end API flow (through the gateway)

All calls used `http://localhost:8080`; no request went directly to a service
port. Tokens were obtained from `POST /api/auth/login` with the seeded demo
users (`admin/Admin@123`, `operator/Operator@123`).

| Step | Request | Observed |
| --- | --- | --- |
| Login | `POST /api/auth/login` (admin) | `200`, access token issued |
| Login | `POST /api/auth/login` (operator) | `200`, access token issued |
| Instruments | `GET /api/instruments` | `200`, 4 seeded instruments, all active |
| Create | `POST /api/orders` GOLD-001 BUY 10 @ 15000 (operator) | `201`, order `ORD-44D7E063`, status `NEW` |
| Submit | `PATCH /api/orders/2/submit` (operator) | `200`, status `SUBMITTED` |
| Operator approve attempt | `PATCH /api/orders/2/approve` (operator) | `403` (role blocked) |
| Duplicate submit | `PATCH /api/orders/2/submit` (operator) | `409` (invalid transition) |
| Approve | `PATCH /api/orders/2/approve` (admin) | `200`, status `APPROVED` |
| Reject path | create SILVER-001 SELL 25 @ 900, submit, reject with reason | `201`, `200`, `200`; final status `REJECTED`, reason stored |
| Cancel path | create CRUDE-001 BUY 5 @ 6500, cancel (operator) | `201`, `200`; final status `CANCELLED` |
| List | `GET /api/orders?page=0&size=2&sort=createdAt,desc` | 2 items on the page, 4 total, 2 pages |
| Filter | `GET /api/orders?status=APPROVED` | 1 order |
| Search | `GET /api/orders?search=SILVER` | 1 order |
| Dashboard as operator | `GET /api/orders/dashboard` (operator) | `403` (role blocked) |
| Dashboard as admin | `GET /api/orders/dashboard` (admin) | `200`; total 4, NEW 1, SUBMITTED 0, APPROVED 1, REJECTED 1, CANCELLED 1 |
| Read-back | `GET /api/orders/2`, `/3`, `/4` | `APPROVED`, `REJECTED`, `CANCELLED` |

The database already contained one `NEW` order from an earlier smoke test, so
the dashboard total of 4 includes it. That is visible in the `NEW` count and is
not counted as part of the flow above.

## 5. Documents

A 70-byte PNG was uploaded to order 3 as its owner:

| Check | Result |
| --- | --- |
| `POST /api/orders/3/documents` (`multipart/form-data`, field `file`) | `201`; id 1, `e2e-proof.png`, `image/png`, 70 bytes |
| `GET /api/orders/3/documents` | 1 document |
| `GET /api/orders/3/documents/1/download` | 70 bytes; SHA-256 equal to the uploaded file |
| Same download without a token | `401` |

The default storage type is `local`; the download was served through the
authenticated controller, not from a public path. The S3 branch was not
exercised against a real bucket.

## 6. Kafka

The broker is Redpanda (Kafka wire protocol); see `known-issues.md` item 19.

```
docker exec com-kafka rpk topic list
order-events  3 partitions  1 replica
```

Consuming from the beginning returned the expected payloads, for example:

```json
{"eventId":"89ee0c36-01d2-4aa6-8329-65600442d526","eventType":"ORDER_CREATED",
 "orderId":"ORD-44D7E063","instrumentId":"GOLD-001","quantity":10,
 "orderType":"BUY","createdBy":"operator","occurredAt":1794792560.860999261}
```

Three events were read (GOLD-001, SILVER-001, CRUDE-001), matching the three
orders created in the flow. `orderId` carries the human-readable order number.

## 7. Redis cache-aside cycle

The list endpoint reads MongoDB directly; the by-id endpoint is the cache-aside
path (`instrument:<id>`, TTL 300 s). The sequence was executed on `GAS-001`:

| Step | Observation |
| --- | --- |
| `GET /api/instruments/GAS-001` | service log `Cache MISS for instrument GAS-001 - reading MongoDB`; key created |
| `GET /api/instruments/GAS-001` again | service log `Cache HIT for instrument GAS-001` |
| `docker exec com-redis redis-cli TTL instrument:GAS-001` | `271` (seconds remaining, below the 300 s TTL) |
| Redis value | JSON with `activityCount: 0` and the seeded instrument fields |
| Create order on GAS-001 | Kafka consumer log `Consumed ORDER_CREATED ... instrumentId=GAS-001`, then `Cache eviction for instrument:GAS-001 (deleted=true)` |
| `EXISTS instrument:GAS-001` immediately after | key removed by the eviction |
| `GET /api/instruments/GAS-001` after the event | log `Cache MISS`; response `activityCount: 1` and a populated `lastOrderAt`, then the key was re-created |

This is the cache-aside behaviour described in the learning guide: read-through
population, explicit TTL, and event-driven invalidation. Redis data is
intentionally not persisted (no volume), so a Redis restart starts cold.

## 8. Database evidence

```
 id | order_number | instrument_id | instrument_symbol | quantity |   price    |  status   | created_by
----+--------------+---------------+-------------------+----------+------------+-----------+------------
  1 | ORD-FA777AC9 | GOLD-001      | GOLD              |   5.0000 |  6200.5000 | NEW       | operator
  2 | ORD-44D7E063 | GOLD-001      | GOLD              |  10.0000 | 15000.0000 | APPROVED  | operator
  3 | ORD-C374512E | SILVER-001    | SILVER            |  25.0000 |   900.0000 | REJECTED  | operator
  4 | ORD-83EB6714 | CRUDE-001     | CRUDEOIL          |   5.0000 |  6500.0000 | CANCELLED | operator
  5 | ORD-BE534CEB | GAS-001       | NATGAS            |   3.0000 |   320.0000 | CANCELLED | operator
```

Order 1 is the earlier smoke-test order. Order 5 was created during the cache
eviction check above and then cancelled; it is the only order added after the
E2E table in section 4 was recorded.

## 9. Documentation and health endpoints

| Endpoint | Result |
| --- | --- |
| `http://localhost:8081/v3/api-docs` | `200` |
| `http://localhost:8082/v3/api-docs` | `200` |
| `http://localhost:8081/swagger-ui/index.html` | `200` |
| `http://localhost:8082/swagger-ui/index.html` | `200` |
| `http://localhost:8081/actuator/health` | `200` |
| `http://localhost:8082/actuator/health` | `200` |
| `http://localhost:8080/actuator/health` | `200`, discovery client `UP` |
| `http://localhost:4200/` | `200` (Angular shell via nginx) |
| `http://localhost:4200/api/instruments` without a token | `401` (proxy reaches the gateway and security applies) |

The gateway test confirms the nginx `/api` proxy works, not just the backend
routes.

## 10. Not verified

- A real AWS S3 upload (the code path exists; no bucket or credentials were
  used).
- A production deployment of any kind.
- A scripted browser click-through of every Angular screen. The production
  build, unit tests and the API flow above were executed; UI behaviour is
  covered by the Jasmine specs rather than a browser automation run.
- Kafka delivery under broker failure, duplicate consumption, and multi-instance
  behaviour.
