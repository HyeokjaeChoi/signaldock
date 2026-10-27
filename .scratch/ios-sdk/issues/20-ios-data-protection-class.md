# 20. iOS Data Protection class for the queue and the installation ID

Status: resolved
Type: grilling
Blocked by: None

## Question for the owner

ADR-0047 D6 and D7 do not name an iOS Data Protection class for the installation ID file and the SQLite queue files. Source: Minor finding of the Claude review on PR #237 (2026-10-27).

- Facts to confirm with Apple docs first: the default class of app files, and what happens when a file of a stricter class is opened before the first unlock after a reboot.
- Failure scenario: the app runs before the first unlock (for example a launch by the system), the SDK cannot open the database, and `track()` or a drain fails.
- Options to put to the owner: (A) set the default class explicitly (complete until first user authentication) and treat an open failure as storage not available (ADR-0017); (B) a stricter class; (C) keep the OS default without stating it.
- Decide before ticket 07 (storage). Record the answer in an ADR-0047 amendment.

## Answer

Owner decision, 2026-10-28: option A. Set `completeUntilFirstUserAuthentication` explicitly on the installation ID file and on the SQLite queue files (database, `-wal`, `-shm`, `-journal`). An open failure makes `initialize()` return `InitError.StorageFailure` with no instance, as on Android (ADR-0003 item 6, ADR-0017); events in that window are dropped, and the host calls `initialize()` again later.

Facts confirmed from Apple (read 2026-10-28):

- Default class of third-party app files: `completeUntilFirstUserAuthentication` ("the default class for all third-party app data not otherwise assigned to a Data Protection class"). https://support.apple.com/guide/security/data-protection-classes-secb010e978a/web
- A file of this class cannot be accessed until the device has booted and the user has unlocked it for the first time; after that it stays accessible while the device is locked. https://developer.apple.com/documentation/foundation/fileprotectiontype/completeuntilfirstuserauthentication

So option A states the existing default. A system launch before the first unlock fails to open the files, and ADR-0017 covers that case. Recorded in the ADR-0047 amendment (2026-10-28). Ticket 07 implements it.
