// k6 script of gate B3 (ADR-0049 amendment, ticket 08). Run it only through perf/ci-slo.sh.
// Fixed load: RATE requests/s of 20 events (about 1 KiB each), no retry: a warm-up of WARMUP_S seconds (JIT, pool, page cache; the first
// run of this gate had p95 from 8 to 38 ms without it), then DURATION_S seconds of measure phase. Both phases are constant-arrival-rate.
// A request counts as bad (`non200`) when the status is not 200 or when the ack does not list all 20 events as accepted.
// Thresholds are absolute only; the p95 rule (2x the committed baseline) is in perf/ci_slo.py (ADR-0049 decision 7).
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const E = __ENV;
const RATE = Number(E.RATE);
const DURATION_S = Number(E.DURATION_S);
const WARMUP_S = Number(E.WARMUP_S);
const EPB = 20;
const SALT = E.SALT; // 8 hex characters; a new one per run, so no event ID repeats in a reused database
const NAMES = ['product_view', 'cart_add', 'purchase'];
const PAD = 'abcdefghijklmnopqrstuvwxyz0123456789 '.repeat(20).slice(0, 600);

// 100 installations, as in perf/common.py. One installation for every request made 30 VUs write the same installation row, and one
// request in a run hit the 500 ms lock timeout (an answer that is not 200). Real installations are separate devices.
const installationId = (seq) => { const i = (seq % 100) + 1; return `${i.toString(16).padStart(8, '0')}-0000-4000-8000-${i.toString(16).padStart(12, '0')}`; };

const non200 = new Counter('non200');

export const options = {
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${WARMUP_S}s`, preAllocatedVUs: 30, maxVUs: 30, gracefulStop: '10s', tags: { phase: 'warmup' } },
    measure: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_S}s`, startTime: `${WARMUP_S + 10}s`, preAllocatedVUs: 30, maxVUs: 30, gracefulStop: '10s', tags: { phase: 'measure' } },
  },
  // The two latency thresholds are always true. k6 puts a sub-metric in the summary only when a threshold names it; perf/ci_slo.py reads them.
  thresholds: {
    non200: ['count==0'], dropped_iterations: ['count==0'],
    'http_req_duration{phase:measure}': ['p(95)>=0'], 'http_reqs{phase:measure}': ['count>=0'],
  },
};

const eventId = (seq, j) => `${SALT}-0000-4000-8000-${(seq * EPB + j).toString(16).padStart(12, '0')}`;

export default function () {
  const seq = (exec.scenario.name === 'warmup' ? 0 : 1000000) + exec.scenario.iterationInTest;
  const now = Date.now();
  const events = [];
  for (let j = 0; j < EPB; j++) {
    events.push({
      contractVersion: '1', eventId: eventId(seq, j), name: NAMES[(seq + j) % 3], occurredAt: new Date(now - j).toISOString(), sdkVersion: '0.1.0',
      properties: { product_id: `sku-${(seq * 7 + j) % 5000}`, category: 'shoes', price: 1000 + j, quantity: 1, in_stock: true, currency: 'KRW', note: PAD },
    });
  }
  const res = http.post(`${E.BASE_URL}/v1/events:batch`, JSON.stringify({ installationId: installationId(seq), events }), {
    headers: { 'Content-Type': 'application/json', 'SignalDock-Contract-Version': '1', Authorization: `Bearer ${E.COLLECTION_KEY}` },
    timeout: '5s',
  });
  let good = res.status === 200;
  if (good) {
    try { const r = res.json('results'); good = Array.isArray(r) && r.length === EPB && r.every((x) => x.status === 'accepted'); } catch (_) { good = false; }
  }
  if (!good) non200.add(1);
}
