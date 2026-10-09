// Issue #1: verifies the generated TypeScript models compile and the
// scalar union / ACK shapes round-trip correctly. Executed by
// `npm run contract-check` (wired into scripts/verify-contract.sh):
// a failed assertion exits non-zero, so conversion regressions fail
// verification instead of passing a compile-only check.
import {
  EventIngestFromJSON,
  EventIngestToJSON,
  BatchAckResponseFromJSON,
} from "../../generated/typescript/models";

function assertEq(actual: unknown, expected: unknown, label: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(`ASSERT FAIL [${label}]: expected ${e}, got ${a}`);
  }
  console.log(`ok [${label}]`);
}

const rawEvent = {
  eventId: "evt-1",
  name: "product_viewed",
  occurredAt: "2026-10-09T21:30:00.123Z",
  properties: { price: 19900, in_stock: true, note: "가나다 🎉" },
  sdkVersion: "0.1.0",
  contractVersion: "1",
};

const req = EventIngestFromJSON(rawEvent);
const back = EventIngestToJSON(req);

assertEq(back.eventId, "evt-1", "eventId round-trip");
assertEq(back.name, "product_viewed", "name round-trip");
assertEq(
  back.occurredAt,
  "2026-10-09T21:30:00.123Z",
  "occurredAt round-trip (millisecond precision)",
);
assertEq(back.properties?.["price"], 19900, "number property round-trip");
assertEq(back.properties?.["in_stock"], true, "boolean property round-trip");
assertEq(
  back.properties?.["note"],
  "가나다 🎉",
  "unicode string property round-trip",
);
assertEq(back.sdkVersion, "0.1.0", "sdkVersion round-trip");
assertEq(back.contractVersion, "1", "contractVersion round-trip");

const ack = BatchAckResponseFromJSON({
  results: [{ index: 0, status: "accepted", eventId: "evt-1" }],
});
assertEq(ack.results[0]?.index, 0, "ack index");
assertEq(ack.results[0]?.status, "accepted", "ack status");
assertEq(ack.results[0]?.eventId, "evt-1", "ack eventId");

console.log("ALL CONTRACT-CHECK ASSERTIONS PASSED");
