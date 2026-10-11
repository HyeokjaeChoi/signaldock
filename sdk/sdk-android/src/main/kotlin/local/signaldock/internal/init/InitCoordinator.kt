package local.signaldock.internal.init

import android.content.Context
import kotlinx.coroutines.CancellationException
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import local.signaldock.InitError
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.internal.EventCollector
import local.signaldock.internal.SignalDockDatabase
import local.signaldock.internal.storage.InstallationIdStore
import local.signaldock.internal.storage.QueueLimits
import local.signaldock.internal.storage.RoomEventSink
import local.signaldock.internal.validation.validateConfig
import java.io.File
import java.io.IOException

/**
 * Shared initialization (Q113, Q114). Callers with the same config share one attempt. A caller's
 * cancellation cancels only its own wait: [Deferred.await] does not cancel the attempt.
 */
internal class InitCoordinator(
    private val scope: CoroutineScope,
    private val build: suspend (Context, SignalDockConfig) -> SignalDock,
) {
    private val lock = Any()
    private var instance: SignalDock? = null
    private var instanceConfig: SignalDockConfig? = null
    private var attempt: Deferred<InitResult>? = null
    private var attemptConfig: SignalDockConfig? = null

    suspend fun initialize(context: Context, config: SignalDockConfig): InitResult {
        val shared: Deferred<InitResult> = synchronized(lock) {
            instance?.let {
                return if (config == instanceConfig) InitResult.Success(it)
                else InitResult.Failure(InitError.ConfigurationConflict)
            }
            attempt?.let {
                if (config != attemptConfig) return InitResult.Failure(InitError.ConfigurationConflict)
                return@synchronized it
            }
            validateConfig(config)?.let { return InitResult.Failure(InitError.InvalidConfiguration(it)) }
            // LAZY: the attempt must be registered before its completion handler can clear it.
            val d = scope.async(start = CoroutineStart.LAZY) { runAttempt(context, config) }
            attempt = d
            attemptConfig = config
            d.start()
            d
        }
        return shared.await()
    }

    private suspend fun runAttempt(context: Context, config: SignalDockConfig): InitResult {
        var success: SignalDock? = null
        try {
            success = build(context, config)
            return InitResult.Success(success)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Any other exception (IO, SQLite, Room, SecurityException, ...) is an expected-style
            // storage failure. Errors (OutOfMemoryError etc.) are not Exceptions and still propagate.
            return storageFailure(e)
        } finally {
            synchronized(lock) {
                attempt = null
                attemptConfig = null
                if (success != null) { instance = success; instanceConfig = config }
            }
        }
    }

    private fun storageFailure(e: Exception) = InitResult.Failure(InitError.StorageFailure(e.javaClass.simpleName))

    companion object {
        /** Process-wide coordinator. The scope is SDK-owned, not GlobalScope (Q114). */
        val default: InitCoordinator by lazy {
            InitCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { c, cfg -> createSdk(c, cfg) }
        }
    }
}

/** The one composition point: objects are connected by constructors (Q87). */
internal suspend fun createSdk(
    context: Context,
    config: SignalDockConfig,
    clock: Clock = Clock { System.currentTimeMillis() },
    ids: IdGenerator = RandomUuidV4,
    limits: QueueLimits = QueueLimits(),
): SignalDock {
    val logger = AndroidLogger(config.logLevel)
    // Everything the SDK persists lives here: excluded from Auto Backup (ADR-0001).
    val dir = File(context.noBackupFilesDir, "signaldock")
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create SDK directory")
    val installationId = InstallationIdStore(dir).getOrCreate(logger)
    val db = Room.databaseBuilder(context, SignalDockDatabase::class.java, File(dir, "queue.db").absolutePath).build()
    try {
        db.queueDao().usage() // open now so storage failures surface during initialization
    } catch (t: Throwable) {
        db.close() // clean up the failed attempt; queue and installation id are kept (Q112)
        throw t
    }
    logger.debug("initialized")
    return SignalDock(EventCollector(installationId, RoomEventSink(db.queueDao()), clock, ids, limits, logger), db.queueDao())
}
