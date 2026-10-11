package local.signaldock.internal.scheduling

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.init.Logger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Scheduling boundary (Q88). WorkManager implementation below; fakes in tests. */
internal interface WorkScheduler {
    /** Makes sure the unique 15-minute recovery Worker exists. */
    fun ensurePeriodic()

    /** Asks for a one-time Worker run at [atMillis] (epoch). Exact timing is not guaranteed. */
    fun requestAt(atMillis: Long)

    /** The Worker calls this when it starts, so that later requests enqueue again. */
    fun workerStarted()
}

internal object NoopScheduler : WorkScheduler {
    override fun ensurePeriodic() {}
    override fun requestAt(atMillis: Long) {}
    override fun workerStarted() {}
}

internal const val ONE_TIME_WORK_NAME = "local.signaldock.delivery"
internal const val PERIODIC_WORK_NAME = "local.signaldock.delivery.periodic"
internal const val PERIODIC_INTERVAL_MINUTES = 15L

/**
 * Unique one-time Worker plus unique 15-minute periodic recovery (spec 8.A, Q57). It uses the
 * host's WorkManager instance and never replaces its configuration. APPEND_OR_REPLACE makes a
 * request made while a Worker runs wait for it instead of being dropped; it never cancels a run.
 */
internal class WorkManagerScheduler(
    context: Context,
    private val clock: Clock,
    private val logger: Logger,
    /** Seam for tests; throws IllegalStateException while WorkManager is not initialized. */
    private val workManager: () -> WorkManager = { WorkManager.getInstance(context.applicationContext) },
) : WorkScheduler {
    private val appContext = context.applicationContext
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Time of the one-time request that is enqueued and has not started; NONE if there is none. */
    private val pendingAt = AtomicLong(NONE)

    override fun ensurePeriodic() = guarded {
        val request = PeriodicWorkRequestBuilder<DeliveryWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        workManager().enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun requestAt(atMillis: Long) = guarded {
        // An enqueued run at or before this time will look at the queue anyway.
        var previous = NONE
        while (true) {
            val pending = pendingAt.get()
            if (pending != NONE && pending <= atMillis) return@guarded
            if (pendingAt.compareAndSet(pending, atMillis)) { previous = pending; break }
        }
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setConstraints(constraints)
            .setInitialDelay((atMillis - clock.nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        try {
            workManager().enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } catch (e: Exception) {
            // Nothing was enqueued: the marker must not suppress later requests.
            pendingAt.compareAndSet(atMillis, previous)
            throw e
        }
    }

    override fun workerStarted() = pendingAt.set(NONE)

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            // WorkManager is not initialized in the host app. Foreground delivery still works.
            logger.warn("WorkManager is not available; background delivery is off")
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * Background delivery. It uses the shared sender through the SDK instance, so it never sends in
 * parallel with a foreground trigger. It does not sleep to wait for a retry time: it ends, and the
 * next run is scheduled for the persisted time.
 */
internal class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // After process recreation the host's Application calls initialize; wait a bounded time for it.
        val sdk = InitCoordinator.default.awaitInstance(initWaitMillis) ?: return Result.success()
        sdk.delivery.runWorker()
        return Result.success()
    }

    internal companion object {
        /** `var` for tests. */
        @Volatile
        var initWaitMillis = 15_000L
    }
}
