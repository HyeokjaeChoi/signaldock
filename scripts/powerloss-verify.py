#!/usr/bin/env python3
"""Host-side verdict of gate A6 (ADR-0049 decision 6): reads the log of scripts/ack-server.py after the power-loss case.

Usage: powerloss-verify.py <log-file> <pre> <lostack> <pending> <tail>   (set sizes, as given to PowerLossPhases)

The log has one line per received event: eventId, status, name (tab separated). Fails (exit 1, reasons on stderr) when
  - the server answered "conflict" to any event (a resend with other content), or
  - an eventId was not "accepted" exactly once, first, and only "duplicate" after that, or
  - an event of the sets pre, lostack or pending never reached the server (lostack and pending: committed before the checkpoint
    and still queued when the emulator was killed, so the SDK must have kept them).
Prints key=value lines that the nightly summary takes: resends (duplicate answers: the lostack events, whose ACK the host
server dropped, and any queue delete that the kill undid) and lostAfterCheckpoint (tail events that never arrived).
These two are counted, not gated.
"""
import sys
from collections import defaultdict


def verify(lines, pre, lostack, pending, tail):
    """Returns (problems, numbers)."""
    statuses = defaultdict(list)  # eventId -> statuses in order
    accepted = {}  # name -> eventId
    for line in lines:
        event_id, status, name = line.rstrip("\n").split("\t")
        statuses[event_id].append(status)
        if status == "accepted":
            accepted[name] = event_id
    problems = []
    for event_id, seq in statuses.items():
        if "conflict" in seq:
            problems.append(f"{event_id}: server answered conflict to a resend {seq}")
        elif seq[0] != "accepted" or any(s != "duplicate" for s in seq[1:]):
            problems.append(f"{event_id}: statuses {seq}, expected accepted once, then duplicate only")
    for prefix, n in (("pl_pre", pre), ("pl_lostack", lostack), ("pl_pending", pending)):
        missing = [f"{prefix}_{i}" for i in range(n) if f"{prefix}_{i}" not in accepted]
        if missing:
            problems.append(f"{len(missing)} {prefix} events never reached the server, first {missing[0]}")
    numbers = {
        "resends": sum(1 for seq in statuses.values() for s in seq if s == "duplicate"),
        "lostAfterCheckpoint": sum(1 for i in range(tail) if f"pl_tail_{i}" not in accepted),
    }
    return problems, numbers


def main(argv):
    path = argv[0]
    pre, lostack, pending, tail = (int(a) for a in argv[1:5])
    with open(path, encoding="utf-8") as f:
        problems, numbers = verify(f.readlines(), pre, lostack, pending, tail)
    for p in problems:
        print("FAIL: " + p, file=sys.stderr)
    for k, v in numbers.items():
        print(f"{k}={v}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
