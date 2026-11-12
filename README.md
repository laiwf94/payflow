# payflow

The part of a checkout that turns "the customer pays for this order" into calls to a payment
provider, an inventory system and a carrier, and keeps all of them consistent when any one of
them fails, times out or the service itself is killed halfway through.

Java 25, Spring Boot 4, PostgreSQL and Kafka. Each order runs as an orchestrated saga
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

The demo runs seven scenarios against the stack and prints each order's final state next to the fake
provider's ledger: a happy path, a declined card, a carrier refusing the address after capture
(refunded), a capture whose answer is lost, an idempotent retry, a `SIGKILL` of the service while an
authorization is in flight, and a refund of one mug of two whose refund and restock answers are both
lost, with the provider ignoring idempotency keys. That one prints the provider's refunds, the
restock and the mugs on the shelf before and after: one refund of 12.50, one mug back. If ports
8080, 8081 or 8090 are taken, set `PAYFLOW_HOST_PORT`, `PAYFLOW_HOST_MANAGEMENT_PORT` and
`PAYFLOW_HOST_SIMULATOR_PORT` for both commands.

A second instance and a rolling restart:

```console
$ docker compose --profile pair up -d --build
$ ./examples/rolling-restart.py
```

This creates 10,000 orders for two merchants (`acme` and `globex`) across both instances, then
pages each merchant's listing 25 at a time, sending every cursor to the other instance, while
more orders keep arriving and each instance in turn is stopped with `SIGTERM` and started again.
It fails unless every merchant's listing has each of its orders exactly once, none of the other
merchant's, newest first, and unless every order completes with one authorization, one capture,
one reservation and one shipment on the fake providers' ledgers. A request that finds its
instance stopping goes to the other one with the same idempotency key. Python 3.10+, standard
library only; `ORDERS`, `PAGE_LIMIT` and `PAGE_DELAY` change the size and pace.

To run it from source, start Postgres and a Kafka-compatible broker, then:

```console
$ ./gradlew bootJar
$ java -jar build/libs/payflow.jar keygen acme      # prints a key and its config entry
$ PAYFLOW_API_KEYS='acme:<hash>' PAYFLOW_DB_PASSWORD=... java -jar build/libs/payflow.jar
```

Released versions come from a `vX.Y.Z` tag, which runs the whole gate again and then publishes
the jar, a CycloneDX SBOM and checksums on the GitHub release, plus an image for amd64 and arm64:

```console
$ docker pull ghcr.io/laiwf94/payflow:X.Y.Z
$ curl -s localhost:8080/livez | jq -r .components.build.details.version
X.Y.Z
```

A build from source reports the version in `build.gradle.kts` (a `-SNAPSHOT`). To run the demo
or the rolling restart against a published image instead of a local build, set
`PAYFLOW_IMAGE=ghcr.io/laiwf94/payflow:X.Y.Z` and start the stack with `--no-build`. What changed
in each version is in [CHANGELOG.md](CHANGELOG.md).

Behind more than one instance, set `PAYFLOW_CURSOR_SECRETS` (at least 32 characters, the same
everywhere) so a listing cursor issued by one instance is accepted by the next. Set
`PAYFLOW_SECRET_KEYS` too, also the same everywhere: it seals webhook signing secrets at rest
(`id:base64key` with a 32-byte key, e.g. `k1:$(openssl rand -base64 32)`; a comma-separated list
rotates it). Without it each process uses a random key and those secrets don't survive a restart.

