package local.signaldock

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The code samples of docs/integration-guide.md. The guide copies the text between the
// `sample:` and `end sample` markers. Each sample compiles with the SDK; the tests below run
// the track and diagnostics samples against a real Room database and a fake transport.

// sample: initialize
class ShopApplication : Application() {
    /** The one initialization attempt. Activities await it before they call track(). */
    lateinit var sdkInit: Deferred<InitResult>
        private set

    override fun onCreate() {
        super.onCreate()
        sdkInit = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            SignalDock.initialize(
                this@ShopApplication,
                SignalDockConfig(endpoint = "https://events.example.com", collectionKey = "your-collection-key"),
            )
        }
    }
}

fun describe(result: InitResult): String = when (result) {
    is InitResult.Success -> "ready"
    is InitResult.Failure -> when (val e = result.error) {
        is InitError.InvalidConfiguration -> "fix the configuration: ${e.message}"
        InitError.ConfigurationConflict -> "another configuration is active"
        is InitError.StorageFailure -> "storage failed (${e.message}); call initialize again later"
    }
}
// end sample

// sample: track
suspend fun onProductViewed(sdk: SignalDock): String {
    val result = sdk.track("product_viewed") {
        property("product_id", "P-001")   // string
        property("quantity", 2)           // number
        property("price", 12.5)           // number
        property("is_test", true)         // boolean
    }
    return when (result) {
        is TrackResult.Success -> "stored on the device: ${result.eventId}" // not server receipt
        is TrackResult.Invalid -> "fix the call: ${result.reason} (${result.field})" // nothing stored
        TrackResult.OptedOut -> "collection is off" // nothing stored
        TrackResult.QueueFull -> "queue is full; the event was dropped" // existing events are kept
        is TrackResult.StorageError -> "storage failed: ${result.message}" // nothing stored
    }
}
// end sample

// sample: opt-out
suspend fun onUserWithdrawsConsent(sdk: SignalDock): Boolean {
    // false: the state could not be stored. It holds until the process ends, but after a
    // restart collection can resume, so call setOptOut(true) again later or tell the user.
    val stored = sdk.setOptOut(true) // from now on, track() stores nothing and returns OptedOut
    // Optional: delete events that wait on the device. null: storage failed, the events are
    // still queued, so call clearQueue() again later or tell the user.
    val deleted = sdk.clearQueue()
    return stored && deleted != null
}

suspend fun onUserGivesConsent(sdk: SignalDock) {
    sdk.setOptOut(false)  // collection resumes
}
// end sample

// sample: cancellation
fun onAddToCart(scope: CoroutineScope, sdk: SignalDock) {
    scope.launch {
        // If this coroutine is cancelled, track() throws CancellationException. The event may
        // already be stored. Do not catch it and do not call track() again for this action.
        sdk.track("add_to_cart") { property("product_id", "P-001") }
    }
}
// end sample

// sample: diagnostics
suspend fun queueSummary(sdk: SignalDock): String {
    val d = sdk.diagnostics()
    if (!d.storageAvailable) return "queue could not be read" // the other fields mean nothing
    return "queued=${d.queuedEvents} bytes=${d.queuedBytes} " +
        "lastAck=${d.lastAck?.accepted} lastError=${d.lastDeliveryError?.kind} " +
        "failures=${d.retryFailureCount} nextAttempt=${d.nextAttemptEpochMillis}"
}
// end sample

@RunWith(RobolectricTestRunner::class)
class IntegrationGuideSamplesTest {
    @Test
    fun `track sample stores one event and queueSummary reports it`() = runBlocking {
        val sdk = newSdk()
        assertTrue(onProductViewed(sdk).startsWith("stored on the device: "))
        assertEquals(1, sdk.diagnostics().queuedEvents)
        assertTrue(queueSummary(sdk).startsWith("queued=1 "))
    }

    @Test
    fun `opt-out sample stops collection and clears the queue`() = runBlocking {
        val sdk = newSdk()
        onProductViewed(sdk)
        assertTrue(onUserWithdrawsConsent(sdk))
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertEquals("collection is off", onProductViewed(sdk))
        onUserGivesConsent(sdk)
        assertTrue(onProductViewed(sdk).startsWith("stored on the device: "))
    }

    @Test
    fun `opt-out sample reports a failed clearQueue and still stops collection`() = runBlocking {
        val sdk = newSdk()
        onProductViewed(sdk)
        (sdk.dao.javaClass.getDeclaredField("__db").apply { isAccessible = true }.get(sdk.dao) as androidx.room.RoomDatabase).close()
        assertFalse(onUserWithdrawsConsent(sdk)) // clearQueue returned null
        assertTrue(sdk.isOptedOut())
    }

    @Test
    fun `opt-out sample reports a failed marker write even when clearQueue succeeds`() = runBlocking {
        val sdk = newSdk()
        onProductViewed(sdk)
        val dir = File(context.noBackupFilesDir, "signaldock")
        // A read-only SDK directory makes creating the marker file fail; the open database still works.
        assertTrue(dir.setWritable(false))
        try {
            assertFalse(onUserWithdrawsConsent(sdk)) // setOptOut(true) returned false
        } finally {
            dir.setWritable(true)
        }
        assertTrue(sdk.isOptedOut())              // holds for this process
        assertEquals(0, sdk.diagnostics().queuedEvents) // clearQueue succeeded
        assertFalse(File(dir, "opt-out").exists())
    }

    @Test
    fun `cancellation sample stores the event when it is not cancelled`() = runBlocking {
        val sdk = newSdk()
        onAddToCart(CoroutineScope(Dispatchers.Default), sdk)
        var queued = 0
        for (i in 1..100) {
            queued = sdk.diagnostics().queuedEvents
            if (queued == 1) break
            kotlinx.coroutines.delay(20)
        }
        assertEquals(1, queued)
    }

    @Test
    fun `describe covers every InitResult case`() {
        assertEquals("ready", describe(InitResult.Success(runBlocking { newSdk() })))
        assertEquals(
            "another configuration is active",
            describe(InitResult.Failure(InitError.ConfigurationConflict)),
        )
    }
}
