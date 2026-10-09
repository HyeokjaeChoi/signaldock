# Mixpanel and PostHog timezone research

Verification date: **2026-10-08, Asia/Seoul**. Tool time: 2026-10-08 08:09 UTC.
Scope: independent research using official documentation only. Amplitude, Grafana, and the final combined assessment are excluded. Product UI behavior, implementation, and tests were not run. Community AI answers in search results were excluded as evidence.

## Conclusions

- Mixpanel: report dates and times use the project timezone. The default is UTC and can be changed in settings. UTC storage and report timezone are separate. Projects created before 2023 have a storage exception. [M1][M2]
- PostHog: the Product analytics Calendar heatmap uses the project timezone. There is no evidence that a Person timezone property or a Dashboard viewer's browser timezone replaces this aggregation setting. Official documentation did not establish a common rule for every insight, date filter, and Person screen. [P1][P3]
- Interpretation for SignalDock: an explicit shared reporting timezone has support in these sources. This research does not establish hardcoded `Asia/Seoul` as an industry standard. The value and whether it can be configured are separate product decisions.

## Concepts to separate first

| Item | Meaning | Caution for SignalDock |
| --- | --- | --- |
| Instant | One point on the actual timeline | A timezone change alone must not change the stored instant. |
| Event time / ingestion time | Time of occurrence / server receipt and processing time | This is the distinction between `occurredAt` and `receivedAt`, separate from timezone selection. |
| Chart bucket | An interval that groups data by hour or day | Date, weekday, and day boundaries can depend on the reporting timezone. |
| Date filter | The rule that converts an input date into a query interval | This requires more than changing result labels. |
| Browser display | The rule that formats an instant as readable text | A local-time display does not imply aggregation in local time. |

These are conceptual distinctions. They do not imply that every vendor uses identical API boundary rules.

## Mixpanel: confirmed facts

1. Project settings: the default timezone is UTC. A Project Owner/Admin can change it in Project Settings. The documentation states that all report dates and times, including date inputs, use the project timezone. `Yesterday` runs from the previous midnight to midnight. Do not describe this as a policy that automatically changes date boundaries for each browser. [M1: Changing your Timezone][M2: Date Range]
2. Storage and output: the current general description separates UTC ingestion/storage from project-timezone output. A setting change changes the output timezone without changing existing data. Projects created **before 2023-01-01** are an exception: timestamps were converted to the project timezone at ingestion and stored that way. Omitting this exception and saying "all Mixpanel data is always stored in UTC" would be inaccurate. [M1: Manage Timezones / Importing Data]
3. Buckets and calendar rules: week and quarter start settings apply at project level to all reports. They also affect weekly buckets and relative periods such as `Week to Date`. Calendar rules exist separately from timezone settings. [M1: Time Period Settings]
4. Raw Export has a separate contract: standard Raw Export interprets `from_date` and `to_date` in UTC. Projects created before 2023 have an exception that uses the current project timezone. Do not apply report date filter rules directly to the Export API. [M1: Exporting Data]

Unconfirmed points and limits: the official documentation reviewed does not explicitly state that per-report timezone overrides are unavailable. State that the officially confirmed basis is the project timezone. Do not claim that overrides are impossible for every API and report. Browser display exceptions in individual event details and exact DST boundary behavior were not tested. General merge/export guidance at the bottom of M1 does not clearly distinguish legacy behavior, so the explicit date-based exceptions above take priority.

## PostHog: confirmed facts

