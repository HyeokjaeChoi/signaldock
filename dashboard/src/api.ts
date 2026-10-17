import {
  ErrorResponseFromJSON,
  ClientReportCountsFromJSON,
  EventNameCountsFromJSON,
  EventPlanStatusCountsFromJSON,
  EventReceiptDetailFromJSON,
  EventReceiptPageFromJSON,
  EventTimeCountsFromJSON,
} from "../../generated/typescript/models";
import type {
  ClientReportCounts,
  EventNameCounts,
  EventPlanStatusCounts,
  EventReceiptDetail,
  EventReceiptPage,
  EventTimeCounts,
  PlanStatus,
} from "../../generated/typescript/models";
import { QueryError } from "./polling";

/** Same-origin calls only. The proxy adds the query key (Q129); no key is ever set here. */
export interface Filters {
  from: string;
  to: string;
  name?: string;
  installationId?: string;
  eventId?: string;
  planStatus?: PlanStatus;
}
export type Sort = "receivedAt_desc" | "receivedAt_asc";
export const PAGE_SIZE = 50;

export interface Api {
  listEvents(filters: Filters, sort: Sort, cursor: string | undefined, signal: AbortSignal): Promise<EventReceiptPage>;
  getEvent(eventId: string, signal: AbortSignal): Promise<EventReceiptDetail>;
  countsByName(filters: Filters, signal: AbortSignal): Promise<EventNameCounts>;
  countsByTime(filters: Filters, signal: AbortSignal): Promise<EventTimeCounts>;
  /** Counts per plan status. The route splits by status, so `planStatus` is never sent (ADR-0022). */
  countsByPlanStatus(filters: Filters, signal: AbortSignal): Promise<EventPlanStatusCounts>;
  /** SDK-reported dropped events (Q164). Only the period applies: the event filters do not, so only `from` and `to` are sent. */
  clientReportCounts(filters: Filters, signal: AbortSignal): Promise<ClientReportCounts>;
}

async function getJson(path: string, params: Record<string, string | undefined>, signal: AbortSignal): Promise<unknown> {
  const query = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== "") query.set(k, v);
  const qs = query.toString();
  let response: Response;
  try {
    response = await fetch(qs ? `${path}?${qs}` : path, {
      headers: { Accept: "application/json", "SignalDock-Contract-Version": "1" },
      signal,
    });
  } catch (e) {
    if (signal.aborted) throw e;
    throw new QueryError("Network error: the server did not answer", undefined, undefined, undefined, "errors.network");
  }
  if (response.ok) return response.json();
  const body: unknown = await response.json().catch(() => undefined);
  const error = body && typeof body === "object" ? ErrorResponseFromJSON(body) : undefined;
  const retryAfter = response.headers.get("Retry-After");
  throw new QueryError(
    error?.message ?? `HTTP ${response.status}`,
    response.status,
    error?.code,
    retryAfter && /^[1-9][0-9]*$/.test(retryAfter) ? Number(retryAfter) * 1000 : undefined,
  );
}

const filterParams = (f: Filters) => ({
  from: f.from, to: f.to, name: f.name, installationId: f.installationId, eventId: f.eventId, planStatus: f.planStatus,
});

export const realApi: Api = {
  async listEvents(f, sort, cursor, signal) {
    return EventReceiptPageFromJSON(
      await getJson("/v1/events", { ...filterParams(f), sort, limit: String(PAGE_SIZE), cursor }, signal),
    );
  },
  async getEvent(eventId, signal) {
    return EventReceiptDetailFromJSON(await getJson(`/v1/events/${encodeURIComponent(eventId)}`, {}, signal));
  },
  async countsByName(f, signal) {
    return EventNameCountsFromJSON(await getJson("/v1/event-counts/by-name", filterParams(f), signal));
  },
  async countsByTime(f, signal) {
    return EventTimeCountsFromJSON(await getJson("/v1/event-counts/by-time", filterParams(f), signal));
  },
  async countsByPlanStatus(f, signal) {
    return EventPlanStatusCountsFromJSON(await getJson("/v1/event-counts/by-plan-status", { ...filterParams(f), planStatus: undefined }, signal));
  },
  async clientReportCounts(f, signal) {
    return ClientReportCountsFromJSON(await getJson("/v1/client-report-counts", { from: f.from, to: f.to }, signal));
  },
};
