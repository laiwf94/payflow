"""A stand-in merchant for WebhookEndToEndTest, run in a container.

POST /hooks is the webhook receiver. It checks signatures with docs/webhooks.md's own Python
example (copied in beside this file as payflow_webhooks.py), drops repeats by Payflow-Event-Id and
logs every request it is sent with what it answered. The test steers it over /control:

  POST /control/secrets  {"secrets": [...]}  the secrets the receiver holds from now on
  POST /control/fail     {"count": n}        answer the next n deliveries 500, before checking them
  GET  /control/log                          every request so far, in the order they were handled

Standard library only.
"""

import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from payflow_webhooks import verify

lock = threading.Lock()
secrets = []
fail_next = 0
seen = set()
log = []


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        global secrets, fail_next
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/control/secrets":
            with lock:
                secrets = list(json.loads(body)["secrets"])
            return self.answer(200, "ok")
        if self.path == "/control/fail":
            with lock:
                fail_next = int(json.loads(body)["count"])
            return self.answer(200, "ok")
        if self.path != "/hooks":
            return self.answer(404, "no such path")
        # One request at a time from here, so the log's order is the order requests were handled.
        with lock:
            held = list(secrets)
            if fail_next > 0:
                fail_next -= 1
                status, outcome = 500, "failed"
            elif not verify(self.headers.get("Payflow-Signature"), body, held):
                status, outcome = 400, "bad_signature"
            elif self.headers.get("Payflow-Event-Id") in seen:
                status, outcome = 200, "duplicate"
            else:
                seen.add(self.headers.get("Payflow-Event-Id"))
                status, outcome = 200, "accepted"
            log.append(
                {
                    "event_id": self.headers.get("Payflow-Event-Id"),
                    "event_type": self.headers.get("Payflow-Event-Type"),
                    "delivery_id": self.headers.get("Payflow-Delivery-Id"),
                    "attempt": int(self.headers.get("Payflow-Delivery-Attempt", "0")),
                    "signature": self.headers.get("Payflow-Signature"),
                    "body": base64.b64encode(body).decode(),
                    "secrets_held": len(held),
                    "status": status,
                    "outcome": outcome,
                }
            )
        return self.answer(status, outcome)

    def do_GET(self):
        if self.path != "/control/log":
            return self.answer(404, "no such path")
        with lock:
            data = json.dumps({"requests": log})
        return self.answer(200, data, "application/json")

    def answer(self, status, text, content_type="text/plain"):
        data = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


server = ThreadingHTTPServer(("0.0.0.0", 8080), Handler)
print("merchant listening on", server.server_address[1], flush=True)
server.serve_forever()
