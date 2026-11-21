#!/usr/bin/env bash
# Timer drain benchmark: TIMERS durable timers all due within SPREAD, fired by INSTANCES payflow
# processes against one Postgres, then checked for exactly-once firing.
#
#   ./bench/timers.sh                          # 10,000 timers due within 1 s, 3 instances, 3 runs
#   TIMERS=50000 INSTANCES=1 RUNS=1 ./bench/timers.sh
#   TIMER_CONCURRENCY=16 ./bench/timers.sh     # payflow.timers.concurrency per instance
#
# Each timer is a real order.payment_expiry timer on a real PENDING order, fired by the real
# ExpiryHandler: one transaction that deletes the timer, flags the order cancel_requested with
# cancel_reason payment_expired, writes order.cancel_requested to the outbox and commits. The
# saga worker is off, so each order keeps the fire's time in updated_at and the drain measures
# the timer path alone; the outbox relay still publishes every event to Redpanda.
#
# A run fails unless every timer fired exactly once and none before it was due:
#   - the timers table is empty and every order was cancelled as payment_expired;
#   - each order has exactly one order.cancel_requested event;
#   - the instances' payflow.timers.fire{outcome=fired} counters add up to TIMERS, and nothing
#     was retried or lost;
#   - no order was flagged (its handler's clock) before its timer's due time.
#
# Lateness is the handler's clock minus the due time, per timer. Needs docker, jq, curl and a
# JDK 25.
set -euo pipefail
cd "$(dirname "$0")/.."

TIMERS=${TIMERS:-10000}
SPREAD_MS=${SPREAD_MS:-1000}
INSTANCES=${INSTANCES:-3}
RUNS=${RUNS:-3}
LEAD_MS=${LEAD_MS:-5000}
PG_PORT=${PG_PORT:-55442}
KAFKA_PORT=${KAFKA_PORT:-59192}
API_PORT=${API_PORT:-18285}
OUT=bench/results
mkdir -p "$OUT"

if ((TIMERS < 1 || SPREAD_MS < 1 || INSTANCES < 1 || RUNS < 1 || LEAD_MS < 1000)); then
  echo "TIMERS, SPREAD_MS, INSTANCES and RUNS must be positive and LEAD_MS at least 1000" >&2
  exit 2
fi

KEY=pf_bench_acme_0123456789abcdefghijklmnop
if command -v sha256sum > /dev/null; then
  HASH=$(printf %s "$KEY" | sha256sum | cut -d' ' -f1)
else
  HASH=$(printf %s "$KEY" | shasum -a 256 | cut -d' ' -f1)
fi

