package local.signaldock.internal.scheduling

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.signaldock.internal.delivery.RunBudget
import local.signaldock.internal.delivery.RunEnd
import local.signaldock.internal.delivery.Sender
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.Logger
import local.signaldock.internal.storage.AddOutcome
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.EventSink
import local.signaldock.internal.storage.QueueLimits
import java.util.concurrent.atomic.AtomicInteger

internal const val WORKER_MAX_BATCHES = 10
internal const val WORKER_SOFT_BUDGET_MILLIS = 30_000L
internal const val FOREGROUND_TRIGGER_EVENTS = 20
internal const val FOREGROUND_TRIGGER_DELAY_MILLIS = 10_000L

/**
 * Runs the shared [Sender] for both entry points and keeps the Worker scheduled. A run that ends
 * with a wait or an exhausted budget schedules the next Worker run for the persisted time; the
 * sender itself still checks that time (WorkManager may run earlier or later).
 */
internal class DeliveryCoordinator(
    internal val sender: Sender,
    private val scheduler: WorkScheduler,
    private val clock: Clock,
    private val logger: Logger,
) {
    /** Foreground drain: no batch budget. */
    suspend fun drainForeground() = run(null)

    /** Worker run: 10 batches or 30 seconds, whichever comes first (Q72). */
    suspend fun runWorker() {
        scheduler.workerStarted()
        run(RunBudget(WORKER_MAX_BATCHES, WORKER_SOFT_BUDGET_MILLIS))
    }

    /** Called at initialization and after a queue addition or foreground exit (spec 8.A). */
    fun ensureScheduled(runAtMillis: Long) {
        scheduler.ensurePeriodic()
        scheduler.requestAt(runAtMillis)
    }

    private suspend fun run(budget: RunBudget?) {
        try {
            val r = sender.run(budget)
            when (r.end) {
                RunEnd.QUEUE_EMPTY -> {}
                RunEnd.WAITING -> scheduler.requestAt(r.nextAttemptMillis ?: clock.nowMillis())
                RunEnd.BUDGET_EXHAUSTED -> scheduler.requestAt(clock.nowMillis())
            }
        } catch (e: Exception) {
            // A closed Room database cancels its own scope: that is a storage failure, not the caller's cancellation.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            // Storage failure in the sender. The queue is untouched; the next trigger or Worker retries.
            logger.warn("delivery run failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * Foreground trigger: a drain starts after [eventThreshold] events were stored, or [delayMillis]
 * after the first event stored since the last drain started. Retry waits are not checked here;
 * the sender refuses to send before the persisted time.
 */
internal class ForegroundTrigger(
    private val scope: CoroutineScope,
    private val eventThreshold: Int = FOREGROUND_TRIGGER_EVENTS,
    private val delayMillis: Long = FOREGROUND_TRIGGER_DELAY_MILLIS,
    private val drain: suspend () -> Unit,
) {
    private val lock = Any()
    private var stored = 0
    private var timer: Job? = null

    // 0 idle, 1 draining, 2 draining and another drain was requested meanwhile.
    private val drainState = AtomicInteger(0)

    fun onEventStored() {
        val now = synchronized(lock) {
            stored++
            if (stored >= eventThreshold) {
                stored = 0
                timer?.cancel()
                timer = null
                true
            } else {
                if (timer == null) {
                    timer = scope.launch {
                        delay(delayMillis)
                        synchronized(lock) { stored = 0; timer = null }
                        requestDrain()
                    }
                }
                false
            }
        }
        if (now) requestDrain()
    }

    /** One drain at a time; a request during a drain makes it run once more. */
    fun requestDrain() {
        while (true) {
            when (drainState.get()) {
                0 -> if (drainState.compareAndSet(0, 1)) {
                    scope.launch {
                        try {
                            while (true) {
                                drain()
                                if (drainState.compareAndSet(1, 0)) break
                                drainState.set(1) // a request arrived during the drain: run once more
                            }
                        } catch (t: Throwable) {
                            drainState.set(0) // after a normal end the state is already 0
                            throw t
                        }
                    }
                    return
                }
                1 -> if (drainState.compareAndSet(1, 2)) return
                else -> return
            }
        }
    }
}

/** Schedules the Worker when the app's last visible activity stops (spec 8.A "foreground exit"). */
internal class ForegroundExitObserver(
    private val clock: Clock,
    private val coordinator: DeliveryCoordinator,
) : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)

    override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }

    override fun onActivityStopped(activity: Activity) {
        if (started.decrementAndGet() == 0 && !activity.isChangingConfigurations) {
            coordinator.ensureScheduled(clock.nowMillis())
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

/** Tells the foreground trigger and scheduler about each stored event; the collector stays unaware. */
internal class NotifyingEventSink(
    private val inner: EventSink,
    private val onAdded: () -> Unit,
) : EventSink {
    override suspend fun add(
        event: EventEntity,
        limits: QueueLimits,
    ): AddOutcome =
        inner.add(event, limits).also { if (it == AddOutcome.ADDED) onAdded() }
}
