# CodeRabbit evaluation for SignalDock

Research date: 2026-10-09. Scope: a personal public GitHub monorepo with an Android Kotlin SDK, a Ktor backend, and a TypeScript dashboard. The initial sections record source research before installation. The addenda record the later installation and live review. Product code was not changed during this evaluation.

## Recommendation

CodeRabbit is a useful candidate for a second PR reviewer, if SignalDock receives the documented OSS entitlement and the owner accepts its access and data policies. Do not buy a plan just to obtain public-repository reviews. Resolve the OSS training-policy conflict before adoption if exclusion from model training is required. This recommendation is an assessment, not a tested result.

**SignalDock-specific entitlement is unverified.** The main agent checked GitHub: SignalDock is public and has zero stars on 2026-10-09. No CodeRabbit account plan or live inline review was checked. Under the documented public-repository policy, expect manual review requests while the repository has fewer than 10 stars.

## Free, OSS, and the trial are different

| Access | PR review entitlement | Duration |
| --- | --- | --- |
| General Free | PR summaries only. Its rate-limit row does not establish inline-review access. | Ongoing Free tier |
| Public/OSS | Team features without a paid subscription, including PR code review | Ongoing public/OSS offer; separate from the trial |
| New-account trial | Default 14-day Advanced trial | Temporary; without a subscription the general account reverts to Free |

