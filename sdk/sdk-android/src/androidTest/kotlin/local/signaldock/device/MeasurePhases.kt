package local.signaldock.device

import android.os.Build
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * SDK load measurement phases (issue 24, ADR-0014). Each phase is one instrumentation run in a fresh
 * app process, started by `scripts/device-measure.sh`:
 *
 *   adb shell am instrument -w -e class local.signaldock.device.MeasurePhases -e phase <name> \
 *       -e endpoint <url> -e key <collection key> -e runId <id> <runner>
 *
 * Phases: `normal` (1 event/s for 60 s), `burst` (100 events/s for 10 s), `offline_store` (1,000 events,
 * unreachable endpoint), `offline_drain` (restarted process, reachable endpoint). They use the public
 * `SignalDock.initialize` path: real Room, real OkHttp, real WorkManager, product defaults.
 * Raw output goes to `files/measure/<runId>/` (`calls-<phase>.csv`, `result-<phase>.json`); the script pulls it.
 * Without a `phase` argument every phase is skipped.
 */
class MeasurePhases {
    private fun phase(name: String) = assumeTrue("phase $name not selected", instrumentationArg("phase") == name)

    private val runId get() = instrumentationArg("runId")!!
    private val outDir get() = File(targetContext.filesDir, "measure/$runId").apply { mkdirs() }
    private val sdkDir get() = File(targetContext.noBackupFilesDir, "signaldock")
    private fun config() = SignalDockConfig(endpoint = instrumentationArg("endpoint")!!, collectionKey = instrumentationArg("key")!!)

    // About 1 KiB per event on the wire (see `eventBytes` in result.json). The text is fixed so every event has the same size.
    private val pad = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(40).take(PAD_CHARS)

    @Volatile private var eventBytes: Int? = null

    private class Call(val i: Int, val schedMicros: Long, val startMicros: Long, val durMicros: Long, val result: String, val eventId: String)

    /** PSS and Java heap in KiB. [settle] runs a GC first (about 0.3 s); without it the value is read at once. */
    private fun memory(settle: Boolean = true): JSONObject {
        if (settle) { System.gc(); Thread.sleep(300) }
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        return JSONObject().put("totalPssKb", mi.totalPss).put("javaHeapUsedKb", (rt.totalMemory() - rt.freeMemory()) / 1024)
    }

    private fun files(): JSONObject = JSONObject().also { o ->
        sdkDir.listFiles()?.sortedBy { it.name }?.forEach { o.put(it.name, it.length()) }
    }

    private fun init(): Pair<SignalDock, Long> {
        val t = System.nanoTime()
        val sdk = (runBlocking { SignalDock.initialize(targetContext, config()) } as InitResult.Success).sdk
        return sdk to (System.nanoTime() - t) / 1000
    }

    /**
     * Calls `track()` [count] times. [rate] events/s is open loop: call i starts at i / rate after the start,
     * on its own coroutine, so slow calls overlap instead of slowing the schedule. [rate] 0 means one call after
     * the other (concurrency 1). Returns the calls and the maximum number of calls in flight.
     */
    private fun inject(sdk: SignalDock, rate: Int, count: Int): Pair<List<Call>, Int> {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val t0 = System.nanoTime()
        suspend fun one(i: Int, schedNanos: Long): Call {
            val start = System.nanoTime()
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            val r = try {
                sdk.track("m_$runId") {
                    property("seq", i)
                    property("run", runId)
                    property("pad", pad)
                }
            } catch (e: Throwable) { e } finally { inFlight.decrementAndGet() }
            val end = System.nanoTime()
            // Right after the first success: this event is still queued (delivery starts at 20 events or 10 s), so its size is known.
            if (i == 0 && r is TrackResult.Success) eventBytes = sdk.dao.oldest(1).firstOrNull()?.sizeBytes
            return Call(
                i, schedNanos / 1000, (start - t0) / 1000, (end - start) / 1000,
                when (r) { is TrackResult.Success -> "ok"; is Throwable -> "exception:" + r.javaClass.simpleName; else -> r.javaClass.simpleName },
                (r as? TrackResult.Success)?.eventId.orEmpty(),
            )
        }
        val calls = if (rate == 0) {
            runBlocking(Dispatchers.Default) { (0 until count).map { one(it, 0) } }
        } else {
            runBlocking(Dispatchers.Default) {
                (0 until count).map { i ->
                    async {
                        val schedNanos = i * 1_000_000_000L / rate
                        delay(((schedNanos - (System.nanoTime() - t0)) / 1_000_000).coerceAtLeast(0))
                        one(i, schedNanos)
                    }
                }.awaitAll()
            }
        }
        return calls to maxInFlight.get()
    }

