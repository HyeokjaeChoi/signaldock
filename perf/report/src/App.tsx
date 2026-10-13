import { useEffect, useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceArea, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { LANGUAGES, formatNumber } from "../../../web-shared/i18n";
import type { Language } from "../../../web-shared/i18n";
import { AXIS, AXIS_LABEL_HEIGHT, BAR_FILL, GRID, LEGEND, PatternDefs, SERIES, SERIES_DASH, THEMES, TOOLTIP, axisLabel, useTheme } from "../../../web-shared/theme";
import type { Theme } from "../../../web-shared/theme";
import { useTranslation } from "./i18n";
import type { T } from "./i18n";

type Stat = { target_p95_ms: number; samples: number; p50_ms: number | null; p95_ms: number | null; max_ms: number | null; outcomes: Record<string, number> };
type Run = {
  run_id: string; counted: boolean; verdict: string; invalid_reasons: string[]; fail_reasons: string[];
  repetition: number; pool_order: string; pool_size: number;
  combination: { seed_size: number; distribution: string; window_days: number; clients: number };
  git: { revision: string; dirty: boolean }; warmup_s: number; measure_s: number;
  endpoints: Record<string, Stat>;
  injection: { scheduled: number; started: number; dropped: number };
  non_ok_outcomes: Record<string, Record<string, number>>; skipped_ticks: Record<string, number>;
  hikari_series: [number, number, number, number][];
  container_series: { t: number; db_cpu_pct?: number; server_cpu_pct?: number; db_mem_mib?: number; server_mem_mib?: number }[];
  cgroup: Record<string, { nr_periods: number | null; nr_throttled: number | null; throttled_usec: number | null }>;
  statements: null | { statements: number; harness_statements: number; top_by_total_time: { calls: number; rows: number; total_exec_ms: number; mean_exec_ms: number; max_exec_ms: number; query: string }[] };
};
type PhaseStat = { requests: number; status: Record<string, number>; retry_after: Record<string, number>; error_codes: Record<string, number>; p50_ms: number | null; p95_ms: number | null; p95_ok_ms: number | null; max_ms: number | null };
type Overload = {
  run_id: string; verdict: string; invalid_reasons: string[]; fail_reasons: string[]; git: { revision: string; dirty: boolean };
  parameters: { concurrency: number; admission_ms: number; pool_size: number; base_s: number; over_s: number; rec_s: number; rate: number; over_rate: number; heavy_rate: number; seed_size: number; dist: string; window_days: number };
  phases: Record<string, Record<string, PhaseStat>>;
  series_5s: [number, string, number, number, number, number, number | null][];
  recovery: { first_clean_5s_window_after_overload_s: number | null; last_30s_collect_p95_ms: number | null; baseline_collect_p95_ms: number; limit_ms: number; last_30s_requests: number; last_30s_non_200: number };
  reconciliation: Record<string, number>; dropped_iterations: number;
};
type Data = { targets_p95_ms: Record<string, number>; runs: Run[]; overload?: Overload[] };
// perf/results/sdk-index.json, written by `perf/sdk_summarize.py --index` (issue 27): committed values of perf/sdk-results/*/run.json.
type SdkRun = {
  run_id: string; avd: string; scenario: "normal" | "burst" | "offline"; verdict: string; reasons: string[]; git_revision: string; started_utc: string;
  device?: string; api?: number; release?: string; abi?: string;
  target_rate_per_s?: number | null; actual_rate_per_s?: number | null; started?: number; planned?: number; event_bytes?: number | null;
  max_calls_in_flight?: number; track_ms?: { p50: number; p95: number; max: number; count: number } | null; track_not_ok?: number;
  missing_on_server?: number; duplicate_server_rows?: number;
  drain?: { seconds: number | null; persisted_retry_wait_at_start_s?: number | null };
  files_bytes_after_drain?: Record<string, number>;
  memory_basis?: "store" | "drain"; pss_delta_kib?: number; heap_delta_kib?: number;
};
type SdkData = { runs: SdkRun[] };

declare global { interface Window { SIGNALDOCK_RESULTS?: Data; SIGNALDOCK_SDK_RESULTS?: SdkData } }

const EPS = ["collect", "list", "by-name", "by-time"];
// Shown in the "no results" messages. Commands stay in their original form.
const SUMMARIZE_COMMAND = "python3 perf/summarize.py perf/results/*/ --out perf/results/report.md --index perf/results/index.json";
const SDK_COMMAND = "python3 perf/sdk_summarize.py --index perf/sdk-results perf/results/sdk-index.json";

function useFmt() {
  const { t, i18n } = useTranslation();
  const lang = i18n.language;
  return {
    t, lang,
    /** One decimal place, in the number format of the selected language. */
    f1: (v: number | null | undefined) => (v == null ? t("common.na") : formatNumber(v, lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 })),
    n: (v: number) => formatNumber(v, lang),
    /** `v` is already a percentage (63.8 means 63.8 %); Intl adds the sign in the style of the language. */
    pct: (v: number) => formatNumber(v / 100, lang, { style: "percent", minimumFractionDigits: 1, maximumFractionDigits: 1 }),
  };
}

