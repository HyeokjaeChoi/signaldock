package local.signaldock.measure

import android.content.Context
import android.os.Build
import android.os.Bundle
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * SDK load measurement phases (issue 24, ADR-0014; shared by the debug androidTest `MeasurePhases` and the
 * R8 release measure app, issue 33, ADR-0018). The phases use the public `SignalDock.initialize` path: real
 * Room, real OkHttp, real WorkManager, product defaults. Only public SDK API is called, so the same source
 * compiles against the SDK library (debug) and against the SDK inside an R8-minified app (release).
 *
 * Phases: `normal` (1 event/s for 60 s), `burst` (100 events/s for 10 s), `offline_store` (1,000 events,
 * unreachable endpoint), `offline_drain` (restarted process, reachable endpoint).
 * Raw output goes to `files/measure/<runId>/` (`calls-<phase>.csv`, `result-<phase>.json`); the script pulls it.
 */
class MeasureRunner(
    private val targetContext: Context,
    private val args: Map<String, String>,
    private val sendStatus: (Bundle) -> Unit,
) {
    /** True if [name] is the selected phase. */
    fun selected(name: String) = args["phase"] == name

    /** Runs the selected phase; false if `phase` names none. */
    fun run(): Boolean {
        when (args["phase"]) {
            "normal" -> scenario("normal", 1, 60)
            "burst" -> scenario("burst", 100, 1000)
            "offline_store" -> offlineStore()
            "offline_drain" -> offlineDrain()
            else -> return false
        }
        return true
    }

    private fun arg(name: String) = args[name]

    private val runId get() = arg("runId")!!
    private val outDir get() = File(targetContext.filesDir, "measure/$runId").apply { mkdirs() }
    private val sdkDir get() = File(targetContext.noBackupFilesDir, "signaldock")
    private fun config() = SignalDockConfig(endpoint = arg("endpoint")!!, collectionKey = arg("key")!!)

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
            if (i == 0 && r is TrackResult.Success) eventBytes = firstQueuedEventBytes()
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

    /** The SDK's own size of the oldest queued event as sent, read from the queue file (public API only; works under R8). */
    private fun firstQueuedEventBytes(): Int? {
        val f = File(sdkDir, "queue.db")
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT sizeBytes FROM events ORDER BY seq LIMIT 1", null).use { c -> return if (c.moveToFirst()) c.getInt(0) else null }
        } finally { db.close() }
    }

    private fun drainSeconds(sdk: SignalDock, timeoutMillis: Long, from: Long = System.nanoTime()): Double? {
        val t = from
        return if (awaitTrue(timeoutMillis) { runBlocking { sdk.diagnostics().queuedEvents } == 0 }) (System.nanoTime() - t) / 1e9 else null
    }

    private fun base(name: String): JSONObject = JSONObject()
        .put("phase", name).put("runId", runId).put("pid", android.os.Process.myPid())
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("api", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        .put("abi", Build.SUPPORTED_ABIS.first())
        .put("debuggable", targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)

    private fun finish(name: String, o: JSONObject) {
        File(outDir, "result-$name.json").writeText(o.toString(2))
        sendStatus(Bundle().apply { putString("phase", name); putString("status", "pass"); putString("runId", runId) })
    }

    /** Waits until [cond] is true; false after [timeoutMillis]. */
    private fun awaitTrue(timeoutMillis: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > end) return false
            Thread.sleep(50)
        }
        return true
    }

    /** The SDK's Logcat lines (tag `SignalDock`). Earlier output stays in the buffer: take a snapshot first. */
    private fun logSnapshot(): List<String> {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-s", "SignalDock:V").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines().filter { it.length > 2 && it[1] == '/' }
        p.waitFor()
        return lines
    }

    private fun warningsSince(before: List<String>): Int =
        logSnapshot().let { now -> if (now.size >= before.size) now.drop(before.size) else now }.count { it.startsWith("W/") || it.startsWith("E/") }

    /** `normal` and `burst`: inject, then wait for the SDK's own delivery to empty the queue (5 minute limit). */
    private fun scenario(name: String, rate: Int, count: Int) {
        val before = logSnapshot()
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
            .put("sdkWarnings", warningsSince(before))
            .put("filesAtInjectionEnd", filesAtEnd).put("filesAfterDrain", files())
            .put("memBeforeInit", memBeforeInit).put("memAfterInit", memAfterInit).put("memAtInjectionEnd", memAtEnd).put("memAfterDrain", memAfterDrain)
            .put("sinceInjectionEndSeconds", (System.nanoTime() - injectionEnd) / 1e9)
        finish(name, o)
    }

    /** Unreachable endpoint (`http://127.0.0.1:9`): 1,000 events back to back. No delivery can succeed. */
    private fun offlineStore() {
        val before = logSnapshot()
        val memBeforeInit = memory()
        val (sdk, initMicros) = init()
        val memAfterInit = memory()
        val (calls, maxInFlight) = inject(sdk, 0, 1000)
        writeCalls("offline_store", calls) // before any wait, so a failed wait keeps the call record
        val filesAtEnd = files()
        val memAfterStore = memory() // read before the wait below, so both describe the end of injection
        // The first delivery attempt runs in the background: wait until it failed and its retry time is persisted (ADR-0014 item 6).
        check(
            awaitTrue(60_000) { runBlocking { sdk.diagnostics() }.let { it.retryFailureCount > 0 && it.nextAttemptEpochMillis != null } },
        ) { "timed out waiting for a failed delivery attempt with a persisted retry time" }
        val d = runBlocking { sdk.diagnostics() }
        val o = base("offline_store")
            .put("targetRate", 0).put("plannedEvents", 1000).put("maxInFlight", maxInFlight).put("initMicros", initMicros)
            .put("eventBytes", eventBytes ?: JSONObject.NULL)
            .put("injectionSeconds", (calls.maxOf { it.startMicros } - calls.minOf { it.startMicros }) / 1e6)
            .put("queued", d.queuedEvents).put("queuedBytes", d.queuedBytes)
            .put("lastDeliveryError", d.lastDeliveryError?.kind?.name ?: JSONObject.NULL).put("retryFailureCount", d.retryFailureCount)
            .put("nextAttemptInSeconds", d.nextAttemptEpochMillis?.let { (it - System.currentTimeMillis()) / 1000.0 } ?: JSONObject.NULL)
            .put("sdkWarnings", warningsSince(before))
            .put("files", filesAtEnd).put("memBeforeInit", memBeforeInit).put("memAfterInit", memAfterInit).put("memAfterStore", memAfterStore)
        finish("offline_store", o)
    }

    /**
     * Process restarted (script force-stops it), endpoint reachable. The SDK finds the stored queue and
     * delivers it by itself; the persisted retry time from the offline phase is honored (ADR-0011 item 6).
     */
    private fun offlineDrain() {
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
