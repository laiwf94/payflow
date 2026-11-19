# Changelog

Notable changes to payflow, newest first. Versions follow [semantic versioning](https://semver.org);
before 1.0 a minor version may break the API, and this file says so when it does. A release's
notes on GitHub link here.

## Unreleased (0.2.0)

### Breaking

- Java 25 and Spring Boot 4 (Spring Framework 7, Jackson 3, Tomcat 11, Flyway 12). The image runs
  on distroless Java 25. Code that embedded payflow's classes needs the same.
- A JSON number or boolean where the API expects a string (`"sku": 123`,
  `"merchant_reference": 7`) is now `400 invalid_argument`; it used to be coerced to a string
  (#76).
- The order state `REFUNDING` is gone (#24). `POST /v1/orders/{id}/refund` now answers `202`
  with the order still `COMPLETED` (or `PARTIALLY_REFUNDED`); it becomes `REFUNDED` when the
  provider has returned the money, with no state in between, and there is no `order.refunding`
  event. Asking again while that refund is pending is a `202` that starts nothing new.
- Upgrading from 0.1.x: migration V14 refuses to run while any order is still `REFUNDING`. Let
  the 0.1.x instances finish those refunds (seconds), then start this release again.
- The payment provider adapter expects refunds as objects with an id and the caller's
  reference, listed per payment (`GET /payments/{id}/refunds`), as the common providers have
  them; the simulator implements this.

### Added

- `GET /v1/orders`: the merchant's orders, newest first, with keyset pagination and an
  HMAC-signed opaque cursor (`next_cursor`, `has_more`, `limit` 1-100). Filters: `state`,
  `created_gte`, `created_lt`, `currency` and `merchant_reference`. A forged, foreign or reused
  cursor is the new `400 invalid_cursor`. `PAYFLOW_CURSOR_SECRETS` takes a comma-separated list
  so the secret can rotate; every instance must share it.
- `merchant_reference` on `POST /v1/orders`: optional, 1-128 printable ASCII characters, unique
  per merchant, returned on the order and in `order.created`. A taken reference is the new
  `409 order_reference_in_use`, which replays under its idempotency key like any other answer.
- Tagged releases: a `vX.Y.Z` or `vX.Y.Z-rc.N` tag on main reruns the gate and publishes the jar,
  a CycloneDX 1.6 SBOM and `SHA256SUMS` on a GitHub release, and a linux/amd64 and linux/arm64
  image at `ghcr.io/laiwf94/payflow:X.Y.Z`.
- Durable timers (#26): a `timers` table, `TimerService` to schedule, reschedule and cancel a
  timer in the caller's transaction, and `TimerWorker`, which claims due timers with `FOR UPDATE
  SKIP LOCKED`, fences each fire on its lease token and fires it in the same transaction as its
  handler's writes, so it fires once across crashes, restarts and instances. Handlers that fail
  are retried with full-jitter backoff. Settings under `payflow.timers.*`.
- Holds that run out (#26): an order whose payment isn't captured within
  `payflow.holds.reservation-window` (15 min) of reserving its stock, or
  `payflow.holds.payment-window` (1 h) of being created, is cancelled on its own. It goes through
  the same compensation as a merchant's cancel (any authorization voided, stock released) and
  ends `CANCELLED`. The order has a new `cancel_reason` (`requested`, `reservation_expired` or
  `payment_expired`), also in the `order.cancel_requested` event's payload; orders cancelled
  before this change have none. New counter `payflow.holds.expired{reason,outcome}`.
- Manual capture (#26): `capture_mode` on `POST /v1/orders` (`automatic`, the default, or
  `manual`). A manual order waits in `PAYMENT_AUTHORIZED`, holding its stock, until the new
  `POST /v1/orders/{id}/capture`, which answers `202` with `capture_requested: true` and writes
  the new `order.capture_requested` event; it can't be captured otherwise (`409
  order_not_capturable`). Every authorized order now shows `payment.authorization_expires_at`,
  `payflow.holds.authorization-lifetime` (7 days) after authorizing began; a manual order not
  captured by then fails as `authorization_expired`, voided and with its stock released. Events
  of a manual order carry `capture_mode: manual`. New counter `payflow.saga.waits`. Clients that
  switch over webhook event types or error codes should add the new ones.
- The running version on `/livez` (`components.build.details.version`) and in the
  `payflow X.Y.Z ready` log line.
- Clean shutdown: on `SIGTERM` the saga worker stops claiming, lets running steps finish for up
  to `payflow.saga.shutdown-grace` (5 s), and hands every lease back, so the next instance
  resumes on its first poll instead of waiting out the lease. New counter
  `payflow.saga.handed_back`.
- Groundwork for partial refunds (#24): a `refunds` table with one row per refund, and a new order
  state, `PARTIALLY_REFUNDED`, between `COMPLETED` and `REFUNDED`. The database refuses, at
  commit and under concurrency, any refunds of an order that add up to more than it captured or
  return more of a line than it bought. Clients that switch over every order state should add the
  new one.
- Refunds run one row at a time (#24). A full refund is a refund of whatever is left of the
  capture, and refunds of one order can settle side by side. A new refund worker, on the saga
  worker's settings, leases due refunds, asks the provider whether the refund exists before
  making it (so a lost answer or a crash never refunds twice, with or without provider-side
  idempotency keys), and records success, refusal or a retry in one fenced write. A refused
  refund is `FAILED` with the provider's reason and frees its share of the capture. New metrics
  `payflow.refund.attempt`, `payflow.refunds.settled`, `payflow.refunds.retries` and
  `payflow.refunds.handed_back`.
- Partial and line-item refunds over the API (#24). `POST /v1/orders/{id}/refunds` takes an
  `amount` or `lines` (sku and quantity, priced from the order) and answers `202` with the
  refund, `PENDING`; `GET /v1/orders/{id}/refunds` lists an order's refunds. New codes
  `409 refund_exceeds_captured` and `409 refund_exceeds_purchased` say how much is left.
- Line-item refunds return their stock (#24). Once the money is back, the refund worker restocks
  the refund's lines from the order's reservation, idempotent on the refund's id, so a lost
  answer or a crash never returns stock twice. The inventory adapter has a new `restock` call
  (`POST /reservations/{id}/restocks` with `{restock_id, lines}`, refusing more than the
  reservation still holds), and the simulator implements it.
- Refund events and refund details (#24). Each refund writes `refund.created` and then
  `refund.succeeded` or `refund.failed` into its order's event stream, with a `refund` object
  (`id`, `amount`, `lines`, `state`, `failure_code`); a success is followed by the order's
  `order.partially_refunded` or `order.refunded`. The timeline shows each refund event's id,
  amount and failure code. Every order the API returns has `refunds`: its ten newest refunds,
  `has_more` and `pending_amount`, read in the same snapshot as the order. A refund event takes
  a `seq` of its own and moves the order's `updated_at`, so consumers that expect only `order.*`
  events should skip types they don't know.
- `examples/demo.sh` scenario 7 refunds one mug of two by line while the refund's and the
  restock's answers are lost and the provider ignores idempotency keys, then prints the order,
  its refunds, the provider's refunds and ledger, the restock and the shelf (#24). The
  simulator's `GET /sim/control/ledger` now includes the payments' `refunds` and the
  reservation's `restocks`, and `GET /sim/control/stock/{sku}` reads a SKU's stock.
- Webhook endpoints (#25). `POST /v1/webhook_endpoints` registers a URL with an event filter
  (`enabled_events`: `["*"]` or any of the order and refund event types) and answers `201` with
  the endpoint's signing secret, `whsec_...`, shown only that once. `GET` lists them or reads
  one, `POST /v1/webhook_endpoints/{id}` changes `url`, `enabled_events`, `description` or
  `state` (`ENABLED`, `DISABLED`), and `DELETE` removes one. A merchant has at most 16; one more
  is the new `409 webhook_endpoint_limit_reached`. Migration V18 adds `webhook_endpoints`,
  `webhook_deliveries` and `webhook_attempts`.
- Webhooks go to public addresses only (#25). A URL whose host is, or resolves to, a loopback,
  private, link-local, cloud metadata, CGNAT, multicast, documentation or reserved address,
  including one wrapped in IPv6 (`::ffff:169.254.169.254`, NAT64, 6to4), is a `400` at
  registration and on update. A host written as a number in any other form than `a.b.c.d`
  (`2130706433`, `0177.0.0.1`) is a `400` too. The new sender (`SafeHttpSender`) resolves once
  per attempt, checks every address, connects to a checked one with the URL's host in `Host` and
  SNI, follows no redirects, and caps an attempt at 10 s, 16 KiB of head and 1 KiB of body
  (`payflow.webhooks.*`). `PAYFLOW_WEBHOOK_ALLOWED_NETWORKS` lets listed networks through for a
  development stack. A head that passes 16 KiB through a run of interim answers is a `protocol`
  failure like any other oversized head, and a chunk size or `Content-Length` that isn't plain
  unsigned digits ends the logged body there instead of being obeyed.
- [docs/webhooks.md](docs/webhooks.md), the guide for whoever writes the receiving end (#25): the
  headers and the event fields, the signature scheme with a test vector, verification in Python,
  Node.js, Java and with openssl, a complete standard-library receiver, how to answer, duplicates
  and ordering, retries and replays, and rotating the secret without dropping an event. The test
  suite runs every example in it against payflow's own signer.
- An end-to-end webhook test (#25): a stand-in merchant in a container, verifying with the
  guide's Python example, gets a completed, refunded order's and a declined order's every event
  exactly once and in `seq` order, through three failed answers and a secret rotation it deploys
  mid-stream.
- Webhook delivery (#25). The outbox relay creates a delivery of each event it publishes to every
  enabled endpoint whose filter takes it, in the same transaction, once per event and endpoint.
  `WebhookDispatcher` POSTs the event's JSON signed in `Payflow-Signature: t=...,v1=...`
  (HMAC-SHA256 over the timestamp, `.` and the raw body), with `Payflow-Event-Id`,
  `Payflow-Event-Type`, `Payflow-Delivery-Id` and `Payflow-Delivery-Attempt`. A 2xx settles it;
  anything else retries with full-jitter backoff (5 s doubling to 1 h) for 3 days, every attempt
  recorded in `webhook_attempts`. One order's events reach each endpoint in order; other orders
  and endpoints don't wait, and one instance sends at most 8 deliveries to an endpoint at once.
  Deleting an endpoint gives up its pending deliveries. New settings under `payflow.webhooks.*`.
- Webhook delivery log, replay and ping (#25). `GET /v1/webhook_endpoints/{id}/deliveries`
  pages an endpoint's deliveries newest first with each one's latest attempt (status, latency,
  the first 1,024 characters of the body, or the reason there was no answer), filtered by
  `state`, `event_id` and creation time; `GET .../deliveries/{delivery_id}` adds the event as
  sent and every attempt. `POST .../replay` sends settled deliveries again by `event_id` or time
  range, at most 1,000 at a time, each with a new 3-day retry window. `POST .../ping` queues a
  signed `ping` event, tried once and shown in the log. Migrations V19 and V20 add
  `replayed_at` and let a ping's delivery have no order.
- Webhook secret rotation and automatic disabling (#25). `POST
  /v1/webhook_endpoints/{id}/rotate_secret` returns a new signing secret, shown once; the old one
  keeps signing beside it for `previous_secret_expires_in` seconds (a day by default, 0 to 7
  days), so `Payflow-Signature` carries two `v1` values during the overlap, and at most two ever
  sign. An endpoint whose every attempt fails for 3 days (`payflow.webhooks.disable-after`) is
  disabled with `disabled_reason: failing` and sent one final `webhook_endpoint.disabled`
  notice, which also goes to the merchant's other endpoints that subscribe to that new event
  type. Endpoints now show `disabled_reason`, `failing_since` and `previous_secret_expires_at`.
  Enabling a disabled endpoint again gives it a fresh three days. Migrations V21 and V22 add the
  columns and let the notice's delivery have no order.
- Secrets at rest: `PAYFLOW_SECRET_KEYS` (`id:base64key`, 32-byte keys, comma-separated to
  rotate) seals webhook signing secrets with AES-256-GCM, bound to their row. A stored
  idempotent answer that carries such a secret is sealed too, and replays unchanged. Unset, each
  process uses a random key and logs a warning; set it, the same on every instance, before
  registering endpoints.
- openapi.yaml lists the error codes each response can carry, and the headers of every response;
  the contract test produces every documented (operation, status, code).
- The compose stack has a second merchant (`globex`), an optional second instance (`app2`,
  profile `pair`) and `PAYFLOW_IMAGE` to run a published image. `examples/rolling-restart.py`
  pages 10,000 orders of the two merchants while both instances restart.
- The chaos property covers refunds racing each other: bursts of amount, line and full refunds
  sent concurrently against orders with repeated skus, under lost answers, crashes and a provider
  that may ignore keys. It checks the provider's refunds and ledger, the restocks, line pricing
  and stock against the stored refunds, and that every refusal was justified.

### Changed

- Migrations V5-V8 add `merchant_reference` and replace the `(merchant_id, created_at DESC)`
  index with `(merchant_id, created_at, id)`. They build indexes `CONCURRENTLY` and validate the
  new CHECK separately, so they run while orders are being taken. Flyway's lock is now
  session-level (`transactional-lock: false`).
- The application clock ticks in microseconds, as Postgres stores, so the `created_at` in a 201
  matches every later read (#77).
- Error responses always carry a JSON body: `Accept: text/html` gets a `406` with the error
  instead of an empty body.
- A cancel or capture request that lands while a saga step runs now makes the order due at once,
  instead of waiting out the retry backoff (or a manual order's wait) the step then writes. Every
  saga write carries the version the worker read (#26).

### Fixed

- docs/webhooks.md said an order's `seq` starts at 1 for `order.created`; it starts at 0. Its
  example event (`order.completed`, `seq` 4) and test vector are corrected to match (#25).
- The chaos property no longer flags a correct `refund_exceeds_captured`: it priced a refused line
  refund at the cheapest units it could have been given rather than the dearest (#82).
- The simulator's control API accepts faults on `inventory.restock`, so the compose stack can
  lose or fail a restock as the in-process tests do (#81).
- `GET` requests answer `503 unavailable` when the database is down, as POSTs did, instead of
  `500 internal` (#78).
- Guava and snappy-java moved to versions without the new advisories (#75). The osv-scanner
  ignore list is empty: the two Spring Framework 6.2 advisories it held are fixed in 7.0.

## 0.1.0 (31 Oct 2026, not tagged)

The first complete version, built before releases were automated.

- Orders as an orchestrated saga: reserve stock, authorize, capture, ship, with compensations
  (cancel the shipment, void or refund, release the stock), cancel before capture and full
  refunds. The state machine is a CHECK constraint, and terminal states are tied to money.
- Crash safety: leases with `FOR UPDATE SKIP LOCKED`, fenced writes, no transaction across a
  network call, and resumed steps that ask the provider before acting again.
- A transactional outbox relayed to Kafka in order per order, and an idempotent consumer with an
  inbox table and a timeline at `GET /v1/orders/{id}/events`.
- Stripe-style idempotency keys on every POST, hashed API keys per merchant, strict JSON with
  size limits, JSON logs with a correlation id, Prometheus metrics and health probes.
- Fake payment, inventory and shipping providers with faults, latency and lost answers,
  in-process or as their own container; a chaos property test, a `SIGKILL` test of the packaged
  jar, the docker compose demo and the k6 benchmark.