const verdictText = (t: T, v: string) => (v === "pass" || v === "fail" || v === "invalid" ? t(`verdict.${v}`) : v);
const comboId = (r: Run) => `${r.combination.seed_size}|${r.combination.distribution}|${r.combination.window_days}|${r.combination.clients}`;
const comboLabel = (t: T, n: (v: number) => string, r: Run) =>
  t("combo.label", { rows: n(r.combination.seed_size), distribution: r.combination.distribution, days: n(r.combination.window_days), clients: n(r.combination.clients) });
const runLabel = (t: T, n: (v: number) => string, r: Run) => t(r.counted ? "combo.run" : "combo.runPilot", { pool: n(r.pool_size), rep: n(r.repetition) });
const dirty = (t: T, d: boolean) => (d ? ` ${t("common.dirty")}` : "");
const reasons = (t: T, invalid: string[], fail: string[]) => [
  ...invalid.map((reason) => t("common.reasonInvalid", { reason })),
  ...fail.map((reason) => t("common.reasonFail", { reason })),
];

function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  return (
    <label className="switch-group">
      {t("language.label")}{" "}
      <select value={i18n.language} onChange={(e) => i18n.changeLanguage(e.target.value as Language)}>
        {LANGUAGES.map((l) => <option key={l} value={l}>{t(`language.${l}`)}</option>)}
      </select>
    </label>
  );
}

function ThemeSwitch() {
  const { t } = useTranslation();
  const { theme, setTheme } = useTheme();
  return (
    <div className="switch-group" role="group" aria-label={t("theme.label")}>
      <span aria-hidden="true">{t("theme.label")}</span>
      <div className="seg">
        {THEMES.map((id: Theme) => (
          <button key={id} type="button" aria-pressed={theme === id} onClick={() => setTheme(id)}>{t(`theme.${id}`)}</button>
        ))}
      </div>
    </div>
  );
}

function Badge({ r }: { r: Run }) {
  const { t } = useTranslation();
  return (
    <>
      <span className={`badge ${r.verdict}`}>{verdictText(t, r.verdict)}</span>
      {!r.counted && <span className="badge pilot">{t("badge.pilot")}</span>}
    </>
  );
}

function Chart({ title, children, height = 240 }: { title: string; children: React.ReactElement; height?: number }) {
  return (
    <figure className="chart">
      <figcaption>{title}</figcaption>
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">{children}</ResponsiveContainer>
      </div>
    </figure>
  );
}

/**
 * Y axis with ~10 % headroom, a top that is a multiple of a 1-2-2.5-5 step (x10^n), and evenly spaced ticks (at most 5 intervals).
 * Spread the result into YAxis.
 */
function niceY(values: (number | null | undefined)[], pad = 1.1) {
  const max = Math.max(0, ...values.filter((v): v is number => typeof v === "number" && Number.isFinite(v)));
  const target = max * pad || 1;
  let step = 1;
  for (let e = -3; e <= 12 && target / step > 5; e++) step = [1, 2, 2.5, 5].map((m) => m * 10 ** e).find((c) => target / c <= 5) ?? step;
  const intervals = Math.ceil(target / step - 1e-9);
  return { domain: [0, intervals * step] as [number, number], ticks: Array.from({ length: intervals + 1 }, (_, i) => +(i * step).toFixed(6)), allowDecimals: step < 1 || undefined };
}
/** Seconds axis: starts at 0 (never below, even if a sample has t < 0), whole numbers, evenly spaced nice ticks. */
const secondsX = (ts: number[]) => ({ type: "number", allowDataOverflow: true, ...niceY(ts, 1) }) as const;

/** Horizontal grouped bars: one row per run, so long run names never collide on an axis (issue 28). */
const rowHeight = (rows: number, perRow: number) => Math.max(150, rows * perRow + 64);

function Compare({ runs, targets }: { runs: Run[]; targets: Record<string, number> }) {
  const { t, n } = useFmt();
  const combos = useMemo(() => [...new Map(runs.map((r) => [comboId(r), r])).values()], [runs]);
  const [combo, setCombo] = useState(combos[0] ? comboId(combos[0]) : "");
  const sel = runs.filter((r) => comboId(r) === combo).sort((a, b) => a.pool_size - b.pool_size || a.repetition - b.repetition || a.run_id.localeCompare(b.run_id));
  return (
    <section className="card section">
      <h2>{t("compare.title")}</h2>
      <p className="note">{t("compare.note")}</p>
      <label className="field inline-field">{t("compare.combination")} <select value={combo} onChange={(e) => setCombo(e.target.value)}>{combos.map((r) => <option key={comboId(r)} value={comboId(r)}>{comboLabel(t, n, r)}</option>)}</select></label>
      <div className="grid">
        {EPS.map((ep) => (
          <Chart key={ep} title={t("compare.chart", { endpoint: ep, target: n(targets[ep]) })} height={rowHeight(sel.length, 70)}>
            <BarChart layout="vertical" data={sel.map((r) => ({ name: r.run_id, full: `${runLabel(t, n, r)} (${r.run_id})`, p50: r.endpoints[ep].p50_ms, p95: r.endpoints[ep].p95_ms, max: r.endpoints[ep].max_ms }))} margin={{ top: 18, left: 8, right: 24 }}>
              <PatternDefs />
              <CartesianGrid {...GRID} horizontal={false} />
              <XAxis {...AXIS} type="number" tickFormatter={n} />
              <YAxis {...AXIS} type="category" dataKey="name" width={150} interval={0} />
              <Tooltip {...TOOLTIP} labelFormatter={(label, payload) => payload?.[0]?.payload?.full ?? label} />
              <Legend {...LEGEND} />
              <ReferenceLine x={targets[ep]} ifOverflow="extendDomain" stroke="var(--bad)" strokeDasharray="6 4" label={{ value: `${targets[ep]} ms`, fontSize: 11, fill: "var(--bad)", position: "top" }} />
              <Bar dataKey="p50" isAnimationActive={false} fill={BAR_FILL.p50} />
              <Bar dataKey="p95" isAnimationActive={false} fill={BAR_FILL.p95} />
              <Bar dataKey="max" name={t("common.max")} isAnimationActive={false} fill={BAR_FILL.max} />
            </BarChart>
          </Chart>
        ))}
      </div>
    </section>
  );
}

