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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Power-loss phases (gate A6, ADR-0049 decision 6, ADR-0029). `scripts/device-durability.sh` case `powerloss` runs
 * them; between `powerloss_write` and `powerloss_check` the host SIGKILLs the emulator process (the guest page
 * cache is lost, as in a power cut) and cold-boots it from the same data partition. Like [FaultPhases], a phase
 * runs only with `-e phase <name>`. The server is the host ACK server (`-e endpoint http://10.0.2.2:<port>`,
 * `scripts/ack-server.py`): its state survives the kill, and the script judges its log.
 *
 * Event names carry the set an event belongs to: `pl_pre_<i>`, `pl_lostack_<i>`, `pl_pending_<i>`, `pl_tail_<i>`
 * (counts: `-e pre`, `-e lostack`, `-e pending`, `-e tail`):
 *  - pre: written and delivered before delivery stops (ACK applied, queue rows deleted).
 *  - lostack: written, sent while the host server drops its answers: the server stored them, the SDK keeps them
 *    queued. After the kill they must still be queued, and the resend must be answered `duplicate`.
 *  - pending: written, never sent, committed before the checkpoint. None may be lost.
 *  - tail: written after the checkpoint, never sent. The kill can lose them. Counted, not gated.
 *
 * Delivery is stopped by construction: the SDK is built with no scheduler and no foreground trigger ([Rig] defaults),
 * so nothing sends unless a phase calls `drainForeground()`; all WorkManager work is cancelled as well. After the
 * checkpoint no phase sends, so what the check finds in the queue is what the checkpoint made durable.
 */
class PowerLossPhases {
    private fun phase(name: String) = assumeTrue("phase $name not selected", instrumentationArg("phase") == name)

    /** The four set sizes travel together; the script passes them as `-e pre`, `-e lostack`, `-e pending`, `-e tail`. */
    private data class Sets(val pre: Int, val lostack: Int, val pending: Int, val tail: Int)

    private fun sets(): Sets {
        fun count(name: String) = instrumentationArg(name)!!.toInt()
        return Sets(count("pre"), count("lostack"), count("pending"), count("tail"))
    }

    private val endpoint get() = instrumentationArg("endpoint")!!

    private fun rig() = Rig(
        AckServer(), dirName = DIR,
        config = SignalDockConfig(endpoint = endpoint, collectionKey = "device-test-key", allowInsecureEndpoint = true),
    )

    /** `drop`: the host server commits and then closes the connection without an answer; `ok`: normal. */
    private fun serverMode(mode: String) {
        val connection = URL("$endpoint/__mode/$mode").openConnection() as HttpURLConnection
        try { check(connection.responseCode == 204) { "server mode $mode: HTTP ${connection.responseCode}" } } finally { connection.disconnect() }
    }

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

