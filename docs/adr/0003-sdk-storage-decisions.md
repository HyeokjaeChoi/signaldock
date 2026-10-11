---
status: accepted
---

# SDK storage and collection decisions (issue 03)

The design sources leave these points open (spec section 8 "C. Small modules and local execution", section 9 items 1, 5 and 6: "fix exact signatures during implementation"). This ADR records the choices made in issue 03. It adds no new product behavior.

## Decisions

1. **KSP pin: 2.3.12.** KSP has no 2.4.x release as of 2026-10-09. Since KSP 2.3 the version is independent of the Kotlin version. 2.3.12 is the latest stable release. It was build-verified with Kotlin 2.4.21, AGP 8.13.2, Gradle 8.14.5 and Room 2.8.5 (Room schema export and DAO generation ran). Pinned in `gradle/libs.versions.toml`.
2. **Module layout.** `sdk/sdk-android` is the one publication module (Q86). `sdk/` stays the independent Gradle build (Q89). Generated Kotlin models are compiled in as a source directory (`../../generated/kotlin/src/main/kotlin`) and used for the event transport JSON, so the size limits are measured on the real contract bytes. Cost: the generated classes are public in the AAR. Hiding or relocating them is left to the distribution issue (R8 consumer test).
3. **Queue row = one event's transport JSON.** The `events` table stores `payloadJson` (the generated `EventIngest` serialized by kotlinx.serialization), its UTF-8 `sizeBytes`, `eventId` (unique), `installationId`, `contractVersion` and `createdAtMillis`. The 16 KiB event limit and the 16 MiB queue budget use `sizeBytes`. The batch wrapper is added by the sender (issue 04).
4. **Whitespace set for names and keys (Q64): Kotlin `Char.isWhitespace()`** (Unicode `White_Space` plus space separators such as U+00A0). Server and fixtures must use the same set. This is not yet in the contract or fixtures; the server issue and the contract fixtures must align with it.
5. **Public API shape.** `SignalDock.initialize(context, config): InitResult` (suspend), `track(name) { property(...) }: TrackResult` (suspend), `diagnostics(): Diagnostics` (suspend). Property overloads: `String`, `Boolean`, `Int`, `Long`, `Double`. There is no `Float` overload, because widening `Float` to `Double` changes the value. Times in diagnostics are epoch milliseconds (`Long`): `java.time` needs API 26 and minSdk is 24. Timeouts use `kotlin.time.Duration`.
6. **Corrupt installation ID file.** If the stored value is not a UUIDv4, the SDK logs a WARN and writes a new ID. Stored events keep the installation ID from collection time (ADR-0001). An unreadable or unwritable file is a storage failure (`InitError.StorageFailure`).
7. **Diagnostics storage.** Tables `rejections` (last 100, no payload or name) and `retry_state` (single row: failure count, next attempt time, last ACK counts) exist in schema version 1. Issue 04 writes `retry_state`. The exported schema is committed under `sdk/sdk-android/schemas/`.
8. **JVM tests.** Robolectric 4.17 with `sdk=35` (SDK 36 needs JDK 21; the pinned JDK is 17) and real Room on the Robolectric file system. No emulator.

## Not covered here

Delivery, ACK application by the sender, WorkManager scheduling and foreground triggers (issue 04). Backup exclusion on a real device and a real process restart are not verified by these JVM tests.
