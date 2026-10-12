import { describe, expect, it } from "vitest";
import { addDays, dateIn, resolvePeriod } from "./period";

const now = Date.parse("2026-10-12T20:30:00Z"); // 2026-10-13 05:30 in Asia/Seoul

describe("resolvePeriod", () => {
  it("Today is [start of the date, start of the next date) in the project timezone", () => {
    expect(dateIn(now, "Asia/Seoul")).toBe("2026-10-13");
    expect(resolvePeriod("today", "", "", "Asia/Seoul", now)).toEqual({
      ok: true, from: "2026-10-12T15:00:00.000Z", to: "2026-10-13T15:00:00.000Z",
    });
  });

  it("last N days counts N calendar dates including today", () => {
    const p7 = resolvePeriod("7d", "", "", "Asia/Seoul", now);
    expect(p7).toMatchObject({ ok: true, from: "2026-10-06T15:00:00.000Z", to: "2026-10-13T15:00:00.000Z" });
    const p30 = resolvePeriod("30d", "", "", "Asia/Seoul", now);
    expect(p30).toMatchObject({ ok: true, from: "2026-09-13T15:00:00.000Z" });
  });

  it("handles a 23-hour DST day", () => {
    const p = resolvePeriod("custom", "2026-03-08", "2026-03-08", "America/New_York", now);
    expect(p).toEqual({ ok: true, from: "2026-03-08T05:00:00.000Z", to: "2026-03-09T04:00:00.000Z" });
  });

  it("rejects custom ranges longer than 30 dates instead of truncating", () => {
    expect(resolvePeriod("custom", "2026-09-01", "2026-09-30", "Asia/Seoul", now).ok).toBe(true);
    expect(resolvePeriod("custom", "2026-09-01", "2026-10-01", "Asia/Seoul", now).ok).toBe(false);
    expect(resolvePeriod("custom", "2026-09-02", "2026-09-01", "Asia/Seoul", now).ok).toBe(false);
    expect(resolvePeriod("custom", "", "", "Asia/Seoul", now).ok).toBe(false);
  });

  it("addDays crosses month ends", () => {
    expect(addDays("2026-10-31", 1)).toBe("2026-11-01");
  });
});
