# payflow design

payflow exists to charge a customer exactly what they owe, once, or not at all. That has to hold
when the payment provider, the inventory system or the carrier fails, answers slowly, or does the
work and then loses the answer, and when the payflow process itself is killed at any instruction.
Three further requirements follow. Every order reaches a final state without a human, unless a
compensation keeps failing, in which case it keeps retrying and says so loudly. A merchant can
retry any request safely: the same request with the same key gives the same answer, and never
creates a second order or a second refund. Other systems learn about every state change, in order
per order, even if Kafka is down when the change happens.

This version leaves out partial captures, multiple payments per order (split
tender), inventory reservation expiry, and running the saga across regions. Some of these come up
under [Growing past one database](#growing-past-one-database).

## The order row

An order row is both the business record and the saga's durable state:

| Columns | Meaning |
|---|---|
| `id`, `merchant_id`, `items`, `shipping_address`, `payment_method` | What was ordered. Items and address are `jsonb`; nothing queries inside them. |
| `merchant_reference` | The merchant's own id for the order (a cart or order number), optional and unique per merchant. |
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

The other tables are `refunds` (one row per refund, below), `outbox` (events waiting for Kafka),
`inbox` and `order_timeline` (the consumer's deduplication and its projection), and
`idempotency_keys`.

### Merchant references

A merchant can tag an order with `merchant_reference`, 1-128 printable ASCII characters without
spaces, enforced by Bean Validation and again by the `orders_merchant_reference_format` CHECK. A
unique index on `(merchant_id, merchant_reference)` makes it a lookup key and a second guard
against duplicate orders, one that works across idempotency keys and outlives their 24 hours. NULLs
are distinct, so orders without a reference never collide.

A second order with a taken reference is `409 order_reference_in_use`, and the message names the
order that holds it. The insert is `INSERT ... ON CONFLICT (merchant_id, merchant_reference) DO
NOTHING` rather than a caught unique violation: a violation would abort the transaction the
idempotency filter shares with the request, and the filter has to commit the 409 in it so that a
retry with the same key replays the 409 instead of running again. Two concurrent creates with one
reference serialize on the index: the second waits for the first to commit and then conflicts, or
inserts if the first rolled back. The `order.created` event carries the reference when there is
one.

Orders also have an index on `(merchant_id, created_at, id)`, the order a merchant's listing walks
with a keyset cursor; `id` breaks ties between orders created in the same microsecond. It
replaces V1's `(merchant_id, created_at DESC)`, since a B-tree scans either way.

### Listing orders

`GET /v1/orders` returns the merchant's orders newest first, ordered by `(created_at, id)`.
Pages are keyset, not OFFSET: the next page is `WHERE (created_at, id) < (:last_created,
:last_id) ORDER BY created_at DESC, id DESC LIMIT n+1`, and Postgres turns the row comparison into
an index condition on `(merchant_id, created_at, id)` scanned backwards, so page 500 costs the
same as page 1 and no sort runs (`OrderRepositoryTest` checks the plan). The extra row says
whether `has_more`. Because a position is a key and not a count, an order created while a client
pages can't shift the ones after it: every order that matched when paging began, and still
matches, comes back exactly once. Orders created later appear if they sort after the cursor and
not otherwise. `id` breaks ties, so a page that ends inside a run of orders created in the same
microsecond resumes after its last order, not after the whole run.

Filters are `state` (repeatable or comma-separated), `created_gte`, `created_lt`, `currency` and
`merchant_reference`, which names at most one order and is how a merchant finds an order by its
own id. The query string is read as strictly as a body: an unknown or repeated parameter is a 400
rather than ignored, because `status=FAILED` would otherwise list every order. Bounds are rounded
up to the microsecond the database stores, which keeps both of them exact.

The cursor is opaque: a version byte, the last order's `created_at` in microseconds and its id,
then the first 128 bits of an HMAC-SHA256 over the merchant, the canonical form of the filters and
those bytes, 55 base64url characters in all. Signing it means a client can't fabricate a position,
reuse another merchant's cursor or change the filters halfway and quietly get pages of a
different listing; each is `400 invalid_cursor`. Merchant scoping doesn't depend on the
signature, since the query always includes the merchant, but the signature keeps the format ours
to change. `limit` (1-100, default 20) isn't signed and may change between pages.
`PAYFLOW_CURSOR_SECRETS` holds the secrets, comma-separated: the first signs and all verify, so a
secret rotates by deploying `new,old` and later `new` without breaking listings in progress. Every
instance needs the same list; unset, each process signs with a random secret and logs a warning,
which is fine for one instance and breaks cursors across a restart.

`OrderListingProperties` checks this with jqwik against Postgres: two merchants, timestamps drawn
from a dozen seconds so ties are common, random filters and page sizes, and new orders inserted
after every page, some before the cursor and some after. Across the pages no order repeats, none
that existed at the start is missing, every order matches, and the sequence is strictly newest
first comparing ids as unsigned bytes, as Postgres does.

### Migrations on a live table

The migrations that added these are written to run while orders are being taken. The column is
added with its CHECK marked `NOT VALID`, so `ADD COLUMN` holds its exclusive lock only for a
catalog change, and the next migration validates it under `SHARE UPDATE EXCLUSIVE`, which
doesn't block reads or writes. Both indexes are built `CONCURRENTLY`, each in a migration of its
own that Flyway runs outside a transaction. That needs `spring.flyway.postgresql.transactional-lock:
false`: Flyway's default lock is an advisory lock held in an open transaction, and a concurrent
index build waits for every open transaction, Flyway's included, so it would wait forever. The
session-level lock still keeps two starting instances from migrating at once. A concurrent build
that fails leaves an `INVALID` index; drop it with `DROP INDEX CONCURRENTLY` and start the service
again, and Flyway retries the migration. Changing a CHECK follows the same two steps: V10 drops
each old state constraint and adds its replacement `NOT VALID` in one short exclusive lock, and
V11 validates them all. The replacements only widen the old rules for existing states, so
validation can't fail on a row the old ones accepted.

Removing something takes the other half, a contract migration, and only once no code writes it.
V14 drops `REFUNDING` from the state constraints. An order still in `REFUNDING` was being refunded
by a 0.1.x instance's step, which this release no longer has, and moving it in SQL could refund it
twice. So V14 replaces the constraints first, under the exclusive lock that the `ALTER` takes, then
refuses to commit if any order is still `REFUNDING`. Flyway rolls the migration back and the new
instance doesn't start. The old instances finish those refunds in seconds, and the next start
goes through. `RefundingMigrationTest` runs exactly that on a database of its own. Orders the old
step finished keep `previous_state = REFUNDING`, so the pair `(REFUNDING, REFUNDED)` stays legal
for them, and V15 validates.

## States, and the database that enforces them

```text
PENDING -> INVENTORY_RESERVED -> PAYMENT_AUTHORIZED -> PAYMENT_CAPTURED -> COMPLETED
   |              |    \                 |                     |
   |              |     +----> REVERSING_PAYMENT <---- CANCELLING_SHIPMENT
   v              v                 |
RELEASING_INVENTORY <---------------+
   |
   +--> FAILED | CANCELLED

COMPLETED -> PARTIALLY_REFUNDED -> REFUNDED, and COMPLETED -> REFUNDED
```

Forward states record what has been done; compensating states record what is being undone. The
table lives in `OrderState` and, independently, in a CHECK constraint. A CHECK constraint can't
see the old row, so a trigger copies the old state into `previous_state` on every update and the
constraint judges the pair. (The trigger also overwrites any `previous_state` a client tries to
set, so it can't be forged.) There is a trap here: `(NULL, 'COMPLETED') IN (...)` is NULL, and a
NULL CHECK passes, so the constraint is a `CASE` that requires `PENDING` when there is no
previous state.

More constraints tie states to money. A `COMPLETED` or `PARTIALLY_REFUNDED` order
has `captured_minor = amount_minor`; `PARTIALLY_REFUNDED` has refunded more than nothing and less
than everything; `REFUNDED` has refunded everything; `FAILED` and `CANCELLED` have
`refunded_minor = captured_minor`. If the code ever tries to fail an order that still holds the
customer's money, the database refuses the write; one of the mutants used to check the chaos
test was caught by exactly this constraint. `OrderSchemaTest` drives every one of the 144 state
pairs through raw SQL and checks the database accepts exactly the 16 legal ones.

`PARTIALLY_REFUNDED` and `REFUNDED` are the order's summary of its refunds, which are rows of
their own. The order changes only when a refund succeeds. A refund still in progress is a
`PENDING` row, not an order state, so refunds of one order run side by side and never hold up,
or wait for, anything else the order does.

### Refunds

Each refund is a row in `refunds`: the order, `amount_minor`, optionally the `lines` it returns
(`[{"sku": ..., "quantity": ...}]`, checked for shape by `refunds_lines_format`), a `state` of
`PENDING`, `SUCCEEDED` or `FAILED`, and the provider's refund id, which a `SUCCEEDED` refund must
have. A refund's order, amount and lines never change, and its state only leaves `PENDING`; a
trigger refuses anything else (`refunds_immutable`, `refunds_legal_transition`).

The rule that matters is that refunds never return more than was taken. A `PENDING` refund
already counts, since it is money promised back. A deferred constraint trigger checks, when the
transaction commits, that the order's refunds that haven't failed add up to at most
`captured_minor` (`refunds_within_captured`), return no more of any sku than the order bought
(`refunds_within_lines`), and belong to an order that is `COMPLETED`, `PARTIALLY_REFUNDED` or
`REFUNDED` (`refunds_order_completed`). The same check runs when the order side changes: a
capture that shrinks, or an order with refunds leaving those states. Deferring it lets one
transaction write the refund and the order in either order.

A sum checked in a trigger is only safe under concurrency if two transactions can't both sum
before either commits. The trigger therefore locks the order row first, `FOR NO KEY UPDATE`: two
refunds of one order queue there, and in `READ COMMITTED` each statement after the lock takes a
fresh snapshot, so the second sees the first's committed refund. (`FOR UPDATE` would deadlock:
each refund's foreign key already holds `FOR KEY SHARE` on the order, which `FOR UPDATE` waits
on.) `SERIALIZABLE` is safe by itself. `REPEATABLE READ` is refused (`refunds_isolation`), because
its snapshot is taken before the lock and would miss the other refund. `RefundSchemaTest` races
six refunds of 6.00 against a 25.00 capture, all inserted before any commits, and exactly four
commit, in every one of ten rounds.

Every write the saga makes is a compare-and-set on `(id, lease_token, state)`, so a worker that
lost its lease, or that is looking at an old copy of the row, changes nothing.

### Running refunds

A refund row carries its own schedule and lease (`next_attempt_at`, `attempt`, `lease_token`,
`lease_until`), and the database holds that bookkeeping to the state. A `PENDING` refund is
always scheduled, so none can be forgotten (`refunds_pending_scheduled`). A settled one is never
scheduled or leased (`refunds_settled_unleased`), and a `FAILED` one records why
(`refunds_failed_explained`). The money check above runs on inserts and state changes only, not
on every lease the worker takes.

`RefundService` creates refunds. It locks the order row with the same `FOR NO KEY UPDATE` the
trigger takes, sums the refunds that haven't failed, and refuses an amount larger than what is
left, so two requests can't both be given the last of the capture. The trigger checks the result
again at commit. A full refund (`POST /v1/orders/{id}/refund`) is one refund of whatever is left.
If pending refunds already cover all of it, nothing new is created and the order comes back as it
is.

`POST /v1/orders/{id}/refunds` takes exactly one of `amount` or `lines`. A line refund is priced
from the order itself, never by the client: each unit comes back at what the order paid for it.
When a sku is on more than one line, possibly at different prices, units come back from the
first line first, after the units earlier refunds that haven't failed already took. So however a
merchant splits a sku's units across refunds, they add up to what those units cost
(`RefundShareTest`). The service refuses, under the order's lock, a sku that isn't on the order
or is named twice (`400 invalid_argument`), more of a sku than is left of what was bought (`409
refund_exceeds_purchased`), and a share larger than what is left of the capture (`409
refund_exceeds_captured`, so a line refund can be refused once amount refunds have used the
money up). Each message says how much is left. The trigger's two rules are the same two checks,
so the database would refuse anything the service let through. `RefundApiTest` races ten
requests for 6.00 of a 25.00 order and six requests for one of two mugs over HTTP: exactly four
and exactly two are made, the rest are 409s, and the provider holds exactly those refunds.
`GET /v1/orders/{id}/refunds` lists an order's refunds, oldest first, in every state.

`RefundWorker` runs them. It works the way the saga worker does, with the same settings: a
dispatcher leases due refunds with `SKIP LOCKED` (a partial index on `next_attempt_at` keeps the
poll cheap when there are none), each runs on a virtual thread, and no transaction is open while
the provider is asked. A refund is a single step, so each claim ends in exactly one fenced write:

- **succeeded:** the refund row, its `refund.succeeded` event, the order's `refunded_minor`, its
  state and the order's event commit together, the order moving to `PARTIALLY_REFUNDED` or
  `REFUNDED`;
- **refused by the provider:** the row is `FAILED` with the provider's reason, with its
  `refund.failed` event, and its share of the capture is free again;
- **otherwise:** the row is scheduled for another try with the saga's backoff, at least 2 s after
  an unknown outcome.

Refunds are retried until the provider answers, as compensations are, with an error log from the
tenth attempt. The order's increment is atomic (`refunded_minor = refunded_minor + amount`), so
refunds of one order that settle at the same moment add up rather than overwrite each other.

`RefundStep` looks before it acts. The provider keeps each refund as an object with the caller's
reference, here the refund row's id, and lists a payment's refunds. Every attempt first asks for
that list. A refund whose answer was lost, or whose worker died mid-call, is found there and
recorded instead of being made again. This holds even at a provider that ignores idempotency
keys, which a payment's refunded total alone couldn't do once it has two partial refunds. The
idempotency key, one per refund row (`Keys.refund`), covers the gap between the lookup and the
call at a provider that does honour keys. `RefundWorkerTest` kills a worker after the provider
refunded with keys ignored, and the next claim records the refund without a second one.

A line refund also returns its units to stock. After the money is back, the step restocks the
refund's lines from the order's reservation through `InventoryService.restock`, under the refund
row's id, which the inventory system treats as idempotent: a retry after a lost answer is a
replay, not a second restock. It never returns more of a SKU than the reservation still holds
(`exceeds_reserved`), and releasing a reservation later releases only what is left of it. Money
goes first on purpose. A refund the provider refuses ends `FAILED` and frees its lines to be
refunded again, so stock returned before the money would come back twice. The refund only
succeeds once both are done. Until then it stays `PENDING`, and each attempt finds the provider's
refund by its reference instead of making another, then repeats the restock. An inventory system
that refuses the restock outright (it has lost the reservation) is retried with an error log, as
a refused release is: the money has gone back, and dropping the stock silently would leave the
shelf short. `UnknownOutcomeTest` loses the restock's answer, its request, and both the refund's
and the restock's answers, with the provider's key handling on and off: each time one refund is
made and one mug goes back to the shelf. Scenario 7 of `examples/demo.sh` does the same against
the compose stack, through the simulator's control API: it loses both answers with keys ignored
and prints the provider's refunds and ledger, the restock and the shelf.

`SagaChaosProperties` puts all of this under random faults. Orders have one to three lines, and a
sku can sit on two lines at two prices. Once the saga has settled them, up to twelve amount, line
and full refunds arrive in up to four bursts, one per worker round, so a burst lands while the
last one's refunds are still pending, retrying or recovering from a crash. Each burst's requests
start together on their own threads and race for the same orders. Every call to the payment
provider and the inventory system can fail, be lost before it arrives or lose its answer, the
provider may ignore idempotency keys, and the refund worker crashes before, after and once it
has committed an attempt. Which request wins a race differs from run to run, so the property
checks what must hold for every interleaving:

- the provider's refunds, and the refund entries in its ledger, are exactly the order's succeeded
  refund rows, by reference and amount;
- each succeeded line refund restocked once, under its own id, with exactly its lines;
- an order's line refunds add up to what the order paid for the first units of each sku, which is
  what pricing units "after the ones already given back" means whatever order the refunds won
  the lock in;
- stock held is exactly what completed orders bought less what their refunds returned;
- every refusal is justified by the final state. No refund fails here and none is removed, so what
  an order has refunded by the end is at least what was promised when a request was refused: an
  amount or line refund refused as `refund_exceeds_captured` must not fit even then (for lines,
  at the cheapest units it could have been given), and `refund_exceeds_purchased` must name more
  of a sku than was bought less what ended up returned.

Two deliberately broken versions fail it within a run: a refund step that skips the restock, and
a line price that ignores units earlier refunds took.

### Refund events and what an order shows

Each refund puts its own events into its order's stream: `refund.created` when it is started,
then exactly one of `refund.succeeded` and `refund.failed`. Each is written in the transaction
that makes the change, by `RefundTransitions`, so it exists exactly when the change committed. A
refund event carries the order's usual fields (its state at that moment, with no
`previous_state`, since the order didn't change state) and a `refund` object: `id`, `amount`,
`lines` for a refund of goods, its `state`, and `failure_code` when the provider refused it. A
success is followed in the same transaction by the order's `order.partially_refunded` or
`order.refunded`, so a consumer always sees the refund before the change it caused.

An event's sequence number is the order's `version`, and the outbox holds `(order, seq)` unique,
so a refund event needs a version of its own: `OrderRepository.touch` adds one (and moves
`updated_at`) without changing anything else. That costs nothing in locking. Starting a refund
already holds the order's row lock, and settling one takes it for the commit-time money check
anyway, and a version-only update doesn't fire `orders_within_refunds`, whose `WHEN` looks only at
the capture and the state.
Completed orders are never leased, so the saga's fenced writes are unaffected. The order of locks
doesn't change either: starting a refund locks the order and then writes a new refund row;
settling one locks its own refund row and then the order. No two transactions lock the same pair
in opposite orders, so no deadlock.

The timeline keeps each refund event's refund id, amount and failure code (V16 adds the columns,
`NOT VALID`; V17 validates them), and `GET /v1/orders/{id}/events` returns them as `refund`. A
consumer reading a refund event without its refund id or amount sends it to the dead-letter topic
as malformed.

Every order the API returns carries `refunds`: up to the ten newest refunds, newest first, in
every state, `has_more` when it has older ones, and `pending_amount`, what its `PENDING` refunds
are returning on top of `payment.refunded_amount`. `OrderViews` reads the orders and their
refunds in one `REPEATABLE READ` snapshot, so the refunds shown always agree with the order's
state and refunded amount, even when a refund settles between the two statements. (The refunds
trigger refuses `REPEATABLE READ` for writes only; these transactions are read-only.) A page of the
listing reads all its orders' refunds in two statements, not one per order: a `LATERAL` join on
the `refunds_order` index takes each order's eleven newest, the eleventh only telling whether more
exist, and an aggregate sums the pending ones. `POST /v1/orders/{id}/refund` reads them inside
its own transaction, under the order's lock, so its `202` shows the refund it just started. A
created or cancelled order can't have refunds yet, so neither is read for those answers.

`RefundWorkerTest` checks the sequence numbers and payloads, `RefundApiTest` checks what the order
shows while a refund is pending, after it succeeds and once there are twelve, the timeline of
three refunds and one refused one, and that the listing shows the same refunds, and
`SagaChaosProperties` checks that under every crash each refund has exactly one `refund.created`
and one `refund.succeeded`, with the order's change right after the success.

On `SIGTERM` the refund worker stops claiming and gives in-flight refunds the same grace as the
saga. It stops alongside the saga worker, not after it, so together they still fit inside
`docker stop`'s 10 s. A refund still waiting for the provider at the deadline is abandoned: fenced
and expired, so the next instance claims it at once as a recovery.

## Running the saga

The saga is orchestrated, as in Garcia-Molina and Salem's
[*Sagas*](https://www.cs.cornell.edu/andru/cs711/2002fa/reading/sagas.pdf): one component decides
the next step from the stored state, rather than services reacting to each other's events. With
three dependencies and a strict order between them, the whole flow is easier to read, test and
change in one place.

### Claiming orders

A dispatcher thread runs

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

### Running steps

Each claimed order runs on its own virtual thread, up to 256 at a time. The thread
runs steps until the order is terminal, has to wait for a retry, or loses its lease. Each step
returns one of three outcomes: advance to a state (with what it learned, such as the payment id),
retry later, or fail into a compensation. The worker turns that into one fenced update plus its
outbox event, in one transaction. A new order wakes the dispatcher after commit instead of
waiting for the 100 ms poll.

### Retries

Delays use exponential backoff with full jitter: attempt *n* waits a uniform random
time in `[0, min(30 s, 200 ms * 2^n)]`, rather than the ceiling itself or the ceiling plus a
little noise. When a dependency recovers from an outage, every order that failed during it is
due again; with a fixed or lightly jittered delay they come back together and knock it over
again. Drawing from the whole window spreads them out evenly, at the cost of some retries coming
sooner than a plain exponential schedule would allow. A step can ask for a
minimum delay; after an unknown outcome it asks for two seconds, so the provider has time to
finish before we ask what happened.

### Deadlines

A forward step that has not succeeded after 8 attempts or 2 minutes gives up and
the order goes to the step's compensation. Compensations have no deadline: giving up on a refund
would leave the customer charged. They retry with backoff capped at 30 s, count retries in a
metric, and log at error level after ten attempts, which is the signal for a human.

### Crashes

If the process dies mid-step, nothing is written; the lease simply runs out (30 s by
default). The next worker to claim the order sees that the previous lease expired instead of
being released and passes `resumed = true` to the step. A step that resumes, or retries, checks
what already happened before doing anything (see
[When nobody knows what happened](#when-nobody-knows-what-happened)). The lease must be longer
than the slowest single step, which is bounded by the dependency timeouts.

### Stopping cleanly

A deploy shouldn't cost every in-flight order a lease's wait, so `SIGTERM` stops the worker
before anything else in the process (it is the last `SmartLifecycle` to start and the first to
stop, so the web server and the in-process simulators are still up while it drains). The
dispatcher stops claiming. Each in-flight order finishes the step it is in, commits it, and gives
its lease back at the next step boundary instead of starting another step. Its row is then
unleased and due now, so the next instance claims it on its first poll and carries on as if the
same worker had continued.

Some steps outlast `payflow.saga.shutdown-grace` (5 s by default, inside `docker stop`'s 10 s).
When it runs out, each order still mid-step is handed back as a crash would leave it, with one
difference: the lease is already expired. The worker replaces the lease token, so any late
write from the abandoned step is fenced out, and sets `lease_until` to `-infinity`, which every
instance's clock has passed. The next claim sees a lease that was not released, so the step runs
with `resumed = true` and looks before acting. The worker then interrupts the abandoned threads.
A recovered claim that is handed back before its resumed step ran is abandoned the same way
rather than released, so the next worker is still told it is resuming.

`ProcessCrashRecoveryTest` stops the packaged jar with `SIGTERM` twice, with a 90 s lease so
that any quick resume must be a lease handed back. The first time, an authorization is in
flight: it finishes and the order is released before capture. The second time, the provider has
applied a capture and holds the answer past a 1 s grace: the order is abandoned. The next
instance resumes it on its first poll, finds the capture and completes it, with one
authorization and one capture on the provider's ledger.

`examples/rolling-restart.py` does the same to a pair of instances under load, outside the gate
because it takes about three minutes. On the compose stack it creates 10,000 orders for two
merchants, then restarts each instance in turn with `docker compose stop` while about 80 orders
a second keep arriving, the fake provider adds 250 ms to every authorization and capture so
steps are always running, and both merchants' listings are paged 25 at a time with each cursor
redeemed on the other instance. A request that finds its instance stopping is retried on the
other with the same idempotency key. On the 0.2.0-rc.1 image (5 Nov 2026, a cloud Linux VM,
everything in Docker) each worker drained in about 240 ms and the process exited 2.3-2.5 s
after `SIGTERM`. All 11,572 orders completed with exactly one authorization, one capture, one
reservation and one shipment each, and both listings had every one of their orders once, newest
first. The same run with a different cursor secret on the second instance fails on page 2 with
`invalid_cursor`, which is the reason `PAYFLOW_CURSOR_SECRETS` must be shared.

### Cancel and refund

A cancel sets a flag, accepted only before capture, and pulls the order's
next attempt forward. The worker checks the flag before each forward step and diverts to that
step's compensation; the order ends `CANCELLED` unless something else had already failed. A
cancel that lands while a capture is in flight takes effect after it: the capture completes, the
next decision sees the flag, and the money is refunded. A refund of a completed order is a
refund row (see [Running refunds](#running-refunds)); the order stays `COMPLETED` until the
provider has returned the money.

### Compensations look before they act

`CANCELLING_SHIPMENT` asks the carrier for a shipment by
reference and cancels it if one exists. `REVERSING_PAYMENT` asks the provider for every payment
with the order's reference and voids each hold and refunds each capture. Neither trusts the order
row, because the forward step they undo may have happened without the row knowing. Running
either twice changes nothing the second time.

## When nobody knows what happened

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

## Getting events to Kafka

Publishing to Kafka from the request (or the step) and committing to Postgres are two systems; a
crash between them loses the event or publishes one for a change that rolled back. The
[transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
(Chris Richardson) writes the event to an `outbox` table in the same transaction as the change.
`OutboxWriter` uses `Propagation.MANDATORY`, so it can't be called outside one. The event's
sequence number is the order's `version`, which every update increments; a refund event takes a
version of its own (see "Refund events and what an order shows").

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

## The consumer side

The order timeline (`GET /v1/orders/{id}/events`) is built by a Kafka consumer, as any downstream
system would build its view. Each event is applied in one transaction that first inserts
`(consumer, event_id)` into `inbox`; on a duplicate the insert conflicts and the event is
skipped. Inbox rows are kept for 14 days, longer than the outbox keeps events, so no redelivery
can outlive its inbox entry. A record that can't be parsed goes to the `payflow.order-events-dlt`
topic straight away; other failures (the database being down) are retried five times, half a
second apart, and then parked there rather than blocking the partition. The consumer's session
timeout is 10 s, so partitions held by a dead instance come back quickly after a crash.

## Merchant webhooks

Merchants learn about their orders without polling by registering webhook endpoints. This part is
being built over the week of #25; so far it has the endpoints themselves, the sender that makes
every attempt, and the dispatcher that turns each relayed event into signed, retried, ordered
deliveries.

### Endpoints

`POST /v1/webhook_endpoints` registers a URL with an event filter, `enabled_events`: `["*"]` for
every type, including types added later, or a list of the types the outbox writes
(`order.created`, `order.cancel_requested`, `order.<state>` for each state an order can move to,
and `refund.created`, `refund.succeeded`, `refund.failed`). `WebhookEvents` builds that list from
the same constants and `OrderState` that the outbox uses, so a new state can't be written without
also becoming subscribable, and `OpenApiContractTest` holds the spec's enum to it. Unknown types,
`*` mixed with others and empty filters are 400s; repeats are dropped. The URL must be an
absolute `http` or `https` URL with a host and no user info or fragment, at most 2,048
characters; scheme and host are lowercased, and an IPv4 host must be four decimal numbers
(`2130706433`, `0177.0.0.1` and `0x7f.1` are refused, since clients disagree on what they mean).
Which addresses that host may resolve to is [the sender's check](#sending-without-reaching-inside),
made at registration and again on every send: an address the sender would refuse, or a name
that resolves to one now, is a 400 that says which address and why. A name that doesn't resolve
yet is accepted, since every send checks again. Changes are a `POST`
to the endpoint (as on Stripe, so they go through idempotency keys like every other write): any of
`url`, `enabled_events`, `description` and `state` (`ENABLED` or `DISABLED`). `DELETE` removes
it, and a second `DELETE` is a 404.

A merchant has at most 16 endpoints, Stripe's limit, which also keeps the listing a single page.
Creating one takes a transaction-scoped advisory lock on the merchant before it counts, so ten
racing creates against twelve existing endpoints make exactly four (`WebhookEndpointApiTest`);
the rest are `409 webhook_endpoint_limit_reached`. A deleted endpoint keeps its row, so its
delivery history stays readable, but leaves the merchant's count, and its secret is wiped.

### The signing secret

Each endpoint gets its own secret, `whsec_` and 32 random bytes, generated by payflow and shown
exactly once, in the `201` that created it. It must be read back to sign every delivery, so
unlike an API key it can't be stored as a hash; it is sealed with AES-256-GCM by `SecretBox`
under `PAYFLOW_SECRET_KEYS`, with a fresh 96-bit nonce per value and the endpoint's id and
merchant bound in as associated data. A ciphertext copied into another endpoint's row therefore
fails to open instead of signing that endpoint's events with someone else's secret. The row keeps
the id of the key that sealed it, so keys rotate: deploy `new,old` (the first seals, all open),
and drop `old` once nothing sealed with it is left.

The idempotency store keeps every POST's answer for 24 hours so a retry can replay it, and the
`201` carries the secret, so that copy would otherwise hold it in the clear. The controller marks
the answer, and the idempotency filter seals its stored copy with the same box, bound to the
merchant and the key, and opens it on replay. A retry after a lost `201` still gets the same
endpoint and the same secret, byte for byte; the database holds neither in the clear, which
`WebhookEndpointApiTest` checks by scanning both tables' rows for the secret.

### Deliveries and attempts

The outbox relay is what feeds webhooks. In the transaction that marks a batch of events
published, after Kafka has acknowledged it, a `RelayListener` inserts one `webhook_deliveries` row
per event and per live, `ENABLED` endpoint of the event's merchant whose filter takes its type,
due at once. So a delivery exists exactly when its event was published: a batch whose commit fails
is published and fanned out again, and the unique (endpoint, event) pair drops the second copy, so
an event the relay hands over twice is delivered once. An endpoint registered after an event was
relayed doesn't get it, and neither does one that was disabled at the time. The row carries the
event's payload, because the outbox row it came from is deleted after its retention and a
delivery must stay replayable; and its order and place in the order's stream (`order_seq`, the
order's version), for ordering. `webhook_attempts` records every try: when it started, how long it
took, the status and the first 1 KiB of the body, or why there was no HTTP answer. The CHECK
constraints refuse a pending delivery with no schedule, a settled one with one, and an attempt
with both or neither of a status and an error; `WebhookSchemaTest` tries each with raw SQL.

### Delivering

`WebhookDispatcher` is a claim loop like the saga's. A dispatcher thread claims due deliveries,
oldest due first, with `FOR UPDATE SKIP LOCKED`, and leases each by bumping its `attempts` and
pushing `next_attempt_at` out by `payflow.webhooks.lease` (60 s, at least the attempt timeout plus
5 s). The attempt number is the fence: an outcome is written only while the delivery is still
pending at the number its claim took, so an instance whose lease ran out can't overwrite the
attempt of the instance that claimed it next. Each claim goes to a virtual thread, at most
`concurrency` (64) at once.

- **Signed.** Each attempt opens the endpoint's sealed secret and sends the stored payload with
  `Payflow-Signature: t=<unix seconds>,v1=<hex>`, where the hex is HMAC-SHA256, keyed with the
  whole `whsec_...` secret, over the timestamp, a full stop and the raw body. Signing the
  timestamp lets a receiver refuse an old request replayed at it (`WebhookSignature.verify`
  accepts five minutes either way); signing the bytes means the receiver checks what arrived
  before parsing it. A header can carry several `v1` values, for rotating secrets. Alongside
  it go `Payflow-Event-Id` (what a receiver deduplicates by), `Payflow-Event-Type`,
  `Payflow-Delivery-Id` and `Payflow-Delivery-Attempt`.
- **Settled by a 2xx.** Anything else (a 3xx, which is never followed, a 4xx, a 5xx) and no answer
  at all (refused address, DNS, connect, TLS, timeout) is a failed attempt, recorded with its
  status or reason, and the delivery is retried.
- **Retried for three days.** The wait before attempt *n* is uniform in [0, min(1 h, 5 s × 2^n)]
  (full jitter, the saga's `Backoff`), so a fleet of instances doesn't retry an endpoint that
  was down in lockstep when it comes back. Retries go on until `retry-window` (3 days) after the
  event; a retry that would land past the window lands on its edge instead, and an attempt that
  fails at or after it gives the delivery up as `FAILED`. At the 1 h cap that is about 150
  attempts for an endpoint that never answers; `WebhookDispatcherTest` drives one through the
  whole window (at a 10-minute cap, several hundred attempts) and checks that every attempt is
  logged and the last is exactly on the edge.
- **In order per order and endpoint.** A delivery is due only when no earlier event of the same
  order is still pending for the same endpoint, whether being sent or waiting to retry, so each
  endpoint sees one order's events in the order they happened. The check is a `NOT EXISTS` on
  the (endpoint, order, seq) index inside the claim; since a delivery in flight stays `PENDING`,
  two instances can't send one order's events to an endpoint out of order either. Other orders
  aren't held up, and neither are other endpoints: a failing endpoint holds back only its own
  copies. A delivery that was given up no longer holds back the ones after it.
- **Fair between endpoints.** One instance sends at most `per-endpoint` (8) deliveries to an
  endpoint at once. The claim skips endpoints already at that number, and what a batch brings
  past it is handed straight back with its attempt returned. An endpoint that hangs until every
  attempt times out therefore holds 8 of the 64 slots; `WebhookDispatcherTest` has a slow
  endpoint at its limit of 2 while a second endpoint's six deliveries all land.
- **Disabled and deleted endpoints.** A disabled endpoint's pending deliveries wait, and go out
  once it is enabled again; while disabled it gets no new ones. Deleting an endpoint gives up
  its pending deliveries in the same transaction.
- **Stopping.** `stop()` stops claiming and gives attempts in flight `shutdown-grace` (3 s) to
  finish. One still waiting for its answer then is recorded as an attempt cut short
  (`shutdown: payflow stopped before the answer arrived`) and made due at once, so the next
  instance sends it on its first poll. The merchant may get that event twice, which the
  at-least-once contract allows; a crash instead leaves the lease to run out (60 s).

### Sending without reaching inside

A webhook URL is chosen by the merchant, and payflow runs next to its database, its providers and
often a cloud metadata service, so the sender must never become a way to reach them (server-side
request forgery). `SafeHttpSender` is a small HTTP/1.1 client over a plain socket, because
off-the-shelf clients look the host up themselves, after any check, and may follow redirects or
go through a proxy that looks it up again:

- **One lookup, every address checked.** Each send resolves the host once and checks every address
  it gets against `AddressPolicy`. One refused address refuses the send, so a name that mixes a
  public and a private answer gets nothing. The connection then goes to one of those checked
  addresses (in order, the next if one refuses the connection), so a name that answers
  differently on a second lookup (DNS rebinding) never gets one. The next send looks up afresh
  and is checked afresh. No proxy is ever used.
- **What is refused.** Public unicast only. In IPv4: `0/8`, `10/8`, `100.64/10` (CGNAT), `127/8`,
  `169.254/16` (link-local, which holds the metadata address `169.254.169.254`), `172.16/12`,
  `192.0.0/24`, the documentation and benchmarking ranges, `192.88.99/24`, `192.168/16`,
  multicast, `240/4` and broadcast. In IPv6, an address that carries an IPv4 address (IPv4-mapped
  `::ffff:0:0/96`, IPv4-translated, NAT64 `64:ff9b::/96`, IPv4-compatible `::/96` and 6to4
  `2002::/16`) is judged by the IPv4 address inside, so `::ffff:169.254.169.254` is the metadata
  address; anything else must be global unicast (`2000::/3`) outside its special-purpose blocks
  (`2001::/23`, documentation, local NAT64), and `::1`, `fc00::/7`, `fe80::/10`, `fec0::/10` and
  multicast are refused. `PAYFLOW_WEBHOOK_ALLOWED_NETWORKS` (empty by default) lets listed CIDR
  networks through for a development stack; it is logged as a warning at startup.
- **The URL's own host.** The request carries the URL's host in `Host`, and over TLS in SNI; the
  certificate is verified against that host (TLS 1.2 or 1.3), never against the address
  connected to. An `https` URL with an IP literal needs a certificate for that address.
- **No redirects.** A 3xx is an answer like any other, and since it isn't a 2xx the delivery has
  failed; following one would send the event, and its signature, to a URL nobody checked.
- **One deadline.** From the lookup to the last byte read, an attempt has
  `payflow.webhooks.timeout` (10 s), and connecting to one address `connect-timeout` (3 s). A
  lookup that hangs is abandoned at the deadline, and a watchdog closes the socket when it
  passes, so a server that sends one byte a second can't hold a thread. A status and headers
  that arrive in time count, even if the body is still coming: the body is only for the log.
- **A capped answer.** At most 16 KiB of status line and headers, and the first 1 KiB of the body
  (de-chunked), which is what `webhook_attempts` keeps; the rest is never read. Callers can't set
  `Host`, `Content-Length`, `Transfer-Encoding` or `Connection`, and a header containing a line
  break is refused, so a header value can't smuggle a second request.

An attempt ends as an HTTP status, or as one of `address_refused`, `dns`, `connect`, `tls`,
`timeout`, `protocol` or `io` with a detail, which is the `error` an attempt row records.
`SafeHttpSenderTest` runs it against real sockets: a rebinding resolver, a mixed answer, mapped and
NAT64 literals, a redirect, a head and a body sent a byte at a time, a 4 MiB body (by length,
until close and chunked), an oversized head, a non-HTTP answer, and TLS with SNI checked on the
server and a certificate for the wrong name. `AddressPolicyTest` covers every range.

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

A replay is the first answer byte for byte, so a replayed error carries the first request's
`request_id` in its body; the `X-Request-Id` header is always the current request's. An answer
that carries a secret shown once (a new webhook endpoint's) is stored sealed, see
[the signing secret](#the-signing-secret); `PAYFLOW_SECRET_KEYS` must therefore keep a retired
key for at least the 24 hours a key is kept, or a replay of an answer sealed with it is a `500`.

## Errors

Every error, from a filter or from a controller, is
`{"error":{"code","message","request_id"}}`, and the code comes from one enum, `ApiError`, which
pairs each code with its status. A filter or handler can't invent a code: it has to name one of
eighteen. The exception handler sets the content type rather than negotiating it, so even a 406
for an `Accept` that rules out JSON gets the JSON body.

openapi.yaml lists, on every error response, the codes it can carry (`x-error-codes`), and the
five that can happen on any request (unknown path, wrong method, unacceptable `Accept`, internal
error, database unreachable) once at the top. `OpenApiContractTest` holds the two to each other
in both directions: the codes in the spec are exactly `ApiError`'s, each under the status
`ApiError` gives it; every (operation, status, code) the spec documents is produced by a real
request; and every response a request produces is documented, with a body that validates and the
headers the spec promises (`X-Request-Id` everywhere, `Location` on a 201, `WWW-Authenticate` on
a 401, `Idempotent-Replayed` only where a stored answer can be replayed). Adding a code or a
response without the other fails the build.

## What fails, and what happens next

| Failure | Effect | Recovery |
|---|---|---|
| Provider returns 503 or refuses connections | Step retries with backoff | Automatic; after 8 attempts or 2 min the order is compensated |
| Provider applies a call and the answer is lost | Step retries after at least 2 s | Lookup by reference finds the result; no second charge |
| Card declined, out of stock, address refused | Step fails | Compensation from that step; order ends `FAILED` with the reason |
| Compensation keeps failing | Order stays in the compensating state | Retries forever, capped at 30 s apart; error log after 10 attempts |
| Provider refuses a refund | Refund ends `FAILED` with the reason; the order is unchanged | Its share of the capture is free for another refund; error log |
| Refund answer lost, or payflow killed mid-refund | Refund stays `PENDING` | The next attempt finds the provider's refund by the row's id; no second refund |
| Restock answer lost after a line refund | Refund stays `PENDING`; the money is back | The next attempt finds the provider's refund, and the restock replays by the refund's id; stock comes back once |
| Inventory refuses a restock | Refund stays `PENDING`; error log | Retries until the inventory system accepts it; the money is never refunded twice |
| payflow killed mid-step | Lease left behind | Lease expires (30 s); the next worker resumes and looks before acting |
| payflow stopped with `SIGTERM` | Running steps finish for up to 5 s; leases are handed back | Next instance claims on its first poll; a step cut off at the deadline resumes and looks before acting |
| Two workers think they own an order | The stale one's writes are fenced out | Compare-and-set on lease token and state |
| Kafka down | Events wait in the outbox | Relay retries; backlog gauge rises; API and saga unaffected |
| Relay dies after sending, before commit | Events sent again | Consumers deduplicate by event id |
| Malformed event on the topic | Moved to the dead-letter topic | Consumer continues |
| Merchant's webhook endpoint down, slow or answering non-2xx | Attempt logged; that order's later events wait for that endpoint | Retried with backoff for 3 days, then given up; other orders and endpoints carry on |
| payflow killed mid-webhook | Delivery lease left behind | Lease expires (60 s); the next instance sends it again, and the receiver deduplicates by event id |
| Postgres down | API requests get 503 `unavailable` (a POST before doing anything, or rolled back if the connection drops mid-request); workers and relay log and keep polling | Resumes when Postgres returns; nothing is held in memory |
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
| `merchant_reference` | 1-128 printable ASCII, no spaces; unique per merchant | `CreateOrderRequest`, `orders_merchant_reference_format` |
| `Idempotency-Key` | 1-255 printable ASCII, no spaces | `IdempotencyFilter` |
| Orders per listing page | 1-100, default 20 | `OrderListRequest` |
| Listing cursor secret | at least 32 characters each | `payflow.cursor-secrets`, `OrderCursors` |
| Webhook endpoints per merchant | 16 | `WebhookEndpointService.MAX_ENDPOINTS` |
| Webhook endpoint URL / description / event types | 2,048 / 256 characters / 1-32 | `WebhookUrls`, `WebhookEndpointService`, `WebhookEvents` |
| Webhook attempt: whole / connect / head / body kept | 10 s / 3 s / 16 KiB / 1 KiB | `payflow.webhooks.*`, `SafeHttpSender` |
| Webhook retries | full jitter from 5 s, capped at 1 h, for 3 days after the event | `payflow.webhooks.retry-*` |
| Webhook attempts in flight per instance: all / per endpoint | 64 / 8 | `payflow.webhooks.concurrency`, `per-endpoint` |
| Webhook lease / shutdown grace | 60 s / 3 s | `payflow.webhooks.lease`, `shutdown-grace` |
| Secret sealing keys | 32 bytes each, ids of 1-32 `a-z 0-9 _ -` | `payflow.secret-keys`, `SecretBox` |
| Idempotency lock timeout / retention | 60 s / 24 h | `payflow.idempotency.*` |
| `X-Request-Id` accepted | 1-64 of `A-Z a-z 0-9 . _ -`, else replaced | `RequestIds` |
| API key length | 256 characters | `ApiKeys` |
| Merchant id | 1-64 of `a-z 0-9 _ -` | `ApiKeys`, `orders_merchant_format` |
| Refunds of an order | add up to at most the capture, and at most each line's quantity | `refunds_within_order` trigger, `RefundService` |
| Refund amount / lines per refund / quantity per line | 1-99,999,999 minor units / 1-50 / 1-1,000 | `CreateRefundRequest` |
| Refunds claimed per poll and in flight | 64 | `payflow.saga.batch-size` |
| Saga lease | 30 s | `payflow.saga.lease` |
| Wait for in-flight steps at shutdown | 5 s | `payflow.saga.shutdown-grace` |
| Orders claimed per poll / in flight | 64 / 256 | `payflow.saga.batch-size`, `max-in-flight` |
| Backoff | 200 ms base, 30 s cap, full jitter | `payflow.saga.backoff-*` |
| Forward step limit | 8 attempts or 2 min | `payflow.saga.max-attempts`, `step-timeout` |
| Delay before resolving an unknown outcome | 2 s | `payflow.saga.unknown-outcome-delay` |
| Dependency connect / read timeout | 500 ms / 2 s | `payflow.dependencies.*` |
| Outbox batch / send timeout / retention | 100 orders / 10 s / 7 days | `payflow.outbox.*` |
| Inbox retention | 14 days | `TimelineProjection.INBOX_RETENTION` |
| Events returned per timeline | 1,000 | `TimelineProjection.forOrder` |
| Refunds shown on an order | the 10 newest, with `has_more` | `RefundSummary.RECENT` |
| Consumer session timeout | 10 s | `spring.kafka.consumer.properties` |
| Database pool | 20 connections | `PAYFLOW_DB_POOL` |

## Security

### Authentication

Merchants authenticate with `Authorization: Bearer <key>`. Keys are 32 random
bytes (`pf_` plus base64url) and configuration holds only their SHA-256, as `merchant:hash`
pairs, so a leaked config or database dump has no usable keys. SHA-256 instead of a password hash
such as bcrypt: the keys have 256 bits of entropy, so there is nothing to brute-force, and a slow
hash on every request would cost CPU for nothing. A presented key is hashed once and compared with
every configured hash using `MessageDigest.isEqual`, without stopping at a match, so the timing
reveals neither which merchant matched nor how much of a hash did.

### Authorization

Every query on orders, idempotency keys, webhook endpoints and the timeline includes
the merchant id. Another merchant's order or endpoint is a 404, indistinguishable from a missing
one; `OrderApiTest` and `WebhookEndpointApiTest` check every endpoint.

### Input

Bean Validation on every field, and the same rules again in the database for the ones
that matter (state, amount, currency format, merchant format, merchant reference format). JSON is read strictly: unknown
fields, strings where numbers belong (`"2"`), numbers or booleans where strings belong (`"sku":
123`), decimals where integers belong and numbers that overflow are all 400s. Provider responses
are read by a separate tolerant mapper that ignores unknown fields and accepts a number as a
string. The total is computed on the server. Free text may contain markup, which
is stored and returned as data inside JSON and never rendered; control characters are refused.
Bodies are capped before they are buffered, including chunked ones.

Listing cursors are signed with HMAC-SHA256 under `PAYFLOW_CURSOR_SECRETS` and checked in
constant time against every configured secret without stopping at a match. They carry only a
timestamp and an order id, so a leaked cursor reveals no more than the merchant could already
read; a cursor from another merchant or another listing is refused.

### Outbound credentials

Provider keys are only sent to the base URL from configuration, which
must be a plain `http(s)://host[:port][/path]` with no user info, query or fragment. Ids that come
back in provider responses (a payment id, a shipment id) are encoded as single path segments, so
a hostile id can't add path components or a query; the client doesn't follow redirects. Responses
are read tolerantly (unknown fields ignored), because a provider adding a field shouldn't cause an
outage.

### Secrets at rest

What payflow must read back in the clear, webhook signing secrets and the stored answers that
carry one, is sealed with AES-256-GCM (`SecretBox`) under `PAYFLOW_SECRET_KEYS`, a list of
`id:base64key` entries of 32 bytes each; see [the signing secret](#the-signing-secret). Every
instance needs the same list. Unset, each process seals with a random key of its own and logs a
warning: fine for a single demo instance, but secrets sealed that way can't be opened after a
restart. Nothing else needs it: API keys and listing cursors are hashed and signed, not sealed.

### Secrets in logs

Keys are never logged; `SimulatorKey`, `ApiKeys`, `OrderCursors` and `SecretBox` override
`toString`.
`ObservabilityTest` runs a full order and checks no API key, provider key or `Bearer` string
appears in any log line.

### Operational surface

Actuator runs on a separate management port bound to 127.0.0.1 and
exposes only health and Prometheus; liveness and readiness are also served on the API port.
`/livez` carries one detail, the running version (its `build` component, which is always up), so
a rolling deploy shows which instances run what; the same version is the first line logged once
the service is ready. The
fake providers require their own bearer key and can be switched off
(`PAYFLOW_SIMULATOR_ENABLED=false`); a production deployment would. The container image is
distroless and runs as `nonroot`.

## Dependencies

Each one is pinned through the Gradle lockfile, at a release at least two weeks old.

| Dependency | Why |
|---|---|
| Spring Boot 4.1 (web MVC, JDBC, validation, actuator, Jackson 3) | HTTP, configuration, health and metrics are commodity; Boot is what Java teams run. JDBC through `JdbcClient` rather than JPA: every query here is specific (`SKIP LOCKED`, compare-and-set, `ON CONFLICT`) and reads better as SQL than as entity mappings. |
| Spring for Apache Kafka | Listener containers, retries and the dead-letter recoverer are a lot of careful code to own. |
| PostgreSQL driver, Flyway | The database and versioned migrations. |
| Micrometer Prometheus registry | Exposition format for the metrics. |
| Testcontainers, jqwik, Awaitility | Real Postgres and Redpanda in tests; property-based testing with shrinking; waiting on asynchronous outcomes without sleeps. |
| Error Prone, Spotless with google-java-format, JaCoCo | Lint as errors, one formatting, the coverage floor. |
| Docker Buildx actions (release only) | Multi-arch build, push and the GitHub Actions layer cache. The SBOM is a 180-line script over the lockfile instead of the CycloneDX Gradle plugin, which would add its own unlocked dependency tree to the build. |
| osv-scanner (CI only) | Known-vulnerability scan of `gradle.lockfile`. Chosen over OWASP dependency-check, which needs an NVD API key and a multi-gigabyte local database, and reads build output rather than the lockfile. |

Left out on purpose: Spring Security (one filter and a constant-time comparison cover API keys),
a resilience library (backoff with jitter is thirty lines, and the retry policy is tied to the
saga's persisted state, not to a call stack), an OpenAPI generator (the document is written by
hand and tested against the routes), and a JSON Schema library (the contract test validates the
subset the document uses in about a hundred lines).

Spring Boot 4.1.1 manages a few libraries with known vulnerabilities fixed in later patch
releases (Jackson, Tomcat, the Postgres driver, lz4-java); the build pins the patched versions,
each on the line Boot already uses. The osv-scanner ignore list is empty: the two Spring
Framework 6.2 advisories it used to carry are fixed in Framework 7.0.9, which Boot 4.1.1 brings.
JSON goes through Jackson 3, whose defaults are stricter than Jackson 2's; `application.yml`
still states every strict setting the API relies on (unknown fields, floats as integers, scalar
coercion, null primitives) rather than trusting the defaults of either version.

## Releases

A tag `vX.Y.Z` (or `vX.Y.Z-rc.N`, published as a prerelease) on a commit that is on main runs
`.github/workflows/release.yml`. The version comes only from the tag: Gradle receives it as
`-PreleaseVersion` and writes it into `META-INF/build-info.properties`, without a build time, so
the same commit and version give the same jar. The workflow runs `./gradlew check` again on the
tagged commit, then:

- **SBOM.** `scripts/sbom.py` writes CycloneDX 1.6 from `gradle.lockfile`: the coordinates are the
  ones locked for `productionRuntimeClasspath`, and the jar is read to keep only what is packaged
  (platform BOMs are locked but not shipped) and to record each library's SHA-256. A jar in
  `BOOT-INF/lib` that the lockfile does not explain fails the build; CI runs the same check on
  every push. A lockfile is flat, so the SBOM says the application contains each library without
  inventing which library pulled in which.
- **Image.** The Dockerfile builds the jar on the runner's own platform and only the runtime
  stage, which copies files and runs nothing, is per architecture, so amd64 and arm64 come from
  one build with no emulation. The amd64 image is started on the compose stack first and must
  report the tag's version on `/livez` and in its log before anything is pushed to
  `ghcr.io/laiwf94/payflow`. `latest` moves only for a final release.
- **Release.** The jar (`payflow-X.Y.Z.jar`), the SBOM and `SHA256SUMS` are attached to a GitHub
  release whose notes give the image digest.

CHANGELOG.md lists what changed, under "Unreleased" until a tag names the version, and the
release notes link to it. `PAYFLOW_IMAGE` points the compose stack at a published image, so
`examples/demo.sh` and `examples/rolling-restart.py` run unchanged against a release.

## Growing past one database

The design already runs as several identical instances against one Postgres: workers and relays
coordinate only through `SKIP LOCKED`, leases and compare-and-set, and nothing is held in memory
between requests. What would change with more volume:

Commits come first. An order costs around a dozen of them (idempotency claim and response, saga
transitions, inbox), and at the benchmark's saturation point Postgres waits on WAL writes.
Grouping a worker's transition with the next step's lease extension, or applying several
consumer events per transaction, would cut that; so would faster storage than a laptop's
virtual disk.

`orders` is updated several times per order and `outbox` is insert-then-update, which makes them
the hot tables. Partitioning `outbox` by day turns cleanup into dropping partitions instead of
deletes.

One relay per instance with head-of-queue locking scales with the number of distinct orders in
flight. Logical decoding (reading the WAL with Debezium or a native subscriber) would remove the
polling and the update per event, at the cost of another moving part.

With many merchants, rate limits per merchant and per dependency would stop one merchant's burst
or one slow provider from occupying all 256 in-flight slots.

The saga assumes one Postgres primary. Running across regions means choosing a home region per
order and routing its steps there, rather than replicating leases.
