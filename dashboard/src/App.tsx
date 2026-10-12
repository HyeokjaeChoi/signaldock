import { useEffect, useState } from "react";
import { NameChart, TimeChart } from "./Charts";
import { CopyButton, Details } from "./Details";
import { realApi } from "./api";
import type { Api, Filters, Sort } from "./api";
import { dateIn, formatInstant, resolvePeriod } from "./period";
import type { Preset } from "./period";
import { usePolledQuery } from "./polling";
import type { QueryState } from "./polling";

// Project timezone (Q78, Q79). Compose passes SIGNALDOCK_TIME_ZONE as a build argument.
export const PROJECT_TIME_ZONE: string = import.meta.env.VITE_SIGNALDOCK_TIME_ZONE ?? "Asia/Seoul";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
interface Draft { name: string; installationId: string; eventId: string }
const EMPTY: Draft = { name: "", installationId: "", eventId: "" };

const clock = (ms: number, tz: string) => new Intl.DateTimeFormat("en-GB", { timeZone: tz, hourCycle: "h23", timeStyle: "medium" }).format(ms);

function Status<T>({ label, state, tz, onRefresh }: { label: string; state: QueryState<T>; tz: string; onRefresh: () => void }) {
  return (
    <div className="status">
      <strong>{label}</strong>:{" "}
      {state.lastSuccessAt ? `last success ${clock(state.lastSuccessAt, tz)}` : state.loading ? "loading..." : "no data yet"}
      {state.error && (
        <span className="error" role="alert">
          {" "}
          | query failed: {state.error.message}
          {state.stopped
            ? " | Updates stopped: this Dashboard and the server use different contract versions. Existing data is kept. "
            : state.nextEligibleAt
              ? ` | next attempt after ${clock(state.nextEligibleAt, tz)} `
              : " "}
          {state.stopped ? (
            <button type="button" onClick={() => window.location.reload()}>Reload page</button>
          ) : (
            <button type="button" onClick={onRefresh}>Retry</button>
          )}
        </span>
      )}
    </div>
  );
}

