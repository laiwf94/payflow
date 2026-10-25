# payflow design

## Goals

A customer is charged exactly what they owe, once, or not at all. That has to hold when the
payment provider, the inventory system or the carrier fails, answers slowly, or does the work and
then loses the answer, and when the payflow process itself is killed at any instruction.

Every order reaches a final state without a human, unless a compensation keeps failing, in which
case it keeps retrying and says so loudly.

A merchant can retry any request safely. The same request with the same key gives the same
answer; it never creates a second order or a second refund.

Other systems learn about every state change, in order per order, even if Kafka is down when the
change happens.

Non-goals for this version: partial captures and partial refunds, multiple payments per order
(split tender), inventory reservation expiry, and running the saga across regions. Some of these
are discussed under [Scaling out](#scaling-out).

## Data model

An order row is both the business record and the saga's durable state:

| Columns | Meaning |
|---|---|
| `id`, `merchant_id`, `items`, `shipping_address`, `payment_method` | What was ordered. Items and address are `jsonb`; nothing queries inside them. |
| `amount_minor`, `currency` | Total in minor units and an ISO 4217 code, computed by the server from the lines. |
| `state`, `previous_state` | Position in the state machine; the previous state exists for the CHECK constraint below. |
| `reservation_id`, `payment_id`, `shipment_id`, `tracking_number` | What the dependencies returned. |
| `captured_minor`, `refunded_minor` | What the payment provider reports, after each money step. |
| `attempt`, `step_started_at`, `next_attempt_at` | Retry bookkeeping for the current step; `next_attempt_at` is null exactly when the order is terminal. |
| `lease_token`, `lease_until` | Which worker holds the order, and until when. |
| `cancel_requested`, `failure_code`, `failure_message`, `correlation_id`, `version` | Requests, outcomes, tracing and an update counter. |

Money is a `long` count of minor units plus a `java.util.Currency`, never `double` or
`BigDecimal`: 1999 EUR is 19.99 euro, 500 JPY is 500 yen. Amounts are between 1 and 99,999,999,
the eight-digit ceiling card processors commonly accept for one charge, and every sum and product
uses `Math.addExact` / `multiplyExact`, so an overflow is a 400 and not a negative total. Codes
with no minor unit (XAU, XTS, XXX) are refused.

The other tables are `outbox` (events waiting for Kafka), `inbox` and `order_timeline` (the
consumer's deduplication and its projection), and `idempotency_keys`.

## Order state machine

```text
PENDING -> INVENTORY_RESERVED -> PAYMENT_AUTHORIZED -> PAYMENT_CAPTURED -> COMPLETED -> REFUNDING -> REFUNDED
   |              |    \                 |                     |
   |              |     +----> REVERSING_PAYMENT <---- CANCELLING_SHIPMENT
   v              v                 |
RELEASING_INVENTORY <---------------+
   |
   +--> FAILED | CANCELLED
```

Forward states record what has been done; compensating states record what is being undone. The
table lives in `OrderState` and, independently, in a CHECK constraint. A CHECK constraint can't
see the old row, so a trigger copies the old state into `previous_state` on every update and the
constraint judges the pair. (The trigger also overwrites any `previous_state` a client tries to
set, so it can't be forged.) A detail that matters: `(NULL, 'COMPLETED') IN (...)` is NULL, and a
NULL CHECK passes, so the constraint is a `CASE` that requires `PENDING` when there is no
previous state.

More constraints tie states to money. A `COMPLETED` or `REFUNDING` order has
`captured_minor = amount_minor`; `REFUNDED` has refunded everything; `FAILED` and `CANCELLED` have
`refunded_minor = captured_minor`. If the code ever tries to fail an order that still holds the
customer's money, the database refuses the write; one of the mutants used to check the chaos
test was caught by exactly this constraint. `OrderSchemaTest` drives every one of the 144 state
pairs through raw SQL and checks the database accepts exactly the 15 legal ones.

Every write the saga makes is a compare-and-set on `(id, lease_token, state)`, so a worker that
lost its lease, or that is looking at an old copy of the row, changes nothing.

## Saga execution

The saga is orchestrated, as in Garcia-Molina and Salem's
[*Sagas*](https://www.cs.cornell.edu/andru/cs711/2002fa/reading/sagas.pdf): one component decides
the next step from the stored state, rather than services reacting to each other's events. With
three dependencies and a strict order between them, the whole flow is easier to read, test and
change in one place.

**Claiming.** A dispatcher thread runs

```sql
WITH due AS (
  SELECT id, lease_token AS expired_token FROM orders
  WHERE next_attempt_at <= :now AND (lease_until IS NULL OR lease_until < :now)
  ORDER BY next_attempt_at LIMIT :limit
  FOR UPDATE SKIP LOCKED)
UPDATE orders o SET lease_token = gen_random_uuid(), lease_until = :now + :lease
FROM due WHERE o.id = due.id
RETURNING o.*, due.expired_token IS NOT NULL AS recovered
```

`SKIP LOCKED` lets any number of workers poll the same table without waiting on each other's
rows. The row lock lasts only for this statement; what protects an order while its step runs is
the lease, because no transaction stays open across a call to another system. A transaction held
open across a two-second provider timeout would pin a pooled connection and row locks for that
long, under load, for every order in flight.

**Running.** Each claimed order runs on its own virtual thread, up to 256 at a time. The thread
runs steps until the order is terminal, has to wait for a retry, or loses its lease. Each step
returns one of three outcomes: advance to a state (with what it learned, such as the payment id),
retry later, or fail into a compensation. The worker turns that into one fenced update plus its
outbox event, in one transaction. A new order wakes the dispatcher after commit instead of
waiting for the 100 ms poll.

**Retries.** Delays use exponential backoff with full jitter: attempt *n* waits a uniform random
time in `[0, min(30 s, 200 ms * 2^n)]` (Marc Brooker,
[*Exponential Backoff And Jitter*](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)).
Spreading retries over the whole window keeps workers from hitting a recovering dependency in
lockstep. A step can ask for a minimum delay; after an unknown outcome it asks for two seconds,
so the provider has time to finish before we ask what happened.

**Deadlines.** A forward step that has not succeeded after 8 attempts or 2 minutes gives up and
the order goes to the step's compensation. Compensations have no deadline: giving up on a refund
would leave the customer charged. They retry with backoff capped at 30 s, count retries in a
metric, and log at error level after ten attempts, which is the signal for a human.

**Crashes.** If the process dies mid-step, nothing is written; the lease simply runs out (30 s by
default). The next worker to claim the order sees that the previous lease expired instead of
being released and passes `resumed = true` to the step. A step that resumes, or retries, checks
what already happened before doing anything (see the next section). The lease must be longer
than the slowest single step, which is bounded by the dependency timeouts.

**Cancel and refund.** A cancel sets a flag, accepted only before capture, and pulls the order's
next attempt forward. The worker checks the flag before each forward step and diverts to that
step's compensation; the order ends `CANCELLED` unless something else had already failed. A
cancel that lands while a capture is in flight takes effect after it: the capture completes, the
next decision sees the flag, and the money is refunded. A refund moves a `COMPLETED` order to
`REFUNDING` and the worker refunds whatever the provider still holds.

**Compensations look before they act.** `CANCELLING_SHIPMENT` asks the carrier for a shipment by
reference and cancels it if one exists. `REVERSING_PAYMENT` asks the provider for every payment
with the order's reference and voids each hold and refunds each capture. Neither trusts the order
row, because the forward step they undo may have happened without the row knowing. Running
either twice changes nothing the second time.

## Unknown outcomes

The HTTP adapters sort every call into four results, and the split between the last two is what
the design rests on:

| Result | When | What the saga does |
|---|---|---|
| Success | 2xx | Advance. |
| Rejected | 4xx, including a 402 decline | Fail into the compensation, or for compensations retry and alert. |
| Transient | connection refused, connect timeout, 503, 429, 401/403 | Retry; nothing happened. |
| Unknown | read timeout, connection lost after sending, 500, 502, 504, 408 | Retry, and look first. |

A 500 counts as unknown: the provider may have committed before failing. A 401 or 403 is our
misconfiguration; failing the customer's order for it would be wrong, so it retries and logs an
error.

After an unknown outcome on authorize or capture, the next attempt is marked as resumed and
starts with a lookup by order reference:

- authorize: an authorized or captured payment exists, use it; a declined one exists, fail as
  declined; nothing exists, authorize again;
- capture: our payment is captured, advance; still authorized, capture again; voided or missing,
  fail into the reversal.

Every attempt of a step also sends the same idempotency key (`authorize-<order>`,
`capture-<order>`, `void-<payment>` and so on), following
[Stripe's design](https://stripe.com/blog/idempotency) (Brandur Leach, *Designing robust and
predictable APIs with idempotency*). Either mechanism alone covers the common case: a provider
that honours keys replays the first result, and the lookup finds the first result. Together they
also cover a provider (or a proxy) that drops keys, a key that expired, and a capture that
succeeded but would be refused on retry, which would otherwise fail an order that was paid for.
`UnknownOutcomeTest` loses the answer to every money-moving call over real HTTP with the fake
provider's key handling on and off; removing the lookup makes the key-less runs authorize twice.

There is one limit to the lookup: it is only authoritative once the provider has finished
processing. A request that is still queued inside the provider when we look could land after it.
The two-second minimum delay before the lookup, the idempotency key, and the reversal (which
re-reads the provider's list before acting) narrow this; closing it entirely needs the provider to
offer either a status query by idempotency key or a guarantee about in-flight requests, which
real providers document in different ways.

## Transactional outbox

Publishing to Kafka from the request (or the step) and committing to Postgres are two systems; a
crash between them loses the event or publishes one for a change that rolled back. The
[transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
(Chris Richardson) writes the event to an `outbox` table in the same transaction as the change.
`OutboxWriter` uses `Propagation.MANDATORY`, so it can't be called outside one. The event's
sequence number is the order's `version`, which every update increments.

A relay publishes the table to Kafka, keyed by order id, so all of one order's events go to one
partition in order. Each poll:

```sql
SELECT o.aggregate_id FROM outbox o
WHERE o.published_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM outbox p
                  WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL
                    AND p.aggregate_seq < o.aggregate_seq)
ORDER BY o.id LIMIT 100
FOR UPDATE SKIP LOCKED
```

locks the head of each order's queue, then publishes that order's whole backlog in sequence,
waits for Kafka to acknowledge (`acks=all`, idempotent producer), and marks the rows published in
the same transaction. Only heads are eligible, and a head stays locked and unpublished until the
batch commits, so a second relay can't pick a later event of the same order meanwhile: several
relays run side by side without reordering anything. `OutboxRelayTest` runs three at once.

The trade-off is that the head rows stay locked while Kafka acknowledges, bounded by a 10 s send
timeout and a 100-order batch. Only outbox rows are locked; order rows and requests are not
affected. If the send or the commit fails, the rows stay unpublished and go out again, so
delivery is at least once: duplicates are possible, gaps are not. Published rows are deleted
after seven days.

When Kafka is down, orders keep flowing: readiness depends on Postgres only, events wait in the
table, and a gauge shows the backlog and the age of its oldest event.

## Consumer and inbox

The order timeline (`GET /v1/orders/{id}/events`) is built by a Kafka consumer, as any downstream
system would build its view. Each event is applied in one transaction that first inserts
`(consumer, event_id)` into `inbox`; on a duplicate the insert conflicts and the event is
skipped. Inbox rows are kept for 14 days, longer than the outbox keeps events, so no redelivery
can outlive its inbox entry. A record that can't be parsed goes to the `payflow.order-events-dlt`
topic straight away; other failures (the database being down) are retried five times, half a
second apart, and then parked there rather than blocking the partition. The consumer's session
timeout is 10 s, so partitions held by a dead instance come back quickly after a crash.

## Idempotency keys

Every POST under `/v1` needs an `Idempotency-Key`. The rules are Stripe's: the same key and the
same request replays the stored status and body, errors included, with `Idempotent-Replayed:
true`; the same key with a different method, path or body is `422 idempotency_key_reused`; the
same key while the first request is still running is `409 idempotency_key_in_use`. Keys are per
merchant and kept for 24 hours. Bodies are compared as canonical JSON (sorted keys, no
whitespace), so a client that reorders fields on retry still matches.

The implementation follows Brandur Leach's
[*Implementing Stripe-like Idempotency Keys in Postgres*](https://brandur.org/idempotency-keys).
A filter claims the key with an `INSERT ... ON CONFLICT DO NOTHING`, committed on its own so
concurrent requests see it, then runs the whole request in one transaction and stores the
response in that same transaction. The order and its stored response therefore commit together or
not at all. A refused request (400, 404, 409) writes nothing but its stored response, which is
committed; the service methods are marked so that a refusal doesn't mark the shared transaction
rollback-only. A 5xx rolls everything back and deletes the claim, so the retry runs fresh; this
differs from Stripe, which replays 500s, and is safe here because the rollback guarantees nothing
happened. A claim whose request died holds a lock for 60 s, after which another request with the
same key takes it over; its work had rolled back with it, so starting over is safe.

## Failure modes

| Failure | Effect | Recovery |
|---|---|---|
| Provider returns 503 or refuses connections | Step retries with backoff | Automatic; after 8 attempts or 2 min the order is compensated |
| Provider applies a call and the answer is lost | Step retries after at least 2 s | Lookup by reference finds the result; no second charge |
| Card declined, out of stock, address refused | Step fails | Compensation from that step; order ends `FAILED` with the reason |
| Compensation keeps failing | Order stays in the compensating state | Retries forever, capped at 30 s apart; error log after 10 attempts |
| payflow killed mid-step | Lease left behind | Lease expires (30 s); the next worker resumes and looks before acting |
| Two workers think they own an order | The stale one's writes are fenced out | Compare-and-set on lease token and state |
| Kafka down | Events wait in the outbox | Relay retries; backlog gauge rises; API and saga unaffected |
| Relay dies after sending, before commit | Events sent again | Consumers deduplicate by event id |
| Malformed event on the topic | Moved to the dead-letter topic | Consumer continues |
| Postgres down | POSTs get 503 `unavailable` before doing anything; workers and relay log and keep polling | Resumes when Postgres returns; nothing is held in memory |
| Client retries a POST | Replay of the first response | Idempotency key |
| Wrong credentials for a provider | Retried as transient, error logged | Fix configuration; orders resume |

## Limits

| Limit | Value | Where |
|---|---|---|
| Request body | 64 KiB, checked before buffering | `BodyLimitFilter.MAX_BODY_BYTES` |
| Provider response body | 1 MiB; larger counts as an unknown outcome | `HttpDependency.MAX_RESPONSE_BYTES` |
| Lines per order | 1-50 | `CreateOrderRequest` |
| Quantity per line | 1-1,000 | `CreateOrderRequest` |
| Unit amount and order total | 1-99,999,999 minor units | `Money.MAX_MINOR_UNITS` |
| Name, address lines / city / postal code / SKU | 200 / 100 / 20 / 64 characters | `CreateOrderRequest` |
| `Idempotency-Key` | 1-255 printable ASCII, no spaces | `IdempotencyFilter` |
| Idempotency lock timeout / retention | 60 s / 24 h | `payflow.idempotency.*` |
| `X-Request-Id` accepted | 1-64 of `A-Z a-z 0-9 . _ -`, else replaced | `RequestIds` |
| API key length | 256 characters | `ApiKeys` |
| Merchant id | 1-64 of `a-z 0-9 _ -` | `ApiKeys`, `orders_merchant_format` |
| Saga lease | 30 s | `payflow.saga.lease` |
| Orders claimed per poll / in flight | 64 / 256 | `payflow.saga.batch-size`, `max-in-flight` |
| Backoff | 200 ms base, 30 s cap, full jitter | `payflow.saga.backoff-*` |
| Forward step limit | 8 attempts or 2 min | `payflow.saga.max-attempts`, `step-timeout` |
| Delay before resolving an unknown outcome | 2 s | `payflow.saga.unknown-outcome-delay` |
| Dependency connect / read timeout | 500 ms / 2 s | `payflow.dependencies.*` |
| Outbox batch / send timeout / retention | 100 orders / 10 s / 7 days | `payflow.outbox.*` |
| Inbox retention | 14 days | `TimelineProjection.INBOX_RETENTION` |
| Events returned per timeline | 1,000 | `TimelineProjection.forOrder` |
| Consumer session timeout | 10 s | `spring.kafka.consumer.properties` |
| Database pool | 20 connections | `PAYFLOW_DB_POOL` |

## Security

**Authentication.** Merchants authenticate with `Authorization: Bearer <key>`. Keys are 32 random
bytes (`pf_` plus base64url) and configuration holds only their SHA-256, as `merchant:hash`
pairs, so a leaked config or database dump has no usable keys. SHA-256 instead of a password hash
such as bcrypt: the keys have 256 bits of entropy, so there is nothing to brute-force, and a slow
hash on every request would cost CPU for nothing. A presented key is hashed once and compared with
every configured hash using `MessageDigest.isEqual`, without stopping at a match, so the timing
reveals neither which merchant matched nor how much of a hash did.

**Authorization.** Every query on orders, idempotency keys and the timeline includes the merchant
id. Another merchant's order is a 404, indistinguishable from a missing one; `OrderApiTest`
checks every endpoint.

**Input.** Bean Validation on every field, and the same rules again in the database for the ones
that matter (state, amount, currency format, merchant format). JSON is read strictly: unknown
fields, strings where numbers belong (`"2"`), decimals where integers belong and numbers that
overflow are all 400s. The total is computed on the server. Free text may contain markup, which
is stored and returned as data inside JSON and never rendered; control characters are refused.
Bodies are capped before they are buffered, including chunked ones.

**Outbound credentials.** Provider keys are only sent to the base URL from configuration, which
must be a plain `http(s)://host[:port][/path]` with no user info, query or fragment. Ids that come
back in provider responses (a payment id, a shipment id) are encoded as single path segments, so
a hostile id can't add path components or a query; the client doesn't follow redirects. Responses
are read tolerantly (unknown fields ignored), because a provider adding a field shouldn't cause an
outage.

**Secrets in logs.** Keys are never logged; `SimulatorKey` and `ApiKeys` override `toString`.
`ObservabilityTest` runs a full order and checks no API key, provider key or `Bearer` string
appears in any log line.

**Operational surface.** Actuator runs on a separate management port bound to 127.0.0.1 and
exposes only health and Prometheus; liveness and readiness are also served on the API port. The
fake providers require their own bearer key and can be switched off
(`PAYFLOW_SIMULATOR_ENABLED=false`); a production deployment would. The container image is
distroless and runs as `nonroot`.

## Dependencies

Each one is pinned through the Gradle lockfile, at a release at least two weeks old.

| Dependency | Why |
|---|---|
| Spring Boot 3.5 (web, JDBC, validation, actuator) | HTTP, configuration, health and metrics are commodity; Boot is what Java teams run. JDBC through `JdbcClient` rather than JPA: every query here is specific (`SKIP LOCKED`, compare-and-set, `ON CONFLICT`) and reads better as SQL than as entity mappings. |
| Spring for Apache Kafka | Listener containers, retries and the dead-letter recoverer are a lot of careful code to own. |
| PostgreSQL driver, Flyway | The database and versioned migrations. |
| Micrometer Prometheus registry | Exposition format for the metrics. |
| Testcontainers, jqwik, Awaitility | Real Postgres and Redpanda in tests; property-based testing with shrinking; waiting on asynchronous outcomes without sleeps. |
| Error Prone, Spotless with google-java-format, JaCoCo | Lint as errors, one formatting, the coverage floor. |
| osv-scanner (CI only) | Known-vulnerability scan of `gradle.lockfile`. Chosen over OWASP dependency-check, which needs an NVD API key and a multi-gigabyte local database, and reads build output rather than the lockfile. |

Left out on purpose: Spring Security (one filter and a constant-time comparison cover API keys),
a resilience library (backoff with jitter is thirty lines, and the retry policy is tied to the
saga's persisted state, not to a call stack), an OpenAPI generator (the document is written by
hand and tested against the routes), and a JSON Schema library (the contract test validates the
subset the document uses in about a hundred lines).

Spring Boot 3.5.16 manages a few libraries with known vulnerabilities fixed in later patch
releases (Jackson, Tomcat, the Postgres driver, lz4-java, log4j-api, commons-compress); the build
pins the patched versions. Two Spring Framework 6.2 advisories have no open-source fix on that
line (they are fixed in 7.0): Server-Sent Event fragment rendering and `XsltView`. payflow uses
neither, and `osv-scanner.toml` records why they are ignored. Moving to Spring Boot 4 removes
both.

## Scaling out

The design already runs as several identical instances against one Postgres: workers and relays
coordinate only through `SKIP LOCKED`, leases and compare-and-set, and nothing is held in memory
between requests. What would change with more volume:

- **Commits.** An order costs around a dozen commits (idempotency claim and response, saga
  transitions, inbox). At the benchmark's saturation point Postgres waits on WAL writes. Grouping
  a worker's transition and the next step's lease extension, or applying several consumer events
  per transaction, would cut that; so would faster storage than a laptop's virtual disk.
- **Hot tables.** `orders` is updated several times per order and `outbox` is insert-then-update.
  Partitioning `outbox` by day turns cleanup into dropping partitions instead of deletes.
- **Relay throughput.** One relay per instance with head-of-queue locking scales with the number
  of distinct orders in flight. Logical decoding (reading the WAL with Debezium or a native
  subscriber) would remove the polling and the update per event, at the cost of another moving
  part.
- **Many merchants.** Rate limits per merchant and per dependency would stop one merchant's burst
  or one slow provider from occupying all 256 in-flight slots.
- **Multiple regions.** The saga assumes one Postgres primary. Running across regions means
  choosing a home region per order and routing its steps there, rather than replicating leases.