    private fun writeCalls(name: String, calls: List<Call>) {
        File(outDir, "calls-$name.csv").writeText(
            "i,sched_us,start_us,dur_us,result,event_id\n" + calls.joinToString("\n") { "${it.i},${it.schedMicros},${it.startMicros},${it.durMicros},${it.result},${it.eventId}" } + "\n",
        )
    }

    private fun drainSeconds(sdk: SignalDock, timeoutMillis: Long, from: Long = System.nanoTime()): Double? {
        val t = from
        return try { awaitTrue(timeoutMillis, "queue empty") { runBlocking { sdk.diagnostics().queuedEvents } == 0 }; (System.nanoTime() - t) / 1e9 } catch (e: AssertionError) { null }
    }

    private fun base(name: String): JSONObject = JSONObject()
        .put("phase", name).put("runId", runId).put("pid", android.os.Process.myPid())
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("api", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        .put("abi", Build.SUPPORTED_ABIS.first())
        .put("debuggable", targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)

    private fun finish(name: String, o: JSONObject) {
        File(outDir, "result-$name.json").writeText(o.toString(2))
        report("phase", name, "status", "pass", "runId", runId)
    }

    /** `normal` and `burst`: inject, then wait for the SDK's own delivery to empty the queue (5 minute limit). */
    private fun scenario(name: String, rate: Int, count: Int) {
        phase(name)
        val before = Logcat.snapshot()
        val memBeforeInit = memory()
        val (sdk, initMicros) = init()
        val memAfterInit = memory()
        val (calls, maxInFlight) = inject(sdk, rate, count)
        val injectionEnd = System.nanoTime() // drain time counts from here
        val queuedAtEnd = runBlocking { sdk.diagnostics() }
        val filesAtEnd = files()
        val memAtEnd = memory(settle = false) // no GC: a GC here would delay the drain clock
        val drain = drainSeconds(sdk, 300_000, injectionEnd)
        val memAfterDrain = memory()
        val d = runBlocking { sdk.diagnostics() }
        writeCalls(name, calls)
        val o = base(name)
            .put("targetRate", rate).put("plannedEvents", count).put("maxInFlight", maxInFlight)
            .put("initMicros", initMicros).put("eventBytes", eventBytes ?: JSONObject.NULL)
            .put("injectionSeconds", (calls.maxOf { it.startMicros } - calls.minOf { it.startMicros }) / 1e6)
            .put("queuedAtInjectionEnd", queuedAtEnd.queuedEvents).put("queuedBytesAtInjectionEnd", queuedAtEnd.queuedBytes)
            .put("drainSeconds", drain ?: JSONObject.NULL).put("queuedAfterDrain", d.queuedEvents)
            .put("lastAck", d.lastAck?.let { JSONObject().put("accepted", it.accepted).put("duplicate", it.duplicate).put("rejected", it.rejected) } ?: JSONObject.NULL)
            .put("lastDeliveryError", d.lastDeliveryError?.kind?.name ?: JSONObject.NULL).put("retryFailureCount", d.retryFailureCount)
            .put("sdkWarnings", Logcat.warnings(Logcat.since(before)).size)
            .put("filesAtInjectionEnd", filesAtEnd).put("filesAfterDrain", files())
            .put("memBeforeInit", memBeforeInit).put("memAfterInit", memAfterInit).put("memAtInjectionEnd", memAtEnd).put("memAfterDrain", memAfterDrain)
            .put("sinceInjectionEndSeconds", (System.nanoTime() - injectionEnd) / 1e9)
        finish(name, o)
    }

    @Test fun normal() = scenario("normal", 1, 60)

    @Test fun burst() = scenario("burst", 100, 1000)

    /** Unreachable endpoint (`http://127.0.0.1:9`): 1,000 events back to back. No delivery can succeed. */
    @Test
    fun offline_store() {
        phase("offline_store")
        val before = Logcat.snapshot()
        val memBeforeInit = memory()
        val (sdk, initMicros) = init()
        val memAfterInit = memory()
        val (calls, maxInFlight) = inject(sdk, 0, 1000)
        writeCalls("offline_store", calls) // before any wait, so a failed wait keeps the call record
        val filesAtEnd = files()
        val memAfterStore = memory() // read before the wait below, so both describe the end of injection
        // The first delivery attempt runs in the background: wait until it failed and its retry time is persisted (ADR-0014 item 6).
        awaitTrue(60_000, "a failed delivery attempt with a persisted retry time") {
            runBlocking { sdk.diagnostics() }.let { it.retryFailureCount > 0 && it.nextAttemptEpochMillis != null }
        }
        val d = runBlocking { sdk.diagnostics() }
        val o = base("offline_store")
            .put("targetRate", 0).put("plannedEvents", 1000).put("maxInFlight", maxInFlight).put("initMicros", initMicros)
            .put("eventBytes", eventBytes ?: JSONObject.NULL)
            .put("injectionSeconds", (calls.maxOf { it.startMicros } - calls.minOf { it.startMicros }) / 1e6)
            .put("queued", d.queuedEvents).put("queuedBytes", d.queuedBytes)
            .put("lastDeliveryError", d.lastDeliveryError?.kind?.name ?: JSONObject.NULL).put("retryFailureCount", d.retryFailureCount)
            .put("nextAttemptInSeconds", d.nextAttemptEpochMillis?.let { (it - System.currentTimeMillis()) / 1000.0 } ?: JSONObject.NULL)
            .put("sdkWarnings", Logcat.warnings(Logcat.since(before)).size)
            .put("files", filesAtEnd).put("memBeforeInit", memBeforeInit).put("memAfterInit", memAfterInit).put("memAfterStore", memAfterStore)
        finish("offline_store", o)
    }

    /**
     * Process restarted (script force-stops it), endpoint reachable. The SDK finds the stored queue and
     * delivers it by itself; the persisted retry time from the offline phase is honored (ADR-0011 item 6).
     */
    @Test
    fun offline_drain() {
        phase("offline_drain")
        val memBeforeInit = memory()
        val start = System.nanoTime()
        val (sdk, _) = init()
        val queuedBefore = runBlocking { sdk.diagnostics() }
        val waitSeconds = queuedBefore.nextAttemptEpochMillis?.let { ((it - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0) }
        val filesBefore = files()
        val drain = drainSeconds(sdk, 600_000, start) // ADR-0014 item 7: from the SDK start
        val d = runBlocking { sdk.diagnostics() }
        val o = base("offline_drain")
            .put("queuedAtStart", queuedBefore.queuedEvents).put("retryWaitAtStartSeconds", waitSeconds ?: JSONObject.NULL)
            .put("drainSeconds", drain ?: JSONObject.NULL).put("queuedAfterDrain", d.queuedEvents)
            .put("lastAck", d.lastAck?.let { JSONObject().put("accepted", it.accepted).put("duplicate", it.duplicate).put("rejected", it.rejected) } ?: JSONObject.NULL)
            .put("lastDeliveryError", d.lastDeliveryError?.kind?.name ?: JSONObject.NULL)
            .put("filesAtStart", filesBefore).put("filesAfterDrain", files())
            .put("memBeforeInit", memBeforeInit).put("memAfterDrain", memory())
            .put("sinceStartSeconds", (System.nanoTime() - start) / 1e9)
        finish("offline_drain", o)
    }

    private companion object {
        /** Tuned with one smoke run so that `eventBytes` (the SDK's own size of one event as sent) is about 1,024. */
        const val PAD_CHARS = 727
    }
}
