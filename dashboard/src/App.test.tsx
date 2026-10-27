import { act, cleanup, fireEvent, render as rtlRender, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { EventReceiptDetail, EventReceiptSummary } from "../../generated/typescript/models";
import { App } from "./App";
import type { Api } from "./api";
import { dictionaries, I18nProvider } from "./i18n";
import { QueryError } from "./polling";
import { translate } from "../../web-shared/i18n";
import type { Language, MessageKey, TOptions } from "../../web-shared/i18n";

vi.mock("./Charts", () => ({ NameChart: () => null, TimeChart: () => null }));

const summary = (n: number): EventReceiptSummary => ({
  eventId: `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`,
  installationId: "11111111-1111-4111-8111-111111111111",
  name: `event_${n}`,
  occurredAt: "2026-10-12T01:00:00.000Z",
  receivedAt: "2026-10-12T01:00:01.000Z",
  sdkVersion: "0.1.0",
  sdkPlatform: "android",
});

const detail: EventReceiptDetail = {
  ...summary(1),
  properties: { price: 19900, in_stock: true, note: "hello" },
};

function fakeApi(overrides: Partial<Api> = {}): Api & Record<"listEvents" | "countsByName" | "countsByPlanStatus" | "clientReportCounts", ReturnType<typeof vi.fn>> {
  return {
    listEvents: vi.fn(async (_f, _s, cursor?: string) =>
      cursor === "c2"
        ? { items: [summary(3)], nextCursor: undefined }
        : { items: [summary(1), summary(2)], nextCursor: "c2" },
    ),
    getEvent: vi.fn(async () => detail),
    countsByName: vi.fn(async () => ({ items: [], truncated: false })),
    countsByTime: vi.fn(async () => ({ timeZone: "Asia/Seoul", bucket: "hour" as const, items: [] })),
    countsByPlanStatus: vi.fn(async () => ({ planned: 0, unplannedEvent: 0, invalidProperties: 0, notChecked: 0 })),
    clientReportCounts: vi.fn(async () => ({ items: [], truncated: false })),
    ...overrides,
  } as never;
}

const flush = () => act(async () => { await vi.advanceTimersByTimeAsync(0); });
const advance = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

const LANGUAGES: Language[] = ["en", "ko"];

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

// Every test runs in both languages. Texts come from the dictionaries, never from literals (Q135, issue 27).
describe.each(LANGUAGES)("App (%s)", (lang) => {
  const t = (key: MessageKey<typeof dictionaries.en>, options?: TOptions) => translate(dictionaries[lang], lang, key, options);
  /** The fixed text parts of a message with a `{{time}}` slot. */
  const parts = (key: "status.lastSuccess" | "status.nextAttempt" | "status.queryFailed") =>
    t(key, { time: "\0", message: "\0" }).split("\0").map((x) => x.trim()).filter(Boolean);
  const render = (api: Api) => rtlRender(<I18nProvider initialLanguage={lang}><App api={api} /></I18nProvider>);

  it("keeps a past page in place while the first page keeps polling", async () => {
    const api = fakeApi();
    render(api);
    await flush();
    expect(screen.getByText("event_1")).toBeTruthy();

    await advance(5000);
    expect(api.listEvents).toHaveBeenCalledTimes(2); // first page polls every 5 s

    fireEvent.click(screen.getByRole("button", { name: t("list.next") }));
    await flush();
    expect(api.listEvents.mock.calls.at(-1)?.[2]).toBe("c2");
    expect(screen.getByText("event_3")).toBeTruthy();

    const calls = api.listEvents.mock.calls.length;
    await advance(30000);
    expect(api.listEvents.mock.calls.length).toBe(calls); // no auto refresh of a past page
    expect(screen.getByText("event_3")).toBeTruthy(); // and no jump to the latest page
    expect(screen.queryByText("event_1")).toBeNull();

    fireEvent.click(screen.getAllByRole("button", { name: t("controls.refresh") })[0]!);
    await flush();
    expect(api.listEvents.mock.calls.length).toBe(calls + 1);
    expect(api.listEvents.mock.calls.at(-1)?.[2]).toBe("c2");

    fireEvent.click(screen.getByRole("button", { name: t("list.first") }));
    await flush();
    expect(screen.getByText("event_1")).toBeTruthy();
  });

  it("keeps the last data and shows the error and last-success time when a query fails", async () => {
    let fail = false;
    const api = fakeApi({
      listEvents: vi.fn(async () => {
        if (fail) throw new QueryError("HTTP 503", 503);
        return { items: [summary(1)] };
      }) as never,
    });
    render(api);
    await flush();
    fail = true;
    await advance(5000);
    expect(screen.getByText("event_1")).toBeTruthy();
    const status = screen.getByText(t("status.events")).parentElement!;
    for (const part of parts("status.lastSuccess")) expect(status.textContent).toContain(part);
    expect(status.textContent).toContain("HTTP 503"); // a bare status error keeps the server text
    for (const part of parts("status.nextAttempt")) expect(status.textContent).toContain(part);
  });

  it("has a skip link as the first focusable element, and it targets the main region (issue 29)", async () => {
    const { container } = render(fakeApi());
    await flush();
    const first = container.querySelector<HTMLElement>("a[href], button, select, input, [tabindex='0']")!;
    expect(first.textContent).toBe(t("app.skip"));
    expect(first.getAttribute("href")).toBe("#main");
    expect(screen.getByRole("main").id).toBe("main");
  });

  it("moves focus to the details heading on open and back to the opening link on Close (issue 29)", async () => {
    render(fakeApi());
    await flush();
    const link = screen.getByRole("button", { name: "event_2" });
    fireEvent.click(link);
    await flush();
    expect(document.activeElement).toBe(screen.getByRole("heading", { level: 2, name: t("details.title") }));
    fireEvent.click(screen.getByRole("button", { name: t("details.close") }));
    await flush();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "event_2" }));
    // Opening another event moves focus to the heading again.
    fireEvent.click(screen.getByRole("button", { name: "event_1" }));
    await flush();
    fireEvent.click(screen.getByRole("button", { name: "event_2" }));
    await flush();
    expect(document.activeElement).toBe(screen.getByRole("heading", { level: 2, name: t("details.title") }));
  });

  it("does not expose the status separators to a screen reader (issue 29)", async () => {
    let fail = false;
    const api = fakeApi({
      listEvents: vi.fn(async () => {
        if (fail) throw new QueryError("HTTP 503", 503);
        return { items: [summary(1)] };
      }) as never,
    });
    render(api);
    await flush();
    fail = true;
    await advance(5000);
    const status = screen.getByText(t("status.events")).parentElement!.cloneNode(true) as HTMLElement;
    expect(status.textContent).toContain("|");
    status.querySelectorAll("[aria-hidden='true']").forEach((el) => el.remove());
    expect(status.textContent).not.toContain("|");
  });

  it("labels the event table and the property table (issue 29)", async () => {
    render(fakeApi());
    await flush();
    fireEvent.click(screen.getByRole("button", { name: "event_1" }));
    await flush();
    expect(screen.getByRole("table", { name: t("list.title", { page: "1" }) })).toBeTruthy();
    expect(screen.getByRole("table", { name: t("details.properties") })).toBeTruthy();
  });

  it("shows the empty-result text and the not-found text", async () => {
    const api = fakeApi({
      listEvents: vi.fn(async () => ({ items: [] })) as never,
    });
    render(api);
    await flush();
    expect(screen.getByText(t("list.empty"))).toBeTruthy();
  });

  it("shows details and copies the stored event JSON", async () => {
    const writeText = vi.fn(async (_text: string) => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const api = fakeApi();
    render(api);
    await flush();

    fireEvent.click(screen.getByRole("button", { name: "event_1" }));
    await flush();
    const panel = screen.getByRole("region", { name: t("details.label") });
    expect(within(panel).getByText("price")).toBeTruthy();
    expect(within(panel).getByText("19900")).toBeTruthy();

    // Details stay while the list polls.
    await advance(5000);
    expect(screen.getByRole("region", { name: t("details.label") })).toBeTruthy();
    expect(api.getEvent).toHaveBeenCalledTimes(1);

    fireEvent.click(within(panel).getByRole("button", { name: t("copy.json") }));
    await flush();
    expect(writeText).toHaveBeenCalledTimes(1);
    const copied = JSON.parse(writeText.mock.calls[0]![0]);
    expect(copied).toEqual(detail);
  });

  it("shows the not-found text for a 404 and a separate retry for other errors", async () => {
    let status = 404;
    const api = fakeApi({
      getEvent: vi.fn(async () => {
        throw new QueryError(status === 404 ? "nf" : "boom", status, status === 404 ? "EVENT_NOT_FOUND" : undefined);
      }) as never,
    });
    render(api);
    await flush();
    fireEvent.click(screen.getByRole("button", { name: "event_1" }));
    await flush();
    expect(screen.getByText(t("details.notFound"))).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: t("details.close") }));
    status = 503;
    fireEvent.click(screen.getByRole("button", { name: "event_1" }));
    await flush();
    expect(screen.getByText(/boom/)).toBeTruthy();
    expect(screen.getByRole("button", { name: t("status.retry") })).toBeTruthy();
  });
  it("shows the total of the shown period as the sub line of Received today (Q144)", async () => {
    const day = 24 * 3600 * 1000;
    const api = fakeApi({
      countsByTime: vi.fn(async () => ({
        timeZone: "Asia/Seoul", bucket: "day" as const,
        items: [{ start: new Date(Date.now() - 3 * day).toISOString(), count: 7 }, { start: new Date().toISOString(), count: 5 }],
      })) as never,
    });
    render(api);
    await flush();
    const card = screen.getByText(t("kpi.today")).parentElement!;
    expect(card.textContent).toContain("5");
    expect(within(card).getByText(t("kpi.todaySub", { value: "12" }))).toBeTruthy();
  });

  it("gives every KPI card a sub line, also without data (Q144)", async () => {
    render(fakeApi());
    await flush();
    for (const label of ["kpi.today", "kpi.lastReceipt", "kpi.state", "kpi.top"] as const) {
      const card = screen.getByText(t(label)).parentElement!;
      expect(card.querySelector(".kpi-sub")?.textContent, label).toBeTruthy();
    }
  });

  it("shows the table timestamp in a monospace element with a dimmed date part (Q146)", async () => {
    const { container } = render(fakeApi());
    await flush();
    const stamp = container.querySelector("td time.stamp")!;
    expect(stamp.getAttribute("datetime")).toBe("2026-10-12T01:00:01.000Z");
    expect(stamp.querySelector(".stamp-date")?.textContent).toMatch(/2026/);
  });

  describe("plan status (ADR-0022)", () => {
    const withStatus = (n: number, planStatus?: EventReceiptSummary["planStatus"]) => ({ ...summary(n), planStatus });
    const counts = (c: Partial<Record<"planned" | "unplannedEvent" | "invalidProperties" | "notChecked", number>>) =>
      vi.fn(async () => ({ planned: 0, unplannedEvent: 0, invalidProperties: 0, notChecked: 0, ...c })) as never;

    it("shows a badge with a word for each status, and Not checked when the event has none", async () => {
      const { container } = render(fakeApi({
        listEvents: vi.fn(async () => ({
          items: [withStatus(1, "planned"), withStatus(2, "unplanned_event"), withStatus(3, "invalid_properties"), withStatus(4)],
        })) as never,
      }));
      await flush();
      expect(Array.from(container.querySelectorAll("td .badge")).map((b) => [b.textContent, b.className])).toEqual([
        [t("plan.planned"), "badge ok"],
        [t("plan.unplanned_event"), "badge warn"],
        [t("plan.invalid_properties"), "badge bad"],
        [t("plan.notChecked"), "badge idle"],
      ]);
      expect(screen.getByRole("columnheader", { name: t("list.plan") })).toBeTruthy();
    });

    it("shows the status in the details", async () => {
      render(fakeApi({ getEvent: vi.fn(async () => ({ ...detail, planStatus: "invalid_properties" })) as never }));
      await flush();
      fireEvent.click(screen.getByRole("button", { name: "event_1" }));
      await flush();
      const panel = screen.getByRole("region", { name: t("details.label") });
      expect(within(panel).getByText(t("details.planStatus")).nextElementSibling!.textContent).toBe(t("plan.invalid_properties"));
    });

    it("filters by status: the select offers All and the three statuses, and Apply and Clear change the queries", async () => {
      const api = fakeApi();
      render(api);
      await flush();
      const select = screen.getByRole("combobox", { name: new RegExp(t("controls.planStatus")) }) as HTMLSelectElement;
      expect(Array.from(select.options).map((o) => [o.value, o.textContent])).toEqual([
        ["", t("plan.all")],
        ["planned", t("plan.planned")],
        ["unplanned_event", t("plan.unplanned_event")],
        ["invalid_properties", t("plan.invalid_properties")],
      ]);
      expect(api.listEvents.mock.calls.at(-1)?.[0].planStatus).toBeUndefined();

      fireEvent.change(select, { target: { value: "invalid_properties" } });
      expect(api.listEvents.mock.calls.at(-1)?.[0].planStatus).toBeUndefined(); // a draft only, like the other filters
      fireEvent.click(screen.getByRole("button", { name: t("controls.apply") }));
      await flush();
      expect(api.listEvents.mock.calls.at(-1)?.[0].planStatus).toBe("invalid_properties");
      expect(api.countsByName.mock.calls.at(-1)?.[0].planStatus).toBe("invalid_properties");

      fireEvent.click(screen.getByRole("button", { name: t("controls.clear") }));
      await flush();
      expect(api.listEvents.mock.calls.at(-1)?.[0].planStatus).toBeUndefined();
      expect(select.value).toBe("");
    });

    it("shows the violations of the shown period as a KPI card with a sub line", async () => {
      render(fakeApi({ countsByPlanStatus: counts({ planned: 5, unplannedEvent: 2, invalidProperties: 1000 }) }));
      await flush();
      const card = screen.getByText(t("kpi.violations")).parentElement!;
      expect(card.querySelector(".kpi-value")!.textContent).toBe(new Intl.NumberFormat(lang).format(1002));
      expect(card.querySelector(".kpi-sub")!.textContent).toBe(t("kpi.violationsSub", { unplanned: "2", invalid: new Intl.NumberFormat(lang).format(1000) }));
    });

    it("shows Not checked instead of a count when no event of the period has a status", async () => {
      render(fakeApi({ countsByPlanStatus: counts({ notChecked: 4 }) }));
      await flush();
      const card = screen.getByText(t("kpi.violations")).parentElement!;
      expect(card.querySelector(".kpi-value")!.textContent).toBe(t("plan.notChecked"));
      expect(card.querySelector(".kpi-sub")!.textContent).toBe(t("kpi.violationsNotChecked", { value: "4" }));
    });

    it("refreshes the plan counts with the other queries and reports its own status", async () => {
      const api = fakeApi();
      render(api);
      await flush();
      expect(screen.getByText(t("status.byPlan"))).toBeTruthy();
      const calls = api.countsByPlanStatus.mock.calls.length;
      fireEvent.click(screen.getAllByRole("button", { name: t("controls.refresh") })[0]!);
      await flush();
      expect(api.countsByPlanStatus.mock.calls.length).toBe(calls + 1);
    });
  });

  describe("dropped events (Q164)", () => {
    const rows = [
      { sdkVersion: "0.2.0", reason: "queue_full" as const, count: 1500 },
      { sdkVersion: "0.1.0", reason: "server_rejected" as const, count: 4 },
    ];
    const card = () => screen.getByRole("region", { name: t("dropped.title") });
    const fmt = (n: number) => new Intl.NumberFormat(lang).format(n);

    it("shows the rows with the translated reason, the wire name, the formatted count, and the total of the shown rows", async () => {
      render(fakeApi({ clientReportCounts: vi.fn(async () => ({ items: rows, truncated: false })) as never }));
      await flush();
      const table = within(card()).getByRole("table", { name: t("dropped.title") });
      const body = Array.from(table.querySelectorAll("tbody tr")).map((r) => Array.from(r.children).map((c) => c.textContent));
      expect(body).toEqual([
        ["0.2.0", `${t("dropped.reasons.queue_full")} queue_full`, fmt(1500)],
        ["0.1.0", `${t("dropped.reasons.server_rejected")} server_rejected`, "4"],
      ]);
      expect(table.querySelector("tfoot")!.textContent).toBe(`${t("dropped.total")}${fmt(1504)}`);
      expect(within(card()).queryByText(t("dropped.truncated", { limit: fmt(100) }))).toBeNull();
    });

    it("shows the empty state and the truncated note", async () => {
      const api = fakeApi();
      render(api);
      await flush();
      expect(within(card()).getByText(t("dropped.empty"))).toBeTruthy();
      expect(within(card()).queryByRole("table")).toBeNull();
      cleanup();
      render(fakeApi({ clientReportCounts: vi.fn(async () => ({ items: rows, truncated: true })) as never }));
      await flush();
      expect(within(card()).getByText(t("dropped.truncated", { limit: fmt(100) }))).toBeTruthy();
    });

    it("uses the period only, never the event filters", async () => {
      const api = fakeApi();
      render(api);
      await flush();
      fireEvent.change(screen.getByRole("textbox", { name: new RegExp(t("controls.eventName")) }), { target: { value: "purchase" } });
      fireEvent.change(screen.getByRole("combobox", { name: new RegExp(t("controls.planStatus")) }), { target: { value: "planned" } });
      const calls = api.clientReportCounts.mock.calls.length;
      fireEvent.click(screen.getByRole("button", { name: t("controls.apply") }));
      await flush();
      expect(api.countsByName.mock.calls.at(-1)?.[0].name).toBe("purchase");
      expect(api.clientReportCounts.mock.calls.length).toBe(calls); // a filter change does not restart it
      fireEvent.change(screen.getByRole("combobox", { name: new RegExp(t("controls.period")) }), { target: { value: "7d" } });
      await flush();
      expect(api.clientReportCounts.mock.calls.length).toBe(calls + 1); // a period change does
    });

    it("keeps the rows on a transient error and shows its own status with a retry", async () => {
      let fail = false;
      const api = fakeApi({
        clientReportCounts: vi.fn(async () => {
          if (fail) throw new QueryError("HTTP 503", 503);
          return { items: rows, truncated: false };
        }) as never,
      });
      render(api);
      await flush();
      fail = true;
      await advance(5000);
      expect(within(card()).getByText("0.2.0")).toBeTruthy();
      const status = screen.getByText(t("status.dropped"), { selector: "strong" }).parentElement!;
      expect(status.textContent).toContain("HTTP 503");
      fail = false;
      fireEvent.click(within(status).getByRole("button", { name: t("status.retry") }));
      await advance(5000); // the retry waits for the backoff (Q109)
      expect(status.textContent).not.toContain("HTTP 503");
    });

    it("stops on a contract error and keeps the rows (Q108)", async () => {
      let fail = false;
      const api = fakeApi({
        clientReportCounts: vi.fn(async () => {
          if (fail) throw new QueryError("no", 400, "CONTRACT_VERSION_UNSUPPORTED");
          return { items: rows, truncated: false };
        }) as never,
      });
      render(api);
      await flush();
      fail = true;
      await advance(5000);
      const calls = api.clientReportCounts.mock.calls.length;
      await advance(60000);
      expect(api.clientReportCounts.mock.calls.length).toBe(calls);
      expect(within(card()).getByText("0.2.0")).toBeTruthy();
      expect(screen.getByText(t("status.dropped"), { selector: "strong" }).parentElement!.textContent).toContain(t("status.stopped"));
    });
  });

  describe("at 720 px or less (Q147)", () => {
    const original = window.matchMedia;
    beforeEach(() => {
      window.matchMedia = ((query: string) => ({ matches: true, media: query, addEventListener: () => {}, removeEventListener: () => {} })) as never;
    });
    afterEach(() => { window.matchMedia = original; });
    const disclosure = (container: HTMLElement) => container.querySelector("details.more-filters") as HTMLDetailsElement;

    it("puts the three exact filters in a closed More filters disclosure; period and sort stay outside", async () => {
      const { container } = render(fakeApi());
      await flush();
      const d = disclosure(container);
      expect(d.open).toBe(false);
      expect(within(d).getByText(t("controls.moreFilters"))).toBeTruthy();
      for (const name of ["eventName", "installationId", "eventId"]) expect(d.querySelector(`input[name='${name}']`), name).toBeTruthy();
      expect(d.querySelector("select[name='planStatus']")).toBeTruthy();
      expect(d.contains(screen.getByRole("combobox", { name: new RegExp(t("controls.period")) }))).toBe(false);
      expect(d.contains(screen.getByRole("combobox", { name: new RegExp(t("controls.sort")) }))).toBe(false);
    });

    it("opens the disclosure and focuses the field when Apply finds an invalid installationId", async () => {
      const { container } = render(fakeApi());
      await flush();
      const input = container.querySelector<HTMLInputElement>("input[name='installationId']")!;
      fireEvent.change(input, { target: { value: "not-a-uuid" } });
      disclosure(container).open = false;
      fireEvent(disclosure(container), new Event("toggle"));
      fireEvent.click(screen.getByRole("button", { name: t("controls.apply") }));
      await flush();
      expect(disclosure(container).open).toBe(true);
      expect(document.activeElement).toBe(input);
    });

    it("closes on Clear also when the draft was never applied", async () => {
      const { container } = render(fakeApi());
      await flush();
      disclosure(container).open = true;
      fireEvent(disclosure(container), new Event("toggle"));
      fireEvent.change(container.querySelector("input[name='eventName']")!, { target: { value: "draft" } });
      fireEvent.click(screen.getByRole("button", { name: t("controls.clear") }));
      await flush();
      expect(disclosure(container).open).toBe(false);
    });

    it("is open while a filter is in force and closes again after Clear", async () => {
      const { container } = render(fakeApi());
      await flush();
      fireEvent.change(container.querySelector("input[name='eventName']")!, { target: { value: "purchase" } });
      fireEvent.click(screen.getByRole("button", { name: t("controls.apply") }));
      await flush();
      expect(disclosure(container).open).toBe(true);
      fireEvent.click(screen.getByRole("button", { name: t("controls.clear") }));
      await flush();
      expect(disclosure(container).open).toBe(false);
    });
  });

  it("has no disclosure on a wide screen: the filters are in the toolbar (Q147)", async () => {
    const { container } = render(fakeApi());
    await flush();
    expect(container.querySelector("details.more-filters")).toBeNull();
    expect(container.querySelector("input[name='eventName']")).toBeTruthy();
  });
});
