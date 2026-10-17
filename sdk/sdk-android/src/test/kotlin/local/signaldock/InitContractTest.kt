package local.signaldock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import local.signaldock.internal.init.AndroidLogger
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.init.createSdk
import local.signaldock.internal.scheduling.NoopScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File

/** Initialization and logging agreements Q110, Q115, Q116 and Q117 that the other JVM tests do not cover. */
@RunWith(RobolectricTestRunner::class)
class InitContractTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun sdkLogs() = ShadowLog.getLogsForTag("SignalDock").map { it.type to it.msg }

    @Test
    fun `Q110 a fatal Error is not turned into a failure result and a new call can retry`() = runBlocking {
        var calls = 0
        val co = InitCoordinator(scope) { c, cfg ->
            if (++calls == 1) throw NoClassDefFoundError("simulated") else createSdk(c, cfg)
        }
        try {
            co.initialize(context, testConfig)
            fail("an Error must propagate to the caller")
        } catch (e: NoClassDefFoundError) {
            // expected
        }
        assertTrue(co.initialize(context, testConfig) is InitResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun `Q115 the default level is WARN and only WARN and ERROR reach Logcat`() {
        assertEquals(LogLevel.WARN, testConfig.logLevel)
        ShadowLog.clear()
        AndroidLogger(LogLevel.WARN).apply { debug("d"); warn("w"); error("e") }
        assertEquals(listOf(android.util.Log.WARN to "w", android.util.Log.ERROR to "e"), sdkLogs())
        ShadowLog.clear()
        AndroidLogger(LogLevel.DEBUG).apply { debug("d"); warn("w"); error("e") }
        assertEquals(3, sdkLogs().size)
        ShadowLog.clear()
        AndroidLogger(LogLevel.ERROR).apply { debug("d"); warn("w"); error("e") }
        assertEquals(listOf(android.util.Log.ERROR to "e"), sdkLogs())
        ShadowLog.clear()
        AndroidLogger(LogLevel.NONE).apply { debug("d"); warn("w"); error("e") }
        assertEquals(emptyList<Pair<Int, String>>(), sdkLogs())
    }

    @Test
    fun `Q115 DEBUG adds initialization and delivery state but never content`() = runBlocking {
        val transport = FakeTransport()
        val config = testConfig.copy(logLevel = LogLevel.DEBUG)
        ShadowLog.clear()
        val sdk = createSdk(
            context, config, Clock { 1_760_000_000_123L }, SequenceIds(),
            transport = transport, scheduler = NoopScheduler, foregroundTriggers = false,
        )
        sdk.track("secret_event") { property("secret_key", "secret_value") }
        sdk.delivery.sender.run()
        val text = sdkLogs().joinToString("\n") { it.second }
        assertTrue("initialization state", text.contains("initialized"))
        assertTrue("delivery state", text.contains("delivery attempt"))
        val installationId = File(context.noBackupFilesDir, "signaldock/installation-id").readText().trim()
        for (secret in listOf("secret_event", "secret_key", "secret_value", config.collectionKey, installationId, "localhost", String(transport.requests.single().body))) {
            assertFalse("'$secret' in log", text.contains(secret))
        }
    }

    @Test
    fun `Q116 and Q117 Logcat is the only destination and the level is fixed at initialization`() = runBlocking {
        // No public log sink type, no logger parameter, no level setter: the only log setting is the config's logLevel.
        assertTrue(runCatching { Class.forName("local.signaldock.LogSink") }.isFailure)
        val configFields = SignalDockConfig::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("endpoint", "collectionKey", "logLevel", "callTimeout", "connectTimeout", "readTimeout", "writeTimeout", "allowInsecureEndpoint"), configFields)
        val publicNames = (SignalDock::class.java.methods + SignalDockConfig::class.java.methods).map { it.name.lowercase() }
        assertTrue(publicNames.none { it.startsWith("set") && (it.contains("log") || it.contains("level")) })
        assertTrue(publicNames.none { it.contains("logsink") || it.contains("logger") })

        // A later initialize with another level is a configuration conflict and keeps the instance.
        val co = InitCoordinator(scope) { c, cfg -> createSdk(c, cfg, scheduler = NoopScheduler, foregroundTriggers = false) }
        val first = (co.initialize(context, testConfig) as InitResult.Success).sdk
        assertEquals(InitResult.Failure(InitError.ConfigurationConflict), co.initialize(context, testConfig.copy(logLevel = LogLevel.DEBUG)))
        assertTrue(co.initialize(context, testConfig) is InitResult.Success)
        assertTrue((co.initialize(context, testConfig) as InitResult.Success).sdk === first)
    }
}
