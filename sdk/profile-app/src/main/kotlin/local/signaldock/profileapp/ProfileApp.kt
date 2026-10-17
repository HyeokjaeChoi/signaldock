package local.signaldock.profileapp

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Trace
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig

/** Name of the trace section that :baseline-profile measures. */
const val SDK_READY = "profileapp:sdk_ready"

/**
 * Holds the one initialize() call. It starts on first use (MainActivity), not in onCreate: the Macrobenchmark
 * library starts this process by a broadcast to install the profile, and an SDK that runs then schedules
 * WorkManager jobs that start the process again after the compile step, which the library rejects.
 */
class ProfileApplication : Application() {
    val init: Deferred<InitResult> by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            SignalDock.initialize(
                this@ProfileApplication,
                // Port 9 on the device itself: nothing answers, so the sender runs its request and failure path.
                // The key is a dummy: an empty key fails validation and initialize() would return at once.
                SignalDockConfig(
                    endpoint = "http://127.0.0.1:9",
                    collectionKey = "profile-dummy-key",
                    allowInsecureEndpoint = true,
                ),
            )
        }
    }
}

/** Waits for initialize(), tracks a few events, reads diagnostics, then reports "fully drawn". */
class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply { text = "SignalDock profile app"; textSize = 20f }
        setContentView(output)
        // The benchmark reads this async trace section: from here to the end of the SDK work (initialize(), the
        // tracks, diagnostics()). Async, because the work runs on several threads. Frame metrics are not used:
        // they fail on emulators without render-thread slices.
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(SDK_READY, 0)
        scope.launch {
            try {
                val sdk = (application as ProfileApplication).init.await().let { (it as? InitResult.Success)?.sdk }
                if (sdk == null) { output.text = "init failed"; return@launch }
                repeat(EVENTS) { i -> sdk.track("profile_event") { property("i", i) } }
                output.text = sdk.diagnostics().toString()
            } finally {
                if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(SDK_READY, 0)
            }
            reportFullyDrawn()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object { const val EVENTS = 20 }
}