function Detail({ r }: { r: Run }) {
  const { t, f1, n } = useFmt();
  const nonOk = Object.entries(r.non_ok_outcomes);
  const skipped = Object.entries(r.skipped_ticks).filter(([, c]) => c);
  const top = r.statements?.top_by_total_time ?? [];
  return (
    <section className="card section">
      <h2>{r.run_id} <Badge r={r} /></h2>
      <p className="note">
        {t("detail.note", { combo: comboLabel(t, n, r), pool: n(r.pool_size), rep: n(r.repetition), warmup: n(r.warmup_s), measure: n(r.measure_s), git: `${r.git.revision}${dirty(t, r.git.dirty)}` })}
      </p>
      {reasons(t, r.invalid_reasons, r.fail_reasons).map((x) => <p key={x} className="reason">{x}</p>)}
      <h3>{t("detail.latency")}</h3>
      <div className="table-wrap"><table>
        <caption className="sr-only">{t("detail.latency")}</caption>
        <thead><tr><th scope="col">{t("detail.endpoint")}</th><th scope="col" className="num">{t("detail.samples")}</th><th scope="col" className="num">p50</th><th scope="col" className="num">p95</th><th scope="col" className="num">{t("common.max")}</th><th scope="col" className="num">{t("detail.targetP95")}</th><th scope="col">{t("detail.outcomes")}</th></tr></thead>
        <tbody>{EPS.map((ep) => { const x = r.endpoints[ep]; return (
          <tr key={ep}><td>{ep}</td><td className="num">{n(x.samples)}</td><td className="num">{f1(x.p50_ms)}</td><td className={x.p95_ms != null && x.p95_ms > x.target_p95_ms ? "num bad" : "num"}>{f1(x.p95_ms)}</td><td className="num">{f1(x.max_ms)}</td><td className="num">{n(x.target_p95_ms)}</td><td>{JSON.stringify(x.outcomes)}</td></tr>); })}</tbody>
      </table></div>
      <h3>{t("detail.validity")}</h3>
      <ul>
        <li className={r.injection.started === r.injection.scheduled && !r.injection.dropped ? "" : "bad"}>
          {t("detail.iterations", { started: n(r.injection.started), scheduled: n(r.injection.scheduled), dropped: n(r.injection.dropped) })}
        </li>
        <li className={nonOk.length ? "bad" : ""}>{t("detail.nonOk", { value: nonOk.length ? JSON.stringify(Object.fromEntries(nonOk)) : t("common.none") })}</li>
        <li className={skipped.length ? "bad" : ""}>{t("detail.skipped", { value: skipped.length ? JSON.stringify(Object.fromEntries(skipped)) : t("common.none") })}</li>
      </ul>
      <h3>{t("detail.hikari", { warmup: n(r.warmup_s) })}</h3>
      {r.hikari_series.length ? (
        <Chart title={t("detail.hikariChart")}>
          <LineChart data={r.hikari_series.map(([ts, active, idle, pending]) => ({ t: ts, active, idle, pending }))}>
            <CartesianGrid {...GRID} />
            <XAxis {...AXIS} {...secondsX(r.hikari_series.map((h) => h[0]))} dataKey="t" height={AXIS_LABEL_HEIGHT} label={axisLabel(t("axis.seconds"))} />
            <YAxis {...AXIS} {...niceY(r.hikari_series.flatMap(([, a, i, p]) => [a, i, p]))} />
            <Tooltip {...TOOLTIP} />
            <Legend {...LEGEND} />
            <ReferenceLine x={r.warmup_s} stroke="var(--fg-muted)" strokeDasharray="4 4" label={{ value: t("detail.measurementStart"), fontSize: 11, fill: "var(--fg-muted)", position: "insideTopRight" }} />
            <Line dataKey="active" stroke={SERIES[0]} strokeDasharray={SERIES_DASH[0]} strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line dataKey="idle" stroke={SERIES[1]} strokeDasharray={SERIES_DASH[1]} strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line dataKey="pending" stroke="var(--bad)" strokeDasharray={SERIES_DASH[2]} strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      ) : <p className="note">{t("detail.noPool")}</p>}
      <h3>{t("detail.containers")}</h3>
      <div className="grid">
        <Chart title={t("detail.cpuChart")}>
          <LineChart data={r.container_series}>
            <CartesianGrid {...GRID} />
            <XAxis {...AXIS} {...secondsX(r.container_series.map((c) => c.t))} dataKey="t" height={AXIS_LABEL_HEIGHT} label={axisLabel(t("axis.seconds"))} />
            <YAxis {...AXIS} {...niceY(r.container_series.flatMap((c) => [c.server_cpu_pct, c.db_cpu_pct]))} />
            <Tooltip {...TOOLTIP} />
            <Legend {...LEGEND} />
            <Line dataKey="server_cpu_pct" name={t("detail.server")} stroke={SERIES[0]} strokeDasharray={SERIES_DASH[0]} strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line dataKey="db_cpu_pct" name={t("detail.db")} stroke={SERIES[1]} strokeDasharray={SERIES_DASH[1]} strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
        <Chart title={t("detail.memChart")}>
          <LineChart data={r.container_series}>
            <CartesianGrid {...GRID} />
            <XAxis {...AXIS} {...secondsX(r.container_series.map((c) => c.t))} dataKey="t" height={AXIS_LABEL_HEIGHT} label={axisLabel(t("axis.seconds"))} />
            <YAxis {...AXIS} {...niceY(r.container_series.flatMap((c) => [c.server_mem_mib, c.db_mem_mib]))} />
            <Tooltip {...TOOLTIP} />
            <Legend {...LEGEND} />
            <Line dataKey="server_mem_mib" name={t("detail.server")} stroke={SERIES[0]} strokeDasharray={SERIES_DASH[0]} strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line dataKey="db_mem_mib" name={t("detail.db")} stroke={SERIES[1]} strokeDasharray={SERIES_DASH[1]} strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      </div>
      <div className="table-wrap"><table>
        <caption>{t("detail.throttling")}</caption>
        <thead><tr><th scope="col">{t("detail.container")}</th><th scope="col" className="num">{t("detail.periods")}</th><th scope="col" className="num">{t("detail.throttledPeriods")}</th><th scope="col" className="num">{t("detail.throttledMs")}</th></tr></thead>
        <tbody>{Object.entries(r.cgroup).map(([k, v]) => (
          <tr key={k}><td>{k}</td><td className="num">{v.nr_periods == null ? t("common.na") : n(v.nr_periods)}</td><td className="num">{v.nr_throttled == null ? t("common.na") : n(v.nr_throttled)}</td><td className="num">{v.throttled_usec == null ? t("common.na") : f1(v.throttled_usec / 1000)}</td></tr>))}</tbody>
      </table></div>
      <h3>{t("detail.statements")}</h3>
      {r.statements ? (
        <>
          <p className="note">{t("detail.statementsNote", { statements: n(r.statements.statements), harness: n(r.statements.harness_statements) })}</p>
          <div className="table-wrap"><table>
            <caption className="sr-only">{t("detail.statements")}</caption>
            <thead><tr><th scope="col" className="num">{t("detail.calls")}</th><th scope="col" className="num">{t("detail.rows")}</th><th scope="col" className="num">{t("detail.totalMs")}</th><th scope="col" className="num">{t("detail.meanMs")}</th><th scope="col" className="num">{t("detail.maxMs")}</th><th scope="col">{t("detail.query")}</th></tr></thead>
            <tbody>{top.map((s, i) => (
              <tr key={i}><td className="num">{n(s.calls)}</td><td className="num">{n(s.rows)}</td><td className="num">{f1(s.total_exec_ms)}</td><td className="num">{f1(s.mean_exec_ms)}</td><td className="num">{f1(s.max_exec_ms)}</td><td><code>{s.query}</code></td></tr>))}</tbody>
          </table></div>
        </>
      ) : <p className="note">{t("detail.noStatements")}</p>}
    </section>
  );
}

