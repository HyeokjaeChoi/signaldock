// Theme choice (Q139, Q140, ADR-0016): default dark, saved choice, storage failures, and the inline script that avoids a flash.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { THEME_STORAGE_KEY, changeTheme, currentTheme, initTheme, resolveTheme } from "./theme";

const root = join(dirname(fileURLToPath(import.meta.url)), "../..");

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
  delete document.documentElement.dataset.theme;
});

describe("theme", () => {
  it("defaults to dark and ignores an unknown saved value", () => {
    expect(resolveTheme(null)).toBe("dark");
    expect(resolveTheme("sepia")).toBe("dark");
    expect(resolveTheme("light")).toBe("light");
    initTheme();
    expect(currentTheme()).toBe("dark");
  });

  it("changeTheme sets the attribute and saves the choice; initTheme restores it", () => {
    changeTheme("light");
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
    delete document.documentElement.dataset.theme;
    initTheme();
    expect(currentTheme()).toBe("light");
  });

  it("works when storage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    expect(() => initTheme()).not.toThrow();
    expect(currentTheme()).toBe("dark");
    expect(() => changeTheme("light")).not.toThrow();
    expect(currentTheme()).toBe("light");
  });

  it.each(["dashboard/index.html", "perf/report/index.html"])("%s sets the saved theme before the first paint with the same key", (file) => {
    const html = readFileSync(join(root, file), "utf8");
    expect(html).toContain(THEME_STORAGE_KEY);
  });
});
