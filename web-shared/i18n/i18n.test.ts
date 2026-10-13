// Tests of the shared i18n code (Q135 to Q137). They run with the Dashboard's test runner: `cd dashboard && npm test`.
import { afterEach, describe, expect, it, vi } from "vitest";
import { dictionaries as dashboard } from "../../dashboard/src/i18n";
import { dictionaries as report } from "../../perf/report/src/i18n";
import { formatBucketLabel, formatClock, formatDateTime, formatNumber } from "./format";
import { STORAGE_KEY, resolveFromBrowser, resolveLanguage, save } from "./language";
import { LANGUAGES, PLURAL_CATEGORIES } from "./messages";
import type { Messages } from "./messages";
import { leaves, pluralSuffix, translate } from "./translate";

const messages: Messages = {
  runs: { title: "Runs", rows: "{{rows}} rows, pool {{ pool }}" },
  files_one: "{{count}} file",
  files_other: "{{count}} files",
};

describe("translate", () => {
  it("looks up dot keys and interpolates {{name}} with optional spaces", () => {
    expect(translate(messages, "en", "runs.title")).toBe("Runs");
    expect(translate(messages, "en", "runs.rows", { rows: 3, pool: 4 })).toBe("3 rows, pool 4");
  });

  it("leaves a placeholder without a value as written, and returns an unknown key as the key", () => {
    expect(translate(messages, "en", "runs.rows", { rows: 3 })).toBe("3 rows, pool {{ pool }}");
    expect(translate(messages, "en", "runs.nope")).toBe("runs.nope");
    expect(translate(messages, "en", "runs")).toBe("runs"); // a branch is not a message
    expect(translate(messages, "en", "constructor")).toBe("constructor"); // no prototype lookup
  });

  it("picks the plural form with Intl.PluralRules", () => {
    expect(translate(messages, "en", "files", { count: 1 })).toBe("1 file");
    expect(translate(messages, "en", "files", { count: 2 })).toBe("2 files");
    expect(translate(messages, "en", "files", { count: 0 })).toBe("0 files");
    // Korean has one category, so `_other` serves every count.
    expect(translate({ files_other: "파일 {{count}}개" }, "ko", "files", { count: 1 })).toBe("파일 1개");
  });
});

describe("language resolution (Q136)", () => {
  it("URL first, then the saved choice, then the browser language", () => {
    expect(resolveLanguage({ search: "?lang=ko", saved: "en", navigatorLanguage: "en-US" })).toBe("ko");
    expect(resolveLanguage({ search: "?lang=en", saved: "ko", navigatorLanguage: "ko-KR" })).toBe("en");
    expect(resolveLanguage({ search: "", saved: "ko", navigatorLanguage: "en-US" })).toBe("ko");
    expect(resolveLanguage({ saved: null, navigatorLanguage: "ko-KR" })).toBe("ko");
    expect(resolveLanguage({ saved: null, navigatorLanguage: "KO" })).toBe("ko");
    expect(resolveLanguage({ saved: null, navigatorLanguage: "fr-FR" })).toBe("en");
    expect(resolveLanguage({})).toBe("en");
  });

  it("ignores an unknown value in the URL or in storage", () => {
    expect(resolveLanguage({ search: "?lang=de", saved: "ko", navigatorLanguage: "en" })).toBe("ko");
    expect(resolveLanguage({ search: "?lang=de", saved: "xx", navigatorLanguage: "ko-KR" })).toBe("ko");
  });

  describe("browser storage", () => {
    afterEach(() => {
      vi.restoreAllMocks();
      window.localStorage.clear();
    });

    it("saves the choice and reads it back", () => {
      save("ko");
      expect(window.localStorage.getItem(STORAGE_KEY)).toBe("ko");
      expect(resolveFromBrowser()).toBe("ko");
    });

    it("still works when storage throws", () => {
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
      vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
      expect(() => save("ko")).not.toThrow();
      expect(["ko", "en"]).toContain(resolveFromBrowser());
    });
  });
});

