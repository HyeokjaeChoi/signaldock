package local.signaldock.consumer

import android.app.Application
import kotlinx.coroutines.CoroutineScope
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
        init = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            SignalDock.initialize(
                this@ConsumerApplication,
                SignalDockConfig(
                    endpoint = BuildConfig.SIGNALDOCK_ENDPOINT,
                    collectionKey = BuildConfig.SIGNALDOCK_COLLECTION_KEY,
                    logLevel = LogLevel.INFO,
                ),
            )
        }
    }
}
