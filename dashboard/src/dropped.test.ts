import { describe, expect, it } from "vitest";
import { droppedTotal } from "./dropped";

describe("droppedTotal", () => {
  it("is undefined before the first result", () => expect(droppedTotal(undefined)).toBeUndefined());
  it("is 0 for no rows", () => expect(droppedTotal({ items: [], truncated: false })).toBe(0));
  it("sums the shown rows", () =>
    expect(droppedTotal({ items: [{ sdkVersion: "0.1.0", reason: "queue_full", count: 7 }, { sdkVersion: "0.2.0", reason: "storage_error", count: 5 }], truncated: true })).toBe(12));
});