const PHASES = ["baseline", "overload", "recovery"] as const;
const OEPS = ["collect", "probe", "heavy"];
const counts = (o: Record<string, number>) => (Object.keys(o).length ? Object.entries(o).map(([k, v]) => `${k}: ${v}`).join(", ") : "-");

const BAND_FILL: Record<(typeof PHASES)[number], string> = { baseline: "var(--band-baseline)", overload: "var(--band-overload)", recovery: "var(--band-recovery)" };

/**
 * Baseline, overload, and recovery bands on a time chart (issue 28). `ts` are the x values of the chart's data. On a bar chart
 * (`continuous` false) a band spans the bars of its phase; on a line chart it spans the phase's real boundaries.
 * Each band carries its phase name as text, so the phase is not told by color alone.
 */
function phaseBands(t: T, p: Overload["parameters"], ts: number[], continuous: boolean) {
  const end = p.base_s + p.over_s;
  const edges = { baseline: [-Infinity, p.base_s], overload: [p.base_s, end], recovery: [end, Infinity] } as const;
  return PHASES.flatMap((ph) => {
    const inside = ts.filter((x) => x >= edges[ph][0] && x < edges[ph][1]);
    if (!inside.length) return [];
    const x1 = continuous ? Math.max(ts[0], edges[ph][0]) : inside[0];
    const x2 = continuous ? Math.min(ts[ts.length - 1], edges[ph][1]) : inside[inside.length - 1];
    return [
      <ReferenceArea key={ph} x1={x1} x2={x2} fill={BAND_FILL[ph]} fillOpacity={1} ifOverflow="hidden" stroke="none"
        label={inside.length > 2 ? { value: t(`overload.phases.${ph}`), position: "top", fill: "var(--fg-muted)", fontSize: 11 } : undefined} />,
    ];
  });
}

