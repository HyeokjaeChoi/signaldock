// KPI cards (Q140, ADR-0016). Every value comes from data the page already fetched: no new request, no new API.
import type { EventNameCounts, EventPlanStatusCounts, EventReceiptPage, EventTimeCounts } from "../../generated/typescript/models";
import type { Sort } from "./api";
import { dateIn } from "./period";
import type { QueryState } from "./polling";

/** Events received on calendar date `day` (`YYYY-MM-DD`, project timezone): the sum of by-time buckets that start on that date.
 *  `undefined` when the shown period has no bucket on that date (a past custom range), and when the server reports another
 *  timezone: its day buckets then start on other dates, so no exact total for the project date can be built (the page shows
 *  the mismatch notice). Filters in force apply to the counts. */
export function receivedOnDay(counts: EventTimeCounts | undefined, day: string, timeZone: string): number | undefined {
  if (!counts || counts.timeZone !== timeZone) return undefined;
  const buckets = counts.items.filter((b) => dateIn(new Date(b.start).getTime(), timeZone) === day);
  return buckets.length ? buckets.reduce((sum, b) => sum + b.count, 0) : undefined;
}

/** Events of the whole shown period: the sum of all by-time buckets (Q144). `undefined` before the first result. Filters in force apply. */
export function periodTotal(counts: EventTimeCounts | undefined): number | undefined {
  return counts ? counts.items.reduce((sum, b) => sum + b.count, 0) : undefined;
}

/** Plan violations of the shown period (ADR-0022): events whose name is not in the plan, plus events with properties that do not match.
 *  `checked` is the events that carry a status; with 0 checked no plan was in force for them. `undefined` before the first result. */
export function planViolations(counts: EventPlanStatusCounts | undefined):
  { violations: number; unplanned: number; invalid: number; checked: number; notChecked: number } | undefined {
  if (!counts) return undefined;
  return {
    violations: counts.unplannedEvent + counts.invalidProperties,
    unplanned: counts.unplannedEvent,
    invalid: counts.invalidProperties,
    checked: counts.planned + counts.unplannedEvent + counts.invalidProperties,
    notChecked: counts.notChecked,
  };
}

/** The name with the largest count. A tie keeps the first name in server order. */
export function topEvent(counts: EventNameCounts | undefined): { name: string; count: number } | undefined {
  let top: { name: string; count: number } | undefined;
  for (const item of counts?.items ?? []) if (!top || item.count > top.count) top = { name: item.name, count: item.count };
  return top;
}

/** Newest `receivedAt` (ms). The first page sorted newest first holds the latest receipt; other views do not, so they give `undefined`. */
export function lastReceipt(page: EventReceiptPage | undefined, sort: Sort, onFirstPage: boolean): number | undefined {
  if (!page || !onFirstPage || sort !== "receivedAt_desc" || page.items.length === 0) return undefined;
  return Math.max(...page.items.map((e) => new Date(e.receivedAt).getTime()));
}

export type Health = "live" | "paused" | "loading" | "retrying" | "stopped" | "idle";

/**
 * One state for the three queries. A contract stop (Q108) wins over a retry wait (Q109), which wins over loading.
 * `polled` (one flag, or one per state) is false when a query does not update by itself (a list page after the
 * first, Q77). Such a query never retries by itself, so its success or failure is "paused", never "live" or "retrying".
 */
export function queryHealth(states: QueryState<unknown>[], polled: boolean | boolean[] = true): { health: Health; nextEligibleAt?: number } {
  const auto = (i: number) => (Array.isArray(polled) ? polled[i] ?? true : polled);
  if (states.some((s) => s.stopped)) return { health: "stopped" };
  const retrying = states.filter((s, i) => s.error && auto(i));
  const waits = retrying.map((s) => s.nextEligibleAt).filter((x): x is number => x !== undefined);
  if (retrying.length > 0) return { health: "retrying", nextEligibleAt: waits.length ? Math.max(...waits) : undefined };
  if (states.some((s) => s.loading && !s.lastSuccessAt)) return { health: "loading" };
  if (states.some((s) => s.error)) return { health: "paused" };
  if (states.every((s) => s.lastSuccessAt)) return { health: states.every((_, i) => auto(i)) ? "live" : "paused" };
  return { health: "idle" };
}
