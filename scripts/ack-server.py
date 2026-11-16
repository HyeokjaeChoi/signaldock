#!/usr/bin/env python3
"""Host-side ACK server for scripts/device-durability.sh (cases airplane and powerloss, ADR-0029, ADR-0049).

Usage: ack-server.py <port> <log-file>
Mode: `GET /__mode/drop` makes the server commit each batch and then close the connection without an answer (a lost ACK:
the SDK keeps the events and resends them later); `GET /__mode/ok` is the default.
Answers every POST like the ingestion server for ACK purposes (architecture.md "Lost ACKs and event
deduplication"): first sight of an eventId is "accepted"; a later send with the same content is "duplicate";
a later send with other content is "rejected" with code "CONFLICT". Appends one line per received event to
<log-file>: eventId, status ("accepted", "duplicate" or "conflict") and event name, separated by tabs.
The emulator reaches the server at 10.0.2.2; airplane mode cuts that route, which the loopback address does
not follow. The state lives in this process, so it survives a kill of the emulator (case powerloss).
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

port, log = int(sys.argv[1]), sys.argv[2]
seen = {}  # eventId -> canonical content
mode = "ok"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global mode
        if self.path in ("/__mode/ok", "/__mode/drop"):
            mode = self.path.rsplit("/", 1)[1]
            self.send_response(204)
        else:
            self.send_response(404)
        self.end_headers()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/v1/client-reports":  # SDK client report (ADR-0036): not an event batch
            self.send_response(204)
            self.end_headers()
            return
        results = []
        with open(log, "a") as f:
            for i, event in enumerate(body["events"]):
                eid = event["eventId"]
                content = json.dumps([body["installationId"], event], sort_keys=True)
                if eid not in seen:
                    seen[eid] = content
                    status, item = "accepted", {"status": "accepted"}
                elif seen[eid] == content:
                    status, item = "duplicate", {"status": "duplicate"}
                else:
                    status, item = "conflict", {"status": "rejected", "code": "CONFLICT"}
                results.append({"index": i, "eventId": eid, **item})
                f.write(f"{eid}\t{status}\t{event['name']}\n")
        if mode == "drop":
            return  # HTTP/1.0: the connection closes with no answer
        out = json.dumps({"results": results}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", port), Handler).serve_forever()
