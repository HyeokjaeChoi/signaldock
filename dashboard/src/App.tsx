import { useEffect, useState } from "react";
import { NameChart, TimeChart } from "./Charts";
import { CopyButton, Details } from "./Details";
import { realApi } from "./api";
import type { Api, Filters, Sort } from "./api";
import { dateIn, resolvePeriod } from "./period";
import type { Preset } from "./period";
import { usePolledQuery } from "./polling";
import type { QueryState } from "./polling";
import { errorText } from "./errorText";
import { useTranslation } from "./i18n";
import type { T } from "./i18n";
import { LANGUAGES, formatBucketLabel, formatClock, formatDateTime } from "../../web-shared/i18n";
import type { Language } from "../../web-shared/i18n";

// Project timezone (Q78, Q79). Compose passes SIGNALDOCK_TIME_ZONE as a build argument.
export const PROJECT_TIME_ZONE: string = import.meta.env.VITE_SIGNALDOCK_TIME_ZONE ?? "Asia/Seoul";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
interface Draft { name: string; installationId: string; eventId: string }
const EMPTY: Draft = { name: "", installationId: "", eventId: "" };

function Status<D>({ label, state, tz, onRefresh }: { label: string; state: QueryState<D>; tz: string; onRefresh: () => void }) {
  const { t, i18n } = useTranslation();
  const clock = (ms: number) => formatClock(ms, tz, i18n.language);
  return (
    <div className="status">
      <strong>{label}</strong>:{" "}
      {state.lastSuccessAt ? t("status.lastSuccess", { time: clock(state.lastSuccessAt) }) : state.loading ? t("status.loading") : t("status.noData")}
      {state.error && (
        <span className="error" role="alert">
          {" | "}
          {t("status.queryFailed", { message: errorText(t, state.error) })}
          {state.stopped
            ? ` | ${t("status.stopped")} `
            : state.nextEligibleAt
              ? ` | ${t("status.nextAttempt", { time: clock(state.nextEligibleAt) })} `
              : " "}
          {state.stopped ? (
            <button type="button" onClick={() => window.location.reload()}>{t("status.reload")}</button>
          ) : (
            <button type="button" onClick={onRefresh}>{t("status.retry")}</button>
          )}
        </span>
      )}
    </div>
  );
}

function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  return (
    <label className="lang">
      {t("language.label")}{" "}
      <select value={i18n.language} onChange={(e) => i18n.changeLanguage(e.target.value as Language)}>
        {LANGUAGES.map((l) => <option key={l} value={l}>{t(`language.${l}`)}</option>)}
      </select>
    </label>
  );
}

const periodMessage = (t: T, p: { key: "period.chooseDates" | "period.endBeforeStart" | "period.tooLong"; count?: number }) =>
  t(p.key, p.count === undefined ? undefined : { count: p.count });

