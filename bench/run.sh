#!/usr/bin/env bash
# End-to-end benchmark: payflow running natively with the in-process simulators (no added
# latency), Postgres and Redpanda in Docker, k6 creating orders at fixed rates.
#
#   ./bench/run.sh            # rates 250 500 1000 2000, 30 s each
#   RATES="500" DURATION=60s ./bench/run.sh
#
# Saga latency is measured in Postgres: completed_at (updated_at of the COMPLETED row) minus
# created_at, for orders created during the run. Needs docker, k6, jq and a JDK 21.
set -euo pipefail
cd "$(dirname "$0")/.."

RATES=${RATES:-"250 500 1000 2000"}
DURATION=${DURATION:-30s}
PG_PORT=${PG_PORT:-55432}
KAFKA_PORT=${KAFKA_PORT:-59092}
API_PORT=${API_PORT:-18085}
OUT=bench/results
mkdir -p "$OUT"

KEY=pf_bench_acme_0123456789abcdefghijklmnop
HASH=$(printf %s "$KEY" | shasum -a 256 | cut -d' ' -f1)

cleanup() {
  [[ -n ${APP_PID:-} ]] && kill "$APP_PID" 2> /dev/null || true
  docker rm -f payflow-bench-pg payflow-bench-kafka > /dev/null 2>&1 || true
}
trap cleanup EXIT

# PG_TMPFS=1 keeps Postgres data in memory, to see the service's own ceiling when the disk
# (Docker Desktop's virtual disk, here) is not the limit.
PG_DATA=()
[[ ${PG_TMPFS:-0} == 1 ]] && PG_DATA=(--tmpfs /var/lib/postgresql/data)
docker run -d --rm --name payflow-bench-pg -p 127.0.0.1:$PG_PORT:5432 ${PG_DATA[@]+"${PG_DATA[@]}"} \
  -e POSTGRES_DB=payflow -e POSTGRES_USER=payflow -e POSTGRES_PASSWORD=bench \
  postgres:17.10-alpine -c max_connections=200 -c synchronous_commit=${PG_SYNC_COMMIT:-on} > /dev/null
docker run -d --rm --name payflow-bench-kafka -p 127.0.0.1:$KAFKA_PORT:$KAFKA_PORT \
  docker.redpanda.com/redpandadata/redpanda:v25.2.9 redpanda start --mode=dev-container --smp=2 \
  --kafka-addr=0.0.0.0:$KAFKA_PORT --advertise-kafka-addr=127.0.0.1:$KAFKA_PORT > /dev/null

./gradlew -q bootJar
until docker exec payflow-bench-pg pg_isready -U payflow > /dev/null 2>&1; do sleep 0.5; done
until docker exec payflow-bench-kafka rpk cluster health 2> /dev/null | grep -q 'Healthy:.*true'; do sleep 0.5; done

psql() { docker exec payflow-bench-pg psql -U payflow -d payflow -At -c "$1"; }

PAYFLOW_DB_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/payflow PAYFLOW_DB_PASSWORD=bench \
PAYFLOW_DB_POOL=${DB_POOL:-40} PAYFLOW_KAFKA_BOOTSTRAP=127.0.0.1:$KAFKA_PORT PAYFLOW_API_KEYS="acme:$HASH" \
PAYFLOW_PORT=$API_PORT PAYFLOW_MANAGEMENT_PORT=$((API_PORT + 1)) \
  java -Xmx2g -jar build/libs/payflow-0.1.0.jar \
  --logging.level.io.github.laiwf94.payflow=WARN \
  > "$OUT/app.log" 2>&1 &
APP_PID=$!
until curl -fsS "http://127.0.0.1:$API_PORT/readyz" > /dev/null 2>&1; do sleep 0.5; done

# Warm-up: JIT and connection pools.
k6 run -q -e API="http://127.0.0.1:$API_PORT" -e KEY="$KEY" -e RATE=200 -e DURATION=15s bench/orders.js > /dev/null

printf '%-8s %-10s %-12s %-12s %-12s %-12s %-10s\n' rate completed "orders/s" "api p50" "api p99" "saga p50" "saga p99"
for rate in $RATES; do
  psql "TRUNCATE orders, outbox, inbox, order_timeline, idempotency_keys" > /dev/null
  k6 run -q -e API="http://127.0.0.1:$API_PORT" -e KEY="$KEY" -e RATE="$rate" -e DURATION="$DURATION" \
    --summary-export "$OUT/k6-$rate.json" bench/orders.js > /dev/null
  # Let the saga finish whatever is still in flight.
  for _ in $(seq 1 240); do
    left=$(psql "SELECT count(*) FROM orders WHERE next_attempt_at IS NOT NULL")
    [[ $left == 0 ]] && break
    sleep 0.5
  done
  read -r done tput p50 p99 <<< "$(psql "
    SELECT count(*),
           round(count(*) / extract(epoch FROM max(updated_at) - min(created_at))),
           round(1000 * extract(epoch FROM percentile_cont(0.5) WITHIN GROUP (ORDER BY updated_at - created_at))::numeric, 1),
           round(1000 * extract(epoch FROM percentile_cont(0.99) WITHIN GROUP (ORDER BY updated_at - created_at))::numeric, 1)
    FROM orders WHERE state = 'COMPLETED'" | tr '|' ' ')"
  api50=$(jq -r '.metrics.http_req_duration["p(50)"]' "$OUT/k6-$rate.json")
  api99=$(jq -r '.metrics.http_req_duration["p(99)"]' "$OUT/k6-$rate.json")
  failed=$(jq -r '.metrics.checks.fails' "$OUT/k6-$rate.json")
  printf '%-8s %-10s %-12s %-12s %-12s %-12s %-10s %s\n' "$rate/s" "$done" "$tput" \
    "$(printf '%.1f ms' "$api50")" "$(printf '%.1f ms' "$api99")" "$p50 ms" "$p99 ms" \
    "$( [[ $failed != 0 ]] && echo "($failed API errors)")"
done
