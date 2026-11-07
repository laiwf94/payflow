#!/usr/bin/env python3
"""Pages 10,000 orders of two merchants while both payflow instances restart, one at a time.

    docker compose --profile pair up -d --build && ./examples/rolling-restart.py

The stack is the compose file's two instances, app and app2, on one Postgres and one broker.
The run:

1. creates ORDERS orders (default 10,000), half for "acme" and half for "globex", spread over
   both instances;
2. pages each merchant's listing PAGE_LIMIT at a time, sending each page's cursor to the other
   instance, while more orders keep arriving (LIVE_RATE a second), the fake payment provider
   takes PROVIDER_LATENCY_MS longer to authorize and capture so steps are always running, and
   another thread restarts app, then app2, with SIGTERM (docker compose stop), waiting for each
   to be ready again;
3. checks every page: no order listed twice, none of the merchant's seeded orders missing, no
   other merchant's order, strictly newest first;
4. waits for every order to finish and checks against the fake providers' ledgers that each one
   was authorized once and captured once, for its amount, with one reservation and one shipment.

A request that finds its instance down goes to the other one with the same Idempotency-Key,
so a create that the stopping instance did commit is replayed, not made twice.

Python 3.10+ standard library only. Exits non-zero and says why when a check fails.
"""

from __future__ import annotations

import calendar
import concurrent.futures
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
COMPOSE = ["docker", "compose", "-f", str(HERE.parent / "docker-compose.yml"), "--profile", "pair"]

INSTANCES = {
    "app": os.environ.get("API_1", f"http://127.0.0.1:{os.environ.get('PAYFLOW_HOST_PORT', '8080')}"),
    "app2": os.environ.get("API_2", f"http://127.0.0.1:{os.environ.get('PAYFLOW_HOST_PORT_2', '8180')}"),
}
SIM = os.environ.get("SIM", f"http://127.0.0.1:{os.environ.get('PAYFLOW_HOST_SIMULATOR_PORT', '8090')}")
SIM_KEY = os.environ.get("SIM_KEY", "sim_demo_key_0123456789abcdef")
MERCHANTS = {
    "acme": os.environ.get("ACME_KEY", "pf_demo_acme_0123456789abcdefghijklmnop"),
    "globex": os.environ.get("GLOBEX_KEY", "pf_demo_globex_0123456789abcdefghijklmn"),
}
ORDERS = int(os.environ.get("ORDERS", "10000"))
PAGE_LIMIT = int(os.environ.get("PAGE_LIMIT", "25"))
# Pause between pages while the restart is still running, so paging spans both restarts.
PAGE_DELAY = float(os.environ.get("PAGE_DELAY", "0.3"))
CREATORS = int(os.environ.get("CREATORS", "16"))
# Orders per second created during the restart, over LIVE_THREADS threads.
LIVE_RATE = float(os.environ.get("LIVE_RATE", "80"))
LIVE_THREADS = 4
# Added to every authorization and capture during the restart, so SIGTERM finds steps running.
PROVIDER_LATENCY_MS = int(os.environ.get("PROVIDER_LATENCY_MS", "250"))
SETTLE_TIMEOUT = float(os.environ.get("SETTLE_TIMEOUT", "300"))
# How long a request keeps trying the two instances before the run gives up on it.
FAILOVER_TIMEOUT = 60.0

TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED"}
NON_TERMINAL = [
    "PENDING",
    "INVENTORY_RESERVED",
    "PAYMENT_AUTHORIZED",
    "PAYMENT_CAPTURED",
    "CANCELLING_SHIPMENT",
    "REVERSING_PAYMENT",
    "RELEASING_INVENTORY",
]
SKU = "rr-widget"


class Failed(Exception):
    pass


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def http(method: str, url: str, key: str | None = None, body: dict | None = None,
         headers: dict | None = None, timeout: float = 10) -> tuple[int, dict, dict]:
    data = None if body is None else json.dumps(body).encode()
    h = {"Accept": "application/json"}
    if body is not None:
        h["Content-Type"] = "application/json"
    if key:
        h["Authorization"] = f"Bearer {key}"
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, dict(r.headers), json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw) if raw else {}
        except ValueError:
            parsed = {"raw": raw.decode(errors="replace")}
        return e.code, dict(e.headers), parsed


class Stats:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.failovers = 0
        self.replays = 0
        self.served = {name: 0 for name in INSTANCES}

    def add(self, **kw: int) -> None:
        with self.lock:
            for k, v in kw.items():
                setattr(self, k, getattr(self, k) + v)

    def serve(self, name: str) -> None:
        with self.lock:
            self.served[name] += 1


STATS = Stats()


