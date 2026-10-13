// WCAG AA contrast of the design tokens in both themes (ADR-0016 decision 4). Reads tokens.css, so a changed color is checked.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "tokens.css"), "utf8");
const block = (selector: string) => css.slice(css.indexOf(selector)).match(/\{([^}]*)\}/)![1]!;
const hexes = (body: string) => Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6})\s*;/gi)].map((m) => [m[1]!, m[2]!]));
const dark = hexes(block(":root {"));
const light = { ...dark, ...hexes(block(':root[data-theme="light"]')) };

const channel = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const luminance = (hex: string) => {
  const [r, g, b] = [1, 3, 5].map((i) => channel(parseInt(hex.slice(i, i + 2), 16) / 255)) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
};
/** `a` painted over `b` at `percent` (what `color-mix(in srgb, a percent, transparent)` gives on `b`). */
const over = (a: string, b: string, percent: number) =>
  "#" + [1, 3, 5].map((i) => Math.round(parseInt(a.slice(i, i + 2), 16) * percent + parseInt(b.slice(i, i + 2), 16) * (1 - percent)).toString(16).padStart(2, "0")).join("");

describe.each([["dark", dark], ["light", light]] as const)("%s theme contrast (AA, 4.5:1)", (_name, t) => {
  const surfaces = ["bg", "surface", "surface-raised", "input-bg"] as const;
  it.each(["fg", "fg-muted", "accent", "ok", "warn", "bad"])("%s on every surface", (fg) => {
    for (const bg of surfaces) expect(contrast(t[fg]!, t[bg]!), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
  });
  it("text on the filled primary button", () => expect(contrast(t["accent-fg"]!, t.accent!)).toBeGreaterThanOrEqual(4.5));
  it("status text on its soft badge background", () => {
    for (const k of ["ok", "warn", "bad"]) expect(contrast(t[k]!, over(t[k]!, t.surface!, 0.14)), k).toBeGreaterThanOrEqual(4.5);
  });
  it("text on the selected row", () => {
    const selected = over(t.accent!, t.surface!, 0.16);
    for (const fg of ["fg", "fg-muted", "accent"]) expect(contrast(t[fg]!, selected), fg).toBeGreaterThanOrEqual(4.5);
  });
  it("text on the active switch button (it sits on the page background)", () => {
    expect(contrast(t.fg!, over(t.accent!, t.bg!, 0.16))).toBeGreaterThanOrEqual(4.5);
  });
  it("chart axis text and the three series on the chart surface (3:1 for graphics)", () => {
    expect(contrast(t["chart-axis"]!, t.surface!)).toBeGreaterThanOrEqual(4.5);
    for (const c of ["c1", "c2", "c3"]) expect(contrast(t[c]!, t.surface!), c).toBeGreaterThanOrEqual(3);
  });
});
