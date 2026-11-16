# API documentation

All browser traffic goes through the gateway at `http://localhost:8080` (or the
Angular origin, which proxies `/api`). Each Spring service also exposes its own
Swagger UI for exploration:

| Service | Swagger UI | OpenAPI JSON | Actuator health |
| --- | --- | --- | --- |
| order-service | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs | http://localhost:8081/actuator/health |
| instrument-service | http://localhost:8082/swagger-ui.html | http://localhost:8082/v3/api-docs | http://localhost:8082/actuator/health |
| api-gateway | — | — | http://localhost:8080/actuator/health |
| eureka-server | — | — | http://localhost:8761/actuator/health |

`GET /actuator/health` is public. Swagger and the OpenAPI document are public in
the service security configuration for demo convenience; everything under
`/api/**` except login requires a bearer token.

## Authentication

### `POST /api/auth/login`

```json
{
  "username": "admin",
  "password": "Admin@123"
}
```

Response `200`:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 7200,
  "user": { "id": 1, "username": "admin", "role": "ADMIN" }
}
```

Errors: `400` for blank fields, `401` for bad credentials or a disabled user.
The demo users are `admin/Admin@123` and `operator/Operator@123`; passwords can
be overridden and seeding disabled through environment variables.

### `GET /api/auth/me`

Returns the authenticated user from the token subject:

```json
{ "id": 2, "username": "operator", "role": "OPERATIONS" }
```

Send `Authorization: Bearer <token>` on every following request.

## Orders

Base path: `/api/orders`.

### `GET /api/orders`

| Parameter | Type | Default | Notes |
| --- | --- | --- | --- |
| `instrumentId` | string | — | Exact catalogue id, e.g. `GOLD-001` |
| `status` | enum | — | `NEW`, `SUBMITTED`, `APPROVED`, `REJECTED`, `CANCELLED` |
| `search` | string | — | Case-insensitive order number, instrument symbol or name |
| `page` | int | `0` | Zero-based; negative values are clamped |
| `size` | int | `10` | Clamped to 1–100 |
| `sort` | string | `createdAt,desc` | Allow-list: `createdAt`, `updatedAt`, `orderNumber`, `price`, `quantity`, `status`; suffix `,asc` or `,desc` |

Operations users only receive their own orders. Administrators receive every
order. The rule is applied inside the JPA query.

Response `200`:

```json
{
  "content": [
    {
      "id": 42,
      "orderNumber": "ORD-44D7E063",
      "instrumentId": "GOLD-001",
      "instrumentSymbol": "GOLD",
      "instrumentName": "Gold",
      "orderType": "BUY",
      "quantity": 10,
      "price": 15000,
      "status": "SUBMITTED",
      "remarks": "Sample order",
      "rejectionReason": null,
      "createdBy": "operator",
      "createdAt": "2026-01-01T10:00:00Z",
      "updatedAt": "2026-01-01T10:01:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

### `POST /api/orders`

```json
{
  "instrumentId": "GOLD-001",
  "orderType": "BUY",
  "quantity": 10,
  "price": 15000,
  "remarks": "Sample order"
}
```

Validation: instrument id non-blank (≤ 32); type required; quantity and price
positive with at most 4 decimals and 15 integer digits; remarks ≤ 500. The
instrument is re-read through Feign and must be active. The order is stored as
`NEW`, an `ORDER_CREATED` event is published to `order-events`, and the response
is `201` with the `OrderResponse` shape shown above.

| Failure | Status | Message |
| --- | --- | --- |
| Bean Validation | 400 | `Validation failed` + `fieldErrors` |
| Unknown instrument | 404 | `Instrument not found: ...` |
| Inactive instrument | 422 | `Instrument is inactive: ...` |
| Instrument-service unreachable | 503 | `Instrument service is unavailable...` |
| Duplicate/constraint conflict | 409 | `The request conflicts with existing data` |

### `GET /api/orders/{id}`

Returns one order. Operations users may only read their own; otherwise `403`.

### `PATCH /api/orders/{id}/submit`

Custom explicit lifecycle step: `NEW → SUBMITTED`. Owner or admin. A second
submit returns `409` because `SUBMITTED` cannot transition to `SUBMITTED`.

### `PATCH /api/orders/{id}/approve`

ADMIN only, `SUBMITTED → APPROVED`. Operators receive `403`; any other current
state receives `409`.

### `PATCH /api/orders/{id}/reject`

ADMIN only, `SUBMITTED → REJECTED`.

```json
{ "reason": "Price outside the approved band" }
```

The reason is required (5–500 characters in the UI; the API enforces non-blank
and ≤ 500) and stored on the order.

### `PATCH /api/orders/{id}/cancel`

Owner or admin, `NEW` or `SUBMITTED → CANCELLED`. Approved, rejected and
cancelled orders cannot be cancelled again (`409`).

### `GET /api/orders/dashboard`

ADMIN only.

```json
{
  "totalOrders": 12,
  "pendingOrders": 3,
  "approvedOrders": 6,
  "rejectedOrders": 1,
  "cancelledOrders": 1,
  "newOrders": 1,
  "ordersByStatus": {
    "NEW": 1,
    "SUBMITTED": 3,
    "APPROVED": 6,
    "REJECTED": 1,
    "CANCELLED": 1
  },
  "recentOrders": [ { "id": 12, "orderNumber": "ORD-...", "...": "..." } ]
}
```

`pendingOrders` counts `SUBMITTED`; `newOrders` is separate.

## Order documents

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/orders/{orderId}/documents` | Multipart upload, field name `file` |
| GET | `/api/orders/{orderId}/documents` | List metadata |
| GET | `/api/orders/{orderId}/documents/{documentId}/download` | Authenticated byte stream |

Upload constraints: max 5 MB; MIME types `application/pdf`, `image/png`,
`image/jpeg`, DOCX and XLSX. Owner or admin only. Oversized requests return
`413`; unsupported types return `400`. Metadata is stored in PostgreSQL and the
bytes go to local storage or S3 depending on `app.storage.type`.

`GET .../documents` returns:

```json
[
  {
    "id": 7,
    "fileName": "invoice.pdf",
    "contentType": "application/pdf",
    "sizeBytes": 48211,
    "uploadedBy": "operator",
    "uploadedAt": "2026-01-01T10:05:00Z"
  }
]
```

The download sets `Content-Disposition: attachment` and the stored content type;
there is no public object URL.

## Instruments

Base path: `/api/instruments`. Reads are available to any authenticated user;
writes are ADMIN only.

### `GET /api/instruments?search=gold&active=true`

Returns an array sorted by symbol. `search` matches name or symbol
case-insensitively; `active` filters active/inactive.

```json
[
  {
    "id": "GOLD-001",
    "symbol": "GOLD",
    "name": "Gold",
    "category": "PRECIOUS_METAL",
    "indicativePrice": 15000,
    "active": true,
    "activityCount": 3,
    "lastOrderAt": "2026-01-01T10:00:00Z",
    "createdAt": "2025-12-31T10:00:00Z",
    "updatedAt": "2026-01-01T10:00:00Z"
  }
]
```

### `GET /api/instruments/{id}`

Cache-aside read. Returns the same shape. The first call logs `Cache MISS` and
populates Redis; a repeat within the TTL logs `Cache HIT`. Unknown ids return
`404`.

### `POST /api/instruments`

ADMIN only.

```json
{
  "id": "PLATINUM-001",
  "symbol": "PLATINUM",
  "name": "Platinum",
  "category": "PRECIOUS_METAL",
  "indicativePrice": 32000,
  "active": true
}
```

`id` becomes the MongoDB `_id` and must be 3–32 uppercase letters, digits or
hyphens; `symbol` is 2–16 uppercase letters/digits. Duplicate id or symbol
returns `409`. Response `201`.

### `PUT /api/instruments/{id}`

ADMIN only; updates symbol, name, category and indicative price. The id is
immutable. Evicts the Redis key.

### `PATCH /api/instruments/{id}/status`

ADMIN only.

```json
{ "active": false }
```

Deactivating prevents new orders (Feign validation returns 422) while keeping
existing orders readable. Evicts the Redis key.

## Error format

Every handled error uses the same `ApiError` body:

```json
{
  "timestamp": "2026-01-01T10:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Order ORD-44D7E063 cannot transition from APPROVED to CANCELLED",
  "path": "/api/orders/42/cancel",
  "requestId": "f0d1...",
  "fieldErrors": null
}
```

`fieldErrors` is populated for Bean Validation failures:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "fieldErrors": { "quantity": "Quantity must be positive" }
}
```

Status codes used:

| Code | Meaning in this API |
| --- | --- |
| 200 | Successful read/update |
| 201 | Order, instrument or document created |
| 400 | Validation, malformed body, invalid document |
| 401 | Missing/expired/invalid JWT or bad login |
| 403 | Role or ownership rule rejected the caller |
| 404 | Order, instrument, document or user not found |
| 409 | Invalid lifecycle transition, duplicate instrument, optimistic-lock conflict |
| 413 | Document larger than 5 MB |
| 422 | Instrument exists but is inactive |
| 503 | Instrument-service unavailable from order-service |
| 500 | Unexpected error; details are logged, generic message returned |

## Example session with curl

```bash
BASE=http://localhost:8080

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator","password":"Operator@123"}' \
  | jq -r .accessToken)

# Create an order (returns NEW)
curl -s -X POST "$BASE/api/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"instrumentId":"GOLD-001","orderType":"BUY","quantity":10,"price":15000,"remarks":"curl demo"}'

# Submit it
curl -s -X PATCH "$BASE/api/orders/1/submit" -H "Authorization: Bearer $TOKEN"

# List with filters and pagination
curl -s "$BASE/api/orders?status=SUBMITTED&page=0&size=10&sort=createdAt,desc" \
  -H "Authorization: Bearer $TOKEN"
```

On PowerShell, use `Invoke-RestMethod` or quote JSON carefully. The Angular UI
and Swagger UI are easier for a live demonstration.

The gateway forwards an incoming `X-Request-Id` or generates one, returns it in
the response header, and includes it in the MDC log pattern (`[requestId]`). Use
it to correlate the browser request with order-service and instrument-service
logs.
