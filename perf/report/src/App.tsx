import { useEffect, useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { LANGUAGES, formatNumber } from "../../../web-shared/i18n";
import type { Language } from "../../../web-shared/i18n";
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
  };
}

const verdictText = (t: T, v: string) => (v === "pass" || v === "fail" || v === "invalid" ? t(`verdict.${v}`) : v);
const comboId = (r: Run) => `${r.combination.seed_size}|${r.combination.distribution}|${r.combination.window_days}|${r.combination.clients}`;
const comboLabel = (t: T, n: (v: number) => string, r: Run) =>
  t("combo.label", { rows: n(r.combination.seed_size), distribution: r.combination.distribution, days: r.combination.window_days, clients: r.combination.clients });
const runLabel = (t: T, r: Run) => t(r.counted ? "combo.run" : "combo.runPilot", { pool: r.pool_size, rep: r.repetition });
const dirty = (t: T, d: boolean) => (d ? ` ${t("common.dirty")}` : "");
const reasons = (t: T, invalid: string[], fail: string[]) => [
  ...invalid.map((reason) => t("common.reasonInvalid", { reason })),
  ...fail.map((reason) => t("common.reasonFail", { reason })),
];

function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  return (
    <label>
      {t("language.label")}{" "}
      <select value={i18n.language} onChange={(e) => i18n.changeLanguage(e.target.value as Language)}>
        {LANGUAGES.map((l) => <option key={l} value={l}>{t(`language.${l}`)}</option>)}
      </select>
    </label>
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
    <figure>
      <figcaption>{title}</figcaption>
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">{children}</ResponsiveContainer>
      </div>
    </figure>
  );
}