PIDS=()
cleanup() {
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do kill "$pid" 2> /dev/null || true; done
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do wait "$pid" 2> /dev/null || true; done
  docker rm -f payflow-timers-pg payflow-timers-kafka > /dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --rm --name payflow-timers-pg -p 127.0.0.1:$PG_PORT:5432 \
  -e POSTGRES_DB=payflow -e POSTGRES_USER=payflow -e POSTGRES_PASSWORD=bench \
  postgres:17.10-alpine -c max_connections=200 -c synchronous_commit=${PG_SYNC_COMMIT:-on} > /dev/null
docker run -d --rm --name payflow-timers-kafka -p 127.0.0.1:$KAFKA_PORT:$KAFKA_PORT \
  docker.redpanda.com/redpandadata/redpanda:v25.2.9 redpanda start --mode=dev-container --smp=1 \
  --kafka-addr=0.0.0.0:$KAFKA_PORT --advertise-kafka-addr=127.0.0.1:$KAFKA_PORT > /dev/null

./gradlew -q bootJar
until docker exec payflow-timers-pg pg_isready -U payflow > /dev/null 2>&1; do sleep 0.5; done
until docker exec payflow-timers-kafka rpk cluster health 2> /dev/null | grep -q 'Healthy:.*true'; do
  sleep 0.5
done

psql() { docker exec -i payflow-timers-pg psql -U payflow -d payflow -v ON_ERROR_STOP=1 -At "$@"; }

EXTRA=()
[[ -n ${TIMER_CONCURRENCY:-} ]] && EXTRA+=(--payflow.timers.concurrency="$TIMER_CONCURRENCY")
for i in $(seq 0 $((INSTANCES - 1))); do
  port=$((API_PORT + 2 * i))
  PAYFLOW_DB_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/payflow PAYFLOW_DB_PASSWORD=bench \
  PAYFLOW_DB_POOL=${DB_POOL:-20} PAYFLOW_KAFKA_BOOTSTRAP=127.0.0.1:$KAFKA_PORT \
  PAYFLOW_API_KEYS="acme:$HASH" PAYFLOW_PORT=$port PAYFLOW_MANAGEMENT_PORT=$((port + 1)) \
    java -Xmx512m -jar build/libs/payflow.jar \
    --payflow.saga.enabled=false \
    --logging.level.io.github.laiwf94.payflow=WARN ${EXTRA[@]+"${EXTRA[@]}"} \
    > "$OUT/timers-app-$i.log" 2>&1 &
  PIDS+=($!)
  # One at a time, so Flyway migrates once and the others find it done.
  until curl -fsS "http://127.0.0.1:$port/readyz" > /dev/null 2>&1; do
    kill -0 "${PIDS[-1]}" 2> /dev/null || { echo "instance $i died; see $OUT/timers-app-$i.log" >&2; exit 1; }
    sleep 0.5
  done
done

# Sum of one counter over every instance, from each one's Prometheus endpoint.
metric() {
  local sum=0 i v
  for i in $(seq 0 $((INSTANCES - 1))); do
    v=$(curl -fsS "http://127.0.0.1:$((API_PORT + 2 * i + 1))/actuator/prometheus" \
      | awk -v re="$1" '$0 ~ re { s += $NF } END { printf "%d", s }')
    sum=$((sum + v))
  done
  echo "$sum"
}
FIRED='^payflow_timers_fire_seconds_count\\{.*kind="order\\.payment_expiry".*outcome="fired"'
RETRIED='^payflow_timers_retries_total\\{'
LOST='^payflow_timers_lost_total\\{'
per_instance() {
  local i out=()
  for i in $(seq 0 $((INSTANCES - 1))); do
    out+=("$(curl -fsS "http://127.0.0.1:$((API_PORT + 2 * i + 1))/actuator/prometheus" \
      | awk -v re="$FIRED" '$0 ~ re { s += $NF } END { printf "%d", s }')")
  done
  local IFS=/
  echo "${out[*]}"
}

load() { # $1 timers, $2 spread in ms
  psql -v n="$1" -v spread="$2" -v lead="$LEAD_MS" <<'SQL'
SET client_min_messages = warning;
TRUNCATE orders, outbox, inbox, order_timeline, timers CASCADE;
WITH t AS (SELECT clock_timestamp() + make_interval(secs => :lead / 1000.0) AS first),
gen AS (
  -- Due times evenly over the spread, in a random order relative to the order ids. A payment
  -- expiry is due one payment window (1 h) after the order was created, as OrderHolds schedules it.
  SELECT gen_random_uuid() AS id,
         date_trunc('milliseconds', t.first)
           + make_interval(secs => (i * :spread / 1000.0) / :n) AS due
  FROM t, generate_series(0, :n - 1) AS i
),
o AS (
  INSERT INTO orders (id, merchant_id, state, amount_minor, currency, items, shipping_address,
      payment_method, step_started_at, next_attempt_at, correlation_id, created_at, updated_at)
  SELECT id, 'acme', 'PENDING', 2500, 'EUR',
         '[{"sku":"sku_bench","quantity":1,"unit_amount":2500}]',
         '{"name":"Bench","line1":"1 Main St","city":"Berlin","postal_code":"10115","country":"DE"}',
         'pm_card_ok', due - interval '1 hour', due + interval '10 years',
         'bench-' || id, due - interval '1 hour', due - interval '1 hour'
  FROM gen
  RETURNING id, created_at
)
INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, created_at, updated_at)
SELECT 'order.payment_expiry', id, created_at + interval '1 hour',
       created_at + interval '1 hour', clock_timestamp(), clock_timestamp()
FROM o;
SQL
}