describe("Intl formatters (Q135)", () => {
  const instant = Date.parse("2026-10-12T20:30:05.123Z"); // 2026-10-13 05:30 in Asia/Seoul

  it("show time in the given timezone, not the browser timezone", () => {
    expect(formatDateTime(instant, "Asia/Seoul", "en")).toBe("13/10/2026, 05:30:05.123");
    expect(formatDateTime(instant, "UTC", "en")).toBe("12/10/2026, 20:30:05.123");
    expect(formatClock(instant, "Asia/Seoul", "en")).toBe("05:30:05");
    expect(formatBucketLabel(instant, true, "Asia/Seoul", "en")).toBe("05:30");
    expect(formatBucketLabel(instant, false, "Asia/Seoul", "en")).toBe("13/10");
  });

  it("follow the language", () => {
    expect(formatDateTime(instant, "Asia/Seoul", "ko")).toContain("2026");
    expect(formatDateTime(instant, "Asia/Seoul", "ko")).not.toBe(formatDateTime(instant, "Asia/Seoul", "en"));
    expect(formatNumber(1234567.5, "en")).toBe("1,234,567.5");
    expect(formatNumber(1234567.5, "ko")).toBe("1,234,567.5");
  });
});

/**
 * Format rules of the real dictionaries (Q135). They keep the dictionaries drop-in compatible with i18next:
 * strings only, no `{` except `{{name}}`, plural keys in complete pairs, the same keys in both languages.
 */
describe.each([
  ["dashboard", dashboard],
  ["perf/report", report],
])("dictionaries of %s", (_page, dictionaries) => {
  const keysOf = (m: Messages) => leaves(m).map(([k]) => k);
  const base = (k: string) => k.replace(new RegExp(`_(${PLURAL_CATEGORIES.join("|")})$`), "");

  it.each(LANGUAGES)("%s: every leaf is a non-empty string", (lang) => {
    for (const [key, value] of leaves(dictionaries[lang])) {
      expect(typeof value, key).toBe("string");
      expect((value as string).trim(), key).not.toBe("");
    }
  });

  it.each(LANGUAGES)("%s: no brace except {{name}}, and no $t() nesting", (lang) => {
    for (const [key, value] of leaves(dictionaries[lang])) {
      const text = value as string;
      expect(text.replace(/\{\{\s*[\w.]+\s*\}\}/g, ""), key).not.toMatch(/[{}]/);
      expect(text, key).not.toContain("$t(");
    }
  });

  it.each(LANGUAGES)("%s: plural keys come in complete sets for the language", (lang) => {
    const needed = new Intl.PluralRules(lang).resolvedOptions().pluralCategories;
    const keys = keysOf(dictionaries[lang]);
    const plural = new Set(keys.filter((k) => pluralSuffix(k)).map(base));
    for (const b of plural) {
      const forms = keys.filter((k) => base(k) === b && pluralSuffix(k)).map((k) => pluralSuffix(k));
      expect(forms.sort(), b).toEqual([...needed].sort());
      expect(keys, `${b} is also a plain key`).not.toContain(b);
    }
  });

  it("both languages have the same keys (plural suffixes aside)", () => {
    const names = (m: Messages) => [...new Set(keysOf(m).map(base))].sort();
    expect(names(dictionaries.ko)).toEqual(names(dictionaries.en));
  });

  it("both languages use the same {{placeholders}} for each key", () => {
    const slots = (m: Messages) =>
      new Map(leaves(m).map(([k, v]) => [base(k), [...new Set((v as string).match(/\{\{\s*[\w.]+\s*\}\}/g) ?? [])].map((s) => s.replace(/[{}\s]/g, "")).sort().join(",")]));
    const en = slots(dictionaries.en);
    for (const [key, ko] of slots(dictionaries.ko)) expect(ko, key).toBe(en.get(key));
  });
});