def call(prefer: str, method: str, path: str, key: str, body: dict | None = None,
         headers: dict | None = None) -> tuple[int, dict, dict]:
    """Sends to `prefer`, and to the other instance whenever the one tried is down or stopping."""
    names = [prefer] + [n for n in INSTANCES if n != prefer]
    deadline = time.monotonic() + FAILOVER_TIMEOUT
    attempt = 0
    while True:
        name = names[attempt % 2]
        try:
            status, h, parsed = http(method, INSTANCES[name] + path, key, body, headers)
            # 503: the instance is draining or its database pool is closing. Anything else is
            # the answer.
            if status != 503:
                STATS.serve(name)
                return status, h, parsed
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
            pass
        attempt += 1
        STATS.add(failovers=1)
        if time.monotonic() > deadline:
            raise Failed(f"{method} {path}: neither instance answered for {FAILOVER_TIMEOUT:.0f} s")
        time.sleep(0.05 if attempt % 2 else 0.25)


def order_body(merchant: str, ref: str, amount: int) -> dict:
    return {
        "items": [{"sku": SKU, "quantity": 1, "unit_amount": amount}],
        "currency": "EUR",
        "merchant_reference": ref,
        "shipping_address": {"name": "Alice Example", "line1": "1 Main Street",
                             "city": "Springfield", "postal_code": "12345", "country": "NL"},
        "payment_method": "pm_card_ok",
    }


def create(merchant: str, ref: str, amount: int, prefer: str) -> dict:
    key = str(uuid.uuid4())
    status, h, order = call(prefer, "POST", "/v1/orders", MERCHANTS[merchant],
                            order_body(merchant, ref, amount), {"Idempotency-Key": key})
    if status != 201:
        raise Failed(f"create {ref}: HTTP {status} {order}")
    if h.get("Idempotent-Replayed") == "true":
        STATS.add(replays=1)
    return {"id": order["id"], "merchant": merchant, "ref": ref, "amount": amount,
            "created_at": order["created_at"]}


