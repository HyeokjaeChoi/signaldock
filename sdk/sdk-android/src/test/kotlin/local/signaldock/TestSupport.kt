package local.signaldock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.internal.delivery.BatchHttpRequest
import local.signaldock.internal.delivery.BatchTransport
import local.signaldock.internal.delivery.Jitter
import local.signaldock.internal.delivery.TransportResult
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.IdGenerator
import local.signaldock.internal.init.Logger
import local.signaldock.internal.init.createSdk
import local.signaldock.internal.scheduling.NoopScheduler
import local.signaldock.internal.scheduling.WorkScheduler
import local.signaldock.internal.storage.QueueLimits
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal val context: Context get() = ApplicationProvider.getApplicationContext()

internal val testConfig = SignalDockConfig(endpoint = "http://localhost:8080", collectionKey = "test-key", allowInsecureEndpoint = true)

internal class FakeLogger : Logger {
    val warnings = mutableListOf<String>()
    override fun debug(msg: String) {}
    override fun warn(msg: String) { warnings += msg }
    override fun error(msg: String) { warnings += msg }
}

internal class SequenceIds : IdGenerator {
    private val n = AtomicLong()
    override fun newId(): String = "00000000-0000-4000-8000-%012d".format(n.incrementAndGet())
}

internal class FakeClock(var now: Long = 1_760_000_000_123L) : Clock {
    override fun nowMillis(): Long = now
}

/** Scripted transport. [handler] decides each response; [gate] holds responses back; in-flight calls are counted. */
internal class FakeTransport(var handler: suspend (BatchHttpRequest) -> TransportResult = { ackAll(it) }) : BatchTransport {
    val requests = CopyOnWriteArrayList<BatchHttpRequest>()

    /** Client reports (ADR-0036), kept apart from [requests] so batch assertions do not see them. */
    val reports = CopyOnWriteArrayList<BatchHttpRequest>()

    /** Every call in order: "batch" or "report". */
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var reportHandler: suspend (BatchHttpRequest) -> TransportResult = { TransportResult.Http(204, null, null) }
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()

    @Volatile
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun sendReport(request: BatchHttpRequest): TransportResult {
        reports += request
        calls += "report"
        return reportHandler(request)
    }

    override suspend fun send(request: BatchHttpRequest): TransportResult {
        requests += request
        calls += "batch"
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            gate?.await()
            return handler(request)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

internal class RecordingScheduler : WorkScheduler {
    val periodic = AtomicInteger()
    val requests = CopyOnWriteArrayList<Long>()
    val started = AtomicInteger()
    override fun ensurePeriodic() { periodic.incrementAndGet() }
    override fun requestAt(atMillis: Long) { requests += atMillis }
    override fun workerStarted() { started.incrementAndGet() }
}

internal fun eventIds(request: BatchHttpRequest): List<String> =
    Json.parseToJsonElement(String(request.body, Charsets.UTF_8)).jsonObject["events"]!!.jsonArray
        .map { it.jsonObject["eventId"]!!.jsonPrimitive.content }

/** A valid 200 ACK for [request]; [status] picks the status per index, [code] is used for "rejected". */
internal fun ackAll(request: BatchHttpRequest, status: (Int) -> String = { "accepted" }, code: String = "TEST_CODE"): TransportResult.Http {
    val items = eventIds(request).mapIndexed { i, id ->
        val s = status(i)
        val codePart = if (s == "rejected") ""","code":"$code"""" else ""
        """{"index":$i,"eventId":"$id","status":"$s"$codePart}"""
    }
    return TransportResult.Http(200, """{"results":[${items.joinToString(",")}]}""", null)
}

internal fun http(status: Int, retryAfter: String? = null) = TransportResult.Http(status, null, retryAfter)

internal fun requestEvents(request: BatchHttpRequest): JsonObject = Json.parseToJsonElement(String(request.body, Charsets.UTF_8)).jsonObject

/** Real Room database in the Robolectric noBackupFilesDir; fake clock, ids, transport and scheduler. */
internal suspend fun newSdk(
    limits: QueueLimits = QueueLimits(),
    ids: IdGenerator = SequenceIds(),
    nowMillis: Long = 1_760_000_000_123L,
    clock: Clock = Clock { nowMillis },
    transport: BatchTransport = FakeTransport(),
    jitter: Jitter = Jitter { 0 },
    scheduler: WorkScheduler = NoopScheduler,
    scope: CoroutineScope = CoroutineScope(Job()),
    foregroundTriggers: Boolean = false,
    logger: Logger = FakeLogger(),
): SignalDock = createSdk(
    context, testConfig, clock, ids, limits, transport, jitter, scheduler, scope, foregroundTriggers, logger,
)
