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
});

const detail: EventReceiptDetail = {
  ...summary(1),
  properties: { price: 19900, in_stock: true, note: "hello" },
};

function fakeApi(overrides: Partial<Api> = {}): Api & { listEvents: ReturnType<typeof vi.fn> } {
  return {
    listEvents: vi.fn(async (_f, _s, cursor?: string) =>
      cursor === "c2"
        ? { items: [summary(3)], nextCursor: undefined }
        : { items: [summary(1), summary(2)], nextCursor: "c2" },
    ),
    getEvent: vi.fn(async () => detail),
    countsByName: vi.fn(async () => ({ items: [], truncated: false })),
    countsByTime: vi.fn(async () => ({ timeZone: "Asia/Seoul", bucket: "hour" as const, items: [] })),
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
});
