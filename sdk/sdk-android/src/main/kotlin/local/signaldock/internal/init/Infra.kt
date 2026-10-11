package local.signaldock.internal.init

import android.util.Log
import local.signaldock.LogLevel
import java.util.UUID

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
