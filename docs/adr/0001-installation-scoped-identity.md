---
status: accepted
---

# Installation-scoped UUIDv4 identifier

The first SignalDock release identifies client app installations without linking them to logged-in users. At the user's request to decide after researching industry practice, the installation ID is a UUIDv4 string generated with Android's standard `UUID.randomUUID().toString()`. The reviewed default random ID generation paths in Segment Kotlin, Amplitude Kotlin, and Mixpanel Android also use UUIDv4. This ID is generated infrequently for each installation and does not need UUIDv7's time ordering. Identity lifetimes differ across products, so their generation practices inform the choice, while SignalDock defines its own contract.

The SDK generates and stores the installation ID during first initialization. It reuses the ID across normal app restarts, process restarts, and app updates. The ID is stored in an SDK-specific location under `noBackupFilesDir` to exclude restoration through Android Auto Backup. The intended behavior is to create a new ID after data clearing, reinstallation, or a supported restore to a new device. The SDK does not change backup settings for the whole client app. This decision prioritizes identifying separate installations over identity continuity between installations.

## Implementation and verification implications

- Concurrent initialization and Worker execution must use the same stored ID. Verify atomic generation and storage, and handling of storage failures.
- Each event stores the installation ID from collection time. Delivery must not replace it with the current installation ID.
- Installation IDs and event IDs are separate. This decision does not select a UUID version for event IDs.
- In the later Q42 agreement, the SDK queue, failure diagnostics, and retry state were also excluded from backup and device-transfer restoration. They persist across restarts and normal updates of the same installation, but previous data is not carried over after data clearing, reinstallation, or a supported restore to a new device. Document that unsent events can be lost at this boundary. Backup policies for other client app data remain unchanged. The exclusion implementation, including DB sidecars, and device verification have not been performed.
- This is currently a design decision. After implementation, verify normal restarts, data clearing, reinstallation, and backup restoration in supported environments. Do not claim guarantees for every manufacturer's transfer tool or arbitrary restoration by a host app.

## Evidence

- [Sol 6.1 research report: official documentation and pinned commit comparison](../../.scratch/signaldock-portfolio/installation-id-research.md)
- [Android UUID.randomUUID(): UUIDv4 generation](https://developer.android.com/reference/java/util/UUID#randomUUID())
- [Android Auto Backup: default backup scope and restoration](https://developer.android.com/identity/data/autobackup)
- [Android getNoBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir())
