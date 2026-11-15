import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { EventReceiptDetail, EventReceiptSummary } from "../../generated/typescript/models";
import { App } from "./App";
import type { Api } from "./api";
import { dictionaries, I18nProvider } from "./i18n";
import { QueryError } from "./polling";
import { seriousViolations } from "../../web-shared/a11y/axe";
import { translate } from "../../web-shared/i18n";
import type { Language, MessageKey } from "../../web-shared/i18n";

// ADR-0049 W4: axe-core on every view of the Dashboard, in both languages. A `serious` or `critical` violation fails the test.
// Real timers: axe yields to the event loop. The charts draw nothing in jsdom (no layout), so they are stubbed as in App.test.tsx.
vi.mock("./Charts", () => ({ NameChart: () => null, TimeChart: () => null }));

const summary: EventReceiptSummary = {
  eventId: "00000000-0000-4000-8000-000000000001",
  installationId: "11111111-1111-4111-8111-111111111111",
  name: "purchase",
  occurredAt: "2026-10-12T01:00:00.000Z",
  receivedAt: "2026-10-12T01:00:01.000Z",
  sdkVersion: "0.1.0",
  sdkPlatform: "android",
  planStatus: "invalid_properties",
};
const detail: EventReceiptDetail = { ...summary, properties: { price: 19900, in_stock: true, note: "hello" } };

function fakeApi(overrides: Partial<Api> = {}): Api {
  return {
    listEvents: () =>
      Promise.resolve({
        items: [summary, { ...summary, eventId: "00000000-0000-4000-8000-000000000002", sdkPlatform: "ios", name: "signup" }],
      }),
    getEvent: () => Promise.resolve(detail),
    countsByName: () => Promise.resolve({ items: [{ name: "purchase", count: 2 }], truncated: false }),
    countsByTime: () => Promise.resolve({ timeZone: "Asia/Seoul", bucket: "hour", items: [] }),
    countsByPlanStatus: () => Promise.resolve({ planned: 3, unplannedEvent: 1, invalidProperties: 1, notChecked: 0 }),
    clientReportCounts: () =>
      Promise.resolve({ items: [{ sdkPlatform: "android", sdkVersion: "0.1.0", reason: "queue_full", count: 4 }], truncated: false }),
    ...overrides,
  };
}

const failing = (e: QueryError) => () => Promise.reject(e);
const fetchFailed = new QueryError("Failed to fetch", undefined, undefined, undefined, "errors.network");

/** Narrow layout: the toolbar filters sit in the "More filters" disclosure (App.tsx, NARROW). */
function setNarrow(narrow: boolean) {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: narrow,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

beforeEach(() => {
  setNarrow(false);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute("lang");
});

describe.each<Language>(["en", "ko"])("axe-core: Dashboard views (%s)", (lang) => {
  const t = (key: MessageKey<typeof dictionaries.en>) => translate(dictionaries[lang], lang, key);
  const open = (api: Api) =>
    render(
      <I18nProvider initialLanguage={lang}>
        <App api={api} />
      </I18nProvider>,
    );
  /** The list has loaded and axe sees the final markup. */
  const loaded = () => screen.findByText("purchase", { selector: "button" });

  it("event list with the empty detail pane", async () => {
    open(fakeApi());
    await loaded();
    expect(await seriousViolations()).toEqual([]);
  });

  it("event details open", async () => {
    open(fakeApi());
    fireEvent.click(await loaded());
    await screen.findByRole("heading", { name: t("details.title") });
    expect(await seriousViolations()).toEqual([]);
  });

  it("empty list and empty counts", async () => {
    open(
      fakeApi({
        listEvents: () => Promise.resolve({ items: [] }),
        countsByName: () => Promise.resolve({ items: [], truncated: false }),
        clientReportCounts: () => Promise.resolve({ items: [], truncated: false }),
      }),
    );
    await screen.findByText(t("list.empty"));
    expect(await seriousViolations()).toEqual([]);
  });

  it("failed queries with the retry button", async () => {
    open(fakeApi({ listEvents: failing(fetchFailed), countsByName: failing(fetchFailed), getEvent: failing(fetchFailed) }));
    await waitFor(() => {
      expect(screen.getAllByRole("alert").length).toBeGreaterThan(0);
    });
    expect(await seriousViolations()).toEqual([]);
  });

  it("failed event details", async () => {
    open(fakeApi({ getEvent: failing(fetchFailed) }));
    fireEvent.click(await loaded());
    await screen.findByRole("button", { name: t("status.retry") });
    expect(await seriousViolations()).toEqual([]);
  });

  it("stopped polling after a contract version error, with the reload button", async () => {
    const unsupported = new QueryError("Unsupported", 400, "CONTRACT_VERSION_UNSUPPORTED");
    open(fakeApi({ listEvents: failing(unsupported), countsByName: failing(unsupported) }));
    await screen.findAllByRole("button", { name: t("status.reload") });
    expect(await seriousViolations()).toEqual([]);
  });

  it("event details not found", async () => {
    open(fakeApi({ getEvent: failing(new QueryError("Not found", 404, "EVENT_NOT_FOUND")) }));
    fireEvent.click(await loaded());
    await screen.findByText(t("details.notFound"));
    expect(await seriousViolations()).toEqual([]);
  });

  it("custom period without dates", async () => {
    open(fakeApi());
    await loaded();
    fireEvent.change(screen.getByRole("combobox", { name: new RegExp(t("controls.period")) }), { target: { value: "custom" } });
    await screen.findByText(t("period.chooseDates"));
    expect(await seriousViolations()).toEqual([]);
  });

  it("filter with an invalid id", async () => {
    const { container } = open(fakeApi());
    await loaded();
    fireEvent.change(container.querySelector<HTMLInputElement>("input[name='installationId']")!, { target: { value: "not-a-uuid" } });
    fireEvent.click(screen.getByRole("button", { name: t("controls.apply") }));
    await screen.findByText(t("filter.installationIdUuid"));
    expect(await seriousViolations()).toEqual([]);
  });

  it("narrow layout with the filters disclosure open", async () => {
    setNarrow(true);
    const { container } = open(fakeApi());
    await loaded();
    const disclosure = container.querySelector<HTMLDetailsElement>("details.more-filters");
    expect(disclosure).not.toBeNull();
    fireEvent.click(screen.getByText(t("controls.moreFilters")));
    expect(await seriousViolations()).toEqual([]);
  });
});
