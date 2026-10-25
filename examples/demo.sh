#!/usr/bin/env bash
# Walks through payflow's failure handling against the docker compose stack:
#
#   docker compose up -d --build && ./examples/demo.sh
#
# Needs curl and jq. Each scenario prints the order's final state and what the fake payment
# provider recorded for it, so you can check nobody was charged twice.
set -euo pipefail

API=${API:-http://127.0.0.1:${PAYFLOW_HOST_PORT:-8080}}
SIM=${SIM:-http://127.0.0.1:${PAYFLOW_HOST_SIMULATOR_PORT:-8090}}
KEY=${PAYFLOW_KEY:-pf_demo_acme_0123456789abcdefghijklmnop}
SIM_KEY=${SIM_KEY:-sim_demo_key_0123456789abcdef}
COMPOSE="docker compose -f $(cd "$(dirname "$0")/.." && pwd)/docker-compose.yml"

say() { printf '\n== %s\n' "$*"; }

sim() { # method path [json]
  curl -fsS -X "$1" "$SIM/sim/control$2" -H "Authorization: Bearer $SIM_KEY" \
    -H 'Content-Type: application/json' ${3:+-d "$3"} > /dev/null
}

order_body() { # payment_method postal_code
  cat <<EOF
{"items":[{"sku":"mug","quantity":2,"unit_amount":1250},{"sku":"tea","quantity":1,"unit_amount":800}],
 "currency":"EUR",
 "shipping_address":{"name":"Alice Example","line1":"1 Main Street","city":"Springfield",
                     "postal_code":"$2","country":"NL"},
 "payment_method":"$1"}
EOF
}

create() { # payment_method postal_code [idempotency_key]
  curl -fsS "$API/v1/orders" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${3:-$(uuidgen)}" -d "$(order_body "$1" "$2")" | jq -r .id
}

state() { curl -fsS "$API/v1/orders/$1" -H "Authorization: Bearer $KEY" | jq -r .state; }

wait_for() { # id state...
  local id=$1; shift
  for _ in $(seq 1 120); do
    local s; s=$(state "$id")
    for want in "$@"; do [[ $s == "$want" ]] && return 0; done
    sleep 0.25
  done
  echo "order $id stuck in $(state "$id")" >&2
  return 1
}

show() { # id
  curl -fsS "$API/v1/orders/$1" -H "Authorization: Bearer $KEY" |
    jq -c '{state, amount, failure: .failure.code, captured: .payment.captured_amount, refunded: .payment.refunded_amount, tracking: .shipment.tracking_number}'
  curl -fsS "$SIM/sim/control/ledger?reference=$1" -H "Authorization: Bearer $SIM_KEY" |
    jq -c '{provider_operations: [.payment_operations[] | "\(.operation) \(.amount)"], shipment: .shipment.status}'
}

echo "waiting for payflow at $API"
for _ in $(seq 1 120); do curl -fsS "$API/readyz" > /dev/null 2>&1 && break; sleep 1; done
curl -fsS "$API/readyz" > /dev/null
sim POST /reset

say "1. Happy path: reserve, authorize, capture, ship"
id=$(create pm_card_ok 12345)
wait_for "$id" COMPLETED
show "$id"

say "2. Card declined: the stock reservation is released"
id=$(create pm_card_insufficient_funds 12345)
wait_for "$id" FAILED
show "$id"

say "3. Carrier refuses the address after capture: the payment is refunded"
id=$(create pm_card_ok 00000)
wait_for "$id" FAILED
show "$id"

say "4. Capture applied but the answer is lost (timeout): resolved without charging twice"
sim PUT /settings '{"honor_idempotency_keys":false}'
sim PUT /faults/payment.capture '{"script":["unknown_after"]}'
id=$(create pm_card_ok 12345)
wait_for "$id" COMPLETED
show "$id"
sim PUT /settings '{"honor_idempotency_keys":true}'
sim DELETE /faults

say "5. Client retries with the same Idempotency-Key"
key=$(uuidgen)
first=$(create pm_card_ok 12345 "$key")
second=$(curl -fsS -D - -o /dev/null "$API/v1/orders" -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$(order_body pm_card_ok 12345)" |
  tr -d '\r' | awk -F': ' 'tolower($1)=="location"{print $2} tolower($1)=="idempotent-replayed"{print "replayed=" $2}')
echo "first: /v1/orders/$first"
echo "retry: $second" | tr '\n' ' '; echo
status=$(curl -sS -o /dev/null -w '%{http_code}' "$API/v1/orders" -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$(order_body pm_card_expired 12345)")
echo "same key, different body: HTTP $status"

say "6. The process is killed (SIGKILL) while the authorization is in flight"
sim PUT /faults/payment.authorize '{"latency_ms":1500}'
id=$(create pm_card_ok 12345)
wait_for "$id" INVENTORY_RESERVED
$COMPOSE kill -s SIGKILL app > /dev/null 2>&1
echo "killed in state INVENTORY_RESERVED; provider already has: $(curl -fsS "$SIM/sim/control/ledger?reference=$id" -H "Authorization: Bearer $SIM_KEY" | jq -c '[.payment_operations[].operation]')"
sim DELETE /faults
$COMPOSE start app > /dev/null 2>&1
for _ in $(seq 1 120); do curl -fsS "$API/readyz" > /dev/null 2>&1 && break; sleep 1; done
wait_for "$id" COMPLETED
show "$id"

say "Event timeline for the last order (from Kafka, through the consumer)"
for _ in $(seq 1 120); do
  curl -fsS "$API/v1/orders/$id/events" -H "Authorization: Bearer $KEY" |
    jq -e '.data | map(.event_type) | index("order.completed")' > /dev/null && break
  sleep 0.5
done
curl -fsS "$API/v1/orders/$id/events" -H "Authorization: Bearer $KEY" | jq -r '.data[] | "\(.seq)\t\(.event_type)"'