drain() { # $1 timers; waits until the table is empty or 120 s after the first was due
  local left
  for _ in $(seq 1 $(((LEAD_MS + 120000) / 100))); do
    left=$(psql -c "SELECT count(*) FROM timers")
    [[ $left == 0 ]] && return 0
    sleep 0.1
  done
  echo "  $left of $1 timers still pending 120 s after the first was due" >&2
  return 1
}

printf '%-6s %-8s %-10s %-12s %-12s %-12s %-12s %-12s %s\n' run timers "drain" "timers/s" \
  "late p50" "late p99" "late max" "after last" "fired per instance"
failed=0
# A warm-up run of 1,000 timers first, so the measured runs don't pay for JIT and pool start.
for run in warmup $(seq 1 "$RUNS"); do
  n=$TIMERS
  [[ $run == warmup ]] && n=1000
  fired0=$(metric "$FIRED") retried0=$(metric "$RETRIED") lost0=$(metric "$LOST")
  per0=$(per_instance)
  load "$n" "$SPREAD_MS" > /dev/null
  if ! drain "$n"; then failed=1; continue; fi
  # Let the counters, which move after each commit, catch up with the table.
  sleep 0.5
  fired=$(($(metric "$FIRED") - fired0))
  retried=$(($(metric "$RETRIED") - retried0))
  lost=$(($(metric "$LOST") - lost0))
  IFS=/ read -r -a p0 <<< "$per0"
  IFS=/ read -r -a p1 <<< "$(per_instance)"
  per=()
  for i in "${!p1[@]}"; do per+=($((p1[i] - p0[i]))); done
  read -r orders cancelled early events dupes drain_ms p50 p99 pmax after <<< "$(psql -F ' ' -c "
    WITH f AS (
      SELECT o.id, o.cancel_reason, o.updated_at AS fired_at, o.created_at + interval '1 hour' AS due,
             (SELECT count(*) FROM outbox e
              WHERE e.aggregate_id = o.id AND e.event_type = 'order.cancel_requested') AS events
      FROM orders o
    )
    SELECT count(*),
           count(*) FILTER (WHERE cancel_reason = 'payment_expired'),
           count(*) FILTER (WHERE fired_at < due),
           sum(events),
           count(*) FILTER (WHERE events <> 1),
           round(1000 * extract(epoch FROM max(fired_at) - min(due))),
           round(1000 * extract(epoch FROM percentile_cont(0.5) WITHIN GROUP (ORDER BY fired_at - due))::numeric, 1),
           round(1000 * extract(epoch FROM percentile_cont(0.99) WITHIN GROUP (ORDER BY fired_at - due))::numeric, 1),
           round(1000 * extract(epoch FROM max(fired_at - due))::numeric, 1),
           round(1000 * extract(epoch FROM max(fired_at) - max(due))::numeric, 1)
    FROM f")"
  problems=()
  ((orders == n)) || problems+=("$orders orders, expected $n")
  ((cancelled == n)) || problems+=("$((n - cancelled)) orders not cancelled as payment_expired")
  ((events == n && dupes == 0)) || problems+=("$events cancel events, $dupes orders without exactly one")
  ((fired == n)) || problems+=("fired counters add up to $fired, expected $n")
  ((retried == 0 && lost == 0)) || problems+=("$retried retried, $lost lost")
  ((early == 0)) || problems+=("$early fired before they were due")
  rate=$(awk -v n="$n" -v ms="$drain_ms" 'BEGIN { printf "%d", (ms > 0 ? n * 1000 / ms : n) }')
  printf '%-6s %-8s %-10s %-12s %-12s %-12s %-12s %-12s %s\n' "$run" "$n" "$drain_ms ms" \
    "$rate" "$p50 ms" "$p99 ms" "$pmax ms" "$after ms" "$(IFS=/; echo "${per[*]}")"
  if ((${#problems[@]})); then
    printf '  FAILED: %s\n' "${problems[@]}" >&2
    failed=1
  fi
done
exit "$failed"
