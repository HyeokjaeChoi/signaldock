// k6 load script for issue 23 (performance-plan.md "Collection load" and "Data and queries"). Local k6 only.
// All parameters come from environment variables set by perf/run_one.py; keys are never written to results.
//
// collect:   constant-arrival-rate, RATE requests/s of 20 events, 30 preallocated = 30 max VUs, 5 s timeout, no retry.
// dash_*:    CLIENTS Dashboard clients, each polling list, by-name and by-time on a 5 s tick (one VU per client and
//            endpoint). A tick whose previous request is still running is skipped and counted.
// Phases are tagged warmup/measure so the summarizer can drop warm-up. Duration is http_req_duration-style
// (sending + waiting + receiving); connection wait is recorded separately as blocked_ms.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const E = __ENV;
const WARM = Number(E.WARMUP_S);
const TOTAL_S = WARM + Number(E.MEASURE_S);
const RATE = Number(E.RATE || 5);
const EPB = 20;
const CLIENTS = Number(E.CLIENTS);
const BASE = E.BASE_URL;
const SALT = E.SALT;
const TICK_MS = 5000;
const EXP_TOTAL = Number(E.EXPECT_TOTAL);
const EXP_LIST = Math.min(50, EXP_TOTAL);
const EXP_NAMES = JSON.parse(E.EXPECT_NAMES);
const WINDOW = `from=${encodeURIComponent(E.FROM)}&to=${encodeURIComponent(E.TO)}`;

const bodies = new SharedArray('bodies', () => open(E.BODIES).split('\n').filter((l) => l));

const reqMs = new Trend('req_ms', true);
const blockedMs = new Trend('blocked_ms', true);
const accepted = new Counter('acks_accepted');
const duplicate = new Counter('acks_duplicate');
const rejected = new Counter('acks_rejected');
const exhausted = new Counter('bodies_exhausted');
const ticks = new Counter('ticks_total');
const skipped = new Counter('ticks_skipped');
const wrong = new Counter('agg_wrong');

export const options = {
  summaryTrendStats: ['min', 'med', 'p(95)', 'max', 'count'],
  scenarios: {
    collect: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${TOTAL_S}s`,
      preAllocatedVUs: 30, maxVUs: 30, exec: 'collect', gracefulStop: '10s',
    },
    dash_list: { executor: 'constant-vus', vus: CLIENTS, duration: `${TOTAL_S}s`, exec: 'dashList', gracefulStop: '10s' },
    dash_name: { executor: 'constant-vus', vus: CLIENTS, duration: `${TOTAL_S}s`, exec: 'dashName', gracefulStop: '10s' },
    dash_time: { executor: 'constant-vus', vus: CLIENTS, duration: `${TOTAL_S}s`, exec: 'dashTime', gracefulStop: '10s' },
  },
};

const eventId = (seq, j) => `${SALT}-0000-4000-8000-${(seq * EPB + j).toString(16).padStart(12, '0')}`;

function outcomeOf(res) {
  if (res.status === 0) return res.error_code === 1050 ? 'timeout' : `neterr_${res.error_code}`;
  return res.status === 200 ? 'ok' : `http_${res.status}`;
}

function record(ep, res, outcome, extra) {
  const tags = Object.assign({ ep, outcome, status: String(res.status) }, extra);
  reqMs.add(res.timings.duration, tags);
  blockedMs.add(res.timings.blocked + res.timings.connecting, tags);
}

export function collect() {
  const seq = exec.scenario.iterationInTest;
  const body = bodies[seq];
  const extra = {
    phase: seq < WARM * RATE ? 'warmup' : seq < TOTAL_S * RATE ? 'measure' : 'tail', seq: String(seq),
    at: String(Math.round(exec.instance.currentTestRunDuration)), vu: String(exec.vu.idInTest),
  };
  if (body === undefined) { exhausted.add(1); return; }
  const res = http.post(`${BASE}/v1/events:batch`, body, {
    headers: {
      'Content-Type': 'application/json', 'SignalDock-Contract-Version': '1',
      Authorization: `Bearer ${E.COLLECTION_KEY}`,
    },
    timeout: '5s', tags: { ep: 'collect' },
  });
  let outcome = outcomeOf(res);
  if (outcome === 'ok') {
    let results = null;
    try { results = res.json('results'); } catch (_) { /* counted as ack_bad */ }
    let good = Array.isArray(results) && results.length === EPB;
    if (good) {
      results.forEach((r, j) => {
        if (r.status === 'accepted') accepted.add(1); else if (r.status === 'duplicate') duplicate.add(1); else rejected.add(1);
        if (r.status !== 'accepted' || r.index !== j || r.eventId !== eventId(seq, j)) good = false;
      });
    }
    if (!good) outcome = 'ack_bad';
  }
  record('collect', res, outcome, extra);
}

let nextTick = 0; // per VU

function dash(ep, path, valid) {
  const total = TOTAL_S * 1000;
  const tickAt = nextTick * TICK_MS;
  let now = exec.instance.currentTestRunDuration;
  if (tickAt >= total) { sleep(Math.max(total - now, 0) / 1000); return; }
  if (tickAt > now) sleep((tickAt - now) / 1000);
  const phase = tickAt < WARM * 1000 ? 'warmup' : 'measure';
  ticks.add(1, { ep, phase });
  const res = http.get(`${BASE}${path}`, {
    headers: { 'SignalDock-Contract-Version': '1', Authorization: `Bearer ${E.QUERY_KEY}` },
    timeout: '5s', tags: { ep },
  });
  let outcome = outcomeOf(res);
  if (outcome === 'ok') {
    let good = false;
    try { good = valid(res.json()); } catch (_) { /* counted as wrong */ }
    if (!good) { outcome = 'wrong'; wrong.add(1, { ep, phase }); }
  }
  record(ep, res, outcome, { phase, tick: String(nextTick), at: String(Math.round(tickAt)), vu: String(exec.vu.idInTest) });
  const next = Math.floor(exec.instance.currentTestRunDuration / TICK_MS) + 1;
  if (next - nextTick - 1 > 0) skipped.add(next - nextTick - 1, { ep, phase });
  nextTick = next;
}

const sum = (items) => items.reduce((a, i) => a + i.count, 0);

export function dashList() {
  dash('list', `/v1/events?${WINDOW}&limit=50`, (b) =>
    b.items.length === EXP_LIST && b.items.every((e, i) => i === 0 || b.items[i - 1].receivedAt >= e.receivedAt));
}
export function dashName() {
  dash('by-name', `/v1/event-counts/by-name?${WINDOW}`, (b) =>
    !b.truncated && sum(b.items) === EXP_TOTAL && b.items.every((i) => EXP_NAMES[i.name] === i.count));
}
export function dashTime() {
  dash('by-time', `/v1/event-counts/by-time?${WINDOW}`, (b) => sum(b.items) === EXP_TOTAL);
}
