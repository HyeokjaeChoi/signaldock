package local.signaldock.device

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Power-loss phases (gate A6, ADR-0049 decision 6, ADR-0029). `scripts/device-durability.sh` case `powerloss` runs
 * them; between `powerloss_write` and `powerloss_check` the host SIGKILLs the emulator process (the guest page
 * cache is lost, as in a power cut) and cold-boots it from the same data partition. Like [FaultPhases], a phase
 * runs only with `-e phase <name>`. The server is the host ACK server (`-e endpoint http://10.0.2.2:<port>`,
 * `scripts/ack-server.py`): its state survives the kill, and the script judges its log.
 *
 * Event names carry the set an event belongs to: `pl_pre_<i>`, `pl_pending_<i>`, `pl_post_<i>`, `pl_tail_<i>`
 * (counts: `-e pre`, `-e pending`, `-e post`, `-e tail`):
 *  - pre: written and delivered before delivery stops (ACK applied, queue rows deleted).
 *  - pending: written, never delivered, committed before the checkpoint. None may be lost.
 *  - post: written after the checkpoint and delivered. The kill can lose the queue delete, so they can come back.
 *  - tail: written after the checkpoint, not delivered. The kill can lose them. Counted, not gated.
 *
 * Delivery is stopped by construction: the SDK is built with no scheduler and no foreground trigger ([Rig] defaults),
 * so nothing sends unless a phase calls `drainForeground()`; all WorkManager work is cancelled as well.
 */
class PowerLossPhases {
    private fun phase(name: String) = assumeTrue("phase $name not selected", instrumentationArg("phase") == name)

    private fun count(name: String) = instrumentationArg(name)!!.toInt()

    private fun rig() = Rig(
        AckServer(), dirName = DIR,
        config = SignalDockConfig(endpoint = instrumentationArg("endpoint")!!, collectionKey = "device-test-key", allowInsecureEndpoint = true),
    )

    private suspend fun SignalDock.trackSet(prefix: String, n: Int) =
        repeat(n) { check(track("${prefix}_$it") is TrackResult.Success) { "track ${prefix}_$it failed" } }

    private fun queuedNames(sdk: SignalDock): List<String> = runBlocking { sdk.dao.oldest(10_000) }.map { eventNameOf(it.payloadJson) }

    /** Process 1: creates the installation and an empty queue. The script runs `sync` afterwards, so they are durable. */
    @Test
    fun powerloss_prepare() {
        phase("powerloss_prepare")
        runBlocking { rig().initialize() } // no close(): the directory must stay
        report("phase", "powerloss_prepare", "status", "pass", "pid", android.os.Process.myPid().toString())
    }

    /** Process 2: writes the four sets with the checkpoint between, reports `armed`, and waits for the kill. */
    @Test
    fun powerloss_write() {
        phase("powerloss_write")
        val (pre, pending, post, tail) = listOf("pre", "pending", "post", "tail").map(::count)
        val sdk = runBlocking { rig().initialize() }
        runBlocking {
            assertEquals("the prepared queue is empty", 0, sdk.diagnostics().queuedEvents)
            sdk.trackSet("pl_pre", pre)
            sdk.delivery.drainForeground()
            assertEquals("the pre events were delivered and deleted", 0, sdk.diagnostics().queuedEvents)
            WorkManager.getInstance(targetContext).cancelAllWork().result.get(20, TimeUnit.SECONDS)

            sdk.trackSet("pl_pending", pending)
            assertEquals("only the pending events are queued", (0 until pending).map { "pl_pending_$it" }, queuedNames(sdk))
            // The sync point: nothing else writes now, so a checkpoint that cannot finish is a harness error, not a product failure.
            val (busy, log, checkpointed) = sdk.sqlite().query("PRAGMA wal_checkpoint(FULL)").use {
                it.moveToFirst(); Triple(it.getInt(0), it.getInt(1), it.getInt(2))
            }
            if (busy != 0 || log != checkpointed) {
                report("phase", "powerloss_write", "status", "invalid", "reason", "checkpoint busy=$busy log=$log checkpointed=$checkpointed")
                throw AssertionError("invalid run: checkpoint busy=$busy log=$log checkpointed=$checkpointed")
            }

            sdk.trackSet("pl_post", post)
            sdk.delivery.drainForeground() // ACK, then the queue delete: the kill can lose the delete, which makes a resend
            sdk.trackSet("pl_tail", tail)
            report("phase", "powerloss_write", "status", "armed", "checkpoint", "busy=$busy log=$log checkpointed=$checkpointed", "pid", android.os.Process.myPid().toString())
        }
        Thread.sleep(300_000) // the host kills the emulator now; a run that reaches the end of this wait is a harness error
        throw AssertionError("not killed within 5 minutes")
    }

    /** Process 3, after the cold boot: integrity first, without the SDK; then the SDK delivers what is left. */
    @Test
    fun powerloss_check() {
        phase("powerloss_check")
        val (pre, pending, post, tail) = listOf("pre", "pending", "post", "tail").map(::count)
        val file = File(targetContext.noBackupFilesDir, "$DIR/signaldock/queue.db")
        assertTrue("the queue file survived: ${file.path}", file.exists())
        // A no-op error handler: the default one deletes a corrupt database, which would hide the failure.
        val sql = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { })
        val integrity: String
        val names: List<String>
        try {
            integrity = sql.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) }
            names = sql.rawQuery("SELECT payloadJson FROM events ORDER BY seq", null).use { c ->
                generateSequence { if (c.moveToNext()) eventNameOf(c.getString(0)) else null }.toList()
            }
        } finally { sql.close() }
        assertEquals("PRAGMA integrity_check", "ok", integrity)
        val lost = (0 until pending).map { "pl_pending_$it" } - names.toSet()
        assertEquals("events committed before the checkpoint are lost", emptyList<String>(), lost)

        val kept = fun(prefix: String) = names.count { it.startsWith(prefix) }
        val sdk = runBlocking { rig().initialize() }
        assertEquals("the SDK sees the same queue", names.size, runBlocking { sdk.diagnostics().queuedEvents })
        awaitTrue(120_000, "the recovered queue is delivered") {
            runBlocking { sdk.delivery.drainForeground() }
            runBlocking { sdk.diagnostics().queuedEvents } == 0
        }
        report(
            "phase", "powerloss_check", "status", "pass", "integrity", integrity, "queuedAtBoot", "${names.size}",
            "pendingKept", "${kept("pl_pending_")}", "preBack", "${kept("pl_pre_")}", "postBack", "${kept("pl_post_")}",
            "tailKept", "${kept("pl_tail_")}", "tailLost", "${tail - kept("pl_tail_")}",
            "expected", "pre=$pre pending=$pending post=$post tail=$tail", "pid", android.os.Process.myPid().toString(),
        )
    }

    private companion object {
        const val DIR = "powerloss"
    }
}