function OverloadRun({ o }: { o: Overload }) {
  const { t, f1, n } = useFmt();
  const p = o.parameters;
  const rc = o.reconciliation;
  const p95Rows = o.series_5s.filter((r) => r[1] === "collect").map(([ts, , , , , , p95]) => ({ t: ts, p95 }));
  return (
    <section className="card section">
      <h2>{t("overload.title", { id: o.run_id })} <span className={`badge ${o.verdict}`}>{verdictText(t, o.verdict)}</span></h2>
      <p className="note">
        {t("overload.note", {
          concurrency: n(p.concurrency), admission: n(p.admission_ms), pool: n(p.pool_size), rows: n(p.seed_size), dist: p.dist, days: n(p.window_days),
          base: n(p.base_s), over: n(p.over_s), rec: n(p.rec_s), rate: n(p.rate), overRate: n(p.over_rate), heavyRate: n(p.heavy_rate), git: `${o.git.revision}${dirty(t, o.git.dirty)}`,
        })}
      </p>
      {reasons(t, o.invalid_reasons, o.fail_reasons).map((x) => <p key={x} className="reason">{x}</p>)}
      <h3>{t("overload.perPhase")}</h3>
      <div className="table-wrap"><table>
        <caption className="sr-only">{t("overload.perPhase")}</caption>
        <thead><tr><th scope="col">{t("overload.phase")}</th><th scope="col">{t("overload.endpoint")}</th><th scope="col" className="num">{t("overload.requests")}</th><th scope="col">{t("overload.statusCounts")}</th><th scope="col" className="num">{t("overload.retryAfter")}</th><th scope="col">{t("overload.errorCodes")}</th><th scope="col" className="num">p50</th><th scope="col" className="num">p95</th><th scope="col" className="num">{t("overload.p95Ok")}</th><th scope="col" className="num">{t("common.max")}</th></tr></thead>
        <tbody>{PHASES.flatMap((ph) => OEPS.filter((ep) => o.phases[ph]?.[ep]).map((ep) => { const x = o.phases[ph][ep]; return (
          <tr key={ph + ep}><td>{t(`overload.phases.${ph}`)}</td><td>{ep}</td><td className="num">{n(x.requests)}</td><td>{counts(x.status)}</td><td className="num">{counts(x.retry_after)}</td><td>{counts(x.error_codes)}</td><td className="num">{f1(x.p50_ms)}</td><td className="num">{f1(x.p95_ms)}</td><td className="num">{f1(x.p95_ok_ms)}</td><td className="num">{f1(x.max_ms)}</td></tr>); }))}</tbody>
      </table></div>
      <h3>{t("overload.series", { from: n(p.base_s), to: n(p.base_s + p.over_s) })}</h3>
      <div className="grid">
        {OEPS.map((ep) => {
          const rows = o.series_5s.filter((r) => r[1] === ep).map(([ts, , total, s200, s503, other]) => ({ t: ts, "200": s200, "503": s503, other, n: total }));
          return (
          <Chart key={ep} title={t("overload.responses", { endpoint: ep })}>
            <BarChart data={rows} margin={{ top: 22 }}>
              <PatternDefs />
              <CartesianGrid {...GRID} vertical={false} />
              {phaseBands(t, p, rows.map((r) => r.t), false)}
              <XAxis {...AXIS} dataKey="t" interval="preserveStartEnd" minTickGap={24} height={AXIS_LABEL_HEIGHT} label={axisLabel(t("axis.seconds"))} />
              <YAxis {...AXIS} width={44} {...niceY(rows.map((r) => r.n))} />
              <Tooltip {...TOOLTIP} />
              <Legend {...LEGEND} />
              <Bar dataKey="200" stackId="a" isAnimationActive={false} fill={BAR_FILL.ok} />
              <Bar dataKey="503" stackId="a" isAnimationActive={false} fill={BAR_FILL.shed} />
              <Bar dataKey="other" name={t("common.other")} stackId="a" isAnimationActive={false} fill={BAR_FILL.other} />
            </BarChart>
          </Chart>);
        })}
        <Chart title={t("overload.collectP95")}>
          <LineChart data={p95Rows} margin={{ top: 22 }}>
            <CartesianGrid {...GRID} vertical={false} />
            {phaseBands(t, p, p95Rows.map((r) => r.t), true)}
            <XAxis {...AXIS} {...secondsX(p95Rows.map((r) => r.t))} dataKey="t" height={AXIS_LABEL_HEIGHT} label={axisLabel(t("axis.seconds"))} />
            <YAxis {...AXIS} width={44} {...niceY(p95Rows.map((r) => r.p95))} />
            <Tooltip {...TOOLTIP} />
            <Line dataKey="p95" stroke={SERIES[0]} strokeDasharray={SERIES_DASH[0]} strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      </div>
      <h3>{t("overload.recovery")}</h3>
      <ul>
        <li>{t("overload.firstClean", { seconds: o.recovery.first_clean_5s_window_after_overload_s == null ? t("common.na") : n(o.recovery.first_clean_5s_window_after_overload_s) })}</li>
        <li>{t("overload.last30", { requests: n(o.recovery.last_30s_requests), non200: n(o.recovery.last_30s_non_200), p95: f1(o.recovery.last_30s_collect_p95_ms), baseline: f1(o.recovery.baseline_collect_p95_ms), limit: f1(o.recovery.limit_ms) })}</li>
        <li>{t("overload.storage", { ok: n(rc.ok_requests), notStored: n(rc.ok_requests_not_fully_stored), shed: n(rc.shed_requests), shedRows: n(rc.rows_stored_for_shed_requests), other: n(rc.other_requests), duplicates: n(rc.duplicate_ids_in_db), dropped: n(o.dropped_iterations) })}</li>
      </ul>
    </section>
  );
}

