import { useEffect, useRef, useState } from "react";
import { NameChart, TimeChart } from "./Charts";
import { CopyButton, Details } from "./Details";
import { realApi } from "./api";
import type { Api, Filters, Sort } from "./api";
import { dateIn, resolvePeriod } from "./period";
import type { Preset } from "./period";
import { usePolledQuery } from "./polling";
import type { QueryState } from "./polling";
import { errorText } from "./errorText";
import { lastReceipt, queryHealth, receivedOnDay, topEvent } from "./kpi";
import type { Health } from "./kpi";
import { useTranslation } from "./i18n";
import type { T } from "./i18n";
import { LANGUAGES, formatBucketLabel, formatClock, formatDate, formatDateTime, formatNumber } from "../../web-shared/i18n";
import type { Language } from "../../web-shared/i18n";
import { THEMES, useTheme } from "../../web-shared/theme";
import type { Theme } from "../../web-shared/theme";

// Project timezone (Q78, Q79). Compose passes SIGNALDOCK_TIME_ZONE as a build argument.
export const PROJECT_TIME_ZONE: string = import.meta.env.VITE_SIGNALDOCK_TIME_ZONE ?? "Asia/Seoul";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
interface Draft { name: string; installationId: string; eventId: string }
const EMPTY: Draft = { name: "", installationId: "", eventId: "" };

const HEALTH_BADGE: Record<Health, string> = { live: "ok", paused: "idle", loading: "idle", retrying: "warn", stopped: "bad", idle: "idle" };

/** A separator is drawn for the eye only; a screen reader does not read it (issue 29). */
const Sep = () => <span aria-hidden="true"> | </span>;

