#!/usr/bin/env python3
"""Test-only fault proxy between the SDK and the SignalDock server (issue 21, ADR-0010).

Python 3 standard library only. It runs on the host, never in the default Compose path.
It forwards `POST /v1/events:batch` to the upstream server and acts per mode:

  pass           forward and return the real response
  drop-response  forward, let the server commit, read its response, then reset the
                 connection without sending it (an ACK lost after commit)
  mutate         forward with the name of one event changed to start with a space, so the
                 server rejects only that event (same eventId, partial rejection)

Control (same port, never used by the SDK): `GET /__fault/mode`, `POST /__fault/mode/<mode>`,
`GET /__fault/log`. Every batch is also appended to --log as one JSON line.
"""
import argparse
import http.client
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODES = ("pass", "drop-response", "mutate")
MUTATE_INDEX = 1  # the second event of the batch
STATE = {"mode": "pass", "log": []}
LOCK = threading.Lock()


def record(entry, path):
    with LOCK:
        STATE["log"].append(entry)
        if path:
            with open(path, "a") as f:
                f.write(json.dumps(entry) + "\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    upstream = ("127.0.0.1", 8080)
    log_path = None

    def log_message(self, *args):  # quiet; the JSONL log is the record
        pass

    def _send(self, status, body, ctype="application/json", extra=()):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in extra:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/__fault/mode":
            self._send(200, json.dumps({"mode": STATE["mode"]}).encode())
        elif self.path == "/__fault/log":
            with LOCK:
                self._send(200, json.dumps(STATE["log"]).encode())
        else:
            self._send(404, b"{}")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        if self.path.startswith("/__fault/mode/"):
            mode = self.path.rsplit("/", 1)[1]
            if mode not in MODES:
                return self._send(400, b"{}")
            STATE["mode"] = mode
            return self._send(200, json.dumps({"mode": mode}).encode())
        mode = STATE["mode"]
        entry = {"t": time.time(), "mode": mode, "path": self.path}
        try:
            req = json.loads(body)
            entry["eventIds"] = [e.get("eventId") for e in req["events"]]
        except Exception:
            req = None
        if mode == "mutate" and req and len(req["events"]) > MUTATE_INDEX:
            ev = req["events"][MUTATE_INDEX]
            ev["name"] = " " + ev["name"]
            entry["mutatedEventId"] = ev["eventId"]
            body = json.dumps(req, separators=(",", ":")).encode()
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection", "content-length")}
        conn = http.client.HTTPConnection(*self.upstream, timeout=30)
        try:
            conn.request("POST", self.path, body, headers)
            resp = conn.getresponse()
            data = resp.read()
            entry["upstreamStatus"] = resp.status
            entry["upstreamBody"] = data.decode("utf-8", "replace")
            retry_after = resp.getheader("Retry-After")
            ctype = resp.getheader("Content-Type", "application/json")
        except Exception as e:  # upstream down: behave like a dead server
            entry["error"] = repr(e)
            record(entry, self.log_path)
            return self._send(502, b"{}")
        finally:
            conn.close()
        if mode == "drop-response":
            entry["action"] = "dropped"
            record(entry, self.log_path)
            # SO_LINGER 0 turns close() into a reset, like a cut cable after the server committed.
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            self.close_connection = True
            self.connection.close()
            return
        entry["action"] = "delivered"
        record(entry, self.log_path)
        self._send(resp.status, data, ctype, [("Retry-After", retry_after)] if retry_after else [])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen", default="127.0.0.1:8081")
    p.add_argument("--upstream", default="127.0.0.1:8080")
    p.add_argument("--log", default=None)
    a = p.parse_args()
    host, port = a.upstream.rsplit(":", 1)
    Handler.upstream = (host, int(port))
    Handler.log_path = a.log
    lh, lp = a.listen.rsplit(":", 1)
    ThreadingHTTPServer((lh, int(lp)), Handler).serve_forever()


if __name__ == "__main__":
    main()
