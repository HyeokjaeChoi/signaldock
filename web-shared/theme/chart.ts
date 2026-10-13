import { createElement } from "react";

// Shared Recharts style (Q139, Q140, ADR-0016). Plain props that read the design tokens, so a theme switch restyles every chart
// without a re-render. Spread them into the Recharts elements. Series are never told apart by color alone: lines also get
// a dash pattern (`SERIES_DASH`), and every chart has a legend or axis text.
const text = { fill: "var(--chart-axis)", fontSize: 12 };

export const AXIS = { tick: text, tickLine: false, axisLine: { stroke: "var(--chart-grid)" }, stroke: "var(--chart-axis)" } as const;
export const GRID = { stroke: "var(--chart-grid)", strokeDasharray: "3 3" } as const;
export const TOOLTIP = {
  contentStyle: {
    background: "var(--surface-raised)", border: "1px solid var(--line)", borderRadius: "var(--radius-sm)",
    color: "var(--fg)", fontSize: 12, boxShadow: "var(--shadow-pop)",
  },
  labelStyle: { color: "var(--fg)", fontWeight: 600 },
  itemStyle: { color: "var(--fg)" },
  cursor: { fill: "var(--chart-cursor)" },
} as const;
export const LEGEND = { wrapperStyle: { fontSize: 12, color: "var(--fg-muted)" } } as const;

export const SERIES = ["var(--c1)", "var(--c2)", "var(--c3)"] as const;
/** Same order as `SERIES`: solid, dashed, dotted. */
export const SERIES_DASH = [undefined, "6 3", "2 3"] as const;

/**
 * Bar fills that differ by texture as well as color (issue 28): solid, hatch, dots. Put `PatternDefs()` inside the chart
 * (as a child of `BarChart`); legends use the same fills. No JSX here (ADR-0015 decision 3), hence `createElement`.
 */
export const BAR_FILL = {
  p50: "var(--c1)", p95: "url(#sd-hatch-c2)", max: "url(#sd-dots-c3)",
  ok: "var(--ok)", shed: "url(#sd-hatch-c2)", other: "url(#sd-dots-bad)",
} as const;

const hatch = (id: string, color: string) =>
  createElement("pattern", { key: id, id, width: 6, height: 6, patternUnits: "userSpaceOnUse", patternTransform: "rotate(45)" },
    createElement("rect", { width: 6, height: 6, fill: color, fillOpacity: 0.35 }),
    createElement("rect", { width: 3, height: 6, fill: color }));
const dots = (id: string, color: string) =>
  createElement("pattern", { key: id, id, width: 6, height: 6, patternUnits: "userSpaceOnUse" },
    createElement("rect", { width: 6, height: 6, fill: color, fillOpacity: 0.35 }),
    createElement("circle", { cx: 3, cy: 3, r: 1.8, fill: color }));

export const PatternDefs = () => createElement("defs", null, hatch("sd-hatch-c2", "var(--c2)"), dots("sd-dots-c3", "var(--c3)"), dots("sd-dots-bad", "var(--bad)"));