Configuration is environment variables; the main ones are listed in
[application.yml](src/main/resources/application.yml) and the limits in
[docs/DESIGN.md](docs/DESIGN.md#limits).

## The order flow

An order moves through reserve stock, authorize the card, capture, and book the shipment. If a
step fails for good (a decline, no stock, an address the carrier refuses) or runs out of time,
the saga undoes what came before it: cancel the shipment, void or refund the payment, release the
stock. The order ends `COMPLETED`, `FAILED`, `CANCELLED` or, after refunds, `PARTIALLY_REFUNDED`
or `REFUNDED`. Each refund is a row of its own, made at the provider by a refund worker and
recorded on the order only once the money has gone back. A refund of lines also puts those
units back into stock, once, after the money.

The interesting failure is the one where nobody knows what happened. A capture that times out
may have moved the money. payflow never retries a payment call blind: when a step runs again,
after a timeout or after a crash, it first asks the provider what exists for the order and acts
on that. Every attempt of a step also carries the same idempotency key. The tests switch the fake
provider's key handling off, and money still moves exactly once.

The saga is crash-safe because its state lives in Postgres, not memory. Workers lease orders
with `FOR UPDATE SKIP LOCKED`, hold no transaction across a network call, and fence every write
with the lease token. A killed process leaves its lease to expire, and the next worker resumes
the step knowing it is a resume. A process stopped with `SIGTERM` doesn't make anyone wait: it
stops claiming, lets running steps finish for up to 5 s, and hands every lease back, so the next
instance picks the orders up on its first poll.

The state machine is enforced by the database too. A CHECK constraint refuses illegal
transitions, and further constraints tie terminal states to money: a completed order is fully
captured, and a failed one kept nothing. Refunds are rows of their own, and a trigger checked at
commit refuses any set of them that would return more than was captured, even when they race.

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
| `POST /v1/orders` | Create an order; the saga starts once it commits. Returns `201` in `PENDING`. An optional `merchant_reference` is unique per merchant. |
| `GET /v1/orders` | The merchant's orders, newest first, up to 100 a page with a signed `next_cursor`. Filters: `state`, `created_gte`, `created_lt`, `currency`, `merchant_reference`. |
| `GET /v1/orders/{id}` | The order, with payment, shipment and failure details, and `refunds`: its ten newest refunds, `has_more`, and the `pending_amount` still on its way back. |
| `POST /v1/orders/{id}/cancel` | Cancel before capture. `202`; the saga unwinds and ends `CANCELLED`. |
| `POST /v1/orders/{id}/refund` | Refund whatever is left of a completed order's capture. `202` with the order as it is; it becomes `REFUNDED` once the provider has returned the money. |
| `POST /v1/orders/{id}/refunds` | Refund part of a completed order: `{"amount": 500}`, or `{"lines": [{"sku": "mug", "quantity": 1}]}` priced from the order. `202` with the refund, `PENDING`; the order becomes `PARTIALLY_REFUNDED` or `REFUNDED` once the provider has returned the money. |
| `GET /v1/orders/{id}/refunds` | The order's refunds, oldest first, each `PENDING`, `SUCCEEDED` or `FAILED` (with the provider's reason). |
| `GET /v1/orders/{id}/events` | The order's history as consumed from Kafka, including `refund.created`, `refund.succeeded` and `refund.failed` with the refund's id and amount. |
| `POST /v1/webhook_endpoints` | Register a webhook URL with `enabled_events` (`["*"]` or event types). `201` with the endpoint's signing `secret`, shown only this once and stored encrypted. At most 16 per merchant. |
| `GET /v1/webhook_endpoints`, `GET /v1/webhook_endpoints/{id}` | The merchant's endpoints, oldest first, or one of them; never the secret. |
| `POST /v1/webhook_endpoints/{id}` | Change `url`, `enabled_events`, `description` or `state` (`ENABLED`, `DISABLED`). |
| `DELETE /v1/webhook_endpoints/{id}` | Delete the endpoint; its secret is erased. |
| `GET /livez`, `GET /readyz` | Liveness, and readiness (database only; Kafka being down doesn't stop orders). |
| `GET :8081/actuator/prometheus` | Metrics, on the loopback management port. |

Errors look like `{"error":{"code":"...","message":"...","request_id":"..."}}` with stable codes:
`invalid_argument` / `invalid_cursor` 400, `unauthenticated` 401, `not_found` 404, `method_not_allowed` 405,
`not_acceptable` 406, `order_not_cancellable` / `order_not_refundable` /
`order_reference_in_use` / `refund_exceeds_captured` / `refund_exceeds_purchased` /
`webhook_endpoint_limit_reached` / `idempotency_key_in_use` 409, `payload_too_large` 413, `unsupported_media_type` 415,
`idempotency_key_reused` 422, `internal` 500, `unavailable` 503. Another merchant's order is a
404, the same as a missing one, and so is another merchant's webhook endpoint. openapi.yaml lists the codes each response can carry.

## Test suite

| What | How |
|---|---|
| Money and stock under chaos | jqwik generates failure rates for every call to the three providers, whether the payment provider honours idempotency keys, orders of one to three lines (a sku may sit on two lines at two prices) with declines, bad addresses, scarce stock and cancels, crash points inside the saga and refund workers, and up to four bursts of amount, line and full refunds whose requests start together on their own threads and race for the same orders. After everything settles, the providers' own ledgers must show: every order terminal, completed orders charged exactly their amount once, failed and cancelled orders holding nothing, no double authorization or capture, the provider's refunds and ledger entries exactly the succeeded refund rows, each line refund restocked once with exactly its lines, line refunds adding up to what their units cost, every refusal justified by what was refunded in the end, stock held equal to what shipped orders bought less what refunds returned, each order's events a legal path, and each refund's `refund.created` and `refund.succeeded` events exactly once, with the order's change right after the success. Deliberately broken versions of the saga and the refunds fail it. |
| Killing the process | The packaged jar runs as a child process and is killed with `SIGKILL` once while an authorization is in flight and once after the provider captured but before the service heard back. It completes with one authorization, one capture and one shipment. |
| Stopping the process | The packaged jar is stopped with `SIGTERM` while an authorization is in flight (it finishes and the lease is released) and while a capture outlasts the shutdown grace (the lease is fenced and expired). With a 90 s lease, the next instance resumes on its first poll and completes with one authorization and one capture. In-process tests cover draining, the deadline, and claims made while stopping. |
| Lost answers | Every money-moving call is applied by the provider and its answer lost, over real HTTP with real read timeouts, with the provider's key handling on and off. A line refund's restock is lost before and after the inventory applies it: the money goes back once and the stock comes back once. |
| State machine | Every one of the 144 state pairs is tried with raw SQL; the database accepts exactly the 16 the code allows. The migration that drops `REFUNDING` refuses to run while an older release still has an order in it. |
| Refund bounds | Raw SQL refunds race each other in `READ COMMITTED` and `SERIALIZABLE`; the database never lets them add up to more than the capture, or return more of a line than was bought. Ten requests for 6.00 of a 25.00 order race through the service, and exactly four are made. Over HTTP, ten amount refunds and six one-mug refunds of a two-mug order race: exactly four and two are made, the rest are `409`, and the provider holds exactly those refunds. A line refund is priced from the order's lines, however its units are split. |
| Refund worker | Partial refunds settle on their own and add up to a full one, each with its `refund.created` and `refund.succeeded` events under versions of their own; a refused refund fails and frees its share; an unavailable provider is retried with backoff; a worker killed after the provider refunded, with key handling off, is resumed without a second refund; a stale lease can't settle; `SIGTERM` hands back a refund still waiting for the provider. |
| Outbox and consumer | Three relays at once keep each order's events in sequence; a crash after sending produces duplicates, never gaps; every event delivered twice lands once; poison records go to the dead-letter topic. |
| Idempotency | Replays, replayed errors, reuse with another body or path, 20 concurrent duplicates creating one order, takeover of an abandoned key, rollback and release on a 5xx. A taken `merchant_reference` is a 409 that replays with its own key, and 20 concurrent creates with one reference make one order. |
| API | Every endpoint and error code over real HTTP, and a validation matrix: zero, negative, bounds and bounds+1, wrong types, decimals, strings for numbers, 5,000-character fields, emoji, RTL and CJK text, script tags, control characters, unknown currencies and countries, oversized and chunked bodies. |
| Listing | jqwik pages through two merchants' orders with random filters and page sizes while orders keep arriving, many tied on `created_at`, and every page boundary goes through a signed cursor: no order is repeated, none that existed when paging began is skipped, and the order is strictly newest first. Over HTTP, two threads insert while 300 orders are paged; a flipped bit, another merchant's cursor or changed filters is a 400. |
| Rolling restart | Outside the gate, against the compose stack: `examples/rolling-restart.py` pages 10,000 orders of two merchants across two instances while each is stopped with `SIGTERM` and started again under load, and checks every listing and every provider ledger. |
| Webhook endpoints | Over HTTP: the secret is in the `201` and nowhere else, neither in any read nor in the database (the endpoint's row and the stored idempotent answer are scanned for it), yet it opens again to sign; a retry replays the same endpoint and secret; changes, deletion, merchant isolation, and ten racing creates against twelve endpoints make exactly four. Raw SQL can't store a bad state, URL, event filter, secret, delivery schedule or attempt. Every bit of a sealed secret is covered by its tag, and a secret moved to another row doesn't open. |
| Contract | The OpenAPI document and Spring's routes must match both ways. Every documented response and every error code it lists is produced by a real request and checked against its schema and headers, nothing undocumented comes back, and the spec's codes are exactly the ones the code can send. |
| Observability | One correlation id followed from the request through the saga worker to the Kafka consumer in the JSON logs; no API key, provider key or `Bearer` token appears in any log line. |

All of it runs against real Postgres and Redpanda through Testcontainers.

```console
$ ./gradlew check          # format, Error Prone, 425 tests, 90% line coverage floor (currently 94%)
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
  security    API keys, and the box that seals secrets at rest
  webhook     merchant webhook endpoints, event types and URL rules
  api         controllers, request ids, errors, body limit
  sim         the fake providers and their control API
src/main/resources/db/migration   Flyway migrations
examples/demo.sh                  failure scenarios against docker compose
examples/rolling-restart.py       10,000 orders paged across two instances while both restart
bench/                            k6 script and benchmark runner
```

## License

MIT