const SDK_SCENARIOS = ["normal", "burst", "offline"] as const;

function SdkResults({ data, error }: { data: SdkData | null; error: string }) {
  const { t, f1, n } = useFmt();
  const runs = data ? [...data.runs].sort((a, b) => a.avd.localeCompare(b.avd) || SDK_SCENARIOS.indexOf(a.scenario) - SDK_SCENARIOS.indexOf(b.scenario)) : [];
  const measured = runs.filter((r) => r.track_ms);
  const name = (r: SdkRun) => `${r.avd} · ${t(`sdk.scenario.${r.scenario}`)}`;
  const rateRows = runs.filter((r) => r.target_rate_per_s && r.actual_rate_per_s != null).map((r) => ({ name: name(r), percent: Math.round((1000 * r.actual_rate_per_s!) / r.target_rate_per_s!) / 10 }));
  return (
    <section className="card section" id="sdk">
      <h2>{t("sdk.title")}</h2>
      <p className="note">{t("sdk.note")}</p>
      {!data && <p className="note">{error || t("app.loading")}</p>}
      {data && (
        <>
          <div className="table-wrap"><table className="sdk-table">
            <caption className="sr-only">{t("sdk.title")}</caption>
            <thead>
              <tr>
                <th scope="col">{t("sdk.avd")}</th><th scope="col">{t("sdk.scenarioHeader")}</th><th scope="col">{t("sdk.verdict")}</th><th scope="col">{t("sdk.rate")}</th><th scope="col">{t("sdk.track")}</th><th scope="col" className="num">{t("sdk.drain")}</th>
                <th scope="col">{t("sdk.files")}</th><th scope="col">{t("sdk.memory")}</th><th scope="col">{t("sdk.rows")}</th>
              </tr>
            </thead>
            <tbody>{runs.map((r) => {
              const tm = r.track_ms;
              const wait = r.drain?.persisted_retry_wait_at_start_s;
              const fb = r.files_bytes_after_drain;
              return (
                <tr key={r.run_id}>
                  <td>{r.avd}{r.api != null && <div className="note">{t("sdk.device", { device: r.device ?? "", release: r.release ?? "", api: String(r.api), abi: r.abi ?? "" })}</div>}</td>
                  <td>{t(`sdk.scenario.${r.scenario}`)}{r.started != null && r.planned != null && <div className="note">{t("sdk.events", { started: n(r.started), planned: n(r.planned), bytes: r.event_bytes == null ? t("common.na") : n(r.event_bytes), inflight: r.max_calls_in_flight == null ? t("common.na") : n(r.max_calls_in_flight), notOk: n(r.track_not_ok ?? 0) })}</div>}</td>
                  <td><span className={`badge ${r.verdict}`}>{verdictText(t, r.verdict)}</span>{r.reasons.map((x) => <div key={x} className="reason">{x}</div>)}</td>
                  <td className="nowrap">{r.actual_rate_per_s == null ? t("common.na") : `${r.target_rate_per_s == null ? t("sdk.backToBack") : n(r.target_rate_per_s)} / ${n(r.actual_rate_per_s)}`}</td>
                  <td className="nowrap">{tm ? `${f1(tm.p50)} / ${f1(tm.p95)} / ${f1(tm.max)}` : t("common.na")}</td>
                  <td className="num">{r.drain?.seconds == null ? t("common.na") : <span className="nowrap">{f1(r.drain.seconds)}</span>}{r.drain?.seconds != null && wait != null && <div className="note">{t("sdk.drainWait", { wait: f1(wait) })}</div>}</td>
                  <td className="nowrap">{fb ? <><div>{t("sdk.filesDb", { value: n(fb["queue.db"] ?? 0) })}</div><div>{t("sdk.filesWal", { value: n(fb["queue.db-wal"] ?? 0) })}</div><div>{t("sdk.filesShm", { value: n(fb["queue.db-shm"] ?? 0) })}</div></> : t("common.na")}</td>
                  <td className="nowrap">{r.pss_delta_kib == null ? t("common.na") : <><div>{t("sdk.memoryPss", { value: n(r.pss_delta_kib) })}</div><div>{t("sdk.memoryHeap", { value: r.heap_delta_kib == null ? t("common.na") : n(r.heap_delta_kib) })}</div>{r.memory_basis === "store" && <div className="note">{t("sdk.memoryStoreNote")}</div>}</>}</td>
                  <td className="nowrap">{r.missing_on_server == null ? t("common.na") : `${n(r.missing_on_server)} / ${r.duplicate_server_rows == null ? t("common.na") : n(r.duplicate_server_rows)}`}</td>
                </tr>);
            })}</tbody>
          </table></div>
          <p className="note">{t("sdk.memoryNote")} {t("sdk.rateNote")}</p>
          <div className="grid grid-wide">
            <Chart title={t("sdk.trackChart")} height={rowHeight(measured.length, 62)}>
              <BarChart layout="vertical" data={measured.map((r) => ({ name: name(r), p50: r.track_ms!.p50, p95: r.track_ms!.p95, max: r.track_ms!.max }))} margin={{ left: 8, right: 24 }}>
              <PatternDefs />
                <CartesianGrid {...GRID} horizontal={false} />
                <XAxis {...AXIS} type="number" />
                <YAxis {...AXIS} type="category" dataKey="name" width={190} interval={0} />
                <Tooltip {...TOOLTIP} />
                <Legend {...LEGEND} />
                <Bar dataKey="p50" isAnimationActive={false} fill={BAR_FILL.p50} />
                <Bar dataKey="p95" isAnimationActive={false} fill={BAR_FILL.p95} />
                <Bar dataKey="max" name={t("common.max")} isAnimationActive={false} fill={BAR_FILL.max} />
              </BarChart>
            </Chart>
            <Chart title={t("sdk.rateChart")} height={rowHeight(rateRows.length, 40)}>
              <BarChart layout="vertical" data={rateRows} margin={{ top: 16, left: 8, right: 24 }}>
              <PatternDefs />
                <CartesianGrid {...GRID} horizontal={false} />
                <XAxis {...AXIS} type="number" domain={[0, 110]} ticks={[0, 20, 40, 60, 80, 100]} />
                <YAxis {...AXIS} type="category" dataKey="name" width={190} interval={0} />
                <Tooltip {...TOOLTIP} />
                <ReferenceLine x={100} stroke="var(--bad)" strokeDasharray="6 4" label={{ value: t("sdk.targetLine"), fontSize: 11, fill: "var(--bad)", position: "top" }} />
                <Bar dataKey="percent" name={t("sdk.percent")} isAnimationActive={false} fill={SERIES[0]} />
              </BarChart>
            </Chart>
          </div>
        </>
      )}
    </section>
  );
}

