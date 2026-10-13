---
status: accepted
---

# Korean and English UI for the Dashboard and the benchmark report (issue 27)

Issue 27 makes every visible string of `dashboard/` and `perf/report/` available in Korean and English, and adds the SDK device results to the report page. Spec Q135 to Q137 (2026-10-13) fix the decisions. This ADR records how the code follows them and what it leaves open. The user can change these decisions.

## Decisions

1. **Own typed dictionary, no new dependency (Q135).** A dictionary is a nested plain object of strings. A key is a dotted path (`list.title`). Each page has `src/i18n/en.ts` and `src/i18n/ko.ts`, and `src/i18n/index.ts` binds them. The shape is a subset of i18next, so a later switch to i18next needs no change in components or messages:

   | Rule | How it is enforced |
   |---|---|
   | Strings only, JSON-serializable | `Messages` type (`web-shared/i18n/messages.ts`); the format test checks every leaf of both pages |
   | Dotted keys | `translate()` walks the path and ignores inherited properties (`constructor`) |
   | `ko` has the keys of `en`, no more, no less | `SameKeys<typeof en, typeof ko>` in each `ko.ts`; a difference makes `tsc` fail (checked by hand with a removed key and an extra key); the format test checks the same at run time |
   | `t()` accepts only keys of `en` | `MessageKey<typeof en>` type: all leaf paths, with a plural pair `x_one` and `x_other` addressed as `x` |
   | `{{name}}` interpolation | `translate()`; the format test allows no `{` or `}` outside `{{name}}`, and checks that every form of a key (each plural form, both languages) uses the same placeholders. Like i18next's default, `translate()` inserts a value as `String(value)` and does not format it; a caller formats a number or date with the `format.ts` helpers before passing it |
   | Plurals `key_one` and `key_other` with `count` | `translate()` uses `Intl.PluralRules`, then `_other`, then the plain key; the format test requires a complete set for the language (English: `one` and `other`; Korean: `other`) and no plain key beside a plural set |
   | `t(key, options?)` and `useTranslation()` returning `{ t, i18n: { language, changeLanguage } }` | `createI18n()` in `web-shared/i18n/react.ts` |
   | Numbers and dates through `Intl`, times in the project timezone | `formatNumber`, `formatDate`, `formatDateTime`, `formatClock`, `formatBucketLabel` in `web-shared/i18n/format.ts`; the timezone is an argument, so the browser timezone is never used (Q78, Q79) |
   | No context variants, no `$t()` nesting, no ICU | The format test rejects `$t(`. The code has no context or ICU path. Needing one is the trigger to switch |

   English texts keep the ASD-STE100 style. The Korean texts follow the English meaning and add no new content.

2. **Default language (Q136).** `resolveLanguage()` takes `?lang=ko|en`, then the `localStorage` value `signaldock.lang`, then `navigator.language` (starts with `ko`: Korean; else English). An unknown value in the URL or in storage is ignored. Storage reads and writes are in try/catch. A switch writes the choice, sets `<html lang>`, and the page title. If the URL has `?lang=`, the switch updates it with `history.replaceState`, so a reload keeps the choice. This last point is an implementer addition to Q136.

3. **Shared code in `web-shared/i18n/` (Q137).** Plain TypeScript, no `package.json`. Both pages import it by relative path (`../../web-shared/i18n`). Each page adds `../web-shared/**` to its `tsconfig.json` `include` and to Vite `server.fs.allow`. Because the folder has no `node_modules`, `react.ts` and the tests import `react` and `vitest`; each page resolves them to its own copy with `resolve.dedupe` (Vite) and `paths` (TypeScript). `react.ts` has no JSX, so it does not need `react/jsx-runtime`. The Dashboard Dockerfile copies `web-shared/` into the build context. The pages keep their own lockfiles, builds, and messages.

