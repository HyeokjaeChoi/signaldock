// Issue #1: verifies the generated TypeScript models compile and the
// scalar union / ACK shapes round-trip correctly. Executed by
// `npm run contract-check` (wired into scripts/verify-contract.sh):
// a failed assertion exits non-zero, so conversion regressions fail
// verification instead of passing a compile-only check.
import {
  EventIngestFromJSON,
  EventIngestToJSON,
  BatchAckResponseFromJSON,
  ClientReportCountsFromJSON,
  EventPlanStatusCountsFromJSON,
  EventReceiptDetailFromJSON,
  EventReceiptDetailToJSON,
  EventReceiptPageFromJSON,
} from "../../generated/typescript/models";
import { purchaseCompleted } from "../../tracking-plan/generated/typescript/events";

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

// Tracking plan builder output (ADR-0021) is the name + properties part of EventIngest.
const planned = EventIngestToJSON(
  EventIngestFromJSON({
    ...rawEvent,
    ...purchaseCompleted({ orderId: "o-1", itemCount: 2, totalMinor: 9000, isSimulated: true }),
  }),
);
assertEq(planned.name, "purchase_completed", "tracking plan builder name");
assertEq(
  planned.properties,
  { order_id: "o-1", item_count: 2, total_minor: 9000, is_simulated: true },
  "tracking plan builder properties",
);

// ADR-0022: plan status is optional on received events; absent and null both mean "not checked".
const receipt = { eventId: "e1", installationId: "i1", name: "x_y", occurredAt: "t", receivedAt: "t", sdkVersion: "0.1.0", sdkPlatform: "ios" };
const page = EventReceiptPageFromJSON({
  items: [receipt, { ...receipt, planStatus: "unplanned_event" }, { ...receipt, planStatus: null }, { ...receipt, planStatus: "invalid_properties" }],
});
assertEq(page.items.map((i) => i.planStatus), [undefined, "unplanned_event", undefined, "invalid_properties"], "plan status on list items");
const detail = EventReceiptDetailFromJSON({ ...receipt, properties: {}, planStatus: "planned" });
assertEq(EventReceiptDetailToJSON(detail).planStatus, "planned", "plan status on detail round-trip");
assertEq(EventReceiptDetailToJSON(detail).sdkPlatform, "ios", "sdkPlatform on detail round-trip");
assertEq(
  EventPlanStatusCountsFromJSON({ planned: 1, unplannedEvent: 2, invalidProperties: 3, notChecked: 4 }),
  { planned: 1, unplannedEvent: 2, invalidProperties: 3, notChecked: 4 },
  "plan status counts",
);

// ADR-0036: client report counts (a new query response).
assertEq(
  ClientReportCountsFromJSON({ items: [{ sdkPlatform: "android", sdkVersion: "0.1.0", reason: "queue_full", count: 12 }], truncated: false }),
  { items: [{ sdkPlatform: "android", sdkVersion: "0.1.0", reason: "queue_full", count: 12 }], truncated: false },
  "client report counts",
);

console.log("ALL CONTRACT-CHECK ASSERTIONS PASSED");
