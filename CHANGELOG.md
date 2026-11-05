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
- The running version on `/livez` (`components.build.details.version`) and in the
  `payflow X.Y.Z ready` log line.
- Clean shutdown: on `SIGTERM` the saga worker stops claiming, lets running steps finish for up
  to `payflow.saga.shutdown-grace` (5 s), and hands every lease back, so the next instance
  resumes on its first poll instead of waiting out the lease. New counter
  `payflow.saga.handed_back`.
- openapi.yaml lists the error codes each response can carry, and the headers of every response;
  the contract test produces every documented (operation, status, code).
- The compose stack has a second merchant (`globex`), an optional second instance (`app2`,
  profile `pair`) and `PAYFLOW_IMAGE` to run a published image. `examples/rolling-restart.py`
  pages 10,000 orders of the two merchants while both instances restart.

### Changed

- Migrations V5-V8 add `merchant_reference` and replace the `(merchant_id, created_at DESC)`
  index with `(merchant_id, created_at, id)`. They build indexes `CONCURRENTLY` and validate the
  new CHECK separately, so they run while orders are being taken. Flyway's lock is now
  session-level (`transactional-lock: false`).
- The application clock ticks in microseconds, as Postgres stores, so the `created_at` in a 201
  matches every later read (#77).
- Error responses always carry a JSON body: `Accept: text/html` gets a `406` with the error
  instead of an empty body.

### Fixed

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