function Compare({ runs, targets }: { runs: Run[]; targets: Record<string, number> }) {
  const { t, n } = useFmt();
  const combos = useMemo(() => [...new Map(runs.map((r) => [comboId(r), r])).values()], [runs]);
  const [combo, setCombo] = useState(combos[0] ? comboId(combos[0]) : "");
  const sel = runs.filter((r) => comboId(r) === combo).sort((a, b) => a.pool_size - b.pool_size || a.repetition - b.repetition || a.run_id.localeCompare(b.run_id));
  return (
    <section>
      <h2>{t("compare.title")}</h2>
      <p className="note">{t("compare.note")}</p>
      <label>{t("compare.combination")} <select value={combo} onChange={(e) => setCombo(e.target.value)}>{combos.map((r) => <option key={comboId(r)} value={comboId(r)}>{comboLabel(t, n, r)}</option>)}</select></label>
      <div className="grid">
        {EPS.map((ep) => (
          <Chart key={ep} title={t("compare.chart", { endpoint: ep, target: targets[ep] })}>
            <BarChart data={sel.map((r) => ({ name: `${runLabel(t, r)} (${r.run_id})`, p50: r.endpoints[ep].p50_ms, p95: r.endpoints[ep].p95_ms, max: r.endpoints[ep].max_ms }))}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="name" tick={{ fontSize: 10 }} interval={0} />
              <YAxis tickFormatter={n} />
              <Tooltip />
              <Legend />
              <ReferenceLine y={targets[ep]} ifOverflow="extendDomain" stroke="var(--bad)" strokeDasharray="6 4" label={{ value: `${targets[ep]} ms`, fontSize: 11, fill: "var(--bad)" }} />
              <Bar dataKey="p50" isAnimationActive={false} fill="var(--c1)" />
              <Bar dataKey="p95" isAnimationActive={false} fill="var(--c2)" />
              <Bar dataKey="max" name={t("common.max")} isAnimationActive={false} fill="var(--c3)" />
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
    <section>
      <h2>{r.run_id} <Badge r={r} /></h2>
      <p className="note">
        {t("detail.note", { combo: comboLabel(t, n, r), pool: r.pool_size, rep: r.repetition, warmup: r.warmup_s, measure: r.measure_s, git: `${r.git.revision}${dirty(t, r.git.dirty)}` })}
      </p>
      {reasons(t, r.invalid_reasons, r.fail_reasons).map((x) => <p key={x} className="reason">{x}</p>)}
      <h3>{t("detail.latency")}</h3>
      <table>
        <thead><tr><th>{t("detail.endpoint")}</th><th>{t("detail.samples")}</th><th>p50</th><th>p95</th><th>{t("common.max")}</th><th>{t("detail.targetP95")}</th><th>{t("detail.outcomes")}</th></tr></thead>
        <tbody>{EPS.map((ep) => { const x = r.endpoints[ep]; return (
          <tr key={ep}><td>{ep}</td><td>{n(x.samples)}</td><td>{f1(x.p50_ms)}</td><td className={x.p95_ms != null && x.p95_ms > x.target_p95_ms ? "bad" : ""}>{f1(x.p95_ms)}</td><td>{f1(x.max_ms)}</td><td>{x.target_p95_ms}</td><td>{JSON.stringify(x.outcomes)}</td></tr>); })}</tbody>
      </table>
      <h3>{t("detail.validity")}</h3>
      <ul>
        <li className={r.injection.started === r.injection.scheduled && !r.injection.dropped ? "" : "bad"}>
          {t("detail.iterations", { started: r.injection.started, scheduled: r.injection.scheduled, dropped: r.injection.dropped })}
        </li>
        <li className={nonOk.length ? "bad" : ""}>{t("detail.nonOk", { value: nonOk.length ? JSON.stringify(Object.fromEntries(nonOk)) : t("common.none") })}</li>
        <li className={skipped.length ? "bad" : ""}>{t("detail.skipped", { value: skipped.length ? JSON.stringify(Object.fromEntries(skipped)) : t("common.none") })}</li>
      </ul>
      <h3>{t("detail.hikari", { warmup: r.warmup_s })}</h3>
      {r.hikari_series.length ? (
        <Chart title={t("detail.hikariChart")}>
          <LineChart data={r.hikari_series.map(([ts, active, idle, pending]) => ({ t: ts, active, idle, pending }))}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="t" type="number" domain={["dataMin", "dataMax"]} />
            <YAxis allowDecimals={false} />
            <Tooltip />
            <Legend />
            <ReferenceLine x={r.warmup_s} stroke="var(--muted)" strokeDasharray="4 4" />
            <Line dataKey="active" stroke="var(--c1)" dot={false} isAnimationActive={false} />
            <Line dataKey="idle" stroke="var(--c2)" dot={false} isAnimationActive={false} />
            <Line dataKey="pending" stroke="var(--bad)" dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      ) : <p className="note">{t("detail.noPool")}</p>}
      <h3>{t("detail.containers")}</h3>
      <div className="grid">
        <Chart title={t("detail.cpuChart")}>
          <LineChart data={r.container_series}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="t" type="number" domain={["dataMin", "dataMax"]} />
            <YAxis />
            <Tooltip />
            <Legend />
            <Line dataKey="server_cpu_pct" name={t("detail.server")} stroke="var(--c1)" dot={false} isAnimationActive={false} />
            <Line dataKey="db_cpu_pct" name={t("detail.db")} stroke="var(--c2)" dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
        <Chart title={t("detail.memChart")}>
          <LineChart data={r.container_series}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="t" type="number" domain={["dataMin", "dataMax"]} />
            <YAxis />
            <Tooltip />
            <Legend />
            <Line dataKey="server_mem_mib" name={t("detail.server")} stroke="var(--c1)" dot={false} isAnimationActive={false} />
            <Line dataKey="db_mem_mib" name={t("detail.db")} stroke="var(--c2)" dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      </div>
      <table>
        <caption>{t("detail.throttling")}</caption>
        <thead><tr><th>{t("detail.container")}</th><th>{t("detail.periods")}</th><th>{t("detail.throttledPeriods")}</th><th>{t("detail.throttledMs")}</th></tr></thead>
        <tbody>{Object.entries(r.cgroup).map(([k, v]) => (
          <tr key={k}><td>{k}</td><td>{v.nr_periods == null ? t("common.na") : n(v.nr_periods)}</td><td>{v.nr_throttled == null ? t("common.na") : n(v.nr_throttled)}</td><td>{v.throttled_usec == null ? t("common.na") : f1(v.throttled_usec / 1000)}</td></tr>))}</tbody>
      </table>
      <h3>{t("detail.statements")}</h3>
      {r.statements ? (
        <>
          <p className="note">{t("detail.statementsNote", { statements: r.statements.statements, harness: r.statements.harness_statements })}</p>
          <table>
            <thead><tr><th>{t("detail.calls")}</th><th>{t("detail.rows")}</th><th>{t("detail.totalMs")}</th><th>{t("detail.meanMs")}</th><th>{t("detail.maxMs")}</th><th>{t("detail.query")}</th></tr></thead>
            <tbody>{top.map((s, i) => (
              <tr key={i}><td>{n(s.calls)}</td><td>{n(s.rows)}</td><td>{f1(s.total_exec_ms)}</td><td>{f1(s.mean_exec_ms)}</td><td>{f1(s.max_exec_ms)}</td><td><code>{s.query}</code></td></tr>))}</tbody>
          </table>
        </>
      ) : <p className="note">{t("detail.noStatements")}</p>}
    </section>
  );
}

const PHASES = ["baseline", "overload", "recovery"] as const;
const OEPS = ["collect", "probe", "heavy"];
const counts = (o: Record<string, number>) => (Object.keys(o).length ? Object.entries(o).map(([k, v]) => `${k}: ${v}`).join(", ") : "-");

function OverloadRun({ o }: { o: Overload }) {
  const { t, f1, n } = useFmt();
  const p = o.parameters;
  const rc = o.reconciliation;
  return (
    <section>
      <h2>{t("overload.title", { id: o.run_id })} <span className={`badge ${o.verdict}`}>{verdictText(t, o.verdict)}</span></h2>
      <p className="note">
        {t("overload.note", {
          concurrency: p.concurrency, admission: p.admission_ms, pool: p.pool_size, rows: n(p.seed_size), dist: p.dist, days: p.window_days,
          base: p.base_s, over: p.over_s, rec: p.rec_s, rate: p.rate, overRate: p.over_rate, heavyRate: p.heavy_rate, git: `${o.git.revision}${dirty(t, o.git.dirty)}`,
        })}
      </p>
      {reasons(t, o.invalid_reasons, o.fail_reasons).map((x) => <p key={x} className="reason">{x}</p>)}
      <h3>{t("overload.perPhase")}</h3>
      <table>
        <thead><tr><th>{t("overload.phase")}</th><th>{t("overload.endpoint")}</th><th>{t("overload.requests")}</th><th>{t("overload.statusCounts")}</th><th>{t("overload.retryAfter")}</th><th>{t("overload.errorCodes")}</th><th>p50</th><th>p95</th><th>{t("overload.p95Ok")}</th><th>{t("common.max")}</th></tr></thead>
        <tbody>{PHASES.flatMap((ph) => OEPS.filter((ep) => o.phases[ph]?.[ep]).map((ep) => { const x = o.phases[ph][ep]; return (
          <tr key={ph + ep}><td>{t(`overload.phases.${ph}`)}</td><td>{ep}</td><td>{n(x.requests)}</td><td>{counts(x.status)}</td><td>{counts(x.retry_after)}</td><td>{counts(x.error_codes)}</td><td>{f1(x.p50_ms)}</td><td>{f1(x.p95_ms)}</td><td>{f1(x.p95_ok_ms)}</td><td>{f1(x.max_ms)}</td></tr>); }))}</tbody>
      </table>
      <h3>{t("overload.series", { from: p.base_s, to: p.base_s + p.over_s })}</h3>
      <div className="grid">
        {OEPS.map((ep) => (
          <Chart key={ep} title={t("overload.responses", { endpoint: ep })}>
            <BarChart data={o.series_5s.filter((r) => r[1] === ep).map(([ts, , total, s200, s503, other]) => ({ t: ts, "200": s200, "503": s503, other, n: total }))}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="t" />
              <YAxis allowDecimals={false} />
              <Tooltip />
              <Legend />
              <Bar dataKey="200" stackId="a" isAnimationActive={false} fill="var(--ok)" />
              <Bar dataKey="503" stackId="a" isAnimationActive={false} fill="var(--c2)" />
              <Bar dataKey="other" name={t("common.other")} stackId="a" isAnimationActive={false} fill="var(--bad)" />
            </BarChart>
          </Chart>
        ))}
        <Chart title={t("overload.collectP95")}>
          <LineChart data={o.series_5s.filter((r) => r[1] === "collect").map(([ts, , , , , , p95]) => ({ t: ts, p95 }))}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="t" />
            <YAxis />
            <Tooltip />
            <ReferenceLine x={p.base_s} stroke="var(--muted)" strokeDasharray="4 4" />
            <ReferenceLine x={p.base_s + p.over_s} stroke="var(--muted)" strokeDasharray="4 4" />
            <Line dataKey="p95" stroke="var(--c1)" dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      </div>
      <h3>{t("overload.recovery")}</h3>
      <ul>
        <li>{t("overload.firstClean", { seconds: o.recovery.first_clean_5s_window_after_overload_s ?? t("common.na") })}</li>
        <li>{t("overload.last30", { requests: o.recovery.last_30s_requests, non200: o.recovery.last_30s_non_200, p95: f1(o.recovery.last_30s_collect_p95_ms), baseline: f1(o.recovery.baseline_collect_p95_ms), limit: f1(o.recovery.limit_ms) })}</li>
        <li>{t("overload.storage", { ok: n(rc.ok_requests), notStored: n(rc.ok_requests_not_fully_stored), shed: n(rc.shed_requests), shedRows: n(rc.rows_stored_for_shed_requests), other: n(rc.other_requests), duplicates: n(rc.duplicate_ids_in_db), dropped: o.dropped_iterations })}</li>
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
  return (
    <section>
      <h2>{t("sdk.title")}</h2>
      <p className="note">{t("sdk.note")}</p>
      {!data && <p className="note">{error || t("app.loading")}</p>}
      {data && (
        <>
          <table>
            <thead>
              <tr>
                <th>{t("sdk.avd")}</th><th>{t("sdk.scenarioHeader")}</th><th>{t("sdk.verdict")}</th><th>{t("sdk.rate")}</th><th>{t("sdk.track")}</th><th>{t("sdk.drain")}</th>
                <th>{t("sdk.files")}</th><th>{t("sdk.memory")}</th><th>{t("sdk.rows")}</th>
              </tr>
            </thead>
            <tbody>{runs.map((r) => {
              const tm = r.track_ms;
              const wait = r.drain?.persisted_retry_wait_at_start_s;
              const fb = r.files_bytes_after_drain;
              return (
                <tr key={r.run_id}>
                  <td>{r.avd}{r.api != null && <div className="note">{t("sdk.device", { device: r.device ?? "", release: r.release ?? "", api: r.api, abi: r.abi ?? "" })}</div>}</td>
                  <td>{t(`sdk.scenario.${r.scenario}`)}{r.started != null && r.planned != null && <div className="note">{t("sdk.events", { started: n(r.started), planned: n(r.planned), bytes: r.event_bytes ?? t("common.na"), inflight: r.max_calls_in_flight ?? 0, notOk: r.track_not_ok ?? 0 })}</div>}</td>
                  <td><span className={`badge ${r.verdict}`}>{verdictText(t, r.verdict)}</span>{r.reasons.map((x) => <div key={x} className="reason">{x}</div>)}</td>
                  <td>{r.actual_rate_per_s == null ? t("common.na") : `${r.target_rate_per_s == null ? t("sdk.backToBack") : n(r.target_rate_per_s)} / ${n(r.actual_rate_per_s)}`}</td>
                  <td>{tm ? `${f1(tm.p50)} / ${f1(tm.p95)} / ${f1(tm.max)}` : t("common.na")}</td>
                  <td>{r.drain?.seconds == null ? t("common.na") : wait ? t("sdk.drainOffline", { seconds: f1(r.drain.seconds), wait: f1(wait) }) : f1(r.drain.seconds)}</td>
                  <td>{fb ? t("sdk.filesValue", { db: n(fb["queue.db"] ?? 0), wal: n(fb["queue.db-wal"] ?? 0), shm: n(fb["queue.db-shm"] ?? 0) }) : t("common.na")}</td>
                  <td>{r.pss_delta_kib == null ? t("common.na") : t(r.memory_basis === "store" ? "sdk.memoryStore" : "sdk.memoryDrain", { pss: n(r.pss_delta_kib), heap: n(r.heap_delta_kib ?? 0) })}</td>
                  <td>{r.missing_on_server == null ? t("common.na") : `${r.missing_on_server} / ${r.duplicate_server_rows}`}</td>
                </tr>);
            })}</tbody>
          </table>
          <p className="note">{t("sdk.memoryNote")}</p>
          <div className="grid">
            <Chart title={t("sdk.trackChart")}>
              <BarChart data={measured.map((r) => ({ name: name(r), p50: r.track_ms!.p50, p95: r.track_ms!.p95, max: r.track_ms!.max }))}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} interval={0} />
                <YAxis />
                <Tooltip />
                <Legend />
                <Bar dataKey="p50" isAnimationActive={false} fill="var(--c1)" />
                <Bar dataKey="p95" isAnimationActive={false} fill="var(--c2)" />
                <Bar dataKey="max" name={t("common.max")} isAnimationActive={false} fill="var(--c3)" />
              </BarChart>
            </Chart>
            <Chart title={t("sdk.rateChart")}>
              <BarChart data={measured.filter((r) => r.scenario !== "offline").map((r) => ({ name: name(r), target: r.target_rate_per_s, actual: r.actual_rate_per_s }))}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} interval={0} />
                <YAxis />
                <Tooltip />
                <Legend />
                <Bar dataKey="target" name={t("sdk.target")} isAnimationActive={false} fill="var(--c3)" />
                <Bar dataKey="actual" name={t("sdk.actual")} isAnimationActive={false} fill="var(--c1)" />
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

export function App() {
  const { t, n } = useFmt();
  useEffect(() => { document.title = t("app.title"); }, [t]);
  const [data, error] = useJson<Data>(window.SIGNALDOCK_RESULTS, "./index.json", t("app.noResults", { command: SUMMARIZE_COMMAND }));
  const [sdk, sdkError] = useJson<SdkData>(window.SIGNALDOCK_SDK_RESULTS, "./sdk-index.json", t("sdk.noResults", { command: SDK_COMMAND }));
  const [id, setId] = useState("");
  const header = (
    <>
      <h1>{t("app.title")}</h1>
      <LanguageSwitch />
    </>
  );
  if (!data) return <main>{header}<p>{error || t("app.loading")}</p><SdkResults data={sdk} error={sdkError} /></main>;
  const current = data.runs.find((r) => r.run_id === id) ?? data.runs[data.runs.length - 1];
  const overload = data.overload ?? [];
  return (
    <main>
      {header}
      <p className="note">{t("app.source")}</p>
      <section>
        <h2>{t("runs.title")}</h2>
        <table>
          <thead><tr><th>{t("runs.run")}</th><th>{t("runs.status")}</th><th>{t("runs.combination")}</th><th>{t("runs.pool")}</th><th>{t("runs.rep")}</th><th>{t("runs.injection")}</th><th>{t("runs.dropped")}</th><th>{t("runs.nonOk")}</th><th>{t("runs.skippedTicks")}</th></tr></thead>
          <tbody>{data.runs.map((r) => (
            <tr key={r.run_id} className={r.run_id === current.run_id ? "sel" : ""}>
              <td><button onClick={() => setId(r.run_id)}>{r.run_id}</button></td><td><Badge r={r} /></td><td>{comboLabel(t, n, r)}</td><td>{r.pool_size}</td><td>{r.repetition}</td>
              <td>{r.injection.started} / {r.injection.scheduled}</td><td>{r.injection.dropped}</td>
              <td>{Object.keys(r.non_ok_outcomes).length ? t("common.yes") : "0"}</td><td>{Object.values(r.skipped_ticks).reduce((a, b) => a + b, 0)}</td>
            </tr>))}</tbody>
        </table>
      </section>
      {overload.map((o) => <OverloadRun key={o.run_id} o={o} />)}
      <SdkResults data={sdk} error={sdkError} />
      {current && <Compare runs={data.runs} targets={data.targets_p95_ms} />}
      {current && <Detail r={current} />}
    </main>
  );
}
