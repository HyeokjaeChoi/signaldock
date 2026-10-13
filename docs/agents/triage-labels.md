# Triage labels

Use these five canonical triage roles and their repository label strings.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

After the work merges, set `Status: resolved` (the completion state from `issue-tracker.md`; for implementation issues the `Execution:` line is the record, see there). An issue whose `Execution:` line starts with `Done` must be `resolved`, so no agent picks it up again. `scripts/check-repo-rules.sh` checks this.

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the "Label in our tracker" column to match whatever vocabulary you actually use.
