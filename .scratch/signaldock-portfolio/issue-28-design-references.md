# Issue 28 design references

Research for issue 28 (spec Q139, Q140), 2026-10-13. Third-party screenshots are not committed (copyright and Mobbin terms); this file keeps the patterns and the sources only.

## Sources

- Lazyweb report on the current Dashboard screenshot: https://www.lazyweb.com/report/lazyweb/4033bc3f-65b1-4f13-b6ea-de7e8d2aaef6/ (free preview; references include Sentry, Sumo Logic, Better Stack, Fingerprint, Panther, Writer).
- Lazyweb report on the current benchmark report screenshot: https://www.lazyweb.com/report/lazyweb/061a3917-ff53-45ce-9543-313989e4e2c8/ (free preview; reference: Supabase).
- Mobbin (free account, two screens per app): Sentry web (issue feed, issue detail) and Vercel web (overview, project). Datadog, Grafana, and PostHog are not on Mobbin.

## Patterns and where they come from

| Page | Pattern | Seen in |
|---|---|---|
| Dashboard | KPI card row at the top: events today, last receipt, query state, top event name | Lazyweb (Sentry, Fingerprint), Vercel overview cards |
| Dashboard | One sticky toolbar: period, filters, apply/clear/refresh | Lazyweb (Writer, Better Stack) |
| Dashboard | Split layout: table left, selected event detail right, no navigation | Lazyweb hypothesis "Split Drilldown", Sentry issue detail |
| Dashboard | Per-row trend sparkline and count | Sentry issue feed |
| Benchmark | KPI strip: verdict, p95, max, 503 share, dropped, run count; each links to its section | Lazyweb (Supabase) |
| Benchmark | Sticky table of contents: Runs, Overload, SDK, Latency | Lazyweb hypothesis "Sticky TOC Rail" |
| Benchmark | Baseline, overload, and recovery bands on the overload charts | Lazyweb hypothesis "Phase Timeline Lens" (bands only, no filtering) |
| Both | Status badge with a dot and a word; dark default and light theme tokens; dense, readable tables | All sources |

Rejected for issue 28 (Q139, new view): A/B run compare mode (Lazyweb "Run Compare Mode").
