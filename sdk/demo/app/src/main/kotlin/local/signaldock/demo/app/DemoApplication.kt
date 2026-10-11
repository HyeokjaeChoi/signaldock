package local.signaldock.demo.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import local.signaldock.LogLevel
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.demo.core.data.SdkSignalDockClient
import local.signaldock.demo.core.data.SignalDockClient

class DemoApplication : Application() {
    /** The composition root hands this one client to the ViewModels. */
    lateinit var client: SignalDockClient
        private set

    override fun onCreate() {
        super.onCreate()
        // Explicit initialization in Application (spec section 3). Calls to the client wait for it.
        val init = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            SignalDock.initialize(
                this@DemoApplication,
                SignalDockConfig(
                    endpoint = BuildConfig.SIGNALDOCK_ENDPOINT,
                    collectionKey = BuildConfig.SIGNALDOCK_COLLECTION_KEY,
                    logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.WARN,
                ),
            )
        }
        client = SdkSignalDockClient(init)
    }
}
