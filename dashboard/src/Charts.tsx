import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { EventNameCounts, EventTimeCounts } from "../../generated/typescript/models";
import { useTranslation } from "./i18n";
import { formatNumber } from "../../web-shared/i18n";

const SIZE = { width: "100%", height: 220 } as const;

export function NameChart({ counts }: { counts: EventNameCounts }) {
  const { t, i18n } = useTranslation();
  const number = (v: unknown) => (typeof v === "number" ? formatNumber(v, i18n.language) : String(v ?? ""));
  return (
    <>
      <ResponsiveContainer {...SIZE}>
        <BarChart data={counts.items} layout="vertical" margin={{ left: 40 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis type="number" allowDecimals={false} tickFormatter={number} />
          <YAxis type="category" dataKey="name" width={140} />
          <Tooltip formatter={number} />
          <Bar dataKey="count" name={t("counts.series")} fill="#2f6fed" isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
      {counts.truncated && <p className="note">{t("counts.truncated", { limit: number(100) })}</p>}
    </>
  );
}

export function TimeChart({ counts, label }: { counts: EventTimeCounts; label: (iso: string, bucket: string) => string }) {
  const { t, i18n } = useTranslation();
  const number = (v: unknown) => (typeof v === "number" ? formatNumber(v, i18n.language) : String(v ?? ""));
  const data = counts.items.map((b) => ({ label: label(b.start, counts.bucket), count: b.count }));
  return (
    <ResponsiveContainer {...SIZE}>
      <BarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="label" />
        <YAxis allowDecimals={false} tickFormatter={number} />
        <Tooltip formatter={number} />
        <Bar dataKey="count" name={t("counts.series")} fill="#2f6fed" isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}
