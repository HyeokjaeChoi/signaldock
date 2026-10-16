package local.signaldock.internal.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Opt-out state (ADR-0034). A marker file in the SDK directory in noBackupFilesDir: the file exists
 * while the host is opted out. Auto Backup does not restore it (ADR-0001). [optedOut] is read on
 * every `track()`, so it is an in-memory flag; the file only carries it across restarts.
 */
internal class OptOutStore(private val dir: File) {
    @Volatile
    var optedOut: Boolean = File(dir, FILE_NAME).exists()

    /** Writes the current [optedOut] value to disk. Returns false when it could not be stored. */
    fun persist(): Boolean = synchronized(lock) {
        val file = File(dir, FILE_NAME)
        try {
            if (optedOut) {
                if (!file.exists()) FileOutputStream(file).use { it.write(1); it.fd.sync() }
            } else if (file.exists() && !file.delete()) {
                throw IOException("cannot delete opt-out marker")
            }
            true
        } catch (e: Exception) { // IOException, SecurityException: reported as data (ADR-0017)
            false
        }
    }

    private companion object {
        const val FILE_NAME = "opt-out"
        val lock = Any()
    }
}