def compose(*args: str) -> str:
    out = subprocess.run(COMPOSE + list(args), capture_output=True, text=True)
    if out.returncode != 0:
        raise Failed(f"docker compose {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout


def wait_ready(name: str, timeout: float = 120) -> float:
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        try:
            if http("GET", INSTANCES[name] + "/readyz", timeout=2)[0] == 200:
                return time.monotonic() - start
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
            pass
        time.sleep(0.2)
    raise Failed(f"{name} not ready after {timeout:.0f} s")


class RollingRestart(threading.Thread):
    def __init__(self) -> None:
        super().__init__(name="rolling-restart", daemon=True)
        self.steps: list[str] = []
        self.done = threading.Event()
        self.error: BaseException | None = None
        self.finished_at = 0.0

    def run(self) -> None:
        try:
            for name in INSTANCES:
                t0 = time.monotonic()
                compose("stop", "--timeout", "10", name)
                stopped = time.monotonic() - t0
                logs = compose("logs", "--no-log-prefix", "--since", "2m", name)
                handed = [l for l in logs.splitlines() if "saga worker stopped" in l]
                summary = "no stop line in the log"
                if handed:
                    m = re.search(r"saga worker stopped in \d+ ms; orders handed back mid-step: \d+",
                                  handed[-1])
                    summary = m.group(0) if m else handed[-1][:160]
                compose("start", name)
                ready = wait_ready(name)
                step = f"{name}: SIGTERM to exit {stopped:.1f} s ({summary}); ready again {ready:.1f} s after start"
                self.steps.append(step)
                log(step)
        except BaseException as e:  # reported by the main thread
            self.error = e
        finally:
            self.finished_at = time.monotonic()
            self.done.set()


class LiveLoad:
    """Keeps creating orders through the restart, alternating merchants and instances."""

    def __init__(self, run_id: str) -> None:
        self.run_id = run_id
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.created: list[dict] = []
        self.error: BaseException | None = None
        self.name = "live-load"
        self.threads = [threading.Thread(target=self._run, args=(t,), daemon=True)
                        for t in range(LIVE_THREADS)]

    def start(self) -> None:
        for t in self.threads:
            t.start()

    def join(self) -> None:
        for t in self.threads:
            t.join()

    def _run(self, t: int) -> None:
        names = list(INSTANCES)
        i = t
        try:
            while not self.stop.is_set():
                merchant = "acme" if i % 2 == 0 else "globex"
                order = create(merchant, f"rr-{self.run_id}-live-{i}", 100 + i % 900,
                               names[(i // 2) % 2])
                with self.lock:
                    self.created.append(order)
                i += LIVE_THREADS
                self.stop.wait(LIVE_THREADS / LIVE_RATE)
        except BaseException as e:
            self.error = e


class Pager(threading.Thread):
    def __init__(self, merchant: str, restart: RollingRestart, first_page: threading.Event) -> None:
        super().__init__(name=f"pager-{merchant}", daemon=True)
        self.merchant = merchant
        self.restart = restart
        self.first_page = first_page
        self.listed: list[dict] = []
        self.pages = 0
        self.cross_instance = 0
        self.error: BaseException | None = None
        self.finished_at = 0.0

    def run(self) -> None:
        names = list(INSTANCES)
        cursor = None
        last = None
        try:
            while True:
                q = {"limit": PAGE_LIMIT}
                if cursor:
                    q["cursor"] = cursor
                prefer = names[self.pages % 2]
                status, _, page = call(prefer, "GET", "/v1/orders?" + urllib.parse.urlencode(q),
                                       MERCHANTS[self.merchant])
                if status != 200:
                    raise Failed(f"{self.merchant} page {self.pages + 1}: HTTP {status} {page}")
                self.pages += 1
                if cursor:
                    self.cross_instance += 1
                self.listed.extend(page["data"])
                if self.pages == 1:
                    self.first_page.set()
                if not page["has_more"]:
                    if "next_cursor" in page:
                        raise Failed(f"{self.merchant}: next_cursor on the last page")
                    break
                cursor = page["next_cursor"]
                if cursor == last:
                    raise Failed(f"{self.merchant}: the same cursor twice")
                last = cursor
                if not self.restart.done.is_set():
                    time.sleep(PAGE_DELAY)
        except BaseException as e:
            self.error = e
        finally:
            self.finished_at = time.monotonic()


def sim_control(method: str, path: str, body: dict | None = None) -> None:
    status = http(method, f"{SIM}/sim/control{path}", body=body,
                  headers={"Authorization": f"Bearer {SIM_KEY}"})[0]
    if status not in (200, 204):
        raise Failed(f"simulator {method} {path}: HTTP {status}")


def check_listing(merchant: str, listed: list[dict], seeded: list[dict], others: set[str]) -> None:
    ids = [o["id"] for o in listed]
    seen: set[str] = set()
    dupes = {i for i in ids if i in seen or seen.add(i)}
    if dupes:
        raise Failed(f"{merchant}: {len(dupes)} orders listed twice, e.g. {sorted(dupes)[:3]}")
    missing = [o for o in seeded if o["id"] not in seen]
    if missing:
        raise Failed(f"{merchant}: {len(missing)} seeded orders never listed, e.g. "
                     f"{[o['id'] for o in missing[:3]]}")
    foreign = seen & others
    if foreign:
        raise Failed(f"{merchant}: listed {len(foreign)} of the other merchant's orders")
    for a, b in zip(listed, listed[1:]):
        # Newest first by (created_at, id). created_at is RFC 3339 UTC with the same number of
        # fraction digits for a stored value, so compare it as a parsed instant.
        ka, kb = (iso_micros(a["created_at"]), uuid.UUID(a["id"]).bytes), \
                 (iso_micros(b["created_at"]), uuid.UUID(b["id"]).bytes)
        if not ka > kb:
            raise Failed(f"{merchant}: {a['id']} listed before {b['id']} but not newer")


def iso_micros(s: str) -> int:
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", s)
    if not m:
        raise Failed(f"unexpected timestamp {s}")
    whole = time.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S")
    frac = (m.group(2) or "").ljust(9, "0")
    return calendar.timegm(whole) * 1_000_000 + int(frac[:6])


def list_all(merchant: str, query: dict) -> list[dict]:
    out, cursor = [], None
    while True:
        q = dict(query, limit=100)
        if cursor:
            q["cursor"] = cursor
        status, _, page = call("app", "GET", "/v1/orders?" + urllib.parse.urlencode(q, doseq=True),
                               MERCHANTS[merchant])
        if status != 200:
            raise Failed(f"listing {merchant} {query}: HTTP {status} {page}")
        out.extend(page["data"])
        if not page["has_more"]:
            return out
        cursor = page["next_cursor"]


def check_ledger(order: dict) -> str | None:
    q = urllib.parse.urlencode({"reference": order["id"]})
    status, _, led = http("GET", f"{SIM}/sim/control/ledger?{q}",
                          headers={"Authorization": f"Bearer {SIM_KEY}"})
    if status != 200:
        return f"ledger HTTP {status}"
    ops = [(op["operation"], op["amount"]) for op in led["payment_operations"]]
    want = [("authorize", order["amount"]), ("capture", order["amount"])]
    if ops != want:
        return f"provider operations {ops}, want {want}"
    if not led.get("reservation"):
        return "no stock reservation"
    if not led.get("shipment"):
        return "no shipment"
    return None


def main() -> int:
    run_id = uuid.uuid4().hex[:8]
    log(f"run {run_id}: {ORDERS} orders over {', '.join(MERCHANTS)}; instances {INSTANCES}")
    for name in INSTANCES:
        wait_ready(name)
    for name, url in INSTANCES.items():
        version = http("GET", url + "/livez")[2]["components"]["build"]["details"]["version"]
        log(f"{name} runs payflow {version}")
    sim_control("PUT", f"/stock/{SKU}", {"available": 1_000_000_000})

    t0 = time.monotonic()
    names = list(INSTANCES)
    jobs = [("acme" if i % 2 == 0 else "globex", f"rr-{run_id}-{i}", 100 + random.randrange(9900),
             names[(i // 2) % 2]) for i in range(ORDERS)]
    with concurrent.futures.ThreadPoolExecutor(CREATORS) as pool:
        seeded = list(pool.map(lambda j: create(*j), jobs))
    log(f"created {len(seeded)} orders in {time.monotonic() - t0:.1f} s")
    by_merchant = {m: [o for o in seeded if o["merchant"] == m] for m in MERCHANTS}

    restart = RollingRestart()
    live = LiveLoad(run_id)
    first_pages = {m: threading.Event() for m in MERCHANTS}
    pagers = [Pager(m, restart, first_pages[m]) for m in MERCHANTS]
    for op in ("payment.authorize", "payment.capture"):
        sim_control("PUT", f"/faults/{op}", {"latency_ms": PROVIDER_LATENCY_MS})
    paging_start = time.monotonic()
    for p in pagers:
        p.start()
    live.start()
    for e in first_pages.values():
        e.wait(30)
    restart.start()
    for p in pagers:
        p.join()
    restart.done.wait()
    live.stop.set()
    live.join()
    sim_control("DELETE", "/faults")
    for t in (restart, live, *pagers):
        if t.error:
            raise t.error if isinstance(t.error, Failed) else Failed(f"{t.name}: {t.error!r}")

    paging_end = max(p.finished_at for p in pagers)
    if paging_end < restart.finished_at:
        raise Failed("paging finished before the rolling restart did; raise PAGE_DELAY")
    for p in pagers:
        others = {o["id"] for m, os_ in by_merchant.items() if m != p.merchant for o in os_}
        others |= {o["id"] for o in live.created if o["merchant"] != p.merchant}
        check_listing(p.merchant, p.listed, by_merchant[p.merchant], others)
        log(f"{p.merchant}: {len(p.listed)} orders listed ({len(by_merchant[p.merchant])} seeded by this "
            f"run) on {p.pages} pages, {p.cross_instance} cursors redeemed on the other instance; "
            f"none repeated, none missing, newest first")
    log(f"paging took {paging_end - paging_start:.1f} s; the restart finished "
        f"{paging_end - restart.finished_at:.1f} s before the last page")
    log(f"live load created {len(live.created)} orders during the restart; requests failed over "
        f"{STATS.failovers} times, {STATS.replays} creates were replays; served {STATS.served}")

    everything = seeded + live.created
    deadline = time.monotonic() + SETTLE_TIMEOUT
    while True:
        open_ = sum(len(list_all(m, {"state": ",".join(NON_TERMINAL)})) for m in MERCHANTS)
        if open_ == 0:
            break
        if time.monotonic() > deadline:
            raise Failed(f"{open_} orders still not terminal after {SETTLE_TIMEOUT:.0f} s")
        time.sleep(1)
    completed = {o["id"]: o for m in MERCHANTS for o in list_all(m, {"state": "COMPLETED"})}
    not_done = [o for o in everything if o["id"] not in completed]
    if not_done:
        raise Failed(f"{len(not_done)} orders did not complete, e.g. {not_done[:3]}")
    lat = sorted(iso_micros(completed[o["id"]]["updated_at"]) - iso_micros(o["created_at"])
                 for o in everything)
    p = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))] / 1000
    log(f"all {len(everything)} orders COMPLETED; created to completed p50 {p(0.5):.0f} ms, "
        f"p99 {p(0.99):.0f} ms, max {lat[-1] / 1000:.0f} ms")

    with concurrent.futures.ThreadPoolExecutor(CREATORS) as pool:
        problems = [(o["id"], err) for o, err in zip(everything, pool.map(check_ledger, everything))
                    if err]
    if problems:
        raise Failed(f"{len(problems)} orders wrong at the providers, e.g. {problems[:3]}")
    log(f"provider ledgers: every order authorized once and captured once for its amount, "
        f"with one reservation and one shipment")
    for step in restart.steps:
        log(f"restart {step}")
    log("PASS")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Failed as e:
        log(f"FAIL: {e}")
        sys.exit(1)
