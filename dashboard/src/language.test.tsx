// Issue 27: the language of the Dashboard (Q135, Q136). The tests of the texts in each language are in App.test.tsx.
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { EventReceiptDetail, EventReceiptSummary } from "../../generated/typescript/models";
import { leaves, translate } from "../../web-shared/i18n";
import { App } from "./App";
import type { Api } from "./api";
import { I18nProvider, dictionaries } from "./i18n";
import { QueryError } from "./polling";

vi.mock("./Charts", () => ({ NameChart: () => null, TimeChart: () => null }));

const { en, ko } = dictionaries;
const summary: EventReceiptSummary = {
  eventId: "00000000-0000-4000-8000-000000000001",
  installationId: "11111111-1111-4111-8111-111111111111",
  name: "event_1",
  occurredAt: "2026-10-12T01:00:00.000Z",
  receivedAt: "2026-10-12T01:00:01.000Z",
  sdkVersion: "0.1.0",
};
const detail: EventReceiptDetail = { ...summary, properties: { price: 19900 } };

const fakeApi = (overrides: Partial<Api> = {}): Api =>
  ({
    listEvents: vi.fn(async () => ({ items: [summary] })),
    getEvent: vi.fn(async () => detail),
    countsByName: vi.fn(async () => ({ items: [], truncated: false })),
    countsByTime: vi.fn(async () => ({ timeZone: "Asia/Seoul", bucket: "hour" as const, items: [] })),
    ...overrides,
  }) as never;
const failing = (error: QueryError) => fakeApi({ listEvents: vi.fn(async () => { throw error; }) as never });

const flush = () => act(async () => { await vi.advanceTimersByTimeAsync(0); });
const mount = (api: Api, initialLanguage: "en" | "ko") =>
  render(<I18nProvider initialLanguage={initialLanguage}><App api={api} /></I18nProvider>);

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  window.localStorage.clear();
});

/** Text and accessible names on screen. */
const visibleText = () =>
  [
    document.body.textContent ?? "",
    ...[...document.querySelectorAll("[aria-label],[title]")].map((e) => e.getAttribute("aria-label") ?? e.getAttribute("title") ?? ""),
  ].join("\n");

/** Every English message that has a different Korean text and no placeholder. None may show up in the Korean page. */
const englishOnly = leaves(en)
  .filter(([key, text]) => typeof text === "string" && !text.includes("{{") && !key.startsWith("language.") && translate(ko, "ko", key) !== text)
  .map(([, text]) => text as string);

describe("language", () => {
  it("shows no English UI text in Korean (list, details, empty, error, and stopped states)", async () => {
    const apis: Api[] = [
      fakeApi(),
      fakeApi({ listEvents: vi.fn(async () => ({ items: [] })) as never }),
      failing(new QueryError("Network error: the server did not answer", undefined, undefined, undefined, "errors.network")),
      failing(new QueryError("Unsupported", 400, "CONTRACT_VERSION_UNSUPPORTED")),
      failing(new QueryError("Server is busy", 503, "TEMPORARILY_UNAVAILABLE", 1000)),
      fakeApi({ getEvent: vi.fn(async () => { throw new QueryError("not found", 404, "EVENT_NOT_FOUND"); }) as never }),
    ];
    for (const api of apis) {
      const view = mount(api, "ko");
      await flush();
      const open = screen.queryByRole("button", { name: "event_1" });
      if (open) { fireEvent.click(open); await flush(); }
      const text = visibleText();
      for (const english of englishOnly) expect(text, english).not.toContain(english);
      expect(document.documentElement.lang).toBe("ko");
      view.unmount();
    }
  });

  it("shows no Korean text in English", async () => {
    mount(fakeApi(), "en");
    await flush();
    fireEvent.click(screen.getByRole("button", { name: "event_1" }));
    await flush();
    // The language names are written in their own language, so the Korean name is expected.
    expect(visibleText().replace(ko.language.ko, "")).not.toMatch(/[가-힣]/);
  });

  it("the switch changes the language, <html lang>, the title, and the saved choice", async () => {
    mount(fakeApi(), "en");
    await flush();
    expect(document.documentElement.lang).toBe("en");
    fireEvent.change(screen.getByRole("combobox", { name: en.language.label }), { target: { value: "ko" } });
    await flush();
    expect(document.documentElement.lang).toBe("ko");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(ko.app.title);
    expect(document.title).toBe(ko.app.title);
    expect(window.localStorage.getItem("signaldock.lang")).toBe("ko");
    expect(screen.getByRole("combobox", { name: ko.language.label })).toBeTruthy();
  });

  it("shows a known API error code in the selected language and keeps the code", async () => {
    mount(failing(new QueryError("Query key is wrong", 401, "QUERY_KEY_INVALID")), "ko");
    await flush();
    const status = screen.getByText(ko.status.events).parentElement!;
    expect(status.textContent).toContain(ko.errors.code.QUERY_KEY_INVALID);
    expect(status.textContent).toContain("QUERY_KEY_INVALID");
    expect(status.textContent).not.toContain("Query key is wrong");
  });

  it("shows the period error in the selected language, with the plural form", async () => {
    mount(fakeApi(), "ko");
    await flush();
    fireEvent.change(screen.getByRole("combobox", { name: ko.controls.period }), { target: { value: "custom" } });
    const [from, to] = [ko.controls.from, ko.controls.to].map((label) => screen.getByLabelText(label));
    fireEvent.change(from!, { target: { value: "2026-09-01" } });
    fireEvent.change(to!, { target: { value: "2026-10-30" } });
    await flush();
    expect(screen.getByRole("alert").textContent).toBe(translate(ko, "ko", "period.tooLong", { count: 30 }));
  });

  it("keeps a filter error in step with a language switch", async () => {
    mount(fakeApi(), "en");
    await flush();
    fireEvent.change(screen.getByLabelText("installationId"), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: en.controls.apply }));
    expect(screen.getByRole("alert").textContent).toBe(en.filter.installationIdUuid);
    fireEvent.change(screen.getByRole("combobox", { name: en.language.label }), { target: { value: "ko" } });
    await flush();
    expect(screen.getByRole("alert").textContent).toBe(ko.filter.installationIdUuid);
  });
});
