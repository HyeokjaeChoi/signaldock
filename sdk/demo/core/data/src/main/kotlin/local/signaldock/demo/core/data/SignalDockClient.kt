package local.signaldock.demo.core.data

import kotlinx.coroutines.Deferred
import local.signaldock.Diagnostics
import local.signaldock.InitResult
import local.signaldock.TrackResult

/**
 * What the demo features need from the SDK. ViewModels depend on this interface, so tests use a fake.
 * [TrackResult.Success] means the event is stored on the device. It never means server receipt.
 */
interface SignalDockClient {
    suspend fun track(event: DemoEvent): TrackResult

    /** Read-only device queue snapshot. Throws if the SDK is not initialized or storage fails. */
    suspend fun diagnostics(): Diagnostics
}

/** Real facade. [init] is the Application's initialization attempt; calls wait for it. */
class SdkSignalDockClient(private val init: Deferred<InitResult>) : SignalDockClient {
    override suspend fun track(event: DemoEvent): TrackResult = when (val r = init.await()) {
        is InitResult.Success -> r.sdk.track(event.name) {
            for ((key, value) in event.properties) {
                when (value) {
                    is PropertyValue.Text -> property(key, value.value)
                    is PropertyValue.Whole -> property(key, value.value)
                    is PropertyValue.Decimal -> property(key, value.value)
                    is PropertyValue.Flag -> property(key, value.value)
                }
            }
        }
        is InitResult.Failure -> TrackResult.StorageError("SDK not initialized: ${r.error}")
    }

    override suspend fun diagnostics(): Diagnostics = when (val r = init.await()) {
        is InitResult.Success -> r.sdk.diagnostics()
        is InitResult.Failure -> throw IllegalStateException("SDK not initialized: ${r.error}")
    }
}
