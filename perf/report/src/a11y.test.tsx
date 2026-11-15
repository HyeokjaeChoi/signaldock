import { cleanup, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { I18nProvider } from "./i18n";
import { seriousViolations } from "../../../web-shared/a11y/axe";
import type { Language } from "../../../web-shared/i18n";

// ADR-0049 W4: axe-core on the report page, in both languages, with the committed result files (the data that the page shows
// after `npm run build`) and without them. A `serious` or `critical` violation fails the test. Real timers: axe yields to the event loop.
const results = (name: string) => JSON.parse(readFileSync(resolve(import.meta.dirname, "../../results", name), "utf8")) as unknown;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  delete window.SIGNALDOCK_RESULTS;
  delete window.SIGNALDOCK_SDK_RESULTS;
  document.documentElement.removeAttribute("lang");
});

describe.each<Language>(["en", "ko"])("axe-core: report page (%s)", (lang) => {
  const open = () =>
    render(
      <I18nProvider initialLanguage={lang}>
        <App />
      </I18nProvider>,
    );

  it("with the committed server and SDK results", async () => {
    window.SIGNALDOCK_RESULTS = results("index.json") as Window["SIGNALDOCK_RESULTS"];
    window.SIGNALDOCK_SDK_RESULTS = results("sdk-index.json") as Window["SIGNALDOCK_SDK_RESULTS"];
    open();
    await screen.findAllByRole("table");
    expect(await seriousViolations()).toEqual([]);
  });

  it("without result files", async () => {
    vi.stubGlobal("fetch", () => Promise.reject(new Error("offline")));
    open();
    await screen.findAllByText(/summarize\.py/);
    expect(await seriousViolations()).toEqual([]);
  });
});
