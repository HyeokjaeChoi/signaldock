// k6 collection load for the live demo VM (perf/demo-vm/README.md). One stage per run: constant-arrival-rate of
// RATE requests/s, 20 events each, WARMUP_S then MEASURE_S. Same request shape and checks as perf/load.js `collect`.
// The key comes from the environment and is never written to results.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const E = __ENV;
const RATE = Number(E.RATE);
const WARM = Number(E.WARMUP_S || 30);
const TOTAL_S = WARM + Number(E.MEASURE_S || 180);
const SALT = E.SALT; // 8 hex characters, one per stage
const EPB = 20;

const templates = new SharedArray('templates', () => open(E.TEMPLATES).split('\n').filter((l) => l));
const T = templates.length;

const reqMs = new Trend('req_ms', true);
const accepted = new Counter('acks_accepted');
const notAccepted = new Counter('acks_not_accepted');

export const options = {
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
  scenarios: {
    collect: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${TOTAL_S}s`,
      preAllocatedVUs: 50, maxVUs: 50, gracefulStop: '10s',
    },
  },
};

const eventId = (n) => `${SALT}-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const ID = /"eventId":"ffffffff-0000-4000-8000-([0-9a-f]{12})"/g;
const idAt = (seq, t, hex) => eventId(seq * EPB + (parseInt(hex, 16) - t * EPB));

export default function () {
  const seq = exec.scenario.iterationInTest;
  const t = seq % T;
  const body = templates[t].replace(ID, (_, hex) => `"eventId":"${idAt(seq, t, hex)}"`);
  // Phase by elapsed wall time at request start, so a late iteration is not tagged by its index (README: tool change after the 2026-10-31 run).
  const elapsed = exec.instance.currentTestRunDuration;
  const sent = [...body.matchAll(/"eventId":"([0-9a-f-]{36})"/g)].map((m) => m[1]);
  const phase = elapsed < WARM * 1000 ? 'warmup' : elapsed < TOTAL_S * 1000 ? 'measure' : 'tail';
  const res = http.post(`${E.BASE_URL}/v1/events:batch`, body, {
    headers: { 'Content-Type': 'application/json', 'SignalDock-Contract-Version': '1', Authorization: `Bearer ${E.COLLECTION_KEY}` },
    timeout: '5s', tags: { phase },
  });
  let outcome = res.status === 0 ? `neterr_${res.error_code}` : res.status === 200 ? 'ok' : `http_${res.status}`;
  if (outcome === 'ok') {
    let results = null;
    try { results = res.json('results'); } catch (_) { /* ack_bad */ }
    const good = Array.isArray(results) && results.length === EPB && results.every((r, j) => r.status === 'accepted' && r.index === j && r.eventId === sent[j]);
    if (good) accepted.add(EPB, { phase }); else { outcome = 'ack_bad'; notAccepted.add(1, { phase }); }
  }
  reqMs.add(res.timings.duration, { phase, outcome });
}
