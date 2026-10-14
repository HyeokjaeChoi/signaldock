---
status: accepted
---

# SDK crash safety (issue 33, item 1)

Spec section 3 says to report invalid input and expected storage failures through result types, and to propagate caller cancellation. It does not say what the other public calls do when storage or scheduling fails. `CrashSafetyTest` (`sdk-android`, JVM) tests the public API against each recoverable failure class. These decisions fill the gaps. They are implementer decisions that the user can change.

## Decisions

1. **`track()` turns any non-cancellation `Exception` from storage into `TrackResult.StorageError`.** Before, only `IOException`, `SQLiteException` and `IllegalStateException` were caught. A `SecurityException`, `IllegalArgumentException` or other runtime exception from the storage layer would have reached the host. This is the policy that `InitCoordinator` already has for `initialize()`.
2. **A `CancellationException` is caller cancellation only while the caller's coroutine is cancelled.** A closed Room database cancels its own coroutine scope. A `track()`, `diagnostics()` or delivery call on a closed database then threw `JobCancellationException` at a caller that was still active. The SDK now checks the caller's `isActive`: if the caller is active, the exception is a storage failure (`StorageError`, an unavailable snapshot, or a logged delivery failure). If the caller is cancelled, the exception propagates as before (spec section 3, `LifecycleTest`). The cancellation WARN for uncertain storage is unchanged.
3. **`Error` still propagates** (for example `NoClassDefFoundError`, Q110). Only `Exception` is caught. `InitContractTest` covers `initialize()`; `CrashSafetyTest` covers `track()`.
4. **`diagnostics()` does not throw on a storage failure.** The spec gives it a non-null `Diagnostics` result and no error type. The new field `storageAvailable` (default `true`) is `false` when the queue could not be read; the other fields are then empty and carry no meaning. A WARN with the exception class name is logged. Adding a result type would change the public API and every caller, which the first release does not need.
5. **A scheduling failure never reaches the host.** `WorkManagerScheduler` caught only `IllegalStateException` (WorkManager not initialized). A failure in WorkManager's own database would fail `initialize()`, fail `track()` after the event was stored, or crash the host's main thread in the activity lifecycle callback. Every `Exception` except cancellation is now logged as a WARN and ignored; foreground delivery and the next trigger still work. WorkManager can also fail after `enqueue` returns, through the returned `Operation`. The scheduler observes that result: on a failure it logs the same WARN and clears the pending one-time marker, so a later request enqueues again (`SchedulingTest`).
6. **An exception thrown by the host's own `track { ... }` block is not caught.** It is the host's bug and it propagates unchanged.

## Test limits

- A full disk cannot be produced under Robolectric (Room uses WAL, and `PRAGMA max_page_count` does not stop WAL growth). The test injects `SQLiteFullException`, `SQLiteDiskIOException` and `SQLiteCantOpenDatabaseException` at the storage boundary (`EventSink`). The closed database is real: the test closes the Room instance.
- Queue limits use `QueueFull` (spec section 4), which has its own test.
- Calls before initialization cannot happen through the public types: `track()` needs the instance from a successful `initialize()`. The tests cover `initialize()` failures (invalid configuration, conflict, storage failure), the Worker without an instance (`SchedulingTest`), and calls on an instance whose database was closed.
