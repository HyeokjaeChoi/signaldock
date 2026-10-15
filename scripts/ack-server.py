#!/usr/bin/env python3
"""Host-side ACK server for scripts/device-durability.sh (case airplane, ADR-0029).

Usage: ack-server.py <port> <log-file>
Answers every POST like the ingestion server for ACK purposes: first sight of an eventId is "accepted",
later "duplicate". Appends each received eventId to <log-file>, one per line. The emulator reaches it at
10.0.2.2; airplane mode cuts that route, which the loopback address does not follow.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

port, log = int(sys.argv[1]), sys.argv[2]
seen = set()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        results = []
        with open(log, "a") as f:
            for i, event in enumerate(body["events"]):
                eid = event["eventId"]
                results.append({"index": i, "eventId": eid, "status": "duplicate" if eid in seen else "accepted"})
                seen.add(eid)
                f.write(eid + "\n")
        out = json.dumps({"results": results}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass


HTTPServer(("0.0.0.0", port), Handler).serve_forever()