4. **Tests run in one place.** The tests of the shared code (`web-shared/i18n/i18n.test.ts`) run with the Dashboard runner: `include` in `dashboard/vite.config.mts` lists `../web-shared/**/*.test.ts`. They also import the dictionaries of both pages, so the format rules cover `perf/report/` although that page has no test runner. `scripts/verify-contract.sh` already runs `npm test` in `dashboard/`.

5. **No visible text is hard-coded: a scan, not a render, is the main test.** `dashboard/src/hardcoded-strings.test.ts` parses every component file (`.tsx`, not tests) of both pages with the parser of Vite's bundler (`rolldown/utils`, no new dependency; TypeScript 7 has no JavaScript API) and fails on JSX text with a word (two letters in a row), on a string with a word in a JSX expression (also in `?:`, `&&`, `||`, `+`, and template text), and on a string with a word in `aria-label`, `title`, `placeholder`, `alt`, `label`, or `name`. Arguments of `t()` and other calls are not read. A small list of identifiers may stay (`receivedAt`, `installationId`, `eventId`, `ms`, `Hikari`). The scan sees both pages and checks its own rules on sample code. A scan is reliable and fast; a render test only sees the states it reaches. `dashboard/src/language.test.tsx` adds render tests: in Korean no English message of the dictionary is on screen (list, details, empty, error, stopped, and not-found states), in English no Korean text is on screen, and the switch changes the language, `<html lang>`, the title, and the saved choice. `dashboard/src/App.test.tsx` runs every earlier Dashboard test in both languages (`describe.each`) and reads the expected texts from the dictionaries.

6. **Error texts.** The Dashboard shows a translated sentence for a known API error code and keeps the code in its original form (`The query key is missing or wrong. (QUERY_KEY_INVALID)`). The codes are the ones in `openapi/contract-v1.yaml` that a query endpoint can return, plus `TEMPORARILY_UNAVAILABLE` (503). Other server messages, `HTTP <status>`, and the text of a JavaScript `Error` stay as the source sent them. Errors that the Dashboard makes itself (network error, failed request) carry a message key (`QueryError.i18nKey`), and a failed period carries a key (`resolvePeriod` returns `period.chooseDates`, `period.endBeforeStart`, or `period.tooLong` with `count`). The Q108 stopped state and the Q109 retry text are in the dictionaries. The rules of Q107 to Q109 do not change.

7. **Benchmark report: SDK results.** `perf/sdk_summarize.py --index perf/sdk-results perf/results/sdk-index.json` writes the committed values of every `perf/sdk-results/*/run.json` (target and actual rate, started and planned calls, event size, `track()` p50, p95, max, drain seconds and persisted retry wait, file sizes after drain, PSS and heap change, missing and duplicate rows, verdict and reasons, git revision). It computes nothing. It is a separate file because `perf/results/index.json` comes from the run directories of `perf/summarize.py`, which also read the `server.log` files that Git does not hold; writing that index again here would drop the Hikari series. `perf/report/scripts/copy-data.mjs` copies both files into `dist/` and into `dist/index.js`, so the page still opens from a file. The page shows one table row per AVD and scenario, and two charts: `track()` p50, p95, max, and target against actual rate. The offline row shows memory of the store process and the drain time with the persisted retry wait named.

## Not done

- Reason texts that the Python summaries write into the JSON (`invalid_reasons`, `fail_reasons`, and SDK `reasons`) and server error messages that have no known code are data. They are shown as written, in English, also in the Korean page.
- The report page has no render test. It has the scan, the format test of its dictionaries, and the type check. A render test would need a second React and Recharts copy in the Dashboard runner.
- No locale other than `en` and `ko`, and no language detection beyond Q136.
- Issue 28 (visual design) is separate. This issue changes no layout beyond the language switch.

## Limits

- `Intl` output depends on the browser. The tests that compare dates use `en` and fixed time zones.
- Korean line lengths are not tuned for narrow screens.
