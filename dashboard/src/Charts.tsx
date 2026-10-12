import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { EventNameCounts, EventTimeCounts } from "../../generated/typescript/models";

const SIZE = { width: "100%", height: 220 } as const;

export function NameChart({ counts }: { counts: EventNameCounts }) {
  return (
    <>
      <ResponsiveContainer {...SIZE}>
        <BarChart data={counts.items} layout="vertical" margin={{ left: 40 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis type="number" allowDecimals={false} />
          <YAxis type="category" dataKey="name" width={140} />
          <Tooltip />
          <Bar dataKey="count" fill="#2f6fed" isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
      {counts.truncated && <p className="note">Only the 100 largest names are shown.</p>}
    </>
  );
}

export function TimeChart({ counts, label }: { counts: EventTimeCounts; label: (iso: string, bucket: string) => string }) {
  const data = counts.items.map((b) => ({ label: label(b.start, counts.bucket), count: b.count }));
  return (
    <ResponsiveContainer {...SIZE}>
      <BarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="label" />
        <YAxis allowDecimals={false} />
        <Tooltip />
        <Bar dataKey="count" fill="#2f6fed" isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}
