package local.signaldock.device

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Phases of the durability cases that need a real process boundary (spec section 7 "Offline recovery",
 * "Installation lifetime"; ADR-0011). Each phase is one instrumentation run in a fresh app process:
 *
 *   adb shell am instrument -w -e class local.signaldock.device.DurabilityPhases -e phase <name> ...
 *
 * `scripts/device-durability.sh` runs them with `am force-stop`, `adb install -r`, `pm clear`, uninstall and
 * install, and `bmgr` between the phases. Without a `phase` argument (a plain connectedDebugAndroidTest run)
 * every phase is skipped. These tests use the public API (`SignalDock.initialize`) and the real process-wide
 * coordinator, real Room files in the app's real `noBackupFilesDir` and real WorkManager.
 */
class DurabilityPhases {
    private val offline = SignalDockConfig(endpoint = "http://127.0.0.1:9", collectionKey = "device-test-key", allowInsecureEndpoint = true)

    private fun phase(name: String) = assumeTrue("phase $name not selected", instrumentationArg("phase") == name)

    private val harnessDir get() = File(targetContext.filesDir, "harness").apply { mkdirs() }
    private val sdkDir get() = File(targetContext.noBackupFilesDir, "signaldock")
    private val installationIdFile get() = File(sdkDir, "installation-id")

    private fun init(config: SignalDockConfig): SignalDock =
        (runBlocking { SignalDock.initialize(targetContext, config) } as InitResult.Success).sdk

    private fun track(sdk: SignalDock, i: Int): String =
        (runBlocking {
            sdk.track("event_$i") {
                property("index", i)
                property("text", "한글 \"quote\" \\ ü $i")
                property("flag", i % 2 == 0)
                property("ratio", i / 7.0)
                property("big", 9007199254740991L - i)
            }
        } as TrackResult.Success).eventId

    // --- Offline recovery: 1,000 events across a real process restart and reconnection ---

    @Test
    fun offline_store() {
        phase("offline_store")
        val sdk = init(offline)
        val ids = (0 until 1000).map { track(sdk, it) }
        val rows = runBlocking { sdk.dao.oldest(2000) }
        assertEquals(ids, rows.map { it.eventId })
        File(harnessDir, "offline.tsv").writeText(rows.joinToString("\n") { it.eventId + "\t" + sha256(it.payloadJson) })
        assertEquals("offline: nothing may be delivered", 1000, runBlocking { sdk.diagnostics().queuedEvents })
        report("phase", "offline_store", "status", "pass", "stored", "1000", "pid", android.os.Process.myPid().toString())
    }

