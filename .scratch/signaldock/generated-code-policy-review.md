# Review of the Git policy for OpenAPI and jOOQ generated code

Review date: 2026-10-08. The user adopted option 1 of revised Q91. Git tracking and regeneration checks for both OpenAPI and jOOQ replace Q90. Proposal wording below records the review at that time. This was a Staff-level design review. CI, generation, builds, and PR protection rules have not been implemented.

## Proposed conclusion

The recommendation for SignalDock is to commit both OpenAPI Kotlin/TypeScript generated source and jOOQ generated source, then use CI to check that regeneration from clean inputs exactly matches the committed output. Direct edits to generated code are prohibited. The sources of truth are the OpenAPI schema and Flyway migrations. Generated files are derived results that can be reviewed. The user's request to reconsider the policy does not itself approve a change.

The earlier recommendation gave more weight to reducing duplicated generated files and diff noise. In this project, PR visibility into actual Kotlin/TypeScript types, serialization mappings, and jOOQ schema mappings also matters, as does code navigation and normal compilation without generation tools. Git tracking alone does not make the code safer. Excluding generated files and regenerating them correctly each time can also ensure consistency, and CI artifacts can provide generated diffs.

## Three separate checks

| Risk | Required check | Is Git tracking alone sufficient? |
| --- | --- | --- |
| Two branches edit the same line | Resolve Git merge conflicts | Detects only some cases |
| Schema/config and generated source differ | Regenerate all output, then compare file lists and contents | No |
| Generated output matches but API semantics break | Compare against earlier schemas; run wire fixtures, compilation, and runtime contract tests | No |

For example, branch A may add a schema field while branch B changes a generator option. A text merge can succeed even though the result is not the correct output for the merged schema/config. The merge candidate also needs regeneration. Migration checks must handle duplicate migration numbers and edits to existing migrations.

## Differences between the two generators

For OpenAPI, the actual required/nullable/type/serialization representations in SDK/server Kotlin models and Dashboard TypeScript models matter. Generated source in a PR exposes the impact of generator upgrades. An OpenAPI diff alone cannot verify the semantics of generator output.

jOOQ must generate code by applying Flyway migrations to temporary PostgreSQL and inspecting the resulting schema. Checked-in source can allow normal compilation without a code generation DB. Schema validation, integration tests, and runtime DB migrations remain necessary. Pin Postgres/jOOQ/Flyway/JDBC, generator settings, and schema scope. Do not use production or development data databases for codegen.

The official jOOQ documentation describes trade-offs in both policies without a clear winner. Check-in helps track schema changes and changes in generator behavior, but can leave files out of sync. That documentation does not require SignalDock to check in generated source. [official jOOQ documentation](https://www.jooq.org/doc/latest/manual/code-generation/codegen-version-control/)

## Proposed verification flow

1. Use the PR's schema/migrations, generator versions, config, and templates. Do not use the latest external spec or a developer DB as input.
2. Generate all managed code in an empty temporary output directory. Include every OpenAPI Kotlin and TS target. For jOOQ, use the migration result from isolated PostgreSQL.
3. Compare file lists and contents between temporary output and committed generated directories. Treat added, deleted, and changed files as failures. Overwriting an existing directory and checking only tracked diffs can miss stale files and new untracked files.
4. On failure, provide a diff. CI must not automatically commit/push or repair the code to make the check pass.
5. After confirming that committed source matches regeneration, run compilation, contract fixtures, and the required DB integration checks.
6. The same checks must pass after merging with the target branch. Protection rules or a merge queue require a check of repository features and permissions before configuration. They are not currently claimed to be active.

Separate local command roles as well. generate updates derived files from their sources. check-generated only generates temporary output and compares it. Normal compile must not silently overwrite committed generated source. Schema authors update generated output with the schema, and CI blocks omissions. If adopted, this changes Q90 from "always generate before compile" to "require a regeneration match check for the source that will be compiled". Keep Q43's migration→codegen→compile verification path.

## Reproducibility and review noise

Pin generator binary/plugin versions, settings, templates, and the execution environment. Remove unnecessary differences such as timestamps, absolute paths, and environment-specific line endings through generation settings. Do not erase meaningful type/annotation differences through broad post-processing. jOOQ provides output settings such as generatedAnnotationDate; verify them in the selected version. [generatedAnnotationDate](https://www.jooq.org/doc/latest/manual/code-generation/codegen-advanced/codegen-config-generate/codegen-generate-annotations/)

Commit only required generated source and generated support files needed for compilation. Exclude build caches, binaries, logs, temporary databases, and reports tied to execution times. Keep handwritten adapters outside generated directories. Resolve source/config conflicts first, then regenerate to resolve generated-output merge conflicts. Do not finish by mechanically choosing ours/theirs for generated source.

## Checks required during implementation

- Confirm that two clean generation runs with identical inputs produce identical output.
- Confirm failure when the schema changes without updating generated files.
- Confirm detection of added/deleted generated files and manual edits.
- Confirm that regeneration, compilation, and contract tests run on the merged input combination.
- Retain existing upgrade checks from earlier schemas as well as fresh DB migration checks.

Only planning and document review are complete. The independent OpenAPI research was reviewed and incorporated. Official documentation and source pinned to v7.15.0 showed that timestamp options do not remove every time-dependent output and that typescript-fetch conversion functions need review for null/absent-key handling. v7.15.0 was not adopted as the project's version. Recheck templates/options for the version actually selected. [independent OpenAPI research](openapi-generated-tracking-research.md)

Additional official evidence: Git diff behavior depends on the comparison target, so checking tracked changes cannot replace a file-set comparison of the entire generated directory. GitHub merge queue supports validation that includes the target branch and preceding changes. This does not make a merge queue mandatory for SignalDock. The final input state being verified is what matters. [Git diff](https://git-scm.com/docs/git-diff), [GitHub merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
