# Receiving payflow webhooks

payflow tells a merchant's servers what happened to their orders by POSTing each event to the
webhook endpoints the merchant registered. This guide is for whoever writes the receiving end:
what arrives, how to check it came from payflow, and how to answer. How payflow delivers is in
[DESIGN.md](DESIGN.md#merchant-webhooks); the routes are in `src/main/resources/openapi.yaml`.

Every example on this page is run by `WebhookGuideTest`: the signature examples are checked
against payflow's own signer on hundreds of generated requests, the test vector is recomputed,
the example body is compared with what Postgres renders, and the receiver below is started and
sent real deliveries. If this page and payflow disagree, the build fails.

## Registering an endpoint

```sh
curl -sS https://payflow.example/v1/webhook_endpoints \
  -H "Authorization: Bearer $PAYFLOW_API_KEY" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://shop.example/hooks/payflow", "enabled_events": ["*"]}'
```

The `201` carries the endpoint's signing `secret`, `whsec_` followed by 43 characters. It is
shown in that answer only (a retry with the same `Idempotency-Key` replays it), so store it where
your receiver reads its configuration. `enabled_events` is `["*"]` for every type, including ones
added later, or a list of the types below.

The URL must be public: a host that is, or resolves to, a loopback, private, link-local, CGNAT or
cloud metadata address (in IPv4 or wrapped in IPv6) is refused when you register it and again
before every delivery, and redirects are never followed. To test against a server on your own
machine, run payflow with `PAYFLOW_WEBHOOK_ALLOWED_NETWORKS` naming that network
(`127.0.0.1/32`, say), or use a tunnel with a public name.

## What arrives

Each delivery is one `POST` with a JSON body, one event per request:

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `User-Agent` | `payflow-webhooks/<version>` |
| `Payflow-Signature` | `t=<unix seconds>,v1=<hex>`, with a second `v1` while a secret rotates; see below |
| `Payflow-Event-Id` | The event's id, the same on every attempt and every replay. Deduplicate by it. |
| `Payflow-Event-Type` | The event's type, as in the body's `event_type` |
| `Payflow-Delivery-Id` | This endpoint's delivery of the event, as the delivery log names it |
| `Payflow-Delivery-Attempt` | 1 on the first try, then 2, 3 and so on, carrying on across replays |

An order event looks like this (one line on the wire; the key order is Postgres's and means
nothing, so never verify a re-serialized copy):

<!-- example: body -->
```json
{"seq": 4, "state": "COMPLETED", "amount": 2500, "currency": "EUR", "event_id": "0b6f3f8e-2a4c-4d71-9a51-6f0e5b7c9d12", "order_id": "5f2d7c1a-8e3b-4c90-b6a4-1d2e3f4a5b6c", "event_type": "order.completed", "merchant_id": "acme", "occurred_at": "2026-11-15T07:00:00.123456Z", "previous_state": "PAYMENT_CAPTURED", "merchant_reference": "ord-1042"}
```

- `seq` is the event's place in its order's stream: 0 for `order.created`, one more for each
  later event of that order, refund events included, with no gaps. `state` is the order's state after the event and `previous_state`
  the one before, when the event moved it.
- `amount` is in minor units of `currency`. `merchant_reference` is there when the order was
  created with one, `failure_code` when the order failed, and `cancel_reason` (`requested`,
  `reservation_expired` or `payment_expired`) once a cancel was asked for.
- Refund events (`refund.created`, `refund.succeeded`, `refund.failed`) add a `refund` object
  with its `id`, `amount`, `state`, any `lines`, and `failure_code` when it failed.

The types are `order.created`, `order.cancel_requested`, `order.inventory_reserved`,
`order.payment_authorized`, `order.payment_captured`, `order.completed`,
`order.cancelling_shipment`, `order.reversing_payment`, `order.releasing_inventory`,
`order.failed`, `order.cancelled`, `order.partially_refunded`, `order.refunded`,
`refund.created`, `refund.succeeded`, `refund.failed` and `webhook_endpoint.disabled`. Two kinds of
event belong to no order and have no `seq`:

- `ping`, which `POST /v1/webhook_endpoints/{id}/ping` sends to that one endpoint whatever its
  filter: `{"event_id", "event_type": "ping", "merchant_id", "endpoint_id", "created_at"}`. It is
  tried once.
- `webhook_endpoint.disabled`, the final notice when payflow disables an endpoint that has failed
  for three days: `{"event_id", "event_type", "merchant_id", "endpoint_id", "url", "reason",
  "failing_since", "created_at"}`. It goes once to the failing endpoint itself and, with retries,
  to the merchant's other enabled endpoints that subscribe to it, which is how you hear about it.

## Verifying the signature

`Payflow-Signature` is `t=<unix seconds>,v1=<hex>`. The hex is HMAC-SHA256, keyed with the whole
secret including `whsec_` (as UTF-8), over the timestamp's digits, a full stop, and the request
body exactly as it arrived. To check a request:

1. Read the raw body bytes before anything parses them. A framework that hands you a parsed
   object has already lost the bytes that were signed (in Express, use
   `express.raw({type: "application/json"})` on the webhook route).
2. Split the header on commas and each part at its first `=`. Take the one `t` and every `v1`.
   Ignore other keys, so a future scheme can be added beside `v1`.
3. Refuse a `t` more than five minutes from your clock either way. That is what stops someone who
   captured a request from sending it to you again later.
4. Compute the HMAC of `t`, `.` and the body with each secret you hold, and accept the request
   when one of them equals one of the `v1` values. Compare in constant time.

If the check fails, answer `400`. payflow retries, and the failure shows in the delivery log.

### Test vector

<!-- example: vector -->
```text
secret:     whsec_test-only-docs-example-key
previous:   whsec_test-only-docs-previous-key
timestamp:  1794726001
body:       the example body above, as UTF-8, with no line break at the end
signature:  t=1794726001,v1=ab8b8405521852ad24407448f55e7e1347cdc08588d851b95bb8ab0d01dffc61
rotating:   t=1794726001,v1=ab8b8405521852ad24407448f55e7e1347cdc08588d851b95bb8ab0d01dffc61,v1=0c93f1dde7dbeef4bed59cda99db65ff467aff9b82996afda721cdf391ec8683
```

`signature` is what payflow sends with one secret, and `rotating` while `previous` still signs
beside `secret`. Both verify with either key at a clock between 1794725701 and 1794726301.

### Python

<!-- example: python -->
```python
import hashlib
import hmac
import time

TOLERANCE = 300  # seconds either way


def verify(header, body, secrets, tolerance=TOLERANCE, now=None):
    """True when `header`, the Payflow-Signature value, signs `body`, the raw request bytes,
    under one of `secrets` (your endpoint's secret, or two of them while you rotate)."""
    if not header:
        return False
    timestamp = None
    signatures = []
    for part in header.split(","):
        key, sep, value = part.partition("=")
        if not sep:
            return False
        key, value = key.strip(), value.strip()
        if key == "t":
            if timestamp is not None or not (value.isascii() and value.isdigit()):
                return False
            timestamp = int(value)
        elif key == "v1":
            signatures.append(value.encode())
    if timestamp is None or not signatures:
        return False
    if abs((time.time() if now is None else now) - timestamp) > tolerance:
        return False
    signed = str(timestamp).encode() + b"." + body
    ok = False
    for secret in secrets:
        expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest().encode()
        for candidate in signatures:
            ok |= hmac.compare_digest(expected, candidate)
    return ok
```

### Node.js

<!-- example: node -->
```js
const crypto = require("node:crypto");

const TOLERANCE = 300; // seconds either way

// True when `header`, the Payflow-Signature value, signs `body`, a Buffer of the raw request
// bytes, under one of `secrets` (your endpoint's secret, or two of them while you rotate).
function verify(header, body, secrets, tolerance = TOLERANCE, now = Date.now() / 1000) {
  if (!header) return false;
  let timestamp = null;
  const signatures = [];
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq < 0) return false;
    const key = part.slice(0, eq).trim();
    const value = part.slice(eq + 1).trim();
    if (key === "t") {
      if (timestamp !== null || !/^[0-9]+$/.test(value)) return false;
      timestamp = BigInt(value);
    } else if (key === "v1") {
      signatures.push(Buffer.from(value, "utf8"));
    }
  }
  if (timestamp === null || signatures.length === 0) return false;
  if (Math.abs(now - Number(timestamp)) > tolerance) return false;
  let ok = false;
  for (const secret of secrets) {
    const hex = crypto.createHmac("sha256", secret).update(`${timestamp}.`).update(body).digest("hex");
    const expected = Buffer.from(hex, "utf8");
    for (const candidate of signatures) {
      const same = candidate.length === expected.length && crypto.timingSafeEqual(candidate, expected);
      ok = same || ok;
    }
  }
  return ok;
}

module.exports = { verify };
```

### Java

<!-- example: java -->
```java
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PayflowWebhooks {

  public static final long TOLERANCE_SECONDS = 300;

  /**
   * True when {@code header}, the Payflow-Signature value, signs {@code body}, the raw request
   * bytes, under one of {@code secrets}, at {@code nowSeconds} (System.currentTimeMillis() / 1000).
   */
  public static boolean verify(String header, byte[] body, List<String> secrets, long nowSeconds) {
    if (header == null || header.isEmpty()) {
      return false;
    }
    Long timestamp = null;
    List<byte[]> signatures = new ArrayList<>();
    for (String part : header.split(",", -1)) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        return false;
      }
      String key = part.substring(0, eq).trim();
      String value = part.substring(eq + 1).trim();
      if (key.equals("t")) {
        if (timestamp != null || !value.matches("[0-9]{1,18}")) {
          return false;
        }
        timestamp = Long.parseLong(value);
      } else if (key.equals("v1")) {
        signatures.add(value.getBytes(StandardCharsets.UTF_8));
      }
    }
    if (timestamp == null || signatures.isEmpty()) {
      return false;
    }
    if (Math.abs(nowSeconds - timestamp) > TOLERANCE_SECONDS) {
      return false;
    }
    boolean ok = false;
    for (String secret : secrets) {
      byte[] expected = hmac(secret, timestamp, body);
      for (byte[] candidate : signatures) {
        ok |= MessageDigest.isEqual(expected, candidate);
      }
    }
    return ok;
  }

  private static byte[] hmac(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private PayflowWebhooks() {}
}
```

### By hand, with openssl

To see why a request fails, save its body exactly as received to `body.json` and recompute the
`v1` for its `t`:

<!-- example: openssl -->
```sh
{ printf '%s.' "$t"; cat body.json; } | openssl dgst -sha256 -hmac "$secret" | sed 's/^.*= //'
```

If that matches the header but your code refuses the request, your code isn't seeing the raw
bytes, or your clock is more than five minutes off. If it doesn't match, the body was changed on
the way (a proxy re-encoding JSON, a framework adding a line break) or the secret is not the
endpoint's current one.

## A complete receiver

A receiver built on the Python function above, saved as `payflow_webhooks.py`, using only the
standard library. It checks the signature over the raw body, drops events it has already seen,
and answers at once:

<!-- example: receiver -->
```python
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from payflow_webhooks import verify

# The endpoint's secret; during a rotation, the new and the old one, comma-separated.
SECRETS = os.environ["PAYFLOW_WEBHOOK_SECRETS"].split(",")
seen = set()  # event ids already handled; keep these in your database, not in memory


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if not verify(self.headers.get("Payflow-Signature"), body, SECRETS):
            return self.answer(400, "bad signature")
        event_id = self.headers.get("Payflow-Event-Id")
        if event_id in seen:
            return self.answer(200, "duplicate")
        seen.add(event_id)
        event = json.loads(body)
        # Hand the event to your own queue or job here; don't do slow work before answering.
        print(event["event_type"], event_id, event.get("seq"), flush=True)
        return self.answer(200, "ok")

    def answer(self, status, text):
        data = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


server = ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8000"))), Handler)
print("listening on", server.server_address[1], flush=True)
server.serve_forever()
```

Run it with `PAYFLOW_WEBHOOK_SECRETS=whsec_... PORT=8000 python3 receiver.py`, put it behind
your TLS front end, and register its public URL.

## Answering

- **A 2xx means delivered.** Anything else, including a `3xx` (redirects are not followed), a
  `4xx` or a `5xx`, and no answer at all (refused connection, TLS failure, timeout), is a failed
  attempt that payflow retries.
- **Answer within 10 seconds,** counting the connection and the TLS handshake. Queue the work and
  answer first; a receiver that does the work before answering turns a slow day into retries.
- The first 1,024 characters of your answer's body are kept in the delivery log, so a short
  reason (`bad signature`, `unknown order`) helps whoever reads it later. The rest is not read.

## Duplicates, order and retries

- **At least once.** An event can arrive more than once: after a timeout whose answer payflow
  never saw, after payflow restarts mid-attempt, or when someone replays it. The body and
  `Payflow-Event-Id` are the same each time; only the signature's `t` and
  `Payflow-Delivery-Attempt` change. Record the event ids you have handled and answer `200` to one
  you have seen.
- **In order within an order.** An endpoint gets one order's events in `seq` order: payflow sends
  an order's next event only once the one before it has been answered with a 2xx or given up. Events
  of different orders are independent and can interleave in any order. If you skip an order's
  event (you answered 2xx without processing it), compare `seq` with the last one you processed.
- **Retried for three days.** Waits grow from seconds to at most an hour, with random jitter,
  until three days after the event. An event that still hasn't been answered with a 2xx then is
  given up (`FAILED` in the delivery log) and can be sent again with a replay.
- **Disabled after three days of failures.** An endpoint whose every attempt has failed for three
  days is disabled, with a final `webhook_endpoint.disabled` notice. Its pending events wait.
  Enable it again with `POST /v1/webhook_endpoints/{id}` and `{"state": "ENABLED"}`, and what waited
  is sent.
- **The delivery log.** `GET /v1/webhook_endpoints/{id}/deliveries` lists every event sent to an
  endpoint, newest first, with its latest attempt's status, latency and body, or why there was
  none. `.../deliveries/{delivery_id}` adds the event as sent and every attempt. `POST
  .../replay` sends settled events again, by `event_id` or by time range, each with a fresh
  three days of retries.

## Rotating the secret

`POST /v1/webhook_endpoints/{id}/rotate_secret` returns a new secret, shown once. The old one keeps
signing beside it for a day, or for `previous_secret_expires_in` seconds (0 to 604,800), so
every delivery meanwhile carries two `v1` values. To rotate without dropping an event:

1. Rotate, and give your receivers both secrets (`PAYFLOW_WEBHOOK_SECRETS=new,old` above).
2. Roll them out. A receiver with either secret accepts every delivery during the overlap.
3. After the overlap, or once every receiver has the new secret, remove the old one.

If a secret has leaked, rotate with `{"previous_secret_expires_in": 0}` so the old secret stops
signing at once.
