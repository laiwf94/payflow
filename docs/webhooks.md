# Receiving payflow webhooks

payflow tells a merchant's server about changes to its orders and refunds by POSTing signed
events to the URLs the merchant registers. This page is for whoever writes the receiving end:
what arrives, how to check that payflow sent it, and what to expect when things go wrong. How
payflow sends them is in [DESIGN.md](DESIGN.md#merchant-webhooks).

The code on this page runs. `WebhookDocsTest` takes each verification function from this file,
runs it in Python, Node.js and Java against deliveries that payflow's own sender signs and
sends, and runs the complete receiver below as a server. If the page and payflow disagree, the
build fails.

## Registering an endpoint

```sh
curl -sS https://payflow.example/v1/webhook_endpoints \
  -H "Authorization: Bearer $PAYFLOW_API_KEY" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://shop.example/hooks/payflow", "enabled_events": ["*"]}'
```

The `201` carries the endpoint's signing `secret`, `whsec_` followed by 43 characters. It is
shown in that answer only: store it where your receiver can read it, such as a secret manager.
A retry with the same `Idempotency-Key` gets the same endpoint and secret back. `enabled_events`
is `["*"]` for every type, including ones added later, or a list of the types below.

## What arrives

Each event is one `POST` to the endpoint's URL with a JSON body and these headers:

| Header | Value |
|---|---|
| `Payflow-Signature` | `t=<unix seconds>,v1=<hex>`, with a second `v1` while a secret is being rotated |
| `Payflow-Event-Id` | The event's id, a UUID. The same on every attempt and every replay: deduplicate by it. |
| `Payflow-Event-Type` | The event's type, as in the body's `event_type` |
| `Payflow-Delivery-Id` | The delivery: one per event and endpoint |
| `Payflow-Delivery-Attempt` | 1 for the first attempt, then 2, 3 ... including after a replay |
| `Content-Type` | `application/json` |
| `User-Agent` | `payflow-webhooks/<version>` |

An order event looks like this:

```json
{
  "event_id": "0f8e7c1a-5b2d-4c3e-9a71-2d4f6b8c0e13",
  "event_type": "order.completed",
  "order_id": "7a1c9e42-3f60-4b8d-a5e2-91c04d7b2f68",
  "merchant_id": "acme",
  "merchant_reference": "cart-8812",
  "seq": 5,
  "state": "COMPLETED",
  "previous_state": "PAYMENT_CAPTURED",
  "amount": 4200,
  "currency": "EUR",
  "occurred_at": "2026-11-15T09:30:12.418213Z"
}
```

`amount` is in minor units (cents here). `merchant_reference` is present when the order has one,
`previous_state` on a change of state, `failure_code` on a failed order, and refund events carry
a `refund` object (`id`, `amount`, `lines` for a line-item refund, `state`, `failure_code`).
`seq` is the order's version: it goes up by one with every event of that order.

| Type | Sent when |
|---|---|
| `order.created` | An order is accepted |
| `order.inventory_reserved`, `order.payment_authorized`, `order.payment_captured`, `order.completed` | The order moves forward |
| `order.cancel_requested` | A cancel is accepted; the order then unwinds |
| `order.cancelling_shipment`, `order.reversing_payment`, `order.releasing_inventory` | The order is being unwound |
| `order.failed`, `order.cancelled` | The order ended without completing |
| `order.partially_refunded`, `order.refunded` | Some or all of a completed order was refunded |
| `refund.created`, `refund.succeeded`, `refund.failed` | A refund changed |
| `webhook_endpoint.disabled` | payflow disabled one of your endpoints (see below) |
| `ping` | You asked for a test ping (`POST /v1/webhook_endpoints/{id}/ping`) |

## Checking the signature

Anyone can POST to your URL, so check every request before acting on it. The `v1` value is
HMAC-SHA256, keyed with the whole secret (`whsec_` included, as UTF-8 bytes), over the
timestamp `t`, a full stop and the **raw request body**:

```
v1 = hex( HMAC-SHA256( key = secret, message = t + "." + body ) )
```

1. Read the body as bytes, before any JSON parsing. Parsing and serializing again changes
   spacing or key order, and the signature no longer matches.
2. Split the header on `,` into `key=value` pairs. Take the one `t` and every `v1`; ignore keys
   you don't know, so a future scheme can be added beside `v1`.
3. Refuse the request if `t` is more than five minutes from your clock, either way. This stops
   someone who captured a delivery from sending it to you again later.
4. Compute the HMAC and compare it with each `v1` in constant time. Accept the request when any
   one matches.

Answer `400` (or any status that isn't 2xx) when the check fails: payflow logs it and retries,
which is what you want if the fault was yours, such as a secret not deployed yet.

### Python

Standard library only, Python 3.8 or later.

<!-- docs-test:verify-python -->
```python
import hashlib
import hmac
import time

TOLERANCE_SECONDS = 300


def verify(header, body, secret, now=None):
    """True when the Payflow-Signature header signs the raw body bytes with secret."""
    if not header:
        return False
    timestamp, signatures = None, []
    for part in header.split(","):
        key, sep, value = part.strip().partition("=")
        if not sep:
            return False
        if key == "t":
            if timestamp is not None or not (value.isascii() and value.isdigit()):
                return False
            if len(value) > 12:
                return False
            timestamp = int(value)
        elif key == "v1":
            signatures.append(value)
    if timestamp is None or not signatures:
        return False
    if abs((time.time() if now is None else now) - timestamp) > TOLERANCE_SECONDS:
        return False
    signed = str(timestamp).encode("ascii") + b"." + body
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    # Check every value, so the time taken doesn't say which one matched.
    matched = False
    for candidate in signatures:
        matched |= hmac.compare_digest(expected, candidate)
    return matched
```

### Node.js

Built-in `crypto` only, Node.js 18 or later. With Express, take the body with
`express.raw({type: "application/json"})` so `req.body` is a `Buffer` of the bytes received.

<!-- docs-test:verify-node -->
```js
const crypto = require("node:crypto");

const TOLERANCE_SECONDS = 300;

/** True when the Payflow-Signature header signs the raw body (a Buffer) with secret. */
function verify(header, body, secret, now = Date.now() / 1000) {
  if (!header) return false;
  let timestamp = null;
  const signatures = [];
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq < 0) return false;
    const key = part.slice(0, eq).trim();
    const value = part.slice(eq + 1).trim();
    if (key === "t") {
      if (timestamp !== null || !/^[0-9]{1,12}$/.test(value)) return false;
      timestamp = Number(value);
    } else if (key === "v1") {
      signatures.push(value);
    }
  }
  if (timestamp === null || signatures.length === 0) return false;
  if (Math.abs(now - timestamp) > TOLERANCE_SECONDS) return false;
  const expected = crypto
    .createHmac("sha256", secret)
    .update(`${timestamp}.`)
    .update(body)
    .digest();
  let matched = false;
  for (const candidate of signatures) {
    const given = Buffer.from(candidate, "hex");
    if (given.length === expected.length && crypto.timingSafeEqual(given, expected)) {
      matched = true;
    }
  }
  return matched;
}

module.exports = { verify };
```

### Java

JDK only, Java 17 or later. With Spring, take the body as `@RequestBody byte[] body`.

<!-- docs-test:verify-java -->
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

  private PayflowWebhooks() {}

  /** True when the Payflow-Signature header signs the raw body with secret. */
  public static boolean verify(String header, byte[] body, String secret, long nowSeconds) {
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
        if (timestamp != null || !value.matches("[0-9]{1,12}")) {
          return false;
        }
        timestamp = Long.parseLong(value);
      } else if (key.equals("v1")) {
        try {
          signatures.add(HexFormat.of().parseHex(value));
        } catch (IllegalArgumentException notHex) {
          // Can't match; keep looking at the others.
        }
      }
    }
    if (timestamp == null || signatures.isEmpty()) {
      return false;
    }
    if (Math.abs(nowSeconds - timestamp) > TOLERANCE_SECONDS) {
      return false;
    }
    byte[] expected;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
      expected = mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    boolean matched = false;
    for (byte[] candidate : signatures) {
      matched |= MessageDigest.isEqual(expected, candidate);
    }
    return matched;
  }
}
```

## A complete receiver

This one is Python with only the standard library, so it runs as it is: save it as
`receiver.py`, run `PAYFLOW_WEBHOOK_SECRETS=whsec_... python3 receiver.py`, and it listens on
port 8000 (`PORT` changes that). It shows the parts every receiver needs:

- it checks the signature over the raw bytes, against each secret it holds;
- it answers quickly, so a slow handler doesn't turn into payflow's 10-second timeout and a
  retry; real work belongs on a queue;
- it deduplicates by `Payflow-Event-Id`, because the same event can arrive more than once;
- it keeps the highest `seq` it has applied for each order and ignores an older one, which
  matters if you replay or process events concurrently.

<!-- docs-test:receiver-python -->
```python
import hashlib
import hmac
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

