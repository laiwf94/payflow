# payflow

The part of a checkout that turns "the customer pays for this order" into calls to a payment
provider, an inventory system and a carrier, and keeps all of them consistent when any one of
them fails, times out or the service itself is killed halfway through.

Java 21, Spring Boot, PostgreSQL and Kafka. Each order runs as an orchestrated saga
([Garcia-Molina and Salem, *Sagas*](https://www.cs.cornell.edu/andru/cs711/2002fa/reading/sagas.pdf)),
its events leave through a transactional outbox, and every POST takes a Stripe-style
idempotency key.

```console
$ curl -s localhost:8080/v1/orders -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $(uuidgen)" \
    -H 'Content-Type: application/json' -d '{
      "items": [{"sku": "mug", "quantity": 2, "unit_amount": 1250}],
      "currency": "EUR", "payment_method": "pm_card_ok",
      "shipping_address": {"name": "Alice Example", "line1": "1 Main Street",
                           "city": "Springfield", "postal_code": "12345", "country": "NL"}}'
{"id":"6f1c...","state":"PENDING","amount":2500,"currency":"EUR",...}

$ curl -s localhost:8080/v1/orders/6f1c.../events -H "Authorization: Bearer $KEY" | jq -r '.data[].event_type'
order.created
order.inventory_reserved
order.payment_authorized
order.payment_captured
order.completed
```

## Running locally

```console
$ docker compose up -d --build
$ ./examples/demo.sh
```

The demo runs six scenarios against the stack and prints each order's final state next to the
fake provider's ledger: a happy path, a declined card, a carrier refusing the address after
capture (refunded), a capture whose answer is lost, an idempotent retry, and a `SIGKILL` of the
service while an authorization is in flight. If ports 8080, 8081 or 8090 are taken, set
`PAYFLOW_HOST_PORT`, `PAYFLOW_HOST_MANAGEMENT_PORT` and `PAYFLOW_HOST_SIMULATOR_PORT` for both
commands.

To run it from source, start Postgres and a Kafka-compatible broker, then:

```console
$ ./gradlew bootJar
$ java -jar build/libs/payflow-0.1.0.jar keygen acme      # prints a key and its config entry
$ PAYFLOW_API_KEYS='acme:<hash>' PAYFLOW_DB_PASSWORD=... java -jar build/libs/payflow-0.1.0.jar
```

Configuration is environment variables; the main ones are listed in
[application.yml](src/main/resources/application.yml) and the limits in
[docs/DESIGN.md](docs/DESIGN.md#limits).

## The order flow

An order moves through reserve stock, authorize the card, capture, and book the shipment. If a
step fails for good (a decline, no stock, an address the carrier refuses) or runs out of time,
the saga undoes what came before it: cancel the shipment, void or refund the payment, release the
stock. The order ends `COMPLETED`, `FAILED`, `CANCELLED` or, after a refund, `REFUNDED`.

The interesting failure is the one where nobody knows what happened. A capture that times out
may have moved the money. payflow never retries a payment call blind: when a step runs again,
after a timeout or after a crash, it first asks the provider what exists for the order and acts
on that. Every attempt of a step also carries the same idempotency key. The tests switch the fake
provider's key handling off, and money still moves exactly once.

The saga is crash-safe because its state lives in Postgres, not memory. Workers lease orders
with `FOR UPDATE SKIP LOCKED`, hold no transaction across a network call, and fence every write
with the lease token. A killed process leaves its lease to expire, and the next worker resumes
the step knowing it is a resume.

The state machine is enforced by the database too. A CHECK constraint refuses illegal
transitions, and further constraints tie terminal states to money: a completed order is fully
captured, and a failed one kept nothing.

Events leave through a transactional outbox
([Richardson](https://microservices.io/patterns/data/transactional-outbox.html)): each state
change writes its event in the same transaction, and a relay publishes to Kafka in order per
order, at least once. The consumer deduplicates through an inbox table.

Idempotency keys follow Stripe's rules
([Leach, *Implementing Stripe-like Idempotency Keys in Postgres*](https://brandur.org/idempotency-keys)).
The same key and body replays the stored response, errors included; a different body is a 422;
a concurrent duplicate is a 409. The response is stored in the same transaction as the order.

Retries use exponential backoff with full jitter, each forward step has a deadline, and
compensations never give up. Money is `long` minor units with an ISO 4217 currency, and every
sum is overflow-checked. The fake providers fail like real ones, with errors, latency and calls
that are applied but never answered, and they run in-process or as their own container.

Around the saga sit hashed API keys compared in constant time and scoped per merchant, strict
JSON with size limits, JSON logs with a correlation id that follows an order into Kafka headers,
Prometheus metrics, liveness and readiness probes, and actuator on a loopback port.

## HTTP API

All `/v1` endpoints take `Authorization: Bearer <key>`. Every POST needs an `Idempotency-Key`.
The full contract is [openapi.yaml](src/main/resources/static/openapi.yaml), also served at
`/openapi.yaml`.

| Endpoint | Purpose |
|---|---|
| `POST /v1/orders` | Create an order; the saga starts once it commits. Returns `201` in `PENDING`. |
| `GET /v1/orders/{id}` | The order, with payment, shipment and failure details. |
| `POST /v1/orders/{id}/cancel` | Cancel before capture. `202`; the saga unwinds and ends `CANCELLED`. |
| `POST /v1/orders/{id}/refund` | Refund a completed order in full. `202`; ends `REFUNDED`. |
| `GET /v1/orders/{id}/events` | The order's history as consumed from Kafka. |
| `GET /livez`, `GET /readyz` | Liveness, and readiness (database only; Kafka being down doesn't stop orders). |
| `GET :8081/actuator/prometheus` | Metrics, on the loopback management port. |

Errors look like `{"error":{"code":"...","message":"...","request_id":"..."}}` with stable codes:
`invalid_argument` 400, `unauthenticated` 401, `not_found` 404, `method_not_allowed` 405,
`not_acceptable` 406, `order_not_cancellable` / `order_not_refundable` /
`idempotency_key_in_use` 409, `payload_too_large` 413, `unsupported_media_type` 415,
`idempotency_key_reused` 422, `internal` 500, `unavailable` 503. Another merchant's order is a
404, the same as a missing one.

## Test suite

| What | How |
|---|---|
| Money and stock under chaos | jqwik generates failure rates for every call to the three providers, whether the payment provider honours idempotency keys, orders with declines, bad addresses, scarce stock, cancels and refunds, and crash points inside the worker. After everything settles, the providers' own ledgers must show: every order terminal, completed orders charged exactly their amount once, failed and cancelled orders holding nothing, no double authorization or capture, stock balanced, and each order's events a legal path. Deliberately broken versions of the saga fail it. |
| Killing the process | The packaged jar runs as a child process and is killed with `SIGKILL` once while an authorization is in flight and once after the provider captured but before the service heard back. It completes with one authorization, one capture and one shipment. |
| Lost answers | Every money-moving call is applied by the provider and its answer lost, over real HTTP with real read timeouts, with the provider's key handling on and off. |
| State machine | Every one of the 144 state pairs is tried with raw SQL; the database accepts exactly the 15 the code allows. |
| Outbox and consumer | Three relays at once keep each order's events in sequence; a crash after sending produces duplicates, never gaps; every event delivered twice lands once; poison records go to the dead-letter topic. |
| Idempotency | Replays, replayed errors, reuse with another body or path, 20 concurrent duplicates creating one order, takeover of an abandoned key, rollback and release on a 5xx. |
| API | Every endpoint and error code over real HTTP, and a validation matrix: zero, negative, bounds and bounds+1, wrong types, decimals, strings for numbers, 5,000-character fields, emoji, RTL and CJK text, script tags, control characters, unknown currencies and countries, oversized and chunked bodies. |
| Contract | The OpenAPI document and Spring's routes must match both ways; every documented response is produced by a real request and checked against its schema. |
| Observability | One correlation id followed from the request through the saga worker to the Kafka consumer in the JSON logs; no API key, provider key or `Bearer` token appears in any log line. |

All of it runs against real Postgres and Redpanda through Testcontainers.

```console
$ ./gradlew check          # format, Error Prone, 242 tests, 90% line coverage floor (currently 95%)
$ ./bench/run.sh           # end-to-end benchmark
```

## Load results

`bench/run.sh` on an Apple M4 Max: payflow runs natively with the in-process providers (no added
latency), Postgres and Redpanda run in Docker Desktop, k6 creates orders at a fixed rate for 30 s
per step. Saga latency is measured in Postgres, from an order's creation to its completion. Each
order makes four provider calls and around a dozen commits, counting the idempotency key, the
saga transitions and the consumer's inbox writes.

| Offered load | Completed | API p50 / p99 | Saga p50 / p99 |
|---|---|---|---|
| 250 orders/s | 250/s | 19.0 / 100.5 ms | 59.8 / 288.8 ms |
| 500 orders/s | 500/s | 4.1 / 43.4 ms | 12.9 / 152.5 ms |
| 750 orders/s | 750/s | 16.0 / 239.6 ms | 65.4 / 788.0 ms |
| 1,000 orders/s | 959/s | 29.2 / 272.4 ms | 142.6 / 1,571 ms |

That is one run. The machine was shared with other work, which moves the numbers: another run
held only 668 orders/s at the same offered load, with saga latency turning into seconds of
queueing. Sampling `pg_stat_activity` at saturation shows the backends waiting on `WALWrite` and
`WalSync`, the Postgres write-ahead log on Docker Desktop's virtual disk, and the service's
connection pool full of requests waiting on those commits.

## Code and design notes

The reasoning behind all of this is in [docs/DESIGN.md](docs/DESIGN.md): the state machine, how
the saga runs and recovers, unknown outcomes, the outbox and inbox, idempotency keys, failure
modes, limits, security, the dependencies and why each is there, and what would change at a
larger scale. The code is laid out like this:

```text
src/main/java/io/github/laiwf94/payflow/
  money       Money: minor units, ISO 4217, checked arithmetic
  order       Order, the state machine, the repository and the service the API calls
  saga        worker, leases, backoff, step contract; saga/steps holds each step
  gateway     payment, inventory and shipping interfaces, HTTP adapters, outcome rules
  outbox      outbox writer and Kafka relay
  timeline    idempotent consumer and the order timeline
  idempotency Idempotency-Key filter and store
  security    API keys
  api         controllers, request ids, errors, body limit
  sim         the fake providers and their control API
src/main/resources/db/migration   Flyway migrations
examples/demo.sh                  failure scenarios against docker compose
bench/                            k6 script and benchmark runner
```

## License

MIT
