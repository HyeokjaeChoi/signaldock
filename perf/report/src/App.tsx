import { useEffect, useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

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

declare global { interface Window { SIGNALDOCK_RESULTS?: Data } }

const EPS = ["collect", "list", "by-name", "by-time"];
const comboKey = (r: Run) => `${r.combination.seed_size} rows · ${r.combination.distribution} · ${r.combination.window_days} d · ${r.combination.clients} clients`;
const runLabel = (r: Run) => `pool ${r.pool_size} · rep ${r.repetition}${r.counted ? "" : " · pilot"}`;
const f1 = (v: number | null | undefined) => (v == null ? "n/a" : v.toFixed(1));

function Badge({ r }: { r: Run }) {
  return (
    <>
      <span className={`badge ${r.verdict}`}>{r.verdict}</span>
      {!r.counted && <span className="badge pilot">pilot: not counted</span>}
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
  const combos = useMemo(() => [...new Set(runs.map(comboKey))], [runs]);
  const [combo, setCombo] = useState(combos[0]);
  const sel = runs.filter((r) => comboKey(r) === combo).sort((a, b) => a.pool_size - b.pool_size || a.repetition - b.repetition || a.run_id.localeCompare(b.run_id));
  return (
    <section>
      <h2>Latency per run and pool</h2>
      <p className="note">One bar group per run (measurement phase only). Repetitions are separate runs; percentiles of different runs are never averaged. Dashed line: p95 target.</p>
      <label>Combination <select value={combo} onChange={(e) => setCombo(e.target.value)}>{combos.map((c) => <option key={c}>{c}</option>)}</select></label>
      <div className="grid">
        {EPS.map((ep) => (
          <Chart key={ep} title={`${ep} (ms, target p95 ${targets[ep]} ms)`}>
            <BarChart data={sel.map((r) => ({ name: `${runLabel(r)} (${r.run_id})`, p50: r.endpoints[ep].p50_ms, p95: r.endpoints[ep].p95_ms, max: r.endpoints[ep].max_ms }))}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="name" tick={{ fontSize: 10 }} interval={0} />
              <YAxis />
              <Tooltip />
              <Legend />
              <ReferenceLine y={targets[ep]} ifOverflow="extendDomain" stroke="var(--bad)" strokeDasharray="6 4" label={{ value: `${targets[ep]} ms`, fontSize: 11, fill: "var(--bad)" }} />
              <Bar dataKey="p50" isAnimationActive={false} fill="var(--c1)" />
              <Bar dataKey="p95" isAnimationActive={false} fill="var(--c2)" />
              <Bar dataKey="max" isAnimationActive={false} fill="var(--c3)" />
            </BarChart>
          </Chart>
        ))}
      </div>
    </section>
  );
}

function Detail({ r }: { r: Run }) {
  const nonOk = Object.entries(r.non_ok_outcomes);
  const skipped = Object.entries(r.skipped_ticks).filter(([, n]) => n);
  const top = r.statements?.top_by_total_time ?? [];
  return (
    <section>
      <h2>{r.run_id} <Badge r={r} /></h2>
      <p className="note">{comboKey(r)} · pool {r.pool_size} · repetition {r.repetition} · warm-up {r.warmup_s} s, measurement {r.measure_s} s · git {r.git.revision}{r.git.dirty ? " (dirty)" : ""}</p>
      {[...r.invalid_reasons.map((x) => `invalid: ${x}`), ...r.fail_reasons.map((x) => `fail: ${x}`)].map((x) => <p key={x} className="reason">{x}</p>)}
      <h3>Latency (ms, measurement)</h3>
      <table>
        <thead><tr><th>Endpoint</th><th>samples</th><th>p50</th><th>p95</th><th>max</th><th>target p95</th><th>outcomes</th></tr></thead>
        <tbody>{EPS.map((ep) => { const x = r.endpoints[ep]; return (
          <tr key={ep}><td>{ep}</td><td>{x.samples}</td><td>{f1(x.p50_ms)}</td><td className={x.p95_ms != null && x.p95_ms > x.target_p95_ms ? "bad" : ""}>{f1(x.p95_ms)}</td><td>{f1(x.max_ms)}</td><td>{x.target_p95_ms}</td><td>{JSON.stringify(x.outcomes)}</td></tr>); })}</tbody>
      </table>
      <h3>Injection validity and errors</h3>
      <ul>
        <li className={r.injection.started === r.injection.scheduled && !r.injection.dropped ? "" : "bad"}>
          Collection iterations: started {r.injection.started} of {r.injection.scheduled} scheduled, dropped {r.injection.dropped}
        </li>
        <li className={nonOk.length ? "bad" : ""}>Non-ok responses in measurement: {nonOk.length ? JSON.stringify(Object.fromEntries(nonOk)) : "none"}</li>
        <li className={skipped.length ? "bad" : ""}>Skipped Dashboard ticks in measurement: {skipped.length ? JSON.stringify(Object.fromEntries(skipped)) : "none"}</li>
      </ul>
      <h3>Hikari pool (1 s samples, seconds since test start; measurement starts at {r.warmup_s} s)</h3>
      {r.hikari_series.length ? (
        <Chart title="active / idle / pending connections">
          <LineChart data={r.hikari_series.map(([t, active, idle, pending]) => ({ t, active, idle, pending }))}>
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
      ) : <p className="note">No pool samples (server.log is not committed and was not found when the index was written).</p>}
      <h3>Containers (docker stats, 5 s samples)</h3>
      <div className="grid">
        <Chart title="CPU % (limit: 200 % per container)">
          <LineChart data={r.container_series}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="t" type="number" domain={["dataMin", "dataMax"]} />
            <YAxis />
            <Tooltip />
            <Legend />
            <Line dataKey="server_cpu_pct" name="server" stroke="var(--c1)" dot={false} isAnimationActive={false} />
            <Line dataKey="db_cpu_pct" name="db" stroke="var(--c2)" dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
        <Chart title="Memory MiB (limits: server 1024, db 2048)">
          <LineChart data={r.container_series}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="t" type="number" domain={["dataMin", "dataMax"]} />
            <YAxis />
            <Tooltip />
            <Legend />
            <Line dataKey="server_mem_mib" name="server" stroke="var(--c1)" dot={false} isAnimationActive={false} />
            <Line dataKey="db_mem_mib" name="db" stroke="var(--c2)" dot={false} isAnimationActive={false} />
          </LineChart>
        </Chart>
      </div>
      <table>
        <caption>CPU throttling over the whole run (cgroup cpu.stat deltas)</caption>
        <thead><tr><th>Container</th><th>periods</th><th>throttled periods</th><th>throttled time (ms)</th></tr></thead>
        <tbody>{Object.entries(r.cgroup).map(([k, v]) => (
          <tr key={k}><td>{k}</td><td>{v.nr_periods ?? "n/a"}</td><td>{v.nr_throttled ?? "n/a"}</td><td>{v.throttled_usec == null ? "n/a" : f1(v.throttled_usec / 1000)}</td></tr>))}</tbody>
      </table>
      <h3>Top pg_stat_statements (by total time, measurement phase)</h3>
      {r.statements ? (
        <>
          <p className="note">{r.statements.statements} statements recorded; {r.statements.harness_statements} issued by the runner itself are left out here (they stay in statements.csv). Query text as PostgreSQL normalizes it.</p>
          <table>
            <thead><tr><th>calls</th><th>rows</th><th>total ms</th><th>mean ms</th><th>max ms</th><th>query</th></tr></thead>
            <tbody>{top.map((s, i) => (
              <tr key={i}><td>{s.calls}</td><td>{s.rows}</td><td>{f1(s.total_exec_ms)}</td><td>{f1(s.mean_exec_ms)}</td><td>{f1(s.max_exec_ms)}</td><td><code>{s.query}</code></td></tr>))}</tbody>
          </table>
        </>
      ) : <p className="note">Not recorded for this run (harness without pg_stat_statements).</p>}
    </section>
  );
}

const PHASES = ["baseline", "overload", "recovery"];
const OEPS = ["collect", "probe", "heavy"];
const counts = (o: Record<string, number>) => (Object.keys(o).length ? Object.entries(o).map(([k, v]) => `${k}: ${v}`).join(", ") : "-");

function OverloadRun({ o }: { o: Overload }) {
  const p = o.parameters;
  const rc = o.reconciliation;
  return (
    <section>
      <h2>Overload run {o.run_id} <span className={`badge ${o.verdict}`}>{o.verdict}</span></h2>
      <p className="note">Q133: the server is saturated on purpose with concurrency {p.concurrency} and admission wait {p.admission_ms} ms (pool {p.pool_size}, {p.seed_size} rows, {p.dist}, {p.window_days} d). Phases: baseline {p.base_s} s, overload {p.over_s} s, recovery {p.rec_s} s, no server restart. Collection {p.rate} rps, {p.over_rate} rps in the overload phase; probe (list) 1 rps; heavy (by-name) {p.heavy_rate} rps in the overload phase only. Not an SLA and not a pool choice. git {o.git.revision}{o.git.dirty ? " (dirty)" : ""}.</p>
      {[...o.invalid_reasons.map((x) => `invalid: ${x}`), ...o.fail_reasons.map((x) => `fail: ${x}`)].map((x) => <p key={x} className="reason">{x}</p>)}
      <h3>Per phase</h3>
      <table>
        <thead><tr><th>Phase</th><th>Endpoint</th><th>requests</th><th>status counts</th><th>Retry-After (s)</th><th>error codes</th><th>p50</th><th>p95</th><th>p95 of 200</th><th>max</th></tr></thead>
        <tbody>{PHASES.flatMap((ph) => OEPS.filter((ep) => o.phases[ph]?.[ep]).map((ep) => { const x = o.phases[ph][ep]; return (
          <tr key={ph + ep}><td>{ph}</td><td>{ep}</td><td>{x.requests}</td><td>{counts(x.status)}</td><td>{counts(x.retry_after)}</td><td>{counts(x.error_codes)}</td><td>{f1(x.p50_ms)}</td><td>{f1(x.p95_ms)}</td><td>{f1(x.p95_ok_ms)}</td><td>{f1(x.max_ms)}</td></tr>); }))}</tbody>
      </table>
      <h3>Status codes over time (per 5 s of request start; overload phase from {p.base_s} s to {p.base_s + p.over_s} s)</h3>
      <div className="grid">
        {OEPS.map((ep) => (
          <Chart key={ep} title={`${ep}: responses per 5 s`}>
            <BarChart data={o.series_5s.filter((r) => r[1] === ep).map(([t, , n, s200, s503, other]) => ({ t, "200": s200, "503": s503, other, n }))}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="t" />
              <YAxis allowDecimals={false} />
              <Tooltip />
              <Legend />
              <Bar dataKey="200" stackId="a" isAnimationActive={false} fill="var(--ok)" />
              <Bar dataKey="503" stackId="a" isAnimationActive={false} fill="var(--c2)" />
              <Bar dataKey="other" stackId="a" isAnimationActive={false} fill="var(--bad)" />
            </BarChart>
          </Chart>
        ))}
        <Chart title="collect: p95 latency per 5 s (ms, all answers)">
          <LineChart data={o.series_5s.filter((r) => r[1] === "collect").map(([t, , , , , , p95]) => ({ t, p95 }))}>
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
      <h3>Recovery and storage</h3>
      <ul>
        <li>First all-200 collection window starts {o.recovery.first_clean_5s_window_after_overload_s} s after the overload phase ended.</li>
        <li>Last 30 s: {o.recovery.last_30s_requests} collection/probe requests, {o.recovery.last_30s_non_200} not 200; collection p95 {f1(o.recovery.last_30s_collect_p95_ms)} ms (baseline {f1(o.recovery.baseline_collect_p95_ms)} ms, limit {f1(o.recovery.limit_ms)} ms).</li>
        <li>ACKed (200) collection requests: {rc.ok_requests}, not fully stored: {rc.ok_requests_not_fully_stored}. Shed (503): {rc.shed_requests}, rows stored for them: {rc.rows_stored_for_shed_requests}. Other answers: {rc.other_requests}. Duplicate IDs in DB: {rc.duplicate_ids_in_db}. Dropped k6 iterations: {o.dropped_iterations}.</li>
      </ul>
    </section>
  );
}

export function App() {
  const [data, setData] = useState<Data | null>(window.SIGNALDOCK_RESULTS ?? null);
  const [error, setError] = useState("");
  const [id, setId] = useState("");
  useEffect(() => {
    if (data) return;
    fetch("./index.json").then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status))))).then(setData)
      .catch(() => setError("No results found. Run: python3 perf/summarize.py perf/results/*/ --out perf/results/report.md --index perf/results/index.json, then npm run build."));
  }, [data]);
  if (!data) return <main><p>{error || "Loading..."}</p></main>;
  const current = data.runs.find((r) => r.run_id === id) ?? data.runs[data.runs.length - 1];
  const overload = data.overload ?? [];
  return (
    <main>
      <h1>SignalDock server load report</h1>
      <p className="note">Source: perf/results/index.json. Every value belongs to one run. There is no overall p95: percentiles of different runs are never averaged. Pilot runs are a local environment record, not an SLA and not a pool choice (Q133). The overload run shows 503 load shedding.</p>
      <section>
        <h2>Runs</h2>
        <table>
          <thead><tr><th>Run</th><th>Status</th><th>Combination</th><th>Pool</th><th>Rep</th><th>Injection</th><th>Dropped</th><th>Non-ok</th><th>Skipped ticks</th></tr></thead>
          <tbody>{data.runs.map((r) => (
            <tr key={r.run_id} className={r.run_id === current.run_id ? "sel" : ""}>
              <td><button onClick={() => setId(r.run_id)}>{r.run_id}</button></td><td><Badge r={r} /></td><td>{comboKey(r)}</td><td>{r.pool_size}</td><td>{r.repetition}</td>
              <td>{r.injection.started} / {r.injection.scheduled}</td><td>{r.injection.dropped}</td>
              <td>{Object.keys(r.non_ok_outcomes).length ? "yes" : "0"}</td><td>{Object.values(r.skipped_ticks).reduce((a, b) => a + b, 0)}</td>
            </tr>))}</tbody>
        </table>
      </section>
      {overload.map((o) => <OverloadRun key={o.run_id} o={o} />)}
      {current && <Compare runs={data.runs} targets={data.targets_p95_ms} />}
      {current && <Detail r={current} />}
    </main>
  );
}
