// Issue #1: verifies the generated TypeScript models compile and the
// scalar union / ACK shapes are usable from the dashboard code.
import {
  EventIngestFromJSON,
  EventIngestToJSON,
  BatchAckResponseFromJSON,
} from "../../generated/typescript/models";

const raw = {
  installationId: "install-9f2c",
  events: [
    {
      eventId: "evt-1",
      name: "product_viewed",
      occurredAt: "2026-10-09T21:30:00.123Z",
      properties: { price: 19900, in_stock: true, note: "가나다 🎉" },
      sdkVersion: "0.1.0",
      contractVersion: "1",
    },
  ],
};

const req = EventIngestFromJSON(raw.events[0]);
const back = EventIngestToJSON(req);
console.log("event:", back.name, "| price:", back.properties?.["price"]);

const ack = BatchAckResponseFromJSON({
  results: [{ index: 0, status: "accepted", eventId: "evt-1" }],
});
console.log("ack status:", ack.results[0]?.status);
