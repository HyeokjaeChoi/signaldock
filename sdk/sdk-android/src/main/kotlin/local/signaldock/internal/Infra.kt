package local.signaldock.internal

import android.util.Log
import local.signaldock.LogLevel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal const val SDK_VERSION = "0.1.0"
internal const val CONTRACT_VERSION = "1"

/**
 * Messages passed here must never contain event content, event names, installation IDs,
 * credentials, HTTP bodies or raw exception messages (Q115). Callers log fixed text only.
 */
internal interface Logger {
    fun debug(msg: String)
    fun warn(msg: String)
    fun error(msg: String)
}

internal class AndroidLogger(private val level: LogLevel) : Logger {
    override fun debug(msg: String) { if (level <= LogLevel.DEBUG) Log.d(TAG, msg) }
    override fun warn(msg: String) { if (level <= LogLevel.WARN) Log.w(TAG, msg) }
    override fun error(msg: String) { if (level <= LogLevel.ERROR) Log.e(TAG, msg) }
    private companion object { const val TAG = "SignalDock" }
}

internal fun interface Clock { fun nowMillis(): Long }

internal fun interface IdGenerator { fun newId(): String }

internal val RandomUuidV4 = IdGenerator { UUID.randomUUID().toString() }

/**
 * Installation ID in a file under the SDK directory in noBackupFilesDir (ADR-0001).
 * Generated once per installation; concurrent callers in the process get the same value.
 */
internal class InstallationIdStore(private val dir: File, private val ids: IdGenerator = RandomUuidV4) {
    fun getOrCreate(logger: Logger): String = synchronized(LOCK) {
        val file = File(dir, FILE_NAME)
        if (file.exists()) {
            val existing = file.readText().trim()
            if (UUID_V4.matches(existing)) return existing
            // Corrupt content: events keep the ID they were stored with, so a new ID is safe.
            logger.warn("installation id file is invalid; generating a new id")
        }
        val id = ids.newId()
        // Write a temp file, fsync, then rename: readers never see a partial value.
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { it.write(id.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!tmp.renameTo(file)) throw IOException("cannot store installation id")
        id
    }

    private companion object {
        const val FILE_NAME = "installation-id"
        val LOCK = Any()
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
