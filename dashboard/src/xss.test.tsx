import { act, cleanup, fireEvent, render as rtlRender, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { EventReceiptDetail, EventReceiptSummary } from "../../generated/typescript/models";
import { App } from "./App";
import type { Api } from "./api";
import { I18nProvider } from "./i18n";

// Issue 46: key holders send free event names and property values. React escaping is the only protection (threat model).
// These tests feed hostile text through the real components and assert it stays text.
vi.mock("./Charts", () => ({ NameChart: () => null, TimeChart: () => null }));

const PAYLOADS = [
  "<img src=x onerror=alert(1)>",
  '"><script>alert(1)</script>',
  "<svg onload=alert(1)>",
  "</td></tr></table><iframe src=javascript:alert(1)>",
];
const flush = () => act(async () => { await vi.advanceTimersByTimeAsync(0); });
const INJECTED = "img, script, iframe, svg, [onerror], [onload]";

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe.each(PAYLOADS)("hostile text %s", (payload) => {
  const summary: EventReceiptSummary = {
    eventId: "00000000-0000-4000-8000-000000000001",
    installationId: "11111111-1111-4111-8111-111111111111",
    name: payload,
    occurredAt: "2026-10-12T01:00:00.000Z",
    receivedAt: "2026-10-12T01:00:01.000Z",
    sdkVersion: payload,
  };
  const detail: EventReceiptDetail = { ...summary, properties: { [payload]: payload } };
  const api = {
    listEvents: vi.fn(async () => ({ items: [summary] })),
    getEvent: vi.fn(async () => detail),
    countsByName: vi.fn(async () => ({ items: [{ name: payload, count: 3 }], truncated: false })),
    countsByTime: vi.fn(async () => ({ timeZone: "Asia/Seoul", bucket: "hour" as const, items: [] })),
    countsByPlanStatus: vi.fn(async () => ({ planned: 0, unplannedEvent: 0, invalidProperties: 0, notChecked: 0 })),
  } as unknown as Api;
  const render = () => rtlRender(<I18nProvider initialLanguage="en"><App api={api} /></I18nProvider>);

  it("renders event name, property key and value, and top-event KPI as text only", async () => {
    const { container } = render();
    await flush();
    expect(container.querySelector(INJECTED)).toBeNull();
    expect(screen.getByRole("button", { name: payload })).toBeTruthy(); // list row
    expect(container.querySelector("code.kpi-name")?.textContent).toBe(payload);

    fireEvent.click(screen.getByRole("button", { name: payload }));
    await flush();
    expect(container.querySelector(INJECTED)).toBeNull();
    const detailPanel = container.querySelector(".detail")!;
    expect(detailPanel.querySelector("dd")?.textContent).toBe(payload); // name
    expect(detailPanel.querySelector("tbody th")?.textContent).toBe(payload); // property key
    expect(detailPanel.querySelectorAll("tbody td")[1]?.textContent).toBe(payload); // property value
    expect(detailPanel.querySelector("pre")?.textContent).toContain(JSON.stringify(payload).slice(1, -1)); // JSON view
  });

  it("keeps a hostile name typed into the filter as input text", async () => {
    const { container } = render();
    await flush();
    const input = container.querySelector<HTMLInputElement>("input[name=eventName]")!;
    fireEvent.change(input, { target: { value: payload } });
    expect(input.value).toBe(payload);
    expect(container.querySelector(INJECTED)).toBeNull();
  });
});

// Plan-status labels come from the fixed dictionary, not from the server text (ADR-0022): a status the client does not know
// must not reach the page as markup.
describe("plan status label", () => {
  it("never renders a server-sent unknown status as markup", async () => {
    const { PlanBadge } = await import("./PlanBadge");
    const { container } = rtlRender(<I18nProvider initialLanguage="en"><PlanBadge status={PAYLOADS[0] as never} /></I18nProvider>);
    expect(container.querySelector(INJECTED)).toBeNull();
  });
});
