# 20. iOS Data Protection class for the queue and the installation ID

Status: needs-triage
Type: grilling
Blocked by: None

## Question for the owner

ADR-0047 D6 and D7 do not name an iOS Data Protection class for the installation ID file and the SQLite queue files. Source: Minor finding of the Claude review on PR #237 (2026-10-27).

- Facts to confirm with Apple docs first: the default class of app files, and what happens when a file of a stricter class is opened before the first unlock after a reboot.
- Failure scenario: the app runs before the first unlock (for example a launch by the system), the SDK cannot open the database, and `track()` or a drain fails.
- Options to put to the owner: (A) set the default class explicitly (complete until first user authentication) and treat an open failure as storage not available (ADR-0017); (B) a stricter class; (C) keep the OS default without stating it.
- Decide before ticket 07 (storage). Record the answer in an ADR-0047 amendment.
