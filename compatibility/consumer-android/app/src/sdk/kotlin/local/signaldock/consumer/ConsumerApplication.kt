package local.signaldock.consumer

import android.app.Application
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import local.signaldock.InitResult
import local.signaldock.LogLevel
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig

class ConsumerApplication : Application() {
    /** The one initialization attempt, started in onCreate. The activity awaits it. */
    lateinit var init: Deferred<InitResult>
        private set

    override fun onCreate() {
        super.onCreate()
        // Host-cost measurement (issue 36, ADR-0020): initialize() is called on the main thread. The
        // coroutine starts undispatched, so main_ns is the main-thread time until initialize() first
        // suspends (it includes the coroutine setup). total_ns runs until the result is ready.
        val t0 = SystemClock.elapsedRealtimeNanos()
        var outcome = "pending"
        init = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).async(start = CoroutineStart.UNDISPATCHED) {
            SignalDock.initialize(
                this@ConsumerApplication,
                SignalDockConfig(
                    endpoint = BuildConfig.SIGNALDOCK_ENDPOINT,
                    collectionKey = BuildConfig.SIGNALDOCK_COLLECTION_KEY,
                    logLevel = LogLevel.INFO,
                    // The default endpoint is http://10.0.2.2:8080 (emulator host). Allow http only then (ADR-0035).
                    allowInsecureEndpoint = BuildConfig.SIGNALDOCK_ENDPOINT.startsWith("http://", ignoreCase = true),
                ),
            ).also { outcome = if (it is InitResult.Success) "success" else "failure" }
        }
        val mainNs = SystemClock.elapsedRealtimeNanos() - t0
        init.invokeOnCompletion {
            val totalNs = SystemClock.elapsedRealtimeNanos() - t0
            Log.i("SignalDockHostCost", "init main_ns=$mainNs total_ns=$totalNs result=$outcome")
        }
    }
}
