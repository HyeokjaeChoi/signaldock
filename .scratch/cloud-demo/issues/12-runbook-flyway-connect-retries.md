# 12. Runbook section 15: name the Flyway connect retries

Status: claimed
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR 221 review, recorded under AGENTS.md rule 7.

`docs/runbook-demo.md` section 15 says the server runs Flyway "with no other setting". Since PR 221, `Database.migrate()` sets `connectRetries(5)` (back-off 1, 2, 4, 8, 16 s). Update the sentence and say that a database that stays down still fails the start after about 31 s.

Verification: `grep -n connectRetries docs/runbook-demo.md` shows the line.
