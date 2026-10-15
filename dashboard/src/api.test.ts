import { afterEach, describe, expect, it, vi } from "vitest";
import { realApi } from "./api";

const filters = { from: "2026-10-12T00:00:00Z", to: "2026-10-13T00:00:00Z", name: "add_to_cart", planStatus: "invalid_properties" as const };
const signal = new AbortController().signal;

function stubFetch(body: unknown) {
  const fetchMock = vi.fn(async (_url: string) => new Response(JSON.stringify(body), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  return () => new URL(fetchMock.mock.calls[0]![0], "http://dashboard.test");
}

afterEach(() => vi.unstubAllGlobals());

describe("plan status requests (ADR-0022)", () => {
  it("sends the planStatus filter on the list", async () => {
    const url = stubFetch({ items: [] });
    await realApi.listEvents(filters, "receivedAt_desc", undefined, signal);
    expect(url().pathname).toBe("/v1/events");
    expect(url().searchParams.get("planStatus")).toBe("invalid_properties");
  });

  it("sends the planStatus filter on the counts by name and by time", async () => {
    const byName = stubFetch({ items: [], truncated: false });
    await realApi.countsByName(filters, signal);
    expect(byName().searchParams.get("planStatus")).toBe("invalid_properties");
    const byTime = stubFetch({ timeZone: "Asia/Seoul", bucket: "hour", items: [] });
    await realApi.countsByTime(filters, signal);
    expect(byTime().searchParams.get("planStatus")).toBe("invalid_properties");
  });

  it("does not send planStatus to the plan-status counts, which split by it", async () => {
    const url = stubFetch({ planned: 1, unplannedEvent: 2, invalidProperties: 3, notChecked: 4 });
    const counts = await realApi.countsByPlanStatus(filters, signal);
    expect(url().pathname).toBe("/v1/event-counts/by-plan-status");
    expect(url().searchParams.has("planStatus")).toBe(false);
    expect(url().searchParams.get("name")).toBe("add_to_cart"); // the other filters still apply
    expect(counts).toEqual({ planned: 1, unplannedEvent: 2, invalidProperties: 3, notChecked: 4 });
  });
});