# One secret normally; during a rotation, the new one and the old one, comma-separated.
SECRETS = [s for s in os.environ["PAYFLOW_WEBHOOK_SECRETS"].split(",") if s]
TOLERANCE_SECONDS = 300
MAX_BODY = 1024 * 1024

seen_events = set()  # use your database, with a unique key on the event id
order_seq = {}  # order id -> the highest seq applied
lock = Lock()


def verify(header, body, secret, now=None):
    if not header:
        return False
    timestamp, signatures = None, []
    for part in header.split(","):
        key, sep, value = part.strip().partition("=")
        if not sep:
            return False
        if key == "t":
            if timestamp is not None or not (value.isascii() and value.isdigit()):
                return False
            if len(value) > 12:
                return False
            timestamp = int(value)
        elif key == "v1":
            signatures.append(value)
    if timestamp is None or not signatures:
        return False
    if abs((time.time() if now is None else now) - timestamp) > TOLERANCE_SECONDS:
        return False
    signed = str(timestamp).encode("ascii") + b"." + body
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    matched = False
    for candidate in signatures:
        matched |= hmac.compare_digest(expected, candidate)
    return matched


def handle(event):
    """Your code. Here it prints what it would act on."""
    print("handled", event["event_type"], event["event_id"], flush=True)


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > MAX_BODY:
            return self.answer(413)
        body = self.rfile.read(length)  # the raw bytes: verify these, not parsed JSON
        header = self.headers.get("Payflow-Signature")
        if not any(verify(header, body, secret) for secret in SECRETS):
            return self.answer(400)
        event = json.loads(body)
        with lock:
            if event["event_id"] in seen_events:
                return self.answer(200)  # a repeat: already handled
            seen_events.add(event["event_id"])
            order = event.get("order_id")
            if order is not None and event["seq"] <= order_seq.get(order, 0):
                return self.answer(200)  # older than what was applied
            if order is not None:
                order_seq[order] = event["seq"]
        handle(event)
        self.answer(204)

    def answer(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8000"))), Receiver)
    print("listening on", server.server_address[1], flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
```

## Rotating the secret

`POST /v1/webhook_endpoints/{id}/rotate_secret` returns a new secret, shown once. The old one
keeps signing beside it for a day by default (`previous_secret_expires_in`, 0 to 604,800
seconds), and until then every `Payflow-Signature` carries two `v1` values, the new secret's
first. So:

1. Rotate, and add the new secret to your receivers next to the old one (the receiver above
   takes `PAYFLOW_WEBHOOK_SECRETS=new,old`). Each request verifies with either.
2. Once every receiver has the new secret, and before the old one stops signing, remove the old
   one.

Rotating again while an old secret still signs drops that oldest one: at most two ever sign.
If a secret leaks, rotate with `{"previous_secret_expires_in": 0}` to stop the old one at once.

## Retries, ordering and duplicates

- **Success is a 2xx within 10 seconds.** Anything else is a failed attempt: a 3xx (redirects
  are never followed, so update the endpoint's URL instead), a 4xx, a 5xx, a timeout, a refused
  connection or a TLS error, such as a certificate that doesn't name the URL's host.
- **Retries go on for three days.** The wait before attempt *n* is random, between zero and
  5 s × 2<sup>n</sup>, and never more than an hour, so a receiver that was down isn't hit by
  every retry at once when it comes back. After three days the delivery is given up as
  `FAILED`; you can still replay it.
- **Each order's events arrive in order**, per endpoint: payflow doesn't send an order's next
  event to an endpoint until the one before it has succeeded or been given up. Other orders
  aren't held up, so events of different orders interleave.
- **At least once, not exactly once.** An event can arrive twice: after a timeout where your
  server did answer in the end, after payflow restarts mid-attempt, or after a replay. Use
  `Payflow-Event-Id` to ignore repeats.

## The delivery log, replays and pings

- `GET /v1/webhook_endpoints/{id}/deliveries` lists the endpoint's deliveries, newest first, with
  each one's latest attempt: the status, how long it took and the first 1,024 characters of what
  you answered, or why there was no answer. `GET .../deliveries/{delivery_id}` adds the event as
  sent and every attempt.
- `POST /v1/webhook_endpoints/{id}/replay` sends events again, by `{"event_id": ...}` or by a
  range (`{"created_gte": ..., "created_lt": ...}`, optionally `"state": ["FAILED"]`), up to
  1,000 at a time. Each gets three fresh days of retries. Use it after an outage on your side.
- `POST /v1/webhook_endpoints/{id}/ping` sends a signed `ping`, tried once, and answers with a
  `Location` to its delivery, so you can check a new endpoint and its secret end to end.

## When payflow disables an endpoint

An endpoint whose every attempt has failed for three days is disabled, with
`disabled_reason: failing`. payflow then sends a `webhook_endpoint.disabled` event, once to that
endpoint and to each of your other endpoints that subscribe to it, since the failing one probably
won't hear it:

```json
{
  "event_id": "c3b4f1e2-8d7a-4f6e-b5c4-a3b2c1d0e9f8",
  "event_type": "webhook_endpoint.disabled",
  "merchant_id": "acme",
  "endpoint_id": "5e8f2a1b-7c3d-4e9f-8a6b-1c2d3e4f5a6b",
  "url": "https://shop.example/hooks/payflow",
  "reason": "failing",
  "failing_since": "2026-11-12T09:30:12.418213Z",
  "created_at": "2026-11-15T09:31:02.004521Z"
}
```

Events for a disabled endpoint wait. Fix the receiver, then set the endpoint back with
`POST /v1/webhook_endpoints/{id}` and `{"state": "ENABLED"}`: what waited is sent, and the
endpoint gets three fresh days.

## Where webhooks can go

payflow only sends to public addresses. A URL whose host is, or resolves to, a loopback,
private, link-local (including the cloud metadata address `169.254.169.254`), CGNAT or other
non-public address is refused when you register it, and checked again on every attempt, so a
name that later resolves somewhere private gets nothing. IPv6 addresses that carry an IPv4
address, such as `::ffff:10.0.0.1`, are judged by the IPv4 address inside.

For a development stack, the operator can let chosen networks through with
`PAYFLOW_WEBHOOK_ALLOWED_NETWORKS` (CIDR, comma-separated), for example `127.0.0.1/32` to try
the receiver above on the same machine.
