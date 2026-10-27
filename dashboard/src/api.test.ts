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

describe("client report counts (Q164)", () => {
  it("sends only from and to, with the contract header, and decodes the rows", async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) =>
      new Response(JSON.stringify({ items: [{ sdkPlatform: "android", sdkVersion: "0.1.0", reason: "queue_full", count: 3 }], truncated: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const counts = await realApi.clientReportCounts({ ...filters, eventId: "e", installationId: "i" }, signal);
    const url = new URL(fetchMock.mock.calls[0]![0], "http://dashboard.test");
    expect(url.pathname).toBe("/v1/client-report-counts");
    expect([...url.searchParams.keys()].sort()).toEqual(["from", "to"]);
    expect(url.searchParams.get("from")).toBe(filters.from);
    expect(url.searchParams.get("to")).toBe(filters.to);
    expect((fetchMock.mock.calls[0]![1]!.headers as Record<string, string>)["SignalDock-Contract-Version"]).toBe("1");
    expect(counts).toEqual({ items: [{ sdkPlatform: "android", sdkVersion: "0.1.0", reason: "queue_full", count: 3 }], truncated: true });
  });
});

describe("platform filter (ADR-0046 decision 6)", () => {
  const withIos = { ...filters, sdkPlatform: "ios" as const };
  const sent = async (call: (f: typeof withIos | typeof filters) => Promise<unknown>, body: unknown, f: typeof withIos | typeof filters) => {
    const url = stubFetch(body);
    await call(f);
    return url();
  };
  const cases: [string, (f: typeof withIos | typeof filters) => Promise<unknown>, unknown][] = [
    ["/v1/events", (f) => realApi.listEvents(f, "receivedAt_desc", undefined, signal), { items: [] }],
    ["/v1/event-counts/by-name", (f) => realApi.countsByName(f, signal), { items: [], truncated: false }],
    ["/v1/event-counts/by-time", (f) => realApi.countsByTime(f, signal), { timeZone: "Asia/Seoul", bucket: "hour", items: [] }],
    ["/v1/event-counts/by-plan-status", (f) => realApi.countsByPlanStatus(f, signal), { planned: 0, unplannedEvent: 0, invalidProperties: 0, notChecked: 0 }],
    ["/v1/client-report-counts", (f) => realApi.clientReportCounts(f, signal), { items: [], truncated: false }],
  ];
  it.each(cases)("sends sdkPlatform to %s, and nothing when it is absent", async (path, call, body) => {
    const url = await sent(call, body, withIos);
    expect(url.pathname).toBe(path);
    expect(url.searchParams.get("sdkPlatform")).toBe("ios");
    expect((await sent(call, body, filters)).searchParams.has("sdkPlatform")).toBe(false);
  });

  it("sends only from, to, and sdkPlatform to the client report counts", async () => {
    const url = await sent((f) => realApi.clientReportCounts({ ...f, eventId: "e", installationId: "i" }, signal), { items: [], truncated: false }, withIos);
    expect([...url.searchParams.keys()].sort()).toEqual(["from", "sdkPlatform", "to"]);
  });
});
