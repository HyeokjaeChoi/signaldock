# SignalDock PR #9: CodeRabbit and Copilot Lite comparison

Date: 2026-10-09. [PR #9](https://github.com/HyeokjaeChoi/signaldock/pull/9). Comparison target: [`4f0773365a9765e617b26bf4bcab62bf45e785b7`](https://github.com/HyeokjaeChoi/signaldock/commit/4f0773365a9765e617b26bf4bcab62bf45e785b7).

**Both reviews are complete at the same SHA.** Lite found a confirmed execution blocker and a documentation/evidence gap. CodeRabbit found two complementary verification/setup gaps. Both produced advice that needs qualification. Use Lite first for this workflow's build-script/evidence checks and manual OSS CodeRabbit as a secondary reviewer. This single PR does not establish general tool superiority.

## Installation and authorization record

The main agent reports a successful CodeRabbit GitHub App installation, reference `169630044`, restricted to `HyeokjaeChoi/signaldock` (repository ID `1411453341`). The user explicitly approved Terms acceptance, OAuth authorization, and GitHub App permissions. These are recorded completed approvals; this document did not repeat or expand them. [GitHub installation settings](https://github.com/settings/installations/169630044), [repository](https://github.com/HyeokjaeChoi/signaldock).

No Slack connection, payment entry, or paid activation was completed. Installation is distinct from paid subscription activation. The account and authorization observations come from the main agent's supplied execution record; this writing pass did not inspect account settings or change external state.

## Review evidence

| Run | Target and timing | Result | Comparison use |
| --- | --- | --- | --- |
| [CodeRabbit 5471446615](https://github.com/HyeokjaeChoi/signaldock/pull/9#pullrequestreview-5471446615) | `4f07733`; submitted `2026-10-09T14:31:44Z` | Advanced plan, CHILL profile, default configuration; 29 files selected, 22 excluded; 2 inline comments and 1 nitpick | Validated below |
| [Earlier Copilot Lite 5470976522](https://github.com/HyeokjaeChoi/signaldock/pull/9#pullrequestreview-5470976522) | `ee3948cb649725eaf4f737ab05a0b72abea510a9`; submitted `2026-10-09T13:51:45Z` | 9 findings, reported fixed in `4f07733` | Historical contribution only; do not count as current open findings |
| [Copilot Lite 5471497031](https://github.com/HyeokjaeChoi/signaldock/pull/9#pullrequestreview-5471497031) | `4f07733`; requested `2026-10-09T14:24:40Z`, submitted `14:36:08Z` | 4 open findings; 9 prior findings marked resolved; request-to-publication interval 12m 28s | Same-SHA result validated below; interval includes any queue time |

The three published review IDs, commit IDs, and submission times were checked with `gh api`. CodeRabbit's review body supplies its profile, plan, and file counts. The supplied Copilot review body confirms Lite effort and the nine resolved findings; request time comes from the main agent. CodeRabbit request time is not recorded here, so completion timestamps cannot establish relative processing speed. No new review was requested during this documentation pass.

## CodeRabbit findings: checked against the target and spec

| Finding | Validated assessment |
| --- | --- |
| [Allow PATH Java](https://github.com/HyeokjaeChoi/signaldock/pull/9#discussion_r4231230685) | **Policy/usability suggestion.** The verifier explicitly requires `JAVA_HOME` or its bundled JDK and reports a setup error. The spec does not promise PATH-only setup. Removing the guard does not itself verify JDK 17. |
| [Install dashboard dependencies](https://github.com/HyeokjaeChoi/signaldock/pull/9#discussion_r4231230704) | **Valid setup/reproducibility gap.** Bare `tsc` does not prove the pinned compiler was used. The tree has no npm lockfile. The full comment correctly requires adding one first; its one-line suggestion and aggregate prompt assume or omit that prerequisite. `npm ci` alone would fail. [npm requirement](https://docs.npmjs.com/cli/v11/commands/npm-ci/) |
| TypeScript runtime round-trip assertions, in the review body | **Valid verification gap.** The sample only logs conversion results; the workflow runs only type checking. Executable assertions are needed to detect conversion regressions. This overlaps the independent static report's earlier limits. |

Result: **2 verification/setup gaps and 1 policy suggestion**. These are not three demonstrated product failures. No proposed patch was applied.

The review did not report the independent executable-bit defect in `codegen.sh` or the mutable-baseline compatibility defect; both scripts were selected. It also did not report the `__proto__` property-loss defect, but `generated/typescript/runtime.ts` was excluded by its default generated-path filter. This last item is a coverage limitation, not a demonstrated failure to detect an in-scope file defect. Numeric constraints remain a separately documented contract/adapter gap. See [static evidence and detailed validation](2026-10-09-review-comparison-static.md), the [pinned spec](https://github.com/HyeokjaeChoi/signaldock/blob/4f0773365a9765e617b26bf4bcab62bf45e785b7/.scratch/signaldock-portfolio/spec.md), and [ADR-0002](https://github.com/HyeokjaeChoi/signaldock/blob/4f0773365a9765e617b26bf4bcab62bf45e785b7/docs/adr/0002-openapi-json-contract.md).

## Copilot Lite findings: checked against the target and spec

| Finding | Validated assessment |
| --- | --- |
| [Numeric array validation](https://github.com/HyeokjaeChoi/signaldock/pull/9#discussion_r4231272932), template line 521 | **Latent template defect.** The loop checks the outer array instead of its item. It is inside the non-kotlinx_serialization branch, while generation uses kotlinx_serialization. Current property unions contain scalars only; spec line 57 excludes arrays. The bot's High label does not establish current runtime impact. |
| [Executable codegen script](https://github.com/HyeokjaeChoi/signaldock/pull/9#discussion_r4231273028) | **Active P1 defect.** Git mode is `100644`, while verification executes the script directly. Matches independent finding 1. The bot's numeric `0664` wording is imprecise but does not invalidate the missing executable permission. |
| [Missing oasdiff baseline operand](https://github.com/HyeokjaeChoi/signaldock/pull/9#discussion_r4231273098) | **Invalid.** Both spec paths are present. The [pinned tool registers `-o` as a boolean](https://github.com/oasdiff/oasdiff/blob/322d7ae815e5fb80f415bd4e8ef16d4b30cd8bdf/internal/diff.go), so it does not consume the baseline path. This is distinct from the genuine mutable-baseline compatibility defect. |
| [Gradle verification label](https://github.com/HyeokjaeChoi/signaldock/pull/9#discussion_r4231273160) | **Valid documentation/evidence gap.** TOOLCHAIN labels Gradle build-verified, while issue 01 records that Gradle could not run and direct kotlinc/tsc was used. Qualify the claim or add execution evidence. No claim is made that Gradle never ran elsewhere. |

Result: **1 active defect, 1 documentation/evidence gap, 1 latent defect outside the active path, and 1 invalid finding**. The supplied body does not report selected/excluded files. Its broad runtime-serialization summary is not supported for the active scalar configuration by these comments. See the [detailed static validation](2026-10-09-review-comparison-static.md).

## Evidence-based comparison

| Dimension | CodeRabbit Advanced / CHILL | Copilot Lite |
| --- | --- | --- |
| Confirmed contribution | Dependency reproducibility and TypeScript runtime-test gaps | Execution blocker and unsupported Gradle verification label |
| Required qualification | JAVA_HOME is a policy suggestion; npm ci needs the lockfile prerequisite from the full comment | Array branch is dormant; oasdiff argument claim is wrong |
| Overlap with independent review | Runtime-test gap was already noted as a limit | Executable-bit defect was already identified |
| Important unreported items | Executable-bit and mutable-baseline defects in selected scripts | Mutable-baseline defect and CodeRabbit's two verification/setup gaps |
| Scope limits | 29 selected / 22 excluded; generated runtime explicitly excluded | File-selection scope not reported |

Neither review reported numeric-policy gaps or `__proto__` property loss. For the latter, CodeRabbit excluded the file and Copilot's scope is unknown. The same SHA controls source differences, but differing review settings, file filters, and previous-review context remain confounders. Earlier Copilot findings are historical, not nine extra current defects. Raw counts, bot severity labels, and one run do not support a general quality ranking.

## Billing and recommended use

The main agent observed an Advanced trial ending **23 October**, **$90 estimated invoice**, and **Usage $0 / 0 active products**. Manage plan opened Continue to payment; payment was not completed. The estimate does not establish paid activation or a confirmed charge.

Official [plans](https://docs.coderabbit.ai/management/plans) say organizations that do not subscribe revert to Free after trial expiry. Public OSS separately receives Team features without a paid subscription or credit card; repositories with fewer than 10 stars require manual review requests. The [subscription guide](https://docs.coderabbit.ai/management/subscription-management) distinguishes trial access from confirmed paid activation. Actual SignalDock entitlement after expiry remains unobserved. See [billing and OSS research](2026-10-09-coderabbit-review.md).

Recommendation for SignalDock: retain Lite as the first pass for build scripts and execution-evidence claims, because it found the confirmed blocker that CodeRabbit omitted. Use manual CodeRabbit OSS reviews as a complementary secondary pass for verification gaps. Validate every actionable claim against the source and spec before applying a change. Neither tool is a merge gate on its own: both missed the compatibility weakness, and the suggested fixes include an invalid flag diagnosis and a lockfile prerequisite that can be lost in an abbreviated patch. This recommendation is based on the type and impact of validated findings, not 4 versus 3 comments.

Do not buy a subscription based on this sample. CodeRabbit use remains conditional on OSS eligibility and acceptance of the unresolved OSS data-policy issue in the research. The observed run used Advanced trial features; it does not prove identical review behavior or limits after transition to OSS.

## Execution and workspace limits

This evaluation read source and review metadata and wrote research documents. It did not run builds, generators, tests, or proposed fixes. Historical bot and implementation execution claims are not new test evidence. The current working tree contains uncommitted `docs/research/` documents only; no product changes were made in this evaluation. External actions were limited to the approved CodeRabbit account and app setup, the manual CodeRabbit review command, and the Copilot Lite review request. No autofix, product-code push, merge, or paid activation was performed. Both final review results are now recorded.
