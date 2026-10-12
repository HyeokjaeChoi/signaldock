import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PollingQuery, QueryError } from "./polling";

let visible = true;
const env = { now: () => Date.now(), visible: () => visible };

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
  visible = true;
});
afterEach(() => vi.useRealTimers());

const settle = () => vi.advanceTimersByTimeAsync(0);

describe("PollingQuery", () => {
  it("polls every 5 seconds while the page is visible", async () => {
    const fetcher = vi.fn(async () => "ok");
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10000);
    expect(fetcher).toHaveBeenCalledTimes(4);
    q.stop();
  });

  it("does not query while hidden and queries on return", async () => {
    const fetcher = vi.fn(async () => "ok");
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    visible = false;
    await vi.advanceTimersByTimeAsync(60000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    visible = true;
    q.kick(); // visibilitychange
    await settle();
    expect(fetcher).toHaveBeenCalledTimes(2);
    q.stop();
  });

  it("does not start an identical query while one is in flight", async () => {
    let release!: (v: string) => void;
    const fetcher = vi.fn(() => new Promise<string>((r) => (release = r)));
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    q.kick(true);
    q.kick();
    await vi.advanceTimersByTimeAsync(20000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    release("done");
    await settle();
    expect(q.state.data).toBe("done");
    q.stop();
  });

  it("keeps the last data and success time on error, then backs off 5, 10, 20, 30, 30 and resets on success", async () => {
    let fail = false;
    const fetcher = vi.fn(async () => {
      if (fail) throw new QueryError("HTTP 503", 503);
      return "data";
    });
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    expect(q.state.lastSuccessAt).toBe(0);
    fail = true;
    const gaps: number[] = [];
    let last = 0;
    for (let i = 0; i < 5; i++) {
      // advance to the next request
      const before = fetcher.mock.calls.length;
      while (fetcher.mock.calls.length === before) await vi.advanceTimersByTimeAsync(1000);
      gaps.push(Date.now() - last);
      last = Date.now();
    }
    // First failure happens at t=5s (regular poll); waits after failures 1..4 are 5, 10, 20, 30.
    expect(gaps).toEqual([5000, 5000, 10000, 20000, 30000]);
    expect(q.state.data).toBe("data");
    expect(q.state.lastSuccessAt).toBe(0);
    expect(q.state.error?.status).toBe(503);
    expect(q.state.nextEligibleAt).toBe(Date.now() + 30000);
    fail = false;
    await vi.advanceTimersByTimeAsync(30000);
    expect(q.state.error).toBeUndefined();
    expect(q.state.lastSuccessAt).toBe(Date.now());
    const calls = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher.mock.calls.length).toBe(calls + 1); // normal 5 s polling again
    q.stop();
  });

  it("respects a longer Retry-After and ignores a shorter one", async () => {
    const errors = [new QueryError("busy", 503, undefined, 45000), new QueryError("busy", 503, undefined, 1000)];
    const fetcher = vi.fn(async () => {
      const e = errors.shift();
      if (e) throw e;
      return "ok";
    });
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    expect(q.state.nextEligibleAt).toBe(45000);
    await vi.advanceTimersByTimeAsync(44999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(q.state.nextEligibleAt).toBe(45000 + 10000); // second failure: 10 s beats 1 s
    q.stop();
  });

  it("does not let manual refresh or tab return skip a wait", async () => {
    const fetcher = vi.fn(async () => {
      throw new QueryError("down", 503);
    });
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    expect(fetcher).toHaveBeenCalledTimes(1);
    q.kick(true);
    q.kick();
    await vi.advanceTimersByTimeAsync(4999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
    q.stop();
  });

  it("stops polling on a contract error and keeps the data (Q108)", async () => {
    let contract = false;
    const fetcher = vi.fn(async () => {
      if (contract) throw new QueryError("unsupported", 400, "CONTRACT_VERSION_UNSUPPORTED");
      return "data";
    });
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    contract = true;
    await vi.advanceTimersByTimeAsync(5000);
    expect(q.state.stopped).toBe(true);
    expect(q.state.data).toBe("data");
    await vi.advanceTimersByTimeAsync(60000);
    q.kick(true);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("does not treat a generic HTTP 400 as a contract error", async () => {
    const fetcher = vi.fn(async () => {
      throw new QueryError("bad", 400, "INVALID_PARAMETER");
    });
    const q = new PollingQuery(fetcher, env);
    q.start();
    await settle();
    expect(q.state.stopped).toBe(false);
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetcher).toHaveBeenCalledTimes(2);
    q.stop();
  });

  it("loads a past page once and refreshes it only by hand (Q77)", async () => {
    const fetcher = vi.fn(async () => "page");
    const q = new PollingQuery(fetcher, env, false);
    q.start();
    await settle();
    await vi.advanceTimersByTimeAsync(60000);
    q.kick(); // tab return does not refresh a past page
    await settle();
    expect(fetcher).toHaveBeenCalledTimes(1);
    q.kick(true);
    await settle();
    expect(fetcher).toHaveBeenCalledTimes(2);
    q.stop();
  });
});
