# Dashboard timezone practices research

Review date: 2026-10-08. After this research, Q78 adopted a shared project timezone setting, and Q79 adopted Asia/Seoul as the local demo default. This was official documentation research. Product UI behavior and the SignalDock implementation were not tested.

## Confirmed examples

| Product | Behavior in official documentation | Source |
| --- | --- | --- |
| Amplitude | The project timezone defaults to UTC. Changes apply to all project users, queries, and the Dashboard REST API. Ingestion and raw data remain in UTC. [Manage organizations and projects](https://www.amplitude.com/docs/admin/account-management/manage-orgs-projects) |
| Grafana | The default is the browser timezone. Settings are available at server, organization, team, and user levels. User settings take priority. [Organization preferences](https://grafana.com/docs/grafana/latest/administration/organization-preferences/) |
| Mixpanel | Report dates, times, and date inputs use the project timezone. The default is UTC and can be changed. [Managing Projects](https://docs.mixpanel.com/docs/orgs-and-projects/managing-projects), [Reports](https://docs.mixpanel.com/docs/reports#date-range) |
| PostHog | In the Product analytics Calendar heatmap, times and the start of the week use project settings. The default is UTC and can be changed. Do not apply this rule to every insight. [Charts](https://posthog.com/docs/product-analytics/trends/charts) |

The separate agent's Mixpanel and PostHog research was reviewed and incorporated. Community AI answers were excluded as evidence. Mixpanel has storage and export exceptions for projects created before 2023, so the same timezone policy cannot be assumed for all raw data and APIs. Browser display behavior across all PostHog insights and Person screens remains unconfirmed. [official documentation research](timezone-research-sidecar.md)

## Interpretation

The evidence does not establish one industry standard for a fixed timezone or browser timezone. Amplitude's shared project setting lets teams aggregate over the same calendar dates. Grafana's browser default suits observers who inspect data in their local time. These explanations of product intent are the researcher's interpretation.

A stored instant and a report's calendar timezone are separate concepts. For example, 2026-10-08 00:30 Asia/Seoul is 2026-10-07 15:30 UTC. The same event may be included or excluded depending on which timezone defines 'October 8'. Changing numeric labels alone does not change date filters or bucket boundaries.

## SignalDock recommendation: setting adopted in Q78, default adopted in Q79

Use an explicit timezone setting for the single project instead of hardcoding Asia/Seoul. Use Asia/Seoul for the local demo. This is a choice for the Korean-language demo, not a claim about an industry default. The first release can provide a startup setting without an administrator settings screen or per-user overrides.

The server owns the timezone setting, and the Dashboard reads the same value. Keep date filter inputs, bucket calculations, and list/detail/chart displays consistent. Show the timezone on screen. Do not change or re-save the instant in the original timestamp. Retain the receivedAt basis. This research does not approve separate unresolved contracts such as UTC transport format or precision.

The original three Q78 options, fixed Asia/Seoul, browser timezone, and UTC, omitted project configuration as a separate choice. The question must be presented again with the setting owner and default value separated. If an IANA timezone is selected, verify consistent periods and aggregates across DST boundaries and different browser timezones. Do not implement this through simple fixed-offset addition.
