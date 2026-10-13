import type { CSSProperties } from "react";
import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { EventNameCounts, EventTimeCounts } from "../../generated/typescript/models";
import { useTranslation } from "./i18n";
import { formatNumber } from "../../web-shared/i18n";
import { AXIS, GRID, SERIES, TOOLTIP } from "../../web-shared/theme";

// Counts per name: every bar carries its number, so the chart does not rely on color or on reading the axis.
// A per-name trend is not derivable: by-time counts are not split by name (Q140 allows it only if derivable; ADR-0016).
const ROW_PX = 30;

export function NameChart({ counts }: { counts: EventNameCounts }) {
  const { t, i18n } = useTranslation();
  const number = (v: unknown) => (typeof v === "number" ? formatNumber(v, i18n.language) : String(v ?? ""));
  return (
    <>
      <div className="chart-fill" style={{ "--chart-min": `${Math.max(160, counts.items.length * ROW_PX + 36)}px` } as CSSProperties}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={counts.items} layout="vertical" margin={{ left: 0, right: 40 }}>
          <CartesianGrid {...GRID} horizontal={false} />
          <XAxis {...AXIS} type="number" allowDecimals={false} tickFormatter={number} />
          <YAxis {...AXIS} type="category" dataKey="name" width={150} interval={0} />
          <Tooltip {...TOOLTIP} formatter={number} />
          <Bar dataKey="count" name={t("counts.series")} fill={SERIES[0]} radius={[0, 3, 3, 0]} isAnimationActive={false}>
            <LabelList dataKey="count" position="right" formatter={number} fill="var(--fg)" fontSize={12} />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      </div>
      {counts.truncated && <p className="note">{t("counts.truncated", { limit: number(100) })}</p>}
    </>
  );
}

export function TimeChart({ counts, label }: { counts: EventTimeCounts; label: (iso: string, bucket: string) => string }) {
  const { t, i18n } = useTranslation();
  const number = (v: unknown) => (typeof v === "number" ? formatNumber(v, i18n.language) : String(v ?? ""));
  const data = counts.items.map((b) => ({ label: label(b.start, counts.bucket), count: b.count }));
  return (
    <div className="chart-fill">
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data}>
        <CartesianGrid {...GRID} vertical={false} />
        <XAxis {...AXIS} dataKey="label" minTickGap={16} />
        <YAxis {...AXIS} allowDecimals={false} tickFormatter={number} width={44} />
        <Tooltip {...TOOLTIP} formatter={number} />
        <Bar dataKey="count" name={t("counts.series")} fill={SERIES[0]} radius={[3, 3, 0, 0]} isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
    </div>
  );
}
