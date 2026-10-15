import { describe, expect, it } from "vitest";
import type { EventReceiptPage, EventTimeCounts } from "../../generated/typescript/models";
import { lastReceipt, periodTotal, planViolations, queryHealth, receivedOnDay, topEvent } from "./kpi";
import { QueryError } from "./polling";
import type { QueryState } from "./polling";

const TZ = "Asia/Seoul";
const hourly = (items: [string, number][]): EventTimeCounts =>
  ({ timeZone: TZ, bucket: "hour", items: items.map(([start, count]) => ({ start, count })) }) as never;

describe("periodTotal", () => {
  it("sums every bucket of the shown period, and is undefined before the first result (Q144)", () => {
    expect(periodTotal(hourly([["2026-10-12T14:00:00Z", 7], ["2026-10-01T00:00:00Z", 5]]))).toBe(12);
    expect(periodTotal(hourly([]))).toBe(0);
    expect(periodTotal(undefined)).toBeUndefined();
  });
});

describe("receivedOnDay", () => {
  it("sums the buckets that start on the day in the project timezone", () => {
    // 2026-10-12T15:00Z is 00:00 on 13 Oct in Seoul; 14:00Z is still 12 Oct.
    const counts = hourly([["2026-10-12T14:00:00Z", 7], ["2026-10-12T15:00:00Z", 3], ["2026-10-12T16:00:00Z", 4]]);
    expect(receivedOnDay(counts, "2026-10-13", TZ)).toBe(7);
    expect(receivedOnDay(counts, "2026-10-12", TZ)).toBe(7);
  });
  it("is undefined without data or when the period has no bucket on the day", () => {
    expect(receivedOnDay(undefined, "2026-10-13", TZ)).toBeUndefined();
    expect(receivedOnDay(hourly([["2026-10-01T00:00:00Z", 5]]), "2026-10-13", TZ)).toBeUndefined();
  });
  it("is withheld when the server reports another timezone (its day buckets start on other dates)", () => {
    const counts = { ...hourly([["2026-10-12T15:00:00Z", 3]]), timeZone: "America/Los_Angeles" } as EventTimeCounts;
    expect(receivedOnDay(counts, "2026-10-13", TZ)).toBeUndefined();
  });
  it("is 0 for a bucket of the day with no events", () => {
    expect(receivedOnDay(hourly([["2026-10-12T15:00:00Z", 0]]), "2026-10-13", TZ)).toBe(0);
  });
});

describe("planViolations", () => {
  it("adds unplanned events and invalid properties, and counts the checked events (ADR-0022)", () => {
    expect(planViolations({ planned: 5, unplannedEvent: 2, invalidProperties: 3, notChecked: 7 })).toEqual({
      violations: 5, unplanned: 2, invalid: 3, checked: 10, notChecked: 7,
    });
    expect(planViolations({ planned: 0, unplannedEvent: 0, invalidProperties: 0, notChecked: 4 })?.checked).toBe(0);
    expect(planViolations(undefined)).toBeUndefined();
  });
});

describe("topEvent", () => {
  it("takes the largest count, first name on a tie, and nothing when empty", () => {
    expect(topEvent({ items: [{ name: "a", count: 2 }, { name: "b", count: 9 }, { name: "c", count: 9 }], truncated: false })).toEqual({ name: "b", count: 9 });
    expect(topEvent({ items: [], truncated: false })).toBeUndefined();
    expect(topEvent(undefined)).toBeUndefined();
  });
});

describe("lastReceipt", () => {
  const page = { items: [{ receivedAt: "2026-10-12T01:00:02.000Z" }, { receivedAt: "2026-10-12T01:00:01.000Z" }] } as unknown as EventReceiptPage;
  it("is the newest receipt on the first page sorted newest first", () => {
    expect(lastReceipt(page, "receivedAt_desc", true)).toBe(Date.parse("2026-10-12T01:00:02.000Z"));
  });
  it("is not claimed for another sort order, a later page, or no data", () => {
    expect(lastReceipt(page, "receivedAt_asc", true)).toBeUndefined();
    expect(lastReceipt(page, "receivedAt_desc", false)).toBeUndefined();
    expect(lastReceipt({ items: [] } as never, "receivedAt_desc", true)).toBeUndefined();
    expect(lastReceipt(undefined, "receivedAt_desc", true)).toBeUndefined();
  });
});

describe("queryHealth", () => {
  const ok: QueryState<unknown> = { stopped: false, loading: false, lastSuccessAt: 1 };
  it("is live when every query has succeeded", () => expect(queryHealth([ok, ok]).health).toBe("live"));
  it("is paused, not live, when a query does not poll (past list page, Q77)", () => expect(queryHealth([ok, ok], false).health).toBe("paused"));
  it("is paused, not retrying, when a query that does not poll fails (Q77)", () => {
    const failed: QueryState<unknown> = { ...ok, error: new QueryError("x", 503), nextEligibleAt: 5 };
    expect(queryHealth([failed, ok], [false, true]).health).toBe("paused");
    expect(queryHealth([failed, ok], [true, true]).health).toBe("retrying");
  });
  it("is loading before the first success", () => expect(queryHealth([ok, { stopped: false, loading: true }]).health).toBe("loading"));
  it("is idle when nothing runs (invalid period)", () => expect(queryHealth([{ stopped: false, loading: false }]).health).toBe("idle"));
  it("is retrying with the latest next-attempt time after a failure (Q109)", () => {
    const failed = (at: number): QueryState<unknown> => ({ ...ok, error: new QueryError("x", 503), nextEligibleAt: at });
    expect(queryHealth([ok, failed(5), failed(9)])).toEqual({ health: "retrying", nextEligibleAt: 9 });
  });
  it("is stopped on a contract error and wins over a retry (Q108)", () => {
    const stopped: QueryState<unknown> = { ...ok, error: new QueryError("x", 400, "CONTRACT_VERSION_UNSUPPORTED"), stopped: true };
    const failed: QueryState<unknown> = { ...ok, error: new QueryError("x", 503), nextEligibleAt: 5 };
    expect(queryHealth([failed, stopped]).health).toBe("stopped");
  });
});