The current [plan document](https://docs.coderabbit.ai/management/plans) explicitly separates Free from OSS. Therefore, “Free provides only summaries” does not mean that eligible public repositories lose code review after the trial.

CodeRabbit's [OSS page](https://www.coderabbit.ai/oss) describes eligibility as a public repository whose code anyone can read. It says there is no application or approval queue: sign in with GitHub or GitLab and install on the repository. This is the vendor's eligibility description, not a legal license assessment. Its offer includes ongoing free public-repository reviews. The [pricing page](https://www.coderabbit.ai/pricing) also distinguishes free public-repository reviews from the trial. “Ongoing” describes the published offer, not a guarantee that its terms can never change.

The [subscription guide](https://docs.coderabbit.ai/management/subscription-management) explains trial and paid activation. A trial is not evidence of lasting OSS access. Do not infer automatic paid activation merely from trial expiry. Exact account billing state is unknown.

## Prices and operating limits

Current USD list prices per developer: Essentials $30 monthly, or $24/month with annual billing; Team $60 or $48; Advanced $90 or $72. Enterprise requires a quote. Pro and Pro+ are former plan names. [Pricing](https://www.coderabbit.ai/pricing)

The current plan table gives OSS **1–10 PR reviews per developer per rolling hour**, additionally scoped per repository; **100–300 files per review** after exclusions; and **25 chat messages per hour**. Project community and popularity affect OSS limits. Public repositories with **fewer than 10 stars require manual review requests**. Exact SignalDock limits and a general OSS concurrency ceiling are unknown. General Free lists one PR event per hour, but remains summary-only. [Plan limits](https://docs.coderabbit.ai/management/plans)

A push or manual review can consume one event. Multiple commits in one push consume one event. A new push can supersede a running review after its allowance was spent. A rate-limited check can pass even though no review ran; read the status comment. Paid adaptive limits can fall to one review per hour with one review at a time. Do not apply those paid bands to OSS without evidence. [Rate-limit rules](https://docs.coderabbit.ai/management/rate-limits)

## Kotlin, TypeScript, and the monorepo

CodeRabbit says it works with all programming languages, with different levels of proficiency. That is not a guarantee of Android lifecycle, coroutine, or Ktor correctness. [Language support](https://docs.coderabbit.ai/faq#language-support)

Its detekt integration covers `.kt` and `.kts`, accepts a custom configuration, and skips execution when detekt already runs in GitHub workflows. Keep compilation and runtime tests as independent checks. [detekt](https://docs.coderabbit.ai/tools/detekt)

ESLint supports TypeScript and workspace configurations. Important limits: no type-aware `@typescript-eslint` linting; no private plugins/configurations; plugins must be allowed; and execution is skipped if root `package.json` is absent. It finds the nearest supported config for each changed file. SignalDock's actual tool execution is unverified. Keep TypeScript type checking in CI. [ESLint](https://docs.coderabbit.ai/tools/eslint)

A root `.coderabbit.yaml` can define `reviews.path_filters` and `reviews.path_instructions`. Use separate guidance for SDK, server, and dashboard paths. This is one repository; cross-repository linking is not needed just because it contains several packages. No configuration was created. [Configuration reference](https://docs.coderabbit.ai/reference/configuration)

Default exclusions include generated directories, lock files, dependencies, binaries, and build output such as `dist`. Consider explicit exclusions for Android/Gradle build artifacts. Exclusions win; positive includes restrict scope. Exact default patterns must be used to override default exclusions. Filters also affect sparse checkout, so exclusions can reduce tool context. Keep API schemas, migrations, and build manifests eligible. [Path controls](https://docs.coderabbit.ai/configuration/path-instructions)

## Data and GitHub access

**The official training statements conflict for OSS.** The [FAQ](https://docs.coderabbit.ai/faq#data-security) says customer code is not used for model training and is sent to OpenAI and/or Anthropic for review. The [Terms of Service, section 3.3](https://www.coderabbit.ai/legal/terms-of-service) also states no customer-code training and zero retention with model providers. However, the [Privacy Policy, section 7](https://www.coderabbit.ai/privacy-policy) explicitly exempts OSS from its no-training representation and says OSS is used to train its systems. Do not promise no training for SignalDock. The exact applicable OSS treatment is unresolved.

Caching is enabled by default. Caches expire within seven days. The cache document excludes OSS projects from its encryption statement. `reviews.disable_cache: true` disables caching. [Caching](https://docs.coderabbit.ai/reference/caching)

`knowledge_base.opt_out: true` removes retained Knowledge Base data and disables features that need it. It is separate from caching and model training. It does not establish deletion of every log or account record. [Knowledge Base retention](https://docs.coderabbit.ai/knowledge-base/index#opt-out-of-data-retention)

The Privacy Policy gives purpose-based personal-data retention rather than a fixed universal period. It describes US servers and possible residual records after deletion. Exact OSS log retention and provider handling for this repository are unknown. [Privacy Policy, sections 9–12](https://www.coderabbit.ai/privacy-policy)

The [GitHub integration guide](https://docs.coderabbit.ai/platforms/github-com) lists:

- OAuth read access to account email addresses and organizations/teams.
- GitHub App read access to Actions, discussions, members, metadata, and merge queues.
- Read/write access to checks, code, commit statuses, issues, pull requests, and workflows.

The guide says review-only use does not modify workflows, but the requested permission includes writing them. Repository access can be limited to selected repositories. These are documented permissions; no authorization screen was opened or accepted.

## Complementary use with Copilot Lite

User-provided observations only: Copilot Student shows 200 monthly AI credits; Lite review shows 23 used; Balanced failed with a quota error despite showing zero used. Copilot was not researched or independently checked.

Use Lite as an available second opinion. Do not infer that Balanced has usable capacity, that the counters share a quota, or that CodeRabbit has better review quality. If adopted later, request CodeRabbit reviews on ready changes to conserve its separate allowance. Human review, builds, type checks, and tests remain the basis for merge decisions. No comparative runtime evaluation was performed.

## Proposed SignalDock trial

Limit installation to SignalDock. Request one full review on PR #9 and compare its findings with the nine existing Copilot Lite findings. Count confirmed defects, duplicate comments, and incorrect suggestions before choosing a primary reviewer. Keep Copilot Lite available for manual checks during this evaluation.

Use path instructions for `sdk/`, `server/`, and `dashboard/`. Keep `openapi/`, generator templates, build scripts, and contract fixtures in scope. Exclude build output and vendor wrappers. Treat generated DTO review separately from schema and generation-drift checks: an AI review cannot replace the required regeneration, compilation, and compatibility checks in ADR-0002. No `.coderabbit.yaml` was added by this evaluation.

## Addendum: trial expiry, billing, and public OSS (2026-10-09)

Account observations verified by the main agent through Computer Use: Advanced trial ends 23 October; estimated invoice $90; Usage $0 with 0 active products. Manage plan opened a Continue to payment form. No payment information was entered and payment was not completed. Both PR #9 reviews are running against `4f07733`; results remain pending. These observations update the earlier account-unknown context, not the initial research record.

The official [plans document](https://docs.coderabbit.ai/management/plans) says organizations that do not subscribe revert to Free after the trial. It requires a valid payment method before paid activation. The [subscription guide](https://docs.coderabbit.ai/management/subscription-management) requires payment information where needed and confirmed activation; choosing Continue Trial during completed activation can schedule billing for trial end. Therefore, an unpaid, unactivated trial is expected to revert to Free, not automatically become a $90 paid subscription. This is an inference from the documented flow and reported observations. The estimated amount alone is not proof of a confirmed subscription or issued invoice; Usage $0 does not establish the complete billing state.

Public OSS is separate from general Free: eligible public projects receive Team features without a paid subscription or credit card, under OSS limits. Trial expiry does not itself end that documented offer. Repositories with fewer than 10 stars require manual review requests. SignalDock's actual post-trial entitlement is still unobserved. [Official plans and OSS rules](https://docs.coderabbit.ai/management/plans), [official OSS offer](https://www.coderabbit.ai/oss)

Only this note was added. No payment, paid activation, scheduled subscription, plan change, or new review request was made.

## Completed installation and comparison

CodeRabbit was installed on SignalDock only after the user approved the Terms, OAuth access, and GitHub App permissions. Both reviewers completed PR #9 at `4f0773365a9765e617b26bf4bcab62bf45e785b7`. See [the final comparison](2026-10-09-review-comparison.md) for the validated results and current recommendation. Earlier sections describe the pre-installation research snapshot.