function useJson<D>(initial: D | undefined, url: string, noResults: string): [D | null, string] {
  const [data, setData] = useState<D | null>(initial ?? null);
  const [error, setError] = useState(false);
  useEffect(() => {
    if (data) return;
    fetch(url).then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status))))).then(setData).catch(() => setError(true));
  }, [data, url]);
  return [data, error ? noResults : ""];
}

const worstVerdict = (verdicts: string[]) => (verdicts.includes("fail") ? "fail" : verdicts.length > 0 && verdicts.every((v) => v === "pass") ? "pass" : "invalid");

function Toc({ items }: { items: { id: string; label: string }[] }) {
  const { t } = useTranslation();
  const [active, setActive] = useState(items[0]?.id ?? "");
  const key = items.map((i) => i.id).join();
  useEffect(() => {
    const els = key.split(",").map((id) => document.getElementById(id)).filter((e): e is HTMLElement => e != null);
    if (!("IntersectionObserver" in window) || !els.length) return;
    const io = new IntersectionObserver((entries) => {
      const top = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
      if (top) setActive(top.target.id);
    }, { rootMargin: "-10% 0px -70% 0px" });
    els.forEach((e) => io.observe(e));
    return () => io.disconnect();
  }, [key]);
  return (
    <nav className="toc" aria-label={t("toc.label")}>
      <ul>
        {items.map((i) => (
          <li key={i.id}><a href={`#${i.id}`} aria-current={active === i.id ? "location" : undefined} onClick={() => setActive(i.id)}>{i.label}</a></li>
        ))}
      </ul>
    </nav>
  );
}

/** A card links to its section; without a section on the page (no overload run, no run) it is plain content. */
function Kpi({ href, label, value, sub }: { href?: string; label: string; value: React.ReactNode; sub?: string }) {
  const body = (
    <>
      <span className="kpi-label">{label}</span>
      <span className="kpi-value">{value}</span>
      {sub && <span className="kpi-sub">{sub}</span>}
    </>
  );
  return href ? <a className="card kpi" href={href}>{body}</a> : <div className="card kpi">{body}</div>;
}