export function App({ api = realApi }: { api?: Api }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.language;
  const tz = PROJECT_TIME_ZONE;
  useEffect(() => { document.title = t("app.title"); }, [t]);
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
  const [filterError, setFilterError] = useState<"filter.installationIdUuid" | "filter.eventIdUuid" | "">("");
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
    if (draft.installationId && !UUID.test(draft.installationId)) return setFilterError("filter.installationIdUuid");
    if (draft.eventId && !UUID.test(draft.eventId)) return setFilterError("filter.eventIdUuid");
    setFilterError("");
    setApplied(draft);
    resetPaging();
  };
  const refreshAll = () => {
    list.refresh();
    byName.refresh();
    byTime.refresh();
  };
  const timeLabel = (iso: string, bucket: string) => formatBucketLabel(iso, bucket === "hour", tz, lang);

  const page = list.state.data;
  const today = dateIn(now, tz);
  return (
    <main>
      <header>
        <h1>{t("app.title")}</h1>
        <LanguageSwitch />
        <p className="note">
          {t("app.note", { tz, today })}
          {byTime.state.data && byTime.state.data.timeZone !== tz && ` ${t("app.tzMismatch", { serverTz: byTime.state.data.timeZone, tz })}`}
          {" "}{t("app.separateQueries")}
        </p>
      </header>

      <section className="controls" aria-label={t("controls.label")}>
        <label>
          {t("controls.period")}{" "}
          <select value={preset} onChange={(e) => { setPreset(e.target.value as Preset); resetPaging(); }}>
            <option value="today">{t("controls.today")}</option>
            <option value="7d">{t("controls.last7")}</option>
            <option value="30d">{t("controls.last30")}</option>
            <option value="custom">{t("controls.custom")}</option>
          </select>
        </label>
        {preset === "custom" && (
          <>
            <label>{t("controls.from")} <input type="date" value={customStart} onChange={(e) => { setCustomStart(e.target.value); resetPaging(); }} /></label>
            <label>{t("controls.to")} <input type="date" value={customEnd} onChange={(e) => { setCustomEnd(e.target.value); resetPaging(); }} /></label>
          </>
        )}
        <label>
          {t("controls.sort")}{" "}
          <select value={sort} onChange={(e) => { setSort(e.target.value as Sort); resetPaging(); }}>
            <option value="receivedAt_desc">{t("controls.newest")}</option>
            <option value="receivedAt_asc">{t("controls.oldest")}</option>
          </select>
        </label>
        <button type="button" onClick={refreshAll}>{t("controls.refresh")}</button>
        <form onSubmit={applyFilters}>
          <label>{t("controls.eventName")} <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
          <label>installationId <input value={draft.installationId} onChange={(e) => setDraft({ ...draft, installationId: e.target.value })} /></label>
          <label>eventId <input value={draft.eventId} onChange={(e) => setDraft({ ...draft, eventId: e.target.value })} /></label>
          <button type="submit">{t("controls.apply")}</button>
          <button type="button" onClick={() => { setDraft(EMPTY); setApplied(EMPTY); setFilterError(""); resetPaging(); }}>{t("controls.clear")}</button>
          <span className="note"> {t("controls.exactOnly")}</span>
        </form>
        {filterError && <p className="error" role="alert">{t(filterError)}</p>}
        {!period.ok && <p className="error" role="alert">{periodMessage(t, period)}</p>}
      </section>

      <Status label={t("status.events")} state={list.state} tz={tz} onRefresh={list.refresh} />
      <Status label={t("status.byName")} state={byName.state} tz={tz} onRefresh={byName.refresh} />
      <Status label={t("status.byTime")} state={byTime.state} tz={tz} onRefresh={byTime.refresh} />

      <div className="grid">
        <section className="panel" aria-label={t("counts.byName")}>
          <h2>{t("counts.byName")}</h2>
          {byName.state.data && (byName.state.data.items.length ? <NameChart counts={byName.state.data} /> : <p>{t("counts.empty")}</p>)}
        </section>
        <section className="panel" aria-label={t("status.byTime")}>
          <h2>{t("counts.byTime", { bucket: byTime.state.data ? t(`buckets.${byTime.state.data.bucket}`) : "-" })}</h2>
          {byTime.state.data && <TimeChart counts={byTime.state.data} label={timeLabel} />}
        </section>
      </div>

      <section className="panel" aria-label={t("list.label")}>
        <h2>{t("list.title", { page: cursors.length + 1 })}</h2>
        <p className="note">{t("list.note")}</p>
        {page && page.items.length === 0 && <p>{t("list.empty")}</p>}
        {page && page.items.length > 0 && (
          <table>
            <thead>
              <tr><th>receivedAt</th><th>{t("list.name")}</th><th>{t("list.eventId")}</th><th>{t("list.installationId")}</th><th>{t("list.sdk")}</th></tr>
            </thead>
            <tbody>
              {page.items.map((ev) => (
                <tr key={ev.eventId} className={ev.eventId === selected ? "selected" : undefined}>
                  <td>{formatDateTime(ev.receivedAt, tz, lang)}</td>
                  <td><button type="button" className="link" onClick={() => setSelected(ev.eventId)}>{ev.name}</button></td>
                  <td><code>{ev.eventId.slice(0, 8)}</code> <CopyButton text={ev.eventId} label={t("copy.eventIdOf", { id: ev.eventId })} /></td>
                  <td><code>{ev.installationId.slice(0, 8)}</code> <CopyButton text={ev.installationId} label={t("copy.installationIdOf", { id: ev.installationId })} /></td>
                  <td>{ev.sdkVersion}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p>
          <button type="button" disabled={cursors.length === 0} onClick={() => setCursors(cursors.slice(0, -1))}>{t("list.previous")}</button>{" "}
          <button type="button" disabled={cursors.length === 0} onClick={resetPaging}>{t("list.first")}</button>{" "}
          <button type="button" disabled={!page?.nextCursor} onClick={() => page?.nextCursor && setCursors([...cursors, page.nextCursor])}>{t("list.next")}</button>
        </p>
      </section>

      {selected && <Details api={api} eventId={selected} timeZone={tz} onClose={() => setSelected(undefined)} />}
    </main>
  );
}
