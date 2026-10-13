# 27. Korean and English UI for the Dashboard and the benchmark report

Status: needs-triage
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

The Dashboard (`dashboard/`) and the benchmark report page (`perf/report/`) show every UI string in Korean or English. The user selects the language in the UI. Both pages use one approach.

Includes:
- All visible strings: labels, headings, table headers, chart axes and legends, empty, loading, and error states, badges such as "pilot: not counted", and the Dashboard contract-error and retry messages (spec Q107 to Q109).
- Number, date, and time formats that follow the selected locale. Times stay in the project timezone (spec Q78, Q79).
- The benchmark report page also shows the SDK device results (`perf/sdk-results/`, issue 24), which it does not show now (ADR-0014 "Not done"), so that all benchmark results are on one page in both languages.

Excludes: API error codes, event names, property keys, identifiers, and commands. They stay in their original form.

Open questions (decide before implementation, record as spec Q entries):
1. Message catalog: a small typed dictionary per language, or an i18n library. Prefer no new dependency unless needed.
2. Default language: browser language, a fixed default, or a URL parameter; where the choice is kept.
3. Where shared strings and formatters live, given that the two pages have separate builds (ADR-0008, ADR-0013).

Done when both pages render fully in each language with no hard-coded visible string left (checked by a test), existing Dashboard tests pass in both languages, and screenshots of both languages are in the evidence folder.

## Comments

2026-10-13: Created at the user's request.