    @Test
    fun offline_recover() {
        phase("offline_recover")
        val expected = File(harnessDir, "offline.tsv").readLines().map { it.split("\t").let { p -> p[0] to p[1] } }
        assertEquals(1000, expected.size)

        // Before the SDK starts: the committed rows survived the process kill (read without the SDK).
        val persisted = readQueueFile(targetContext.noBackupFilesDir)
        assertEquals(expected.map { it.first }, persisted.map { it.first })
        assertEquals(expected.map { it.second }, persisted.map { sha256(it.second) })
        val installationId = installationIdFile.readText().trim()

        val server = AckServer().start()
        try {
            val sdk = init(server.config())
            val start = System.nanoTime()
            // Delivery runs by itself: the restored queue makes initialization schedule the Worker, and the
            // persisted retry time from the offline phase is honored (no bypass by restart).
            awaitTrue(300_000, "all 1,000 events delivered") { server.committed.size >= 1000 && runBlocking { sdk.diagnostics().queuedEvents } == 0 }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0

            assertEquals("each event was sent exactly once", 1000, server.receivedIds.size)
            assertEquals(expected.map { it.first }.toSet(), server.committed.toSet())
            assertEquals(0, server.duplicateAcks.get())
            val received = server.bodies.flatMap { eventPayloads(it) }.associate { eventIdOf(it) to sha256(it) }
            assertEquals("content is byte-identical to what was stored", expected.toMap(), received)
            val installationIds = server.bodies.map { Json.parseToJsonElement(it).jsonObject["installationId"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf(installationId), installationIds)
            assertTrue(runBlocking { sdk.diagnostics() }.lastAck != null)
            report("phase", "offline_recover", "status", "pass", "deliveredSeconds", "%.1f".format(seconds), "requests", "${server.requestCount.get()}", "pid", android.os.Process.myPid().toString())
        } finally {
            server.close()
        }
    }

    // --- Kill during collection: a success that was reported is durable ---

    @Test
    fun kill_burst_store() {
        phase("kill_burst_store")
        val sdk = init(offline)
        // Each reported success is appended to a log and fsynced; the process is killed by SIGKILL meanwhile.
        FileOutputStream(File(harnessDir, "acked.log"), true).use { log ->
            report("phase", "kill_burst_store", "status", "started")
            var i = 0
            while (true) {
                val id = (runBlocking { sdk.track("burst") { property("i", i) } } as? TrackResult.Success)?.eventId ?: break
                log.write((id + "\n").toByteArray())
                log.fd.sync()
                i++
                Thread.sleep(2)
            }
        }
    }

    @Test
    fun kill_burst_verify() {
        phase("kill_burst_verify")
        val raw = File(harnessDir, "acked.log").readText()
        val acked = raw.split("\n").dropLast(1) // the last line may be cut by the kill
        val db = File(sdkDir, "queue.db")
        val sql = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val integrity = try { sql.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) } } finally { sql.close() }
        assertEquals("ok", integrity)
        val stored = readQueueFile(targetContext.noBackupFilesDir).map { it.first }
        assertEquals("no duplicate rows", stored.size, stored.toSet().size)
        assertTrue("acked ${acked.size} events, stored ${stored.size}", acked.isNotEmpty())
        assertTrue("every reported success is stored", stored.containsAll(acked))
        assertTrue("at most the one in-flight event is stored without a log line", stored.size - acked.size in 0..1)
        // The SDK itself opens the database after the kill and sees the same queue.
        val sdk = init(offline)
        assertEquals(stored.size, runBlocking { sdk.diagnostics().queuedEvents })
        report("phase", "kill_burst_verify", "status", "pass", "acked", "${acked.size}", "stored", "${stored.size}", "integrity", integrity)
    }

    // --- Installation lifetime ---

    @Test
    fun install_seed() {
        phase("install_seed")
        val sdk = init(offline)
        val ids = (0 until 5).map { track(sdk, it) }
        File(harnessDir, "marker.txt").writeText("host app data (backed up)")
        val installationId = installationIdFile.readText().trim()
        report("phase", "install_seed", "status", "pass", "installationId", installationId, "eventIds", ids.joinToString(","))
    }

    @Test
    fun install_check() {
        phase("install_check")
        val expect = instrumentationArg("expect")!! // kept | fresh
        val markerExpect = instrumentationArg("marker")!! // present | absent
        val prevId = instrumentationArg("prevInstallationId")!!
        val prevEvents = instrumentationArg("prevEventIds")!!.split(",")

        assertEquals("host marker file", markerExpect == "present", File(File(targetContext.filesDir, "harness"), "marker.txt").exists())
        val persistedBefore = readQueueFile(targetContext.noBackupFilesDir).map { it.first }
        if (expect == "fresh") {
            assertFalse("no SDK directory before initialization", sdkDir.exists())
            assertEquals(emptyList<String>(), persistedBefore)
        } else {
            assertEquals(prevEvents, persistedBefore)
        }

        val sdk = init(offline)
        val id = installationIdFile.readText().trim()
        val queued = runBlocking { sdk.dao.oldest(100) }.map { it.eventId }
        if (expect == "kept") {
            assertEquals(prevId, id)
            assertEquals(prevEvents, queued)
        } else {
            assertNotEquals("a new installation ID", prevId, id)
            assertTrue("new ID is a UUID", Regex("[0-9a-f-]{36}").matches(id))
            assertEquals(emptyList<String>(), queued)
            assertTrue(queued.none { it in prevEvents })
        }
        report("phase", "install_check", "status", "pass", "expect", expect, "installationId", id, "queued", "${queued.size}")
    }

    // --- Opt-out across a real process restart (ADR-0034): the marker file `opt-out` ---

    private val optOutFile get() = File(sdkDir, "opt-out")

    @Test
    fun optout_set() {
        phase("optout_set")
        val sdk = init(offline)
        assertFalse("collection is on at the first start", sdk.isOptedOut())
        assertTrue(runBlocking { sdk.setOptOut(true) })
        assertTrue(sdk.isOptedOut())
        assertTrue("marker file written", optOutFile.exists())
        assertEquals(TrackResult.OptedOut, runBlocking { sdk.track("opted_out_before_restart") })
        assertEquals(0, runBlocking { sdk.diagnostics().queuedEvents })
        report("phase", "optout_set", "status", "pass", "pid", android.os.Process.myPid().toString())
    }

    @Test
    fun optout_check() {
        phase("optout_check")
        assertTrue("marker file survived the process kill", optOutFile.exists())
        val server = AckServer().start()
        try {
            val sdk = init(server.config())
            assertTrue("opt-out is read from the marker at initialization", sdk.isOptedOut())
            assertEquals(TrackResult.OptedOut, runBlocking { sdk.track("opted_out_after_restart") })
            assertEquals(0, runBlocking { sdk.diagnostics().queuedEvents })
            assertEquals(emptyList<String>(), readQueueFile(targetContext.noBackupFilesDir).map { it.first })
            Thread.sleep(3_000) // A send needs a stored event, so any request in this wait is a bug (Gap: a fixed wait, not an event)
            assertEquals("nothing is sent while opted out", 0, server.requestCount.get())
            report("phase", "optout_check", "status", "pass", "requests", "${server.requestCount.get()}", "pid", android.os.Process.myPid().toString())
        } finally {
            server.close()
        }
    }

    @Test
    fun optout_off() {
        phase("optout_off")
        val sdk = init(offline)
        assertTrue("still opted out at the third start", sdk.isOptedOut())
        assertTrue(runBlocking { sdk.setOptOut(false) })
        assertFalse(sdk.isOptedOut())
        assertFalse("marker file removed", optOutFile.exists())
        assertTrue(runBlocking { sdk.track("collected_again") } is TrackResult.Success)
        assertEquals(1, runBlocking { sdk.diagnostics().queuedEvents })
        report("phase", "optout_off", "status", "pass", "queued", "1")
    }
}