1. Product analytics buckets: the Calendar heatmap groups counts and unique users for selected events by weekday and hour. Each cell's display time and the start of the week follow project date/time settings. The default is UTC and can be changed in project settings. The documentation describes this as an opt-in beta. This finding comes from Product analytics documentation, not an inference from Web analytics. [P1: Calendar heatmap]
2. UTC storage and receipt time: the Data model describes both event `timestamp` and `created_at` as `DateTime64(6, 'UTC')`. `created_at` is the server ingestion time. The two fields have different roles. [P2: Event fields]
3. Ingestion calculations: when both `timestamp` and `sent_at` are sent, their difference from server time can correct clock skew. When only `timestamp` is present, it is used directly. If neither is present and `offset` is also absent, server capture time is used. This calculation is separate from reporting timezone selection. It does not justify automatically adding this behavior to SignalDock's `occurredAt` preservation contract. [P4: Timestamps]
4. Person timezone properties: `$geoip_time_zone` is the timezone associated with the event IP, and `$initial_geoip_time_zone` is the first observed value. These are Person-related data properties. The documentation does not say that they automatically change chart bucket timezones. Do not assume that GeoIP timezone and browser-reported timezone are identical. [P3: GeoIP properties]
5. Scope of date filters and display: a Dashboard can override an insight's date range. The same documentation provides a separate UTC/local display choice for the Logs widget. Do not extend this Logs display feature into a browser timezone policy for all Product analytics charts. [P5: Date range overrides / Logs widget]

Unconfirmed points and limits: the official documentation reviewed does not define timezone and date filter boundaries for all Product analytics insights together. It did not confirm whether Person details and Activity event times default to browser timezone or offer UTC/project/browser switching. Avoid both claims that "PostHog analytics uses person/browser timezone" and that "all PostHog times use the project timezone". Separate the confirmed heatmap rule from unconfirmed screens.

## Implications for SignalDock: proposal, not an adoption decision

The current local documents were checked again.

- The Dashboard contract in `spec.md` and the agreement in `discovery.md` use **receivedAt** for list sorting, period filters, and hourly aggregation. Details also show occurredAt. Offline events belong to the interval of first server storage. Duplicates do not change the first receivedAt or aggregate count.
- A fixed **Asia/Seoul timezone in Q78 is a proposal pending the user's choice**. Do not treat the earlier recommendation as an approved requirement.
- One shared reporting timezone is a reasonable candidate for the single-project local Dashboard that verifies a mobile event SDK. It makes the same date inputs and buckets easier to reproduce across browsers.
- This approach does not require a multi-project management UI. Setting location, mutability, and default can be decided separately. Mixpanel and PostHog support the concept of project configuration, but a Korean-language UI does not require hardcoded Asia/Seoul.
- If adopted, define the relationship between list/detail display, chart buckets/labels, and date input interpretation. Changing only browser display while server buckets use another timezone can cause confusion at date boundaries.
- The `receivedAt` basis can remain. This research provides no reason to switch to `occurredAt` to follow vendor product analytics event timestamps. UTC transport format, precision, and query end boundaries remain follow-up contract items.

## Official sources

All sources were directly checked on 2026-10-08. For some PostHog pages, the web tool could not process `text/markdown`, so HTML/Markdown from official URLs was read directly over HTTPS. The `.md` URLs below are reading formats of the same official documentation. They are not product execution results.

- M1: Mixpanel, Managing Projects: https://docs.mixpanel.com/docs/orgs-and-projects/managing-projects
- M2: Mixpanel, Reports Overview / Date Range: https://docs.mixpanel.com/docs/reports#date-range
- P1: PostHog, Charts / Calendar heatmap: https://posthog.com/docs/product-analytics/trends/charts.md
- P2: PostHog, Data model / Event fields: https://posthog.com/docs/how-posthog-works/data-model
- P3: PostHog, Person properties / GeoIP: https://posthog.com/docs/product-analytics/person-properties.md
- P4: PostHog, Timestamps: https://posthog.com/docs/data/timestamps.md
- P5: PostHog, Dashboards: https://posthog.com/docs/product-analytics/dashboards

Local comparison documents: `AGENTS.md`, `docs/agents/{domain,issue-tracker,triage-labels}.md`, `GLOSSARY.md`, `docs/adr/0002-openapi-json-contract.md`, `.scratch/signaldock-portfolio/{spec,discovery}.md`. No files outside this report were changed.
