package local.signaldock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import local.signaldock.internal.storage.InstallationIdStore
import local.signaldock.internal.init.RandomUuidV4
import local.signaldock.internal.storage.RejectionEntity
import local.signaldock.internal.storage.RetryStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private val sdkDir get() = File(context.noBackupFilesDir, "signaldock")
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `1000 events survive re-creating the SDK on the same files with the same ids and content`() = runBlocking {
        val first = newSdk(ids = RandomUuidV4)
        repeat(1000) { first.track("e") { property("n", it) } }
        val before = first.dao.oldest(2000)

        // Same storage opened again, as after a process restart (a real process restart: not run).
        val second = newSdk(ids = RandomUuidV4)
        val after = second.dao.oldest(2000)
        assertEquals(1000, second.diagnostics().queuedEvents)
        assertEquals(before, after)
        assertEquals(before.map { it.eventId }, after.map { it.eventId })
    }

    @Test
    fun `installation id is a UUIDv4 stable across SDK re-creation`() = runBlocking {
        val a = newSdk(); a.track("e")
        val id1 = File(sdkDir, "installation-id").readText()
        assertTrue(uuidV4.matches(id1))
        val b = newSdk(); b.track("e")
        assertEquals(id1, File(sdkDir, "installation-id").readText())
        assertEquals(setOf(id1), b.dao.oldest(10).map { it.installationId }.toSet())
    }

    @Test
    fun `concurrent callers get the same installation id`() {
        val dir = File(context.noBackupFilesDir, "race").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(16)
        try {
            val ids = (1..64).map { pool.submit<String> { InstallationIdStore(dir).getOrCreate(FakeLogger()) } }.map { it.get() }
            assertEquals(1, ids.toSet().size)
        } finally { pool.shutdown() }
    }

    @Test
    fun `corrupt installation id file is replaced and warned about`() {
        val dir = File(context.noBackupFilesDir, "corrupt").apply { mkdirs() }
        File(dir, "installation-id").writeText("not-a-uuid")
        val log = FakeLogger()
        val id = InstallationIdStore(dir).getOrCreate(log)
        assertTrue(uuidV4.matches(id))
        assertEquals(1, log.warnings.size)
        assertEquals(id, InstallationIdStore(dir).getOrCreate(log))
    }

    @Test
    fun `installation id, queue database and sidecars live only under noBackupFilesDir`() = runBlocking {
        val sdk = newSdk()
        sdk.track("e")
        val names = sdkDir.list()!!.toSet()
        assertTrue("installation-id" in names)
        assertTrue("queue.db" in names)
        // every file the SDK owns is below noBackupFilesDir (excluded from Auto Backup, ADR-0001)
        assertTrue(sdkDir.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        // nothing in the backed-up areas
        assertTrue(context.filesDir.walkTopDown().none { it.isFile && (it.name.contains("signaldock") || it.name.startsWith("queue.db") || it.name == "installation-id") })
        assertTrue(File(context.dataDir, "databases").walkTopDown().none { it.isFile && it.name.startsWith("queue.db") })
        assertTrue(context.getDatabasePath("queue.db").exists().not())
    }

    @Test
    fun `permanent rejection stores no payload, removes the item and keeps the last 100`() = runBlocking {
        val sdk = newSdk()
        repeat(105) { sdk.track("secret_name") { property("secret_key", "secret_value") } }
        val rows = sdk.dao.oldest(200)
        sdk.dao.rejectPermanently(rows.take(103).mapIndexed { i, e -> RejectionEntity(eventId = e.eventId, code = "C$i", rejectedAtMillis = 1000L + i) })
        assertEquals(2, sdk.diagnostics().queuedEvents)
        val d = sdk.diagnostics().recentRejections
        assertEquals(100, d.size)
        assertEquals("C102", d.first().code) // newest first
        assertEquals("C3", d.last().code)
        assertEquals(rows[102].eventId, d.first().eventId)
        // the diagnostic type carries no event content
        assertTrue(d.none { it.toString().contains("secret") })
    }

    @Test
    fun `diagnostics reports queue, last ACK and next retry`() = runBlocking {
        val sdk = newSdk(nowMillis = 5_000)
        assertEquals(Diagnostics("0.3.0", 0, 0, null, emptyList(), null, null, 0, null), sdk.diagnostics())
        sdk.track("e")
        sdk.dao.saveRetryState(RetryStateEntity(failureCount = 3, nextAttemptAtMillis = 9_000, lastAckAtMillis = 7_000, lastAckAccepted = 2, lastAckDuplicate = 1, lastAckRejected = 4, lastErrorKind = "TIMEOUT", lastErrorHttpStatus = null, lastErrorAtMillis = 8_000))
        val d = sdk.diagnostics()
        assertEquals(1, d.queuedEvents)
        assertTrue(d.queuedBytes > 0)
        assertEquals(5_000L, d.oldestPendingEpochMillis)
        assertEquals(3, d.retryFailureCount)
        assertEquals(9_000L, d.nextAttemptEpochMillis)
        assertEquals(AckDiagnostic(7_000, 2, 1, 4), d.lastAck)
        assertEquals(DeliveryErrorDiagnostic(DeliveryErrorKind.TIMEOUT, null, 8_000), d.lastDeliveryError)
    }

    @Test
    fun `last delivery error survives re-opening the database`() = runBlocking {
        val first = newSdk(ids = RandomUuidV4)
        first.dao.saveRetryState(RetryStateEntity(failureCount = 2, nextAttemptAtMillis = 99, lastErrorKind = "RATE_LIMITED", lastErrorHttpStatus = 429, lastErrorAtMillis = 50))
        val second = newSdk(ids = RandomUuidV4)
        val d = second.diagnostics()
        assertEquals(DeliveryErrorDiagnostic(DeliveryErrorKind.RATE_LIMITED, 429, 50), d.lastDeliveryError)
        assertEquals(2, d.retryFailureCount)
        assertEquals(99L, d.nextAttemptEpochMillis)
    }

    @Test
    fun `diagnostics is one consistent snapshot while a writer moves events to rejections`() = runBlocking {
        val sdk = newSdk()
        repeat(50) { sdk.track("e") }
        val ids = sdk.dao.oldest(100).map { it.eventId }
        val writer = async(Dispatchers.Default) {
            ids.forEachIndexed { i, id ->
                sdk.dao.rejectPermanently(listOf(RejectionEntity(eventId = id, code = "C", rejectedAtMillis = i.toLong())))
            }
        }
        // Each rejection moves one item from the queue to the rejections in one transaction,
        // so queued + rejected is always 50 in a single snapshot. Three separate reads could see 51.
        var reads = 0
        while (!writer.isCompleted) {
            val d = sdk.diagnostics()
            assertEquals(50, d.queuedEvents + d.recentRejections.size)
            reads++
        }
        writer.await()
        assertTrue(reads > 0)
        assertEquals(50, sdk.diagnostics().recentRejections.size)
    }
}
