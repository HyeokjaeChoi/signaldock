package local.signaldock.device

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import local.signaldock.DeliveryErrorKind
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import local.signaldock.internal.scheduling.ONE_TIME_WORK_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Device faults that the in-process tests cannot produce: a real airplane-mode switch, a real full disk,
 * and a real WorkManager database failure (ADR-0029). Like [DurabilityPhases], a phase runs only with
 * `-e phase <name>`; `scripts/device-durability.sh` runs them and restores the device state in a trap.
 * The tests change global emulator state (network, free space): run them on an emulator only.
 */
class FaultPhases {
    private fun phase(name: String) = assumeTrue("phase $name not selected", instrumentationArg("phase") == name)

    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } // wait until the command ends
    }

    private val connectivity get() = targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun setAirplane(on: Boolean) {
        shell("cmd connectivity airplane-mode " + if (on) "enable" else "disable")
        awaitTrue(30_000, "airplane mode ${if (on) "on: no active network" else "off: active network"}") { (connectivity.activeNetwork == null) == on }
    }

    // --- Airplane mode: offline as the system reports it, not as a refused connection to 127.0.0.1:9 ---

    /**
     * The server runs on the host (`-e endpoint http://10.0.2.2:<port>`, started by the script). Loopback and the
     * device's own address do not follow airplane mode reliably; a remote address does. The script counts what
     * the host server received.
     */
    @Test
    fun airplane_mode() {
        phase("airplane_mode")
        val endpoint = instrumentationArg("endpoint")!!
        val rig = Rig(AckServer(), config = SignalDockConfig(endpoint = endpoint, collectionKey = "device-test-key", allowInsecureEndpoint = true))
        try {
            runBlocking {
                val sdk = rig.initialize()
                repeat(5) { assertTrue(sdk.track("before_$it") is TrackResult.Success) }
                sdk.delivery.drainForeground()
                assertEquals("network on: delivered to the host server", 0, sdk.diagnostics().queuedEvents)
                assertEquals(5, sdk.diagnostics().lastAck?.accepted)

                setAirplane(true)
                repeat(20) { assertTrue("track needs no network", sdk.track("offline_$it") is TrackResult.Success) }
                sdk.delivery.drainForeground()
                val offline = sdk.diagnostics()
                assertEquals("events wait in the queue", 20, offline.queuedEvents)
                assertEquals(DeliveryErrorKind.NETWORK, offline.lastDeliveryError?.kind)

                setAirplane(false)
                awaitTrue(60_000, "queue delivered after airplane mode off") {
                    runBlocking { sdk.delivery.drainForeground() }
                    runBlocking { sdk.diagnostics().queuedEvents } == 0
                }
                report("phase", "airplane_mode", "status", "pass", "offlineError", "${offline.lastDeliveryError?.kind}", "queuedWhileOffline", "${offline.queuedEvents}")
            }
        } finally {
            runCatching { setAirplane(false) } // never leave the emulator offline
            rig.close()
        }
    }

    // --- Full disk: the filesystem has no free blocks left (ADR-0017 could only inject the exception) ---

    private val filler get() = File(targetContext.filesDir, "filler.bin")

    /** Leaves about [leave] bytes free on the data partition. */
    private fun fillDisk(leave: Long) {
        val free = StatFs(targetContext.filesDir.absolutePath).availableBytes
        check(free > leave) { "free space $free is already below $leave" }
        FileOutputStream(filler).use { Os.posix_fallocate(it.fd, 0, free - leave) }
    }

    @Test
    fun full_disk() {
        phase("full_disk")
        val server = AckServer().start()
        val rig = Rig(server)
        try {
            runBlocking {
                val sdk = rig.initialize()
                val seeded = (0 until 50).map { (sdk.track("seed_$it") as TrackResult.Success).eventId }

                fillDisk(leave = 256 * 1024)
                val successIds = seeded.toMutableList()
                var failure: TrackResult.StorageError? = null
                for (i in 0 until 20_000) {
                    when (val r = sdk.track("fill_$i") { property("pad", "x".repeat(512)) }) {
                        is TrackResult.Success -> successIds += r.eventId
                        is TrackResult.StorageError -> { failure = r; break }
                        else -> throw AssertionError("unexpected $r")
                    }
                }
                assertTrue("track() must report StorageError when the disk is full", failure != null)
                sdk.diagnostics() // must not throw

                // Existing queue intact. A failed commit can still have kept the one event: allow one more row.
                val stored = successIds.size
                val rows = readQueueFile(rig.context.noBackupFilesDir).map { it.first }
                assertTrue("every reported success is stored", rows.containsAll(successIds))
                assertTrue("rows ${rows.size}, reported successes $stored", rows.size in stored..stored + 1)
                assertEquals("the seeded events are still first, in order", seeded, rows.take(seeded.size))
                val db = SQLiteDatabase.openDatabase(File(rig.context.noBackupFilesDir, "signaldock/queue.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                val integrity = try { db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) } } finally { db.close() }
                assertEquals("ok", integrity)

                filler.delete()
                assertTrue("track works again after space is freed", sdk.track("after_free") is TrackResult.Success)
                sdk.delivery.drainForeground()
                assertEquals(0, sdk.diagnostics().queuedEvents)
                assertTrue("every stored event reached the server", server.committed.containsAll(rows))
                assertTrue("every reported success reached the server", server.committed.containsAll(successIds))
                report("phase", "full_disk", "status", "pass", "storedBeforeFull", "$stored", "integrity", integrity, "error", failure!!.message.take(60))
            }
        } finally {
            filler.delete()
            rig.close()
        }
    }

    // --- WorkManager failure: its own database refuses writes ---

    /**
     * Another connection holds the write lock of `androidx.work.workdb`, so every WorkManager enqueue fails
     * after SQLite's busy timeout (SQLiteDatabaseLockedException). A read-only file mode does not work as a
     * fault: Android restores the mode when WorkManager opens the database (ADR-0029).
     */
    @Test
    fun workmanager_fault() {
        phase("workmanager_fault")
        val server = AckServer().start()
        val workManager = WorkManager.getInstance(targetContext)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS) // WorkManager's database is open and written
        val workDb = File(targetContext.noBackupFilesDir, "androidx.work.workdb")
        assertTrue("WorkManager database at ${workDb.path}", workDb.exists())
        val lock = SQLiteDatabase.openDatabase(workDb.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        try {
            lock.beginTransaction() // holds the write lock until endTransaction
            val before = Logcat.snapshot()
            val sdk = (runBlocking { SignalDock.initialize(targetContext, server.config()) } as InitResult.Success).sdk
            val ids = (0 until 3).map { (runBlocking { sdk.track("wm_$it") } as TrackResult.Success).eventId }
            assertEquals("events are stored", ids, runBlocking { sdk.dao.oldest(10) }.map { it.eventId })
            runBlocking { sdk.delivery.drainForeground() }
            assertEquals("foreground delivery works", ids.toSet(), server.committed.toSet())
            // The enqueue failure comes after the call returned: wait for the WARN.
            awaitTrue(30_000, "WARN 'WorkManager request failed'") { Logcat.since(before).any { it.startsWith("W/SignalDock") && it.contains("WorkManager request failed") } }
            val warn = Logcat.since(before).first { it.contains("WorkManager request failed") }
            lock.endTransaction()

            // No stale marker blocks scheduling: after the lock is gone, a delivery Worker is waiting. (WorkManagerScheduler
            // does not enqueue a second one while one is pending, so the test cannot demand a new work ID.)
            assertTrue(runBlocking { sdk.track("after_unlock") } is TrackResult.Success)
            awaitTrue(30_000, "a delivery Worker is ENQUEUED after the lock is gone") {
                workManager.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME).get().any { it.state == WorkInfo.State.ENQUEUED }
            }
            report("phase", "workmanager_fault", "status", "pass", "warn", warn.substringAfter("WorkManager request failed: ").take(60))
        } finally {
            runCatching { lock.endTransaction() }
            lock.close()
            server.close()
        }
    }
}
