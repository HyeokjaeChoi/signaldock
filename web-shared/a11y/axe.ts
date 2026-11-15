import axe from "axe-core";

/**
 * axe-core on the whole document (ADR-0049 W4). Returns one line for each `serious` or `critical` violation; an empty list passes.
 * Lower impacts (`moderate`, `minor`) are not failures. The rule `color-contrast` is off: jsdom has no layout and no computed
 * colors. The contrast of every token pair is checked by `web-shared/theme/contrast.test.ts`.
 */
export async function seriousViolations(): Promise<string[]> {
  const results = await axe.run(document, { rules: { "color-contrast": { enabled: false } }, resultTypes: ["violations"] });
  return results.violations
    .filter((v) => v.impact === "serious" || v.impact === "critical")
    .map((v) => `${v.impact}: ${v.id} (${v.help}) at ${v.nodes.map((n) => n.target.join(" ")).join(" | ")}`);
}
