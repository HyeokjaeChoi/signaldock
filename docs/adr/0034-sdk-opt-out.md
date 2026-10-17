---
status: accepted
---

# SDK opt-out and clearQueue (issue 58, spec Q156)

The user decided (Q156, option a, 2026-10-16) that a host app can stop collection and clear the queue. This ADR records the API and the details that Q156 leaves open.

## Context

- The SDK had no stop switch and no queue clear. The threat model listed this as a gap (`docs/engineering/threat-model.md`).
- Amplitude `optOut` stores and sends nothing while it is `true`. The Segment consent plugin works in the same way.
- All SDK data lives in `noBackupFilesDir/signaldock` (ADR-0001). The SDK instance exists only after a successful `initialize` (Q111).

## Decision

1. **API (additive).** On `SignalDock`: `suspend fun setOptOut(optedOut: Boolean): Boolean`, `fun isOptedOut(): Boolean`, and `suspend fun clearQueue(): Int?`. New result `TrackResult.OptedOut` (a `data object`). The SDK starts with collection on.
2. **`track()` while opted out** returns `OptedOut` before anything else: no validation, no ID, no write. Nothing is sent for it, because nothing was stored.
3. **Queued events stay, and the sender still sends them.** Q156 says the host decides. No design source says that opt-out must stop the sender. A host that must stop sending calls `clearQueue()`. Rejected: a sender that pauses while opted out. It would keep old events on the device with no way to send them, and it would add a second state to the retry logic.
4. **`clearQueue()`** deletes every row of the `events` table in one statement and returns the count. It returns `null` on a storage failure (ADR-0017: no exception; caller cancellation propagates, as in `diagnostics()`). Rejection and ACK diagnostics stay; they hold no event content. A batch that is in flight can still reach the server, and its ACK finds no rows (harmless). Rejected: single-item delete (spec section 6: no arbitrary queue deletion).
5. **Persistence.** A marker file `opt-out` in the SDK directory (`noBackupFilesDir/signaldock`): the file exists while opted out. Auto Backup does not restore it (ADR-0001, ADR-0011), so a restored app starts with collection on. It is read once at initialization into a `@Volatile` flag, which `track()` reads. Rejected: a Room table (needs a schema migration, ADR-0003) and `SharedPreferences` (not in the SDK directory).
6. **`setOptOut` order.** It sets the flag first, so the change holds at once. It then writes the file on `Dispatchers.IO` inside `NonCancellable`, so cancellation cannot leave the file behind the flag. It returns `false` if the file write failed (the change still holds for this process; the SDK writes a WARN). Opted-out is the safe side: if removing the marker fails, the next start is opted out. The file writes run under one lock and write the flag value at that time, so two calls cannot leave the file different from the flag.
7. **Before initialization.** There is no call before initialization, because `setOptOut` is a method of the instance and the instance exists only after `initialize` succeeds. The state is loaded in `createSdk`, before the instance is returned, so the first `track()` already sees it. A host that wants no collection before consent does not call `track()`, or calls `setOptOut(true)` first.
8. **Race.** A `track()` that started before `setOptOut(true)` returned can still store its event. The KDoc says so.
9. **Threads.** The flag is `@Volatile`; file work is under a lock; `clearQueue` is one Room statement. No new thread pool.
10. **Not added.** No opt-out count in `Diagnostics` (the `Diagnostics` constructor must not grow, ADR-0030; `isOptedOut()` gives the state), no log of how many events were dropped, no listener, no server change, and no contract change.

## Compatibility

- Binary compatible: the dump only adds. `apiCheck` passes after `apiDump`.
- Source compatible except one case: a host `when` expression over `TrackResult` with no `else` branch fails to compile until it handles `OptedOut`. The guide sample, the demo `ShopScreen`, and the generated code were checked. `docs/versioning.md` counts "another result type" as documented-behavior change; in `0.x` this is a MINOR change and the CHANGELOG lists it under "Changed".

## Limits

- Gap: the tests use Robolectric and re-create the SDK on the same files. A real process restart with the marker file is not run on a device.
- The opt-out does not remove events already on the server. Server delete is excluded on purpose (ADR-0028).
- The marker file is written with `fsync` of the file but not of the directory. After a power loss right after `setOptOut(true)`, the marker may be missing and collection would restart. This is the same durability as the installation ID file.

## Amendment (2026-10-17, issue 64)

The Gap in "Limits" about a real process restart is partly closed. `DurabilityPhases` has the phases `optout_set`, `optout_check`, and `optout_off`. `scripts/device-durability.sh <serial> optout` runs them with `am force-stop` between them. The phases check three facts:

- After `setOptOut(true)`, the marker file `opt-out` exists, and `track()` returns `OptedOut` with an empty queue.
- After the process kill, a new `initialize` reads the marker: `isOptedOut()` is `true`, `track()` returns `OptedOut`, nothing is stored, and the local `AckServer` gets no request.
- After a second kill, `setOptOut(false)` removes the marker, and `track()` stores an event.

Verified on 2026-10-17: `scripts/device-durability.sh emulator-5556 optout` passed on an API 37 emulator in three different processes, with 0 requests in the second phase. Gap: the "no request" check is a fixed 3-second wait. It is not an event.
