// KPI cards (Q140, ADR-0016). Every value comes from data the page already fetched: no new request, no new API.
import type { EventNameCounts, EventReceiptPage, EventTimeCounts } from "../../generated/typescript/models";
import type { Sort } from "./api";
import { dateIn } from "./period";
import type { QueryState } from "./polling";

/** Events received on calendar date `day` (`YYYY-MM-DD`, project timezone): the sum of by-time buckets that start on that date.
 *  `undefined` when the shown period has no bucket on that date (a past custom range). Filters in force apply to the counts. */
export function receivedOnDay(counts: EventTimeCounts | undefined, day: string, timeZone: string): number | undefined {
  if (!counts) return undefined;
  const buckets = counts.items.filter((b) => dateIn(new Date(b.start).getTime(), timeZone) === day);
  return buckets.length ? buckets.reduce((sum, b) => sum + b.count, 0) : undefined;
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

export type Health = "live" | "loading" | "retrying" | "stopped" | "idle";

/** One state for the three queries. A contract stop (Q108) wins over a retry wait (Q109), which wins over loading. */
export function queryHealth(states: QueryState<unknown>[]): { health: Health; nextEligibleAt?: number } {
  if (states.some((s) => s.stopped)) return { health: "stopped" };
  const waits = states.filter((s) => s.error).map((s) => s.nextEligibleAt).filter((x): x is number => x !== undefined);
  if (states.some((s) => s.error)) return { health: "retrying", nextEligibleAt: waits.length ? Math.max(...waits) : undefined };
  if (states.some((s) => s.loading && !s.lastSuccessAt)) return { health: "loading" };
  if (states.every((s) => s.lastSuccessAt)) return { health: "live" };
  return { health: "idle" };
}
