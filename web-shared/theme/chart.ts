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