function Status<D>({ label, state, tz, onRefresh, polled = true }: { label: string; state: QueryState<D>; tz: string; onRefresh: () => void; polled?: boolean }) {
  const { t, i18n } = useTranslation();
  const clock = (ms: number) => formatClock(ms, tz, i18n.language);
  const { health } = queryHealth([state], polled);
  return (
    <div className="status">
      {health !== "live" && <span className={`badge ${HEALTH_BADGE[health]}`}>{t(`kpi.${health}`)}</span>}
      <strong>{label}</strong>:{" "}
      {state.lastSuccessAt ? t("status.lastSuccess", { time: clock(state.lastSuccessAt) }) : state.loading ? t("status.loading") : t("status.noData")}
      {state.error && (
        <span className="error" role="alert">
          <Sep />
          {t("status.queryFailed", { message: errorText(t, state.error) })}
          {state.stopped
            ? <><Sep />{t("status.stopped")} </>
            : state.nextEligibleAt
              ? <><Sep />{t("status.nextAttempt", { time: clock(state.nextEligibleAt) })} </>
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

function Kpi({ label, value, sub, className }: { label: string; value: React.ReactNode; sub?: React.ReactNode; className?: string }) {
  return (
    <div className={`card kpi ${className ?? ""}`}>
      <span className="kpi-label">{label}</span>
      <span className="kpi-value">{value}</span>
      {sub && <span className="kpi-sub">{sub}</span>}
    </div>
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
  // The event links by id: Close returns focus to the link that opened the details (issue 29).
  const rowLinks = useRef(new Map<string, HTMLButtonElement>());
  const closeDetails = () => {
    const id = selected;
    setSelected(undefined);
    if (id) rowLinks.current.get(id)?.focus();
  };

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
  const applyFilters = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    // Move focus to the invalid field, so a keyboard or screen reader user lands on it.
    const invalid = (field: "installationId" | "eventId", key: "filter.installationIdUuid" | "filter.eventIdUuid") => {
      setFilterError(key);
      (form.elements.namedItem(field) as HTMLInputElement | null)?.focus();
    };
    if (draft.installationId && !UUID.test(draft.installationId)) return invalid("installationId", "filter.installationIdUuid");
    if (draft.eventId && !UUID.test(draft.eventId)) return invalid("eventId", "filter.eventIdUuid");
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
  const today = formatDate(now, tz, lang);
  const filtered = Boolean(applied.name || applied.installationId || applied.eventId);

  // KPI values: derived from the three queries above (Q140). No extra request.
  const receivedToday = receivedOnDay(byTime.state.data, dateIn(now, tz), tz);
  const lastAt = lastReceipt(page, sort, cursors.length === 0);
  const top = topEvent(byName.state.data);
  const { health, nextEligibleAt } = queryHealth([list.state, byName.state, byTime.state], [cursors.length === 0, true, true]);
  const lastSuccess = Math.max(0, list.state.lastSuccessAt ?? 0, byName.state.lastSuccessAt ?? 0, byTime.state.lastSuccessAt ?? 0);
  const clock = (ms: number) => formatClock(ms, tz, lang);

  // Scroll offset = rendered toolbar height (it wraps and grows with errors), so focus never lands under it.
  const toolbarRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const el = toolbarRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const root = document.documentElement;
    const observer = new ResizeObserver(() => root.style.setProperty("--toolbar-height", `${el.offsetHeight + 16}px`));
    observer.observe(el);
    return () => {
      observer.disconnect();
      root.style.removeProperty("--toolbar-height");
    };
  }, []);

  return (
    <>
    <a className="skip-link" href="#main">{t("app.skip")}</a>
    <main id="main" tabIndex={-1}>
      <header className="page-header">
        <h1>{t("app.title")}</h1>
        <div className="switches">
          <ThemeSwitch />
          <LanguageSwitch />
        </div>
        <p className="note">
          {t("app.note", { tz, today })}
          {byTime.state.data && byTime.state.data.timeZone !== tz && ` ${t("app.tzMismatch", { serverTz: byTime.state.data.timeZone, tz })}`}
          {" "}{t("app.separateQueries")}
        </p>
      </header>

      <section className="kpis" aria-label={t("kpi.label")}>
        <Kpi label={t("kpi.today")} value={receivedToday === undefined ? <span className="kpi-none">{t("kpi.none")}</span> : formatNumber(receivedToday, lang)} sub={filtered ? t("kpi.todayHint") : undefined} />
        <Kpi label={t("kpi.lastReceipt")} value={lastAt === undefined ? <span className="kpi-none">{t("kpi.none")}</span> : formatClock(lastAt, tz, lang)} sub={lastAt === undefined ? t("kpi.lastReceiptHint") : formatDate(lastAt, tz, lang)} />
        <Kpi
          label={t("kpi.state")}
          value={<span className={`badge ${HEALTH_BADGE[health]}`}>{t(`kpi.${health}`)}</span>}
          sub={
            health === "live" || health === "paused" ? t("kpi.liveSub", { time: clock(lastSuccess) })
            : health === "retrying" ? (nextEligibleAt ? t("kpi.retrySub", { time: clock(nextEligibleAt) }) : undefined)
            : health === "stopped" ? t("kpi.stoppedSub")
            : undefined
          }
        />
        <Kpi label={t("kpi.top")} value={top ? <code className="kpi-name">{top.name}</code> : <span className="kpi-none">{t("kpi.none")}</span>} sub={top ? t("kpi.topCount", { value: formatNumber(top.count, lang) }) : undefined} />
      </section>

      <section ref={toolbarRef} className="toolbar card" aria-label={t("controls.label")}>
        <div className="toolbar-row">
          <label className="field">
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
              <label className="field">{t("controls.from")} <input type="date" value={customStart} onChange={(e) => { setCustomStart(e.target.value); resetPaging(); }} /></label>
              <label className="field">{t("controls.to")} <input type="date" value={customEnd} onChange={(e) => { setCustomEnd(e.target.value); resetPaging(); }} /></label>
            </>
          )}
          <label className="field">
            {t("controls.sort")}{" "}
            <select value={sort} onChange={(e) => { setSort(e.target.value as Sort); resetPaging(); }}>
              <option value="receivedAt_desc">{t("controls.newest")}</option>
              <option value="receivedAt_asc">{t("controls.oldest")}</option>
            </select>
          </label>
          <form onSubmit={applyFilters} className="toolbar-form">
            <label className="field">{t("controls.eventName")} <input name="eventName" autoComplete="off" spellCheck={false} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
            <label className="field">installationId <input name="installationId" autoComplete="off" spellCheck={false} aria-invalid={filterError === "filter.installationIdUuid" || undefined} aria-describedby={filterError === "filter.installationIdUuid" ? "filter-error" : undefined} value={draft.installationId} onChange={(e) => setDraft({ ...draft, installationId: e.target.value })} /></label>
            <label className="field">eventId <input name="eventId" autoComplete="off" spellCheck={false} aria-invalid={filterError === "filter.eventIdUuid" || undefined} aria-describedby={filterError === "filter.eventIdUuid" ? "filter-error" : undefined} value={draft.eventId} onChange={(e) => setDraft({ ...draft, eventId: e.target.value })} /></label>
            <div className="toolbar-actions">
              <button type="submit" className="primary">{t("controls.apply")}</button>
              <button type="button" onClick={() => { setDraft(EMPTY); setApplied(EMPTY); setFilterError(""); resetPaging(); }}>{t("controls.clear")}</button>
              <button type="button" onClick={refreshAll}>{t("controls.refresh")}</button>
            </div>
          </form>
          <p className="note toolbar-note">{t("controls.exactOnly")}</p>
        </div>
        {filterError && <p id="filter-error" className="error" role="alert">{t(filterError)}</p>}
        {!period.ok && <p className="error" role="alert">{periodMessage(t, period)}</p>}
      </section>

      <div className="statusbar">
        <Status label={t("status.events")} state={list.state} tz={tz} onRefresh={list.refresh} polled={cursors.length === 0} />
        <Status label={t("status.byName")} state={byName.state} tz={tz} onRefresh={byName.refresh} />
        <Status label={t("status.byTime")} state={byTime.state} tz={tz} onRefresh={byTime.refresh} />
      </div>

      <div className="split">
        <section className="card list-card" aria-label={t("list.label")}>
          <h2>{t("list.title", { page: formatNumber(cursors.length + 1, i18n.language) })}</h2>
          <p className="note">{t("list.note")}</p>
          {page && page.items.length === 0 && <p>{t("list.empty")}</p>}
          {page && page.items.length > 0 && (
            <div className="table-wrap pane-scroll">
              <table>
                <caption className="sr-only">{t("list.title", { page: formatNumber(cursors.length + 1, i18n.language) })}</caption>
                <thead>
                  <tr><th scope="col">receivedAt</th><th scope="col">{t("list.name")}</th><th scope="col">{t("list.eventId")}</th><th scope="col">{t("list.installationId")}</th><th scope="col">{t("list.sdk")}</th></tr>
                </thead>
                <tbody>
                  {page.items.map((ev) => (
                    <tr key={ev.eventId} className={ev.eventId === selected ? "selected" : undefined}>
                      <td>{formatDateTime(ev.receivedAt, tz, lang)}</td>
                      <td><button type="button" className="link" ref={(el) => { if (el) rowLinks.current.set(ev.eventId, el); else rowLinks.current.delete(ev.eventId); }} aria-current={ev.eventId === selected ? "true" : undefined} onClick={() => setSelected(ev.eventId)}>{ev.name}</button></td>
                      <td><code>{ev.eventId.slice(0, 8)}</code> <CopyButton text={ev.eventId} label={t("copy.eventIdOf", { id: ev.eventId })} /></td>
                      <td><code>{ev.installationId.slice(0, 8)}</code> <CopyButton text={ev.installationId} label={t("copy.installationIdOf", { id: ev.installationId })} /></td>
                      <td>{ev.sdkVersion}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="pager">
            <button type="button" disabled={cursors.length === 0} onClick={() => setCursors(cursors.slice(0, -1))}>{t("list.previous")}</button>{" "}
            <button type="button" disabled={cursors.length === 0} onClick={resetPaging}>{t("list.first")}</button>{" "}
            <button type="button" disabled={!page?.nextCursor} onClick={() => page?.nextCursor && setCursors([...cursors, page.nextCursor])}>{t("list.next")}</button>
          </p>
        </section>

        <aside className="detail-pane">
          {selected ? (
            <Details api={api} eventId={selected} timeZone={tz} onClose={closeDetails} />
          ) : (
            <div className="card detail-empty"><p className="note">{t("details.select")}</p></div>
          )}
        </aside>
      </div>

      <div className="grid">
        <section className="card chart-card" aria-label={t("counts.byName")}>
          <h2>{t("counts.byName")}</h2>
          {byName.state.data && (byName.state.data.items.length ? <NameChart counts={byName.state.data} /> : <p>{t("counts.empty")}</p>)}
        </section>
        <section className="card chart-card" aria-label={t("status.byTime")}>
          <h2>{t("counts.byTime", { bucket: byTime.state.data ? t(`buckets.${byTime.state.data.bucket}`) : "-" })}</h2>
          {byTime.state.data && <TimeChart counts={byTime.state.data} label={timeLabel} />}
        </section>
      </div>
    </main>
    </>
  );
}
