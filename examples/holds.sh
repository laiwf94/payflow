#!/usr/bin/env bash
# Time limits on holds, end to end against the docker compose stack:
#
#   docker compose up -d --build && ./examples/holds.sh
#
# Needs curl, jq and GNU date. It restarts `app` with the short holds in examples/holds.compose.yml
# (5 s reservation window, extend a waiting authorization 10 s before it lapses) and puts the
# default windows back when it exits.
#
#   1. Unpaid orders hold stock for 5 s while every authorization fails. The process is killed
#      (SIGKILL) inside the window; the next instance fires each hold's timer once, releases the
#      stock and cancels the order as reservation_expired.
#   2. A manual-capture order waits on a 20 s authorization. Its timer extends the authorization
#      once before it lapses, the original expiry passes, and the capture call made at shipment
#      takes the money from the extended hold.
#
# Every check failure exits non-zero.
set -euo pipefail

API=${API:-http://127.0.0.1:${PAYFLOW_HOST_PORT:-8080}}
SIM=${SIM:-http://127.0.0.1:${PAYFLOW_HOST_SIMULATOR_PORT:-8090}}
KEY=${PAYFLOW_KEY:-pf_demo_acme_0123456789abcdefghijklmnop}
SIM_KEY=${SIM_KEY:-sim_demo_key_0123456789abcdef}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE="docker compose -f $ROOT/docker-compose.yml"
HOLDS="$COMPOSE -f $ROOT/examples/holds.compose.yml"
ORDERS=${ORDERS:-3}
WINDOW_MS=5000
LIFETIME_MS=20000
REAUTHORIZE_BEFORE_MS=10000

say() { printf '\n== %s\n' "$*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
now_ms() { date +%s%3N; }
ms() { date -d "$1" +%s%3N; } # ISO-8601 instant -> epoch milliseconds

sim() { # method path [json]
  curl -fsS -X "$1" "$SIM/sim/control$2" -H "Authorization: Bearer $SIM_KEY" \
    -H 'Content-Type: application/json' ${3:+-d "$3"} > /dev/null
}

order_body() { # capture_mode
  cat <<EOF
{"items":[{"sku":"mug","quantity":2,"unit_amount":1250},{"sku":"tea","quantity":1,"unit_amount":800}],
 "currency":"EUR",
 "shipping_address":{"name":"Alice Example","line1":"1 Main Street","city":"Springfield",
                     "postal_code":"12345","country":"NL"},
 "payment_method":"pm_card_ok","capture_mode":"$1"}
EOF
}

create() { # capture_mode
  curl -fsS "$API/v1/orders" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuidgen)" -d "$(order_body "$1")" | jq -r .id
}

order() { curl -fsS "$API/v1/orders/$1" -H "Authorization: Bearer $KEY"; }
ledger() { curl -fsS "$SIM/sim/control/ledger?reference=$1" -H "Authorization: Bearer $SIM_KEY"; }
stock() { curl -fsS "$SIM/sim/control/stock/$1" -H "Authorization: Bearer $SIM_KEY" | jq -r .available; }

wait_for() { # id state [seconds]
  for _ in $(seq 1 $((${3:-30} * 4))); do
    [[ $(order "$1" | jq -r .state) == "$2" ]] && return 0
    sleep 0.25
  done
  fail "order $1 is $(order "$1" | jq -r .state), expected $2"
}

ready() {
  for _ in $(seq 1 120); do curl -fsS "$API/readyz" > /dev/null 2>&1 && return 0; sleep 0.5; done
  fail "payflow at $API never became ready"
}

# The order's events once the consumer has seen `last` (they arrive through Kafka).
events() { # id last_event_type
  local body
  for _ in $(seq 1 120); do
    body=$(curl -fsS "$API/v1/orders/$1/events" -H "Authorization: Bearer $KEY")
    jq -e --arg t "$2" '.data | map(.event_type) | index($t)' <<< "$body" > /dev/null && {
      echo "$body"
      return 0
    }
    sleep 0.5
  done
  fail "order $1 never got $2: $(jq -c '[.data[].event_type]' <<< "$body")"
}

event_ms() { ms "$(jq -r --arg t "$2" '[.data[] | select(.event_type == $t)][0].occurred_at' <<< "$1")"; }
count() { jq --arg t "$2" '[.data[] | select(.event_type == $t)] | length' <<< "$1"; }

restore() {
  sim POST /reset 2> /dev/null || true
  echo
  echo "restoring app with the default hold windows"
  $COMPOSE up -d --no-build --wait app > /dev/null 2>&1 || echo "could not restore app" >&2
}
trap restore EXIT

echo "restarting app with the short holds in examples/holds.compose.yml"
$HOLDS up -d --no-build --wait app > /dev/null 2>&1
ready
sim POST /reset

say "1. Unpaid orders hold stock for 5 s; the process is killed (SIGKILL) inside the window"
mugs=$(stock mug)
teas=$(stock tea)
# Every authorization answers 503, so the saga keeps retrying and nothing pays for the stock.
sim PUT /faults/payment.authorize '{"error_rate":1}'
ids=()
for _ in $(seq 1 "$ORDERS"); do ids+=("$(create automatic)"); done
for id in "${ids[@]}"; do wait_for "$id" INVENTORY_RESERVED 10; done
(($(stock mug) == mugs - 2 * ORDERS && $(stock tea) == teas - ORDERS)) ||
  fail "expected $((2 * ORDERS)) mugs and $ORDERS tea held, shelf has $(stock mug) and $(stock tea)"
sleep 1
killed=$(now_ms)
$COMPOSE kill -s SIGKILL app > /dev/null 2>&1
echo "killed with $ORDERS orders in INVENTORY_RESERVED; stock held: $((mugs - $(stock mug))) mugs, $((teas - $(stock tea))) tea"
$HOLDS start app > /dev/null 2>&1
ready
started=$(now_ms)
echo "next instance ready $((started - killed)) ms after the kill"
for id in "${ids[@]}"; do
  wait_for "$id" CANCELLED 30
  o=$(order "$id")
  ev=$(events "$id" order.cancelled)
  reserved=$(event_ms "$ev" order.inventory_reserved)
  asked=$(event_ms "$ev" order.cancel_requested)
  jq -c '{state, cancel_reason, failure: .failure.code, payment: .payment.id}' <<< "$o"
  echo "  events: $(jq -c '[.data[].event_type]' <<< "$ev")"
  echo "  reserved, killed $((killed - reserved)) ms into the window, cancel requested $((asked - reserved)) ms after the reservation"
  jq -e '.cancel_reason == "reservation_expired" and .failure == null and .payment == null' \
    <<< "$o" > /dev/null || fail "order $id was not cancelled by its reservation hold"
  ((killed < reserved + WINDOW_MS)) || fail "the kill came after order $id's window closed"
  ((asked >= reserved + WINDOW_MS)) || fail "order $id was cancelled before its window closed"
  [[ $(count "$ev" order.cancel_requested) == 1 && $(count "$ev" order.cancelled) == 1 ]] ||
    fail "order $id was not cancelled exactly once"
  l=$(ledger "$id")
  jq -e '.payment_operations == [] and .reservation == null and .shipment == null' <<< "$l" > /dev/null ||
    fail "order $id left something at the providers: $(jq -c . <<< "$l")"
done
echo "shelf: $(stock mug) mugs (was $mugs), $(stock tea) tea (was $teas)"
(($(stock mug) == mugs && $(stock tea) == teas)) || fail "the held stock did not all come back"
sim DELETE /faults

say "2. A manual-capture order's 20 s authorization is extended once, then captured on shipment"
sim PUT /settings "{\"authorization_lifetime_ms\":$LIFETIME_MS}"
id=$(create manual)
wait_for "$id" PAYMENT_AUTHORIZED 10
first=$(order "$id" | jq -r .payment.authorization_expires_at)
echo "authorized; the hold lapses at $first"
for _ in $(seq 1 $((LIFETIME_MS / 250))); do
  [[ $(order "$id" | jq -r .payment.reauthorized_at) != null ]] && break
  sleep 0.25
done
o=$(order "$id")
extended=$(jq -r .payment.reauthorized_at <<< "$o")
second=$(jq -r .payment.authorization_expires_at <<< "$o")
[[ $extended != null ]] || fail "order $id was never re-authorized"
echo "re-authorized at $extended, $(($(ms "$first") - $(ms "$extended"))) ms before the first hold lapsed; it now lapses at $second"
(($(ms "$extended") >= $(ms "$first") - REAUTHORIZE_BEFORE_MS && $(ms "$extended") < $(ms "$first"))) ||
  fail "the extension was not in the last 10 s of the first hold"
(($(ms "$second") >= $(ms "$extended") + LIFETIME_MS - 1000)) || fail "the new hold is not a full lifetime"
# The parcel ships after the first hold would have lapsed.
wait=$(($(ms "$first") + 1000 - $(now_ms)))
((wait > 0)) && sleep "$((wait / 1000)).$(printf '%03d' $((wait % 1000)))"
[[ $(order "$id" | jq -r .state) == PAYMENT_AUTHORIZED ]] || fail "order $id stopped waiting"
[[ $(ledger "$id" | jq -r '.payments[0].status') == AUTHORIZED ]] || fail "the extended hold is gone"
echo "first hold's expiry has passed; shipping, so the merchant captures"
curl -fsS "$API/v1/orders/$id/capture" -X POST -H "Authorization: Bearer $KEY" \
  -H "Idempotency-Key: $(uuidgen)" | jq -c '{state, capture_requested}'
wait_for "$id" COMPLETED 15
o=$(order "$id")
l=$(ledger "$id")
ev=$(events "$id" order.completed)
jq -c '{state, amount, captured: .payment.captured_amount, reauthorized_at: .payment.reauthorized_at, tracking: .shipment.tracking_number}' <<< "$o"
jq -c '{provider_operations: [.payment_operations[] | "\(.operation) \(.amount)"], shipment: .shipment.status}' <<< "$l"
echo "  events: $(jq -c '[.data[].event_type]' <<< "$ev")"
amount=$(jq .amount <<< "$o")
jq -e --argjson a "$amount" \
  '[.payment_operations[] | [.operation, .amount]] == [["authorize", $a], ["reauthorize", $a], ["capture", $a]]
   and .shipment != null' <<< "$l" > /dev/null ||
  fail "expected exactly authorize, reauthorize and capture of $amount and a shipment"
[[ $(count "$ev" order.payment_reauthorized) == 1 && $(count "$ev" order.capture_requested) == 1 ]] ||
  fail "expected one re-authorization and one capture request in the events"

say "All hold checks passed"
