# SignalDock installation ID research

Research date: 2026-10-08. Scope: official documentation, official public code, and RFC 9562. No implementation or SDK execution was performed.

## Conclusion

Use UUIDv4 for SignalDock's installation ID. Android's `java.util.UUID.randomUUID().toString()` produces the standard 36-character string. No prefix or suffix is needed. Generate it once when no stored ID exists, and share it across business events from the same installation. This recommendation is a design judgment based on the code below and the project scope. [Android UUID API](https://developer.android.com/reference/java/util/UUID#randomUUID()), [RFC 9562 §5.4](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.4)

The inspected default random ID generation paths in Segment Kotlin, Amplitude Kotlin, and Mixpanel Android all use `UUID.randomUUID()`. The version was not inferred from the word "UUID" in documentation. The Android API explicitly identifies this function's output as type 4. These three products do not establish a universal industry standard. Firebase Installations uses an FID format that differs from a UUID string. See the code evidence for each product below.

UUIDv7 was the user's example, not a requirement. Installation IDs are not generated for each event. For this project, UUIDv4's simple generation and clear lifecycle matter more than UUIDv7's time ordering. This is a judgment about the two-week, 20-30-hour portfolio scope, not a performance measurement.

## Comparison

The comparison assumes new storage, default settings, and no custom ID. Source evidence is in the pinned commit links in the next section.

| Example | Actual field meaning | Default generation and format | Storage and restart | identify/reset and installation boundaries |
|---|---|---|---|---|
| Segment Analytics-Kotlin Android | `anonymousId`: ID linking anonymous activity. Can coexist with `userId`. | `UUID.randomUUID().toString()`: UUIDv4, 36 characters. | Reads from `SharedPreferences` per write key. Saves initial state and state changes. Reuses a stored value when present. | Normal `identify(userId)` retains the anonymous ID. `reset()` immediately creates a new UUID and clears user ID and traits. The ID is therefore not permanently fixed to an installation. [S1-S4] |
| Amplitude Android-Kotlin | `deviceId`: value used for device/anonymous event identity. Has a separate `userId`. | Default path: UUIDv4 string + `R`, 37 characters. Advertising ID and App Set ID selection both default to `false`. | Saves and reads `device_id` in per-instance `identity.properties`. Reuses a valid stored ID. | `setUserId()` does not directly change the device ID. Default `reset()` clears the user ID and creates a new random device ID. Custom ID, ADID, or App Set ID settings can resolve to the same value again. [A1-A5] |
| Mixpanel Android | `distinct_id`: current analytics identity for events. Anonymous `$device_id` comes from internal `anonymous_id`. | Internal anonymous ID: UUIDv4, 36 characters. Initial `distinct_id`: `$device:` + UUID, 44 characters. | Saves and rereads `anonymous_id`, `events_distinct_id`, and other fields in `SharedPreferences`. | `identify(userId)` changes `distinct_id` to the user ID. The new installation's anonymous ID is retained. Default `reset()` creates new anonymous and distinct IDs. If a custom `DeviceIdProvider` returns the same value, the device ID can remain unchanged after reset. [M1-M3] |
| Firebase Installations Android | FID: ID distinguishing a FirebaseApp installation in Firebase services. Not a login user ID or auth token. | The new FID path transforms UUIDv4 bytes into 22-character Base64url without padding. Neither a standard UUID string nor UUIDv7. | Saves per-FirebaseApp-persistence-key JSON in `getNoBackupFilesDir()`. Reuses stored values and a memory cache. | Separate from login identify/reset. Can change after `delete()`, server deletion/invalidation, or documented long inactivity. Legacy Instance ID migration is an exception. [F1-F4] |

The shared observed pattern is that an SDK creates a random ID and stores it locally. An analytics SDK's anonymous identity and an installation ID have different meanings. In particular, logout `reset()` changes an ID within the same installation. Firebase provides an installation ID, but it is not permanently immutable in every situation.

## Documentation and code evidence

Code was pinned by SHA to the default branch HEAD at research time. These links do not point to moving branches. The SHAs were not verified against each Maven release. The web documents are current pages without separate version labels and can change later.

| Repository | Inspected branch | Pinned commit |
|---|---|---|
| `segmentio/analytics-kotlin` | `main` | `ae263605ab96a9bc874547d800f44400fe02762e` |
| `amplitude/Amplitude-Kotlin` | `main` | `89384390aa8a3136613434d7d5169c5e988f2310` |
| `mixpanel/mixpanel-android` | `master` | `37157c2f9345e38dba3a904aad350ee4091169ea` |
| `firebase/firebase-android-sdk` | `main` | `61d7ea4717667f5da0dd662008ac7c1699081124` |

### Segment Kotlin

- Documentation: Segment's official Identify document describes Anonymous ID as a pseudo-unique ID. It recommends UUIDv4 for new random IDs and says website/mobile libraries use Anonymous ID automatically. The canonical site returned 403, so pinned source from the official documentation repository was used. [S1: Identify documentation](https://github.com/segmentio/segment-docs/blob/747b35d8f5f1889b041f2101185d562d7c4c016e/src/connections/spec/identify.md#L104-L111)
- Code: `defaultState()` first reads the stored anonymous ID and creates one with `UUID.randomUUID()` if absent. `SetUserIdAndTraitsAction` retains the anonymous ID. [S2: Generation and identity state](https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/State.kt#L205-L246)
- Code: Android storage uses `analytics-android-${writeKey}` SharedPreferences. `StorageImpl.initialize()` also subscribes to the initial state, and `userInfoUpdate()` saves the anonymous ID. [S3: Android storage location](https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/android/src/main/java/com/segment/analytics/kotlin/android/Storage.kt#L65-L88), [State persistence](https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/utilities/StorageImpl.kt#L47-L76)
- Code: `identify(userId)` updates user ID/traits. `reset()` creates a new anonymous ID and clears identity. In this Kotlin code, generation occurs at the reset call, not the next app start. [S4: identify](https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/Analytics.kt#L221-L227), [reset](https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/Analytics.kt#L581-L595)

### Amplitude Kotlin

- Documentation: Device ID lifecycle describes existing IDs, selected external IDs, and the UUID + `R` path. `reset()` sets user ID to null and resolves device ID again according to configuration. The document also notes that app data transfer can leave the same stored ID on multiple devices. [A1: Android-Kotlin documentation](https://www.amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk#device-id-lifecycle)
- Code: `generateRandomDeviceId()` is `UUID.randomUUID().toString() + "R"`. Initialization checks configuration ID, valid stored ID, conditional ADID, conditional App Set ID + `S`, then random ID, in that order. [A2: Default generator](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/analytics-core/src/main/java/com/amplitude/core/platform/plugins/ContextPlugin.kt#L14-L18), [Selection order](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/android/src/main/java/com/amplitude/android/plugins/AndroidContextPlugin.kt#L71-L106), [Default settings](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/android/src/main/java/com/amplitude/android/Configuration.kt#L41-L55)
- Code: Default identity storage is file-based. It places `identity.properties` in an instance path under Android's `context.getDir("amplitude", MODE_PRIVATE)`. `saveDeviceId()` and `load()` save/restore `device_id`. A path also clears identity when the API key differs from the stored value. [A3: Android path](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/android/src/main/java/com/amplitude/android/Configuration.kt#L209-L215), [Storage wiring](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/android/src/main/java/com/amplitude/android/storage/AndroidStorageContextV3.kt#L25-L55), [Save and restore](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/analytics-core/src/main/java/com/amplitude/id/FileIdentityStorage.kt#L16-L55)
- Code: `setUserId()` and `setDeviceId()` have separate update paths. Amplitude's `identify()` handles user properties; its name does not make it equivalent to user ID assignment in Segment/Mixpanel. [A4: Identity changes](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/analytics-core/src/main/java/com/amplitude/core/IdentityCoordinator.kt#L31-L44), [Official API description](https://www.amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk)
- Code: Android `reset()` resolves the ID with `forceRegenerate = true`. Configuration ID and external ID selection still apply. The default random path produces a new value, but not every configuration does. [A5: reset](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/android/src/main/java/com/amplitude/android/Amplitude.kt#L160-L172), [User ID removal and ID replacement](https://github.com/amplitude/Amplitude-Kotlin/blob/89384390aa8a3136613434d7d5169c5e988f2310/analytics-core/src/main/java/com/amplitude/core/IdentityCoordinator.kt#L47-L64)

### Mixpanel Android

- Documentation: `identify()` assigns a known user ID. Logout guidance uses `reset()`. Current documentation says a custom `DeviceIdProvider` can control whether the ID persists after reset. [M1: Managing User Identity / Custom Device ID Generation](https://docs.mixpanel.com/docs/tracking-methods/sdks/android#managing-user-identity)
- Code: Without stored identity, the default path creates a UUIDv4 anonymous ID and prefixes `distinct_id` with `$device:`. This applies when no custom provider is present. Identity fields are saved in SharedPreferences. [M2: Generator and initial creation](https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/PersistentIdentity.java#L559-L624), [Persistence](https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/PersistentIdentity.java#L680-L699)
- Code: `identify()` replaces the events distinct ID. `setAnonymousIdIfAbsent()` retains an existing anonymous ID. `reset()` clears and rereads preferences to regenerate default IDs. Events use the anonymous ID as `$device_id`. [M3: identify](https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/MixpanelAPI.java#L866-L894), [Anonymous ID retention/reset](https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/PersistentIdentity.java#L214-L268), [reset](https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/MixpanelAPI.java#L1474-L1487), [event fields](https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/MixpanelAPI.java#L3126-L3133)

### Firebase Installations

- Documentation: FID identifies an app installation. A new ID can be used after client deletion. The document lists reinstall, cache deletion, and 270 days of backend inactivity as rotation cases. Do not extend "cache deletion" into a guarantee that every Android Settings cache-clear operation deletes FID. [F1: Official lifecycle documentation](https://firebase.google.com/docs/projects/manage-installations#monitor_the_firebase_installation_id_lifecycle)
- Code: `RandomFidGenerator` takes bytes from `UUID.randomUUID()`, processes prefix bits and the last byte, Base64url-encodes the result, and truncates it to 22 characters. Its input is UUIDv4; its output is FID format. Do not interpret prefix `0111` as a UUIDv7 version. [F2: FID generator](https://github.com/firebase/firebase-android-sdk/blob/61d7ea4717667f5da0dd662008ac7c1699081124/firebase-installations/src/main/java/com/google/firebase/installations/RandomFidGenerator.java#L23-L83)
- Code: Current storage uses `getNoBackupFilesDir()`. Migration moves an existing `getFilesDir()` file, with fallback to that file if the move fails. Do not apply the same Backup guarantee to all historical versions and migration cases. [F3: Storage path and migration](https://github.com/firebase/firebase-android-sdk/blob/61d7ea4717667f5da0dd662008ac7c1699081124/firebase-installations/src/main/java/com/google/firebase/installations/local/PersistedInstallation.java#L89-L114)
- Code: If stored state has no FID, one is generated and saved. Legacy migration can first read the existing Instance ID. Client deletion and server auth errors transition to a state requiring a new FID. Firebase also registers this local ID with the FIS server. [F4: Generation and migration](https://github.com/firebase/firebase-android-sdk/blob/61d7ea4717667f5da0dd662008ac7c1699081124/firebase-installations/src/main/java/com/google/firebase/installations/FirebaseInstallations.java#L500-L551), [Server invalidation and deletion](https://github.com/firebase/firebase-android-sdk/blob/61d7ea4717667f5da0dd662008ac7c1699081124/firebase-installations/src/main/java/com/google/firebase/installations/FirebaseInstallations.java#L603-L631)

## UUIDv4 and UUIDv7 for this installation ID

| Criterion | UUIDv4 | UUIDv7 |
|---|---|---|
| RFC structure | 122 random bits excluding version/variant | First 48 bits: Unix millisecond timestamp. Remaining 74 bits: random or specified monotonicity subfields |
| Time ordering | No generation-order sorting feature | Benefits time ordering and DB index locality. Does not guarantee actual arrival order or clock accuracy across devices. |
| Project cost | Uses the standard Android API. Needs no time or counter state. | Requires review of generator choice and clock/same-millisecond policies. |
| Exposed information | Does not encode creation time in the ID itself. | Creation time can be read from the ID. |
| Assessment | Suitable for an ID generated infrequently per installation. | Consider when bulk inserts and time ordering are actual requirements. Those requirements are not established for this installation ID. |

Evidence for structure and index properties: [RFC 9562 §2.1](https://www.rfc-editor.org/rfc/rfc9562.html#section-2.1), [§5.4](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.4), [§5.7](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.7), [§6.1](https://www.rfc-editor.org/rfc/rfc9562.html#section-6.1). Android generator evidence: [UUID.randomUUID()](https://developer.android.com/reference/java/util/UUID#randomUUID()). Project suitability is a judgment based on this evidence; no benchmark was run. The RFC's UUIDv7 recommendation also includes comparison with UUIDv1/v6. Do not read it as a requirement to replace every UUIDv4 with UUIDv7.

Installation IDs and event IDs are separate concepts. Do not use installation IDs to deduplicate events. SignalDock creates an event ID per business event and retains it for retries. This report does not decide the event ID's UUID version.

## SignalDock lifecycle recommendations and verification boundaries

These are first-release contract recommendations, not guarantees of completed implementation.

| Situation | Recommended contract / boundary |
|---|---|
| Normal restart, start after process termination, app update | Reuse the stored installation ID. Do not create one per start or batch. |
| App data deletion | Create a new UUIDv4 at the next initialization after the stored ID is gone. If earlier data is separately restored, apply the restore rules. |
| Uninstall and reinstall without restore | Create a new UUIDv4 if ID storage is fresh. |
| Reinstall or data restore to a new device | A new ID cannot be guaranteed automatically. A restored old ID can be reused. |
| Login/logout | The first release does not support user identity. Do not add automatic installation ID rotation as for an analytics logout identity. |

Official Android documentation says default SharedPreferences, DBs, and `getFilesDir()`/`getDir()` files are eligible for Backup and can be restored at installation. `getNoBackupFilesDir()` is excluded from Auto Backup. If reinstall must always produce a different installation ID, decide storage location and Backup policy before UUID version. Verify SDK storage and host app Backup settings together. Detailed platform research and the queue/ID Backup combination are separate main-agent review work. [Android Auto Backup: Files / Restore schedule](https://developer.android.com/identity/data/autobackup)

Verification status: Official documentation contracts and pinned code were checked. None of the four SDKs was run on Android. Storage completion during abrupt kill, concurrent initialization, multi-process use, data clear, reinstall, cloud restore, and device transfer remain unverified at runtime. Reuse after normal restart is also a code-path judgment conditional on completed storage and unchanged settings. Custom providers, legacy migration, all SDK releases, and manufacturer-specific restores were not comprehensively verified.

Final recommendation: Generate and retain a UUIDv4 installation ID locally. Provide only installation identity in the first release. Finalize reinstall/restore boundaries in the documented contract after verifying storage and Backup policy.