/** KPI strip (Q140). Every number belongs to one run and names it: percentiles of different runs are never averaged. */
function KpiStrip({ data, current }: { data: Data; current: Run | undefined }) {
  const { t, f1, n, pct } = useFmt();
  const overload = (data.overload ?? [])[data.overload ? data.overload.length - 1 : 0];
  const verdicts = [...data.runs.map((r) => r.verdict), ...(data.overload ?? []).map((o) => o.verdict)];
  const verdict = worstVerdict(verdicts);
  const collect = current?.endpoints.collect;
  const shed = overload?.phases.overload?.collect;
  const shed503 = shed && shed.requests > 0 ? (shed.status["503"] ?? 0) / shed.requests * 100 : undefined;
  const dropped = data.runs.reduce((sum, r) => sum + r.injection.dropped, 0) + (data.overload ?? []).reduce((sum, o) => sum + o.dropped_iterations, 0);
  const na = <span className="kpi-none">{t("common.na")}</span>;
  return (
    <section className="kpis" aria-label={t("kpi.label")}>
      <Kpi href="#runs" label={t("kpi.verdict")} value={<span className={`badge ${verdict}`}>{verdictText(t, verdict)}</span>}
        sub={t("kpi.verdictSub", { count: verdicts.length, pass: n(verdicts.filter((v) => v === "pass").length), total: n(verdicts.length) })} />
      <Kpi href={current ? "#latency" : undefined} label={t("kpi.p95")} value={collect?.p95_ms == null ? na : t("kpi.ms", { value: f1(collect.p95_ms) })}
        sub={collect && current ? t("kpi.p95Sub", { run: current.run_id, target: n(collect.target_p95_ms) }) : undefined} />
      <Kpi href={current ? "#latency" : undefined} label={t("kpi.max")} value={collect?.max_ms == null ? na : t("kpi.ms", { value: f1(collect.max_ms) })}
        sub={collect && current ? t("kpi.maxSub", { run: current.run_id }) : undefined} />
      <Kpi href={overload ? "#overload" : undefined} label={t("kpi.shed")} value={shed503 === undefined ? na : pct(shed503)}
        sub={shed503 === undefined || !overload ? undefined : t("kpi.shedSub", { run: overload.run_id })} />
      <Kpi href="#runs" label={t("kpi.dropped")} value={n(dropped)} sub={t("kpi.droppedSub")} />
      <Kpi href="#runs" label={t("kpi.runs")} value={n(data.runs.length + (data.overload ?? []).length)}
        sub={t("kpi.runsSub", { pilot: n(data.runs.filter((r) => !r.counted).length), overload: n((data.overload ?? []).length) })} />
    </section>
  );
}

export function App() {
  const { t, n } = useFmt();
  useEffect(() => { document.title = t("app.title"); }, [t]);
  const [data, error] = useJson<Data>(window.SIGNALDOCK_RESULTS, "./index.json", t("app.noResults", { command: SUMMARIZE_COMMAND }));
  const [sdk, sdkError] = useJson<SdkData>(window.SIGNALDOCK_SDK_RESULTS, "./sdk-index.json", t("sdk.noResults", { command: SDK_COMMAND }));
  const [id, setId] = useState("");
  const header = (
    <header className="page-header">
      <h1>{t("app.title")}</h1>
      <div className="switches">
        <ThemeSwitch />
        <LanguageSwitch />
      </div>
    </header>
  );
  const skip = <a className="skip-link" href="#main">{t("app.skip")}</a>;
  if (!data) return <>{skip}<main id="main" tabIndex={-1}>{header}<p>{error || t("app.loading")}</p><SdkResults data={sdk} error={sdkError} /></main></>;
  const current = data.runs.find((r) => r.run_id === id) ?? data.runs[data.runs.length - 1];
  const overload = data.overload ?? [];
  const toc = [
    { id: "runs", label: t("toc.runs") },
    ...(overload.length ? [{ id: "overload", label: t("toc.overload") }] : []),
    { id: "sdk", label: t("toc.sdk") },
    ...(current ? [{ id: "latency", label: t("toc.latency") }] : []),
  ];
  return (
    <>
    {skip}
    <main id="main" tabIndex={-1}>
      {header}
      <p className="note">{t("app.source")}</p>
      <KpiStrip data={data} current={current} />
      <div className="layout">
        <Toc items={toc} />
        <div className="content">
          <section className="card section" id="runs">
            <h2>{t("runs.title")}</h2>
            <div className="table-wrap"><table className="runs-table">
              <caption className="sr-only">{t("runs.title")}</caption>
              <thead><tr><th scope="col">{t("runs.run")}</th><th scope="col">{t("runs.status")}</th><th scope="col">{t("runs.combination")}</th><th scope="col" className="num">{t("runs.pool")}</th><th scope="col" className="num">{t("runs.rep")}</th><th scope="col" className="num">{t("runs.injection")}</th><th scope="col" className="num">{t("runs.dropped")}</th><th scope="col" className="num">{t("runs.nonOk")}</th><th scope="col" className="num">{t("runs.skippedTicks")}</th></tr></thead>
              <tbody>{data.runs.map((r) => (
                <tr key={r.run_id} className={r.run_id === current?.run_id ? "sel" : ""}>
                  <td><button type="button" aria-pressed={r.run_id === current?.run_id} onClick={() => setId(r.run_id)}>{r.run_id}</button></td><td><Badge r={r} /></td><td>{comboLabel(t, n, r)}</td><td className="num">{n(r.pool_size)}</td><td className="num">{n(r.repetition)}</td>
                  <td className="num">{n(r.injection.started)} / {n(r.injection.scheduled)}</td><td className="num">{n(r.injection.dropped)}</td>
                  <td className="num">{Object.keys(r.non_ok_outcomes).length ? t("common.yes") : n(0)}</td><td className="num">{n(Object.values(r.skipped_ticks).reduce((a, b) => a + b, 0))}</td>
                </tr>))}</tbody>
            </table></div>
          </section>
          {overload.length > 0 && <div id="overload" className="stack">{overload.map((o) => <OverloadRun key={o.run_id} o={o} />)}</div>}
          <SdkResults data={sdk} error={sdkError} />
          {current && <div id="latency" className="stack"><Compare runs={data.runs} targets={data.targets_p95_ms} /><Detail r={current} /></div>}
        </div>
      </div>
    </main>
    </>
  );
}