    /** Process 2: writes the four sets with the checkpoint before the last one, reports `armed`, and waits for the kill. */
    @Test
    fun powerloss_write() {
        phase("powerloss_write")
        val n = sets()
        val sdk = runBlocking { rig().initialize() }
        runBlocking {
            assertEquals("the prepared queue is empty", 0, sdk.diagnostics().queuedEvents)
            serverMode("ok")
            sdk.trackSet("pl_pre", n.pre)
            sdk.delivery.drainForeground()
            assertEquals("the pre events were delivered and deleted", 0, sdk.diagnostics().queuedEvents)

            serverMode("drop")
            sdk.trackSet("pl_lostack", n.lostack)
            sdk.delivery.drainForeground() // the server stores them; the SDK gets no answer and keeps them
            serverMode("ok")
            assertEquals("the lost-ACK events stay queued", n.lostack, sdk.diagnostics().queuedEvents)

            WorkManager.getInstance(targetContext).cancelAllWork().result.get(20, TimeUnit.SECONDS)
            sdk.trackSet("pl_pending", n.pending)
            assertEquals(
                "only the lostack and pending events are queued, in order",
                (0 until n.lostack).map { "pl_lostack_$it" } + (0 until n.pending).map { "pl_pending_$it" }, queuedNames(sdk),
            )
            // The sync point: nothing else writes now, so a checkpoint that cannot finish is a harness error, not a product failure.
            // `-e skipCheckpoint 1` is the negative control of the gate: without the sync point, the check must find lost events.
            val (busy, log, checkpointed) =
                if (instrumentationArg("skipCheckpoint") == "1") {
                    Triple(0, 0, 0)
                } else {
                    sdk.sqlite().query("PRAGMA wal_checkpoint(FULL)").use {
                        it.moveToFirst()
                        Triple(it.getInt(0), it.getInt(1), it.getInt(2))
                    }
                }
            if (busy != 0 || log != checkpointed) {
                report("phase", "powerloss_write", "status", "invalid", "reason", "checkpoint busy=$busy log=$log checkpointed=$checkpointed")
                throw AssertionError("invalid run: checkpoint busy=$busy log=$log checkpointed=$checkpointed")
            }

            sdk.trackSet("pl_tail", n.tail)
            report(
                "phase", "powerloss_write", "status", "armed",
                "checkpoint", "busy=$busy log=$log checkpointed=$checkpointed",
                "dbSynchronous", sdk.sqlite().query("PRAGMA synchronous").use { it.moveToFirst(); it.getString(0) },
                "pid", android.os.Process.myPid().toString(),
            )
        }
        Thread.sleep(300_000) // the host kills the emulator now; a run that reaches the end of this wait is a harness error
        throw AssertionError("not killed within 5 minutes")
    }

    /** Process 3, after the cold boot: integrity first, without the SDK; then the SDK delivers what is left. */
    @Test
    fun powerloss_check() {
        phase("powerloss_check")
        val n = sets()
        val file = File(targetContext.noBackupFilesDir, "$DIR/signaldock/queue.db")
        assertTrue("the queue file survived: ${file.path}", file.exists())
        // Not sdk.sqlite(): integrity is checked before the SDK opens the queue.
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
        val wal = File(file.path + "-wal")
        val facts = "queue.db ${file.length()} bytes, -wal ${if (wal.exists()) wal.length() else -1} bytes, ${names.size} rows"
        assertEquals("PRAGMA integrity_check ($facts)", "ok", integrity)
        val lost = ((0 until n.lostack).map { "pl_lostack_$it" } + (0 until n.pending).map { "pl_pending_$it" }) - names.toSet()
        assertEquals("events committed before the checkpoint are lost ($facts)", emptyList<String>(), lost)

        val kept = fun(prefix: String) = names.count { it.startsWith(prefix) }
        // Their delivery and queue delete were committed before the checkpoint, so the delete is durable too.
        assertEquals("pre events came back ($facts)", 0, kept("pl_pre_"))
        val sdk = runBlocking { rig().initialize() }
        assertEquals("the SDK sees the same queue", names.size, runBlocking { sdk.diagnostics().queuedEvents })
        // The failed send before the checkpoint stored a retry time (10 s): the sender waits for it.
        awaitTrue(180_000, "the recovered queue is delivered") {
            runBlocking { sdk.delivery.drainForeground() }
            Thread.sleep(500)
            runBlocking { sdk.diagnostics().queuedEvents } == 0
        }
        report(
            "phase", "powerloss_check", "status", "pass", "integrity", integrity, "queuedAtBoot", "${names.size}",
            "lostackKept", "${kept("pl_lostack_")}", "pendingKept", "${kept("pl_pending_")}", "preBack", "${kept("pl_pre_")}",
            "tailKept", "${kept("pl_tail_")}", "tailLost", "${n.tail - kept("pl_tail_")}",
            "expected", "pre=${n.pre} lostack=${n.lostack} pending=${n.pending} tail=${n.tail}", "pid", android.os.Process.myPid().toString(),
        )
    }

    private companion object {
        const val DIR = "powerloss"
    }
}