export function App({ api = realApi }: { api?: Api }) {
  const tz = PROJECT_TIME_ZONE;
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  const [preset, setPreset] = useState<Preset>("today");
  const [customStart, setCustomStart] = useState("");
  const [customEnd, setCustomEnd] = useState("");
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [applied, setApplied] = useState<Draft>(EMPTY);
  const [filterError, setFilterError] = useState("");
  const [sort, setSort] = useState<Sort>("receivedAt_desc");
  // Cursor stack: [] is the first page. Only the first page polls (Q77); a past page refreshes manually and never jumps to the latest page.
  const [cursors, setCursors] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | undefined>();

  const now = Date.now();
  const period = resolvePeriod(preset, customStart, customEnd, tz, now);
  const filters: Filters | undefined = period.ok
    ? {
        from: period.from,
        to: period.to,
        name: applied.name || undefined,
        installationId: applied.installationId || undefined,
        eventId: applied.eventId || undefined,
      }
    : undefined;
  const filterKey = filters ? JSON.stringify(filters) : null;
  const cursor = cursors.at(-1);

  const list = usePolledQuery(filterKey && `list|${filterKey}|${sort}|${cursor ?? ""}`, (s) => api.listEvents(filters!, sort, cursor, s), cursors.length === 0);
  const byName = usePolledQuery(filterKey && `name|${filterKey}`, (s) => api.countsByName(filters!, s));
  const byTime = usePolledQuery(filterKey && `time|${filterKey}`, (s) => api.countsByTime(filters!, s));

  const resetPaging = () => setCursors([]);
  const applyFilters = (e: React.FormEvent) => {
    e.preventDefault();
    if (draft.installationId && !UUID.test(draft.installationId)) return setFilterError("installationId must be a UUID.");
    if (draft.eventId && !UUID.test(draft.eventId)) return setFilterError("eventId must be a UUID.");
    setFilterError("");
    setApplied(draft);
    resetPaging();
  };
  const refreshAll = () => {
    list.refresh();
    byName.refresh();
    byTime.refresh();
  };
  const timeLabel = (iso: string, bucket: string) => {
    const t = formatInstant(iso, tz); // "dd/mm/yyyy, hh:mm:ss.mmm"
    return bucket === "hour" ? t.slice(12, 17) : t.slice(0, 5);
  };

  const page = list.state.data;
  const today = dateIn(now, tz);
  return (
    <main>
      <header>
        <h1>SignalDock Dashboard</h1>
        <p className="note">
          Stored server records by receivedAt, project timezone {tz} (today is {today}).
          {byTime.state.data && byTime.state.data.timeZone !== tz && ` The server reports ${byTime.state.data.timeZone}; period boundaries here use ${tz}.`}
          {" "}Counts and lists are separate queries and can reflect different times. They are not the SDK's ACK counts or queue state.
        </p>
      </header>

      <section className="controls" aria-label="Period and filters">
        <label>
          Period{" "}
          <select value={preset} onChange={(e) => { setPreset(e.target.value as Preset); resetPaging(); }}>
            <option value="today">Today</option>
            <option value="7d">Last 7 days</option>
            <option value="30d">Last 30 days</option>
            <option value="custom">Custom range</option>
          </select>
        </label>
        {preset === "custom" && (
          <>
            <label>From <input type="date" value={customStart} onChange={(e) => { setCustomStart(e.target.value); resetPaging(); }} /></label>
            <label>To <input type="date" value={customEnd} onChange={(e) => { setCustomEnd(e.target.value); resetPaging(); }} /></label>
          </>
        )}
        <label>
          Sort{" "}
          <select value={sort} onChange={(e) => { setSort(e.target.value as Sort); resetPaging(); }}>
            <option value="receivedAt_desc">Newest first</option>
            <option value="receivedAt_asc">Oldest first</option>
          </select>
        </label>
        <button type="button" onClick={refreshAll}>Refresh</button>
        <form onSubmit={applyFilters}>
          <label>Event name <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
          <label>installationId <input value={draft.installationId} onChange={(e) => setDraft({ ...draft, installationId: e.target.value })} /></label>
          <label>eventId <input value={draft.eventId} onChange={(e) => setDraft({ ...draft, eventId: e.target.value })} /></label>
          <button type="submit">Apply filters</button>
          <button type="button" onClick={() => { setDraft(EMPTY); setApplied(EMPTY); setFilterError(""); resetPaging(); }}>Clear</button>
          <span className="note"> Exact values only.</span>
        </form>
        {filterError && <p className="error" role="alert">{filterError}</p>}
        {!period.ok && <p className="error" role="alert">{period.message}</p>}
      </section>

      <Status label="Events" state={list.state} tz={tz} onRefresh={list.refresh} />
      <Status label="Counts by name" state={byName.state} tz={tz} onRefresh={byName.refresh} />
      <Status label="Counts by time" state={byTime.state} tz={tz} onRefresh={byTime.refresh} />

      <div className="grid">
        <section className="panel" aria-label="Counts by name">
          <h2>Counts by name</h2>
          {byName.state.data && (byName.state.data.items.length ? <NameChart counts={byName.state.data} /> : <p>No events received during this period</p>)}
        </section>
        <section className="panel" aria-label="Counts by time">
          <h2>Counts by time ({byTime.state.data?.bucket ?? "-"})</h2>
          {byTime.state.data && <TimeChart counts={byTime.state.data} label={timeLabel} />}
        </section>
      </div>

      <section className="panel" aria-label="Event list">
        <h2>Events, page {cursors.length + 1}</h2>
        <p className="note">
          One page is not the full event list. Only the first page updates by itself; use Refresh on other pages. The view never returns to the first page by itself.
        </p>
        {page && page.items.length === 0 && (
          <p>
            No events received during this period. Check the dates and filters, and generate events in the demo app.
          </p>
        )}
        {page && page.items.length > 0 && (
          <table>
            <thead>
              <tr><th>receivedAt</th><th>Name</th><th>Event ID</th><th>Installation ID</th><th>SDK</th></tr>
            </thead>
            <tbody>
              {page.items.map((ev) => (
                <tr key={ev.eventId} className={ev.eventId === selected ? "selected" : undefined}>
                  <td>{formatInstant(ev.receivedAt, tz)}</td>
                  <td><button type="button" className="link" onClick={() => setSelected(ev.eventId)}>{ev.name}</button></td>
                  <td><code>{ev.eventId.slice(0, 8)}</code> <CopyButton text={ev.eventId} label={`Copy event ID ${ev.eventId}`} /></td>
                  <td><code>{ev.installationId.slice(0, 8)}</code> <CopyButton text={ev.installationId} label={`Copy installation ID ${ev.installationId}`} /></td>
                  <td>{ev.sdkVersion}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p>
          <button type="button" disabled={cursors.length === 0} onClick={() => setCursors(cursors.slice(0, -1))}>Previous page</button>{" "}
          <button type="button" disabled={cursors.length === 0} onClick={resetPaging}>First page</button>{" "}
          <button type="button" disabled={!page?.nextCursor} onClick={() => page?.nextCursor && setCursors([...cursors, page.nextCursor])}>Next page</button>
        </p>
      </section>

      {selected && <Details api={api} eventId={selected} timeZone={tz} onClose={() => setSelected(undefined)} />}
    </main>
  );
}
