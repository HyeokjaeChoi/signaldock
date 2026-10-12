import { useCallback, useEffect, useRef, useState } from "react";

/** Q109: consecutive failures wait 5, 10, 20, then 30 seconds. */
export const BACKOFF_MS = [5000, 10000, 20000, 30000];
export const POLL_MS = 5000;

export class QueryError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
    readonly retryAfterMs?: number,
  ) {
    super(message);
  }
  /** Q108: only an explicit contract-version error stops polling. */
  get isContractError(): boolean {
    return this.status === 400 && (this.code === "CONTRACT_VERSION_UNSUPPORTED" || this.code === "CONTRACT_HEADER_MISSING");
  }
}

export interface QueryState<T> {
  data?: T;
  lastSuccessAt?: number;
  error?: QueryError;
  /** Earliest time the next request may start after a failure (Q109). */
  nextEligibleAt?: number;
  /** Polling stopped by a contract error (Q108). */
  stopped: boolean;
  loading: boolean;
}

export interface PollEnv {
  now: () => number;
  visible: () => boolean;
}

/**
 * One query with one in-flight request at most. Polls every 5 s while visible.
 * Manual refresh and tab return go through the same gate, so they cannot
 * overlap a running request or skip a backoff wait.
 */
export class PollingQuery<T> {
  state: QueryState<T> = { stopped: false, loading: false };
  private failures = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private inflight: AbortController | undefined;
  private running = false;
  private listeners = new Set<() => void>();

  /**
   * `auto` false (a past list page, Q77): load once on start, then only on a
   * manual refresh. Waits and overlap rules still apply.
   */
  constructor(
    private fetcher: (signal: AbortSignal) => Promise<T>,
    private env: PollEnv,
    private auto = true,
  ) {}

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  start(): void {
    this.running = true;
    this.kick(true);
  }

  stop(): void {
    this.running = false;
    clearTimeout(this.timer);
    this.inflight?.abort();
    this.inflight = undefined;
  }

  /** Manual refresh (`manual` true) and visibility changes both land here. */
  kick(manual = false): void {
    clearTimeout(this.timer);
    if (!this.running || this.state.stopped || !this.env.visible() || this.inflight) return;
    if (!this.auto && !manual) return;
    const wait = Math.max(0, (this.state.nextEligibleAt ?? 0) - this.env.now());
    if (wait > 0) {
      this.timer = setTimeout(() => this.kick(manual), wait);
      return;
    }
    void this.run();
  }

  private set(patch: Partial<QueryState<T>>): void {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }

  private async run(): Promise<void> {
    const controller = new AbortController();
    this.inflight = controller;
    this.set({ loading: true });
    let wait = POLL_MS;
    try {
      const data = await this.fetcher(controller.signal);
      if (controller.signal.aborted) return;
      this.failures = 0;
      this.set({ data, lastSuccessAt: this.env.now(), error: undefined, nextEligibleAt: undefined, loading: false });
    } catch (e) {
      if (controller.signal.aborted) return;
      const error = e instanceof QueryError ? e : new QueryError(e instanceof Error ? e.message : "Query failed");
      if (error.isContractError) {
        this.inflight = undefined;
        this.set({ error, stopped: true, loading: false, nextEligibleAt: undefined });
        return;
      }
      this.failures += 1;
      wait = Math.max(BACKOFF_MS[Math.min(this.failures, BACKOFF_MS.length) - 1]!, error.retryAfterMs ?? 0);
      this.set({ error, nextEligibleAt: this.env.now() + wait, loading: false });
    }
    this.inflight = undefined;
    if (this.running && this.auto) this.timer = setTimeout(() => this.kick(), wait);
  }
}

const browserEnv: PollEnv = { now: () => Date.now(), visible: () => document.visibilityState === "visible" };

/** `key` null disables the query. A new key starts a new query with its own failure count. `auto` false: manual refresh only after the first load. */
export function usePolledQuery<T>(
  key: string | null,
  fetcher: (signal: AbortSignal) => Promise<T>,
  auto = true,
  env: PollEnv = browserEnv,
): { state: QueryState<T>; refresh: () => void } {
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const queryRef = useRef<PollingQuery<T>>(undefined);
  const [state, setState] = useState<QueryState<T>>({ stopped: false, loading: false });

  useEffect(() => {
    if (key === null) {
      queryRef.current = undefined;
      setState({ stopped: false, loading: false });
      return;
    }
    const query = new PollingQuery<T>((s) => fetcherRef.current(s), env, auto);
    queryRef.current = query;
    setState(query.state);
    const unsubscribe = query.subscribe(() => setState(query.state));
    const onVisibility = () => query.kick();
    document.addEventListener("visibilitychange", onVisibility);
    query.start();
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      unsubscribe();
      query.stop();
    };
  }, [key, auto, env]);

  const refresh = useCallback(() => queryRef.current?.kick(true), []);
  return { state, refresh };
}
