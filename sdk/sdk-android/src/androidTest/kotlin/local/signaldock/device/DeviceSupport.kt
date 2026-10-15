package local.signaldock.device

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.internal.delivery.Jitter
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.init.createSdk
import local.signaldock.internal.scheduling.NoopScheduler
import local.signaldock.internal.scheduling.WorkScheduler
import local.signaldock.internal.storage.QueueLimits
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The app under test is the test APK itself (a library has no other app): its data directory is the SDK host's. */
internal val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

internal fun instrumentationArg(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

/** Prints `INSTRUMENTATION_STATUS: key=value` lines that scripts/device-durability.sh reads. Arguments: key, value, key, value, ... */
internal fun report(vararg keyValues: String) {
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { keyValues.toList().chunked(2).forEach { putString(it[0], it[1]) } })
}

/** Same app, other `noBackupFilesDir`, so that each test starts with its own empty SDK storage. */
internal class IsolatedContext(base: Context, private val dir: File) : ContextWrapper(base) {
    override fun getNoBackupFilesDir(): File = dir.apply { mkdirs() }
}

internal fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Event objects of a request body as compact JSON, in order. Equals the stored `payloadJson` of each event. */
internal fun eventPayloads(body: String): List<String> =
    Json.parseToJsonElement(body).jsonObject["events"]!!.jsonArray.map { it.toString() }

internal fun eventIdOf(payload: String): String =
    Json.parseToJsonElement(payload).jsonObject["eventId"]!!.jsonPrimitive.content

/**
 * A MockWebServer on the device that behaves like the ingestion server for ACK purposes: the first time
 * an eventId is seen it is "accepted" (committed), later "duplicate". It records every request.
 */
internal class AckServer(private val delayMillis: Long = 0) {
    enum class Mode { OK, DROP_AFTER_COMMIT, UNAVAILABLE }

    val server = MockWebServer()
    @Volatile var mode: Mode = Mode.OK
    @Volatile var delay: Long = delayMillis
    val committed: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    val bodies = CopyOnWriteArrayList<String>()
    val receivedIds = CopyOnWriteArrayList<String>()
    val duplicateAcks = AtomicInteger()
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val requestCount = AtomicInteger()

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** [bindTo] null: loopback. A device address makes the server unreachable when the device has no network. */
    fun start(bindTo: java.net.InetAddress? = null): AckServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount.incrementAndGet()
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                try {
                    if (delay > 0) Thread.sleep(delay)
                    if (mode == Mode.UNAVAILABLE) return MockResponse.Builder().code(503).build()
                    val body = request.body!!.utf8()
                    bodies += body
                    val ids = eventPayloads(body).map { eventIdOf(it) }
                    receivedIds += ids
                    val results = ids.mapIndexed { i, id ->
                        val status = if (committed.add(id)) "accepted" else { duplicateAcks.incrementAndGet(); "duplicate" }
                        """{"index":$i,"eventId":"$id","status":"$status"}"""
                    }
                    if (mode == Mode.DROP_AFTER_COMMIT) {
                        // Committed, response lost (architecture "Lost ACKs and event deduplication").
                        return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
                    }
                    return MockResponse.Builder().code(200).body("""{"results":[${results.joinToString(",")}]}""").build()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        if (bindTo == null) server.start() else server.start(bindTo, 0)
        return this
    }

    fun config(logLevel: local.signaldock.LogLevel = local.signaldock.LogLevel.WARN) =
        SignalDockConfig(endpoint = url, collectionKey = "device-test-key", logLevel = logLevel)

    fun close() { runCatching { server.close() } }
}

/**
 * One isolated SDK for one test: own storage directory and scope, real Room, real OkHttp to [AckServer],
 * and real WorkManager unless [scheduler] says otherwise. [initialize] also installs a coordinator so the
 * real [local.signaldock.internal.scheduling.DeliveryWorker] finds this SDK.
 */
internal class Rig(
    val server: AckServer,
    private val limits: QueueLimits = QueueLimits(),
    private val scheduler: WorkScheduler? = NoopScheduler,
    private val foregroundTriggers: Boolean = false,
    private val jitter: Jitter = Jitter { 0 },
    val config: SignalDockConfig = server.config(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val context = IsolatedContext(targetContext, File(targetContext.noBackupFilesDir, "rig-" + UUID.randomUUID()))
    private val savedDefault = InitCoordinator.default

    suspend fun initialize(): SignalDock {
        InitCoordinator.default = InitCoordinator(scope) { c, cfg ->
            createSdk(
                c, cfg, limits = limits, jitter = jitter, scheduler = scheduler,
                scope = scope, foregroundTriggers = foregroundTriggers,
            )
        }
        val result = InitCoordinator.default.initialize(context, config)
        return (result as? InitResult.Success ?: throw AssertionError("initialize failed: $result")).sdk
    }

    fun close() {
        scope.cancel()
        InitCoordinator.default = savedDefault
        runCatching { WorkManager.getInstance(targetContext).cancelAllWork().result.get(10, TimeUnit.SECONDS) }
        server.close()
        // Delete the rig's directory (Room database and sidecar files) so repeated runs do not accumulate them.
        runCatching { context.noBackupFilesDir.deleteRecursively() }
    }
}

/** The SDK's Logcat lines (tag `SignalDock`, any priority). Output of earlier tests stays in the buffer: use [since]. */
internal object Logcat {
    fun snapshot(): List<String> {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-s", "SignalDock:V").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines().filter { it.length > 2 && it[1] == '/' }
        p.waitFor()
        return lines
    }

    fun since(before: List<String>): List<String> = snapshot().let { now -> if (now.size >= before.size) now.drop(before.size) else now }

    fun warnings(lines: List<String>): List<String> = lines.filter { it.startsWith("W/") || it.startsWith("E/") }
}

/** Direct, SDK-independent read of the persisted queue (read-only view of what Room committed). */
internal fun readQueueFile(dir: File): List<Pair<String, String>> {
    val db = File(dir, "signaldock/queue.db")
    if (!db.exists()) return emptyList()
    val sql = android.database.sqlite.SQLiteDatabase.openDatabase(db.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
    try {
        sql.rawQuery("SELECT eventId, payloadJson FROM events ORDER BY seq", null).use { c ->
            val out = ArrayList<Pair<String, String>>()
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
            return out
        }
    } finally { sql.close() }
}

/** Waits until [cond] is true or fails after [timeoutMillis]. */
internal fun awaitTrue(timeoutMillis: Long, what: String, cond: () -> Boolean) {
    val end = System.nanoTime() + timeoutMillis * 1_000_000
    while (!cond()) {
        if (System.nanoTime() > end) throw AssertionError("timed out after $timeoutMillis ms waiting for: $what")
        Thread.sleep(50)
    }
}
