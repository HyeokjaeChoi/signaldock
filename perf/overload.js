// k6 load script for the overload run of issue 23 (ADR-0013, Q133). Local k6 only. Parameters come from perf/overload.py.
//
// Three phases on one k6 clock: baseline (BASE_S), overload (OVER_S), recovery (REC_S).
//   collect: ramping-arrival-rate of 20-event batches: RATE rps, OVER_RATE rps in the overload phase, RATE again. 5 s timeout, no retry.
//   probe:   1 request/s, GET /v1/events?limit=50, all phases (read path and recovery check).
//   heavy:   HEAVY_RATE rps of GET /v1/event-counts/by-name over the full window, overload phase only (long DB work holds slots).
// Every response is recorded with its status, Retry-After, and error code. The script never retries a 503.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const E = __ENV;
const BASE_S = Number(E.BASE_S), OVER_S = Number(E.OVER_S), REC_S = Number(E.REC_S);
const RATE = Number(E.RATE), OVER_RATE = Number(E.OVER_RATE), HEAVY_RATE = Number(E.HEAVY_RATE);
const EPB = 20;
const BASE = E.BASE_URL;
const SALT = E.SALT;
const WINDOW = `from=${encodeURIComponent(E.FROM)}&to=${encodeURIComponent(E.TO)}`;
const bodies = new SharedArray('bodies', () => open(E.BODIES).split('\n').filter((l) => l));

const reqMs = new Trend('req_ms', true);
const exhausted = new Counter('bodies_exhausted');

export const options = {
  summaryTrendStats: ['min', 'med', 'p(95)', 'max', 'count'],
  scenarios: {
    collect: {
      executor: 'ramping-arrival-rate', startRate: RATE, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 300, exec: 'collect',
      stages: [{ target: RATE, duration: `${BASE_S}s` }, { target: OVER_RATE, duration: '1s' }, { target: OVER_RATE, duration: `${OVER_S - 2}s` },
        { target: RATE, duration: '1s' }, { target: RATE, duration: `${REC_S + 1}s` }],
      gracefulStop: '10s',
    },
    probe: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s', duration: `${BASE_S + OVER_S + REC_S}s`, preAllocatedVUs: 5, maxVUs: 20, exec: 'probe', gracefulStop: '10s' },
    heavy: {
      executor: 'constant-arrival-rate', rate: HEAVY_RATE, timeUnit: '1s', startTime: `${BASE_S}s`, duration: `${OVER_S}s`,
      preAllocatedVUs: 100, maxVUs: 300, exec: 'heavy', gracefulStop: '10s',
    },
  },
};

const eventId = (seq, j) => `${SALT}-0000-4000-8000-${(seq * EPB + j).toString(16).padStart(12, '0')}`;
const phaseOf = (ms) => (ms < BASE_S * 1000 ? 'baseline' : ms < (BASE_S + OVER_S) * 1000 ? 'overload' : 'recovery');

function outcomeOf(res) {
  if (res.status === 0) return res.error_code === 1050 ? 'timeout' : `neterr_${res.error_code}`;
  if (res.status === 200) return 'ok';
  const ra = res.headers['Retry-After'];
  if (res.status === 503) return ra !== undefined && /^[1-9][0-9]*$/.test(ra) ? 'shed' : 'shed_bad_retry_after';
  return `http_${res.status}`;
}

function record(ep, res, outcome, at, id) {
  let code = '';
  if (res.status >= 400) { try { code = res.json('code') || ''; } catch (_) { code = 'unparsable'; } }
  reqMs.add(res.timings.duration, {
    ep, outcome, status: String(res.status), phase: phaseOf(at), at: String(Math.round(at)), id: String(id),
    retry_after: String(res.headers['Retry-After'] ?? ''), code,
  });
}

export function collect() {
  const seq = exec.scenario.iterationInTest;
  const at = exec.instance.currentTestRunDuration;
  const body = bodies[seq];
  if (body === undefined) { exhausted.add(1); return; }
  const res = http.post(`${BASE}/v1/events:batch`, body, {
    headers: { 'Content-Type': 'application/json', 'SignalDock-Contract-Version': '1', Authorization: `Bearer ${E.COLLECTION_KEY}` },
    timeout: '5s', tags: { ep: 'collect' },
  });
  let outcome = outcomeOf(res);
  if (outcome === 'ok') {
    let results = null;
    try { results = res.json('results'); } catch (_) { /* ack_bad */ }
    let good = Array.isArray(results) && results.length === EPB;
    if (good) results.forEach((r, j) => { if (r.status !== 'accepted' || r.index !== j || r.eventId !== eventId(seq, j)) good = false; });
    if (!good) outcome = 'ack_bad';
  }
  record('collect', res, outcome, at, seq);
}

function get(ep, path) {
  const at = exec.instance.currentTestRunDuration;
  const res = http.get(`${BASE}${path}`, {
    headers: { 'SignalDock-Contract-Version': '1', Authorization: `Bearer ${E.QUERY_KEY}` }, timeout: '5s', tags: { ep },
  });
  record(ep, res, outcomeOf(res), at, exec.scenario.iterationInTest);
}

export function probe() { get('probe', `/v1/events?${WINDOW}&limit=50`); }
export function heavy() { get('heavy', `/v1/event-counts/by-name?${WINDOW}`); }
