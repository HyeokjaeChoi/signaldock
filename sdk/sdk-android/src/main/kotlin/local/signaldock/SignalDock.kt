package local.signaldock

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import local.signaldock.internal.collection.EventCollector
import local.signaldock.internal.delivery.DeprecationNotice
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.init.Logger
import local.signaldock.internal.scheduling.DeliveryCoordinator
import local.signaldock.internal.storage.QueueDao
import local.signaldock.internal.transport.SDK_VERSION

/**
 * The SDK instance. Get it from [initialize]; call [track] only on a successful result (Q111).
 * One instance with one fixed configuration per process.
 *
 * The public calls are [track], [diagnostics] and [initialize]. Expected failures come back as
 * result values; an exception from the SDK's storage or scheduling does not reach the host
 * (ADR-0017). Caller cancellation is the exception: it propagates (spec section 3).
 */
public class SignalDock internal constructor(
    private val collector: EventCollector,
    internal val dao: QueueDao,
    internal val delivery: DeliveryCoordinator,
    private val logger: Logger,
    private val notice: DeprecationNotice,
) {
    /**
     * Collects one event named [name]. Returns [TrackResult.Success] after the event is committed
     * to Room. This is not server receipt. [kotlin.coroutines.cancellation.CancellationException] is
     * propagated; the event may already be stored, so do not collect the same action again on
     * cancellation.
     *
     * Names and property keys must not be empty and must not start or end with whitespace. The
     * SDK does not trim, normalize or convert anything; it returns [TrackResult.Invalid] instead.
     * The call needs no network. The SDK sends stored events later.
     *
     * @param name the event name, chosen by the host app.
     * @param block adds flat string, number and boolean properties to the event; see [EventBuilder].
     * @return the collection result. Handle every case; see [TrackResult].
     */
    public suspend fun track(name: String, block: EventBuilder.() -> Unit = {}): TrackResult =
        collector.track(name, block)

    /**
     * Read-only snapshot of the device queue, recent permanent rejections, ACK and retry state.
     * It does not describe server state. A storage failure is reported as data, not as an
     * exception: [Diagnostics.storageAvailable] is then `false` (ADR-0017).
     *
     * @return one consistent snapshot of the queue state.
     */
    public suspend fun diagnostics(): Diagnostics {
        val snap = try {
            dao.diagnosticsSnapshot()
        } catch (e: Exception) {
            // Caller cancellation propagates; a closed Room database cancels its own scope instead.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            // Storage failure: report it as data, never as an exception (ADR-0017).
            logger.warn("diagnostics storage failed: ${e.javaClass.simpleName}")
            // Narrows the race with a cancellation that arrives after the check above.
            currentCoroutineContext().ensureActive()
            return Diagnostics(SDK_VERSION, 0, 0, null, emptyList(), null, null, 0, null, storageAvailable = false)
                .also { it.contractDeprecation = notice.current }
        }
        val retry = snap.retry
        return Diagnostics(
            sdkVersion = SDK_VERSION,
            queuedEvents = snap.usage.count,
            queuedBytes = snap.usage.bytes,
            oldestPendingEpochMillis = snap.usage.oldestCreatedAtMillis,
            recentRejections = snap.rejections.map { RejectionDiagnostic(it.eventId, it.code, it.rejectedAtMillis) },
            lastAck = retry?.lastAckAtMillis?.let {
                AckDiagnostic(it, retry.lastAckAccepted, retry.lastAckDuplicate, retry.lastAckRejected)
            },
            lastDeliveryError = retry?.lastErrorAtMillis?.let { at ->
                runCatching { DeliveryErrorKind.valueOf(retry.lastErrorKind.orEmpty()) }.getOrNull()
                    ?.let { DeliveryErrorDiagnostic(it, retry.lastErrorHttpStatus, at) }
            },
            retryFailureCount = retry?.failureCount ?: 0,
            nextAttemptEpochMillis = retry?.nextAttemptAtMillis,
        ).also { it.contractDeprecation = notice.current }
    }

    /** Entry point of the SDK. It holds [initialize], the only way to get a [SignalDock] instance. */
    public companion object {
        /**
         * Suspend initialization. Same configuration: shares one attempt and reuses a success.
         * Different configuration: [InitError.ConfigurationConflict]. A failed attempt is not
         * repeated automatically; call again to retry (Q112). Cancelling the caller cancels only
         * its own wait; the attempt continues in the SDK's process scope (Q114).
         *
         * Call it once from the app's `Application`. The SDK keeps only the application context.
         *
         * @param context any [Context]; the SDK keeps only its application context.
         * @param config the configuration, fixed for the SDK lifetime.
         * @return [InitResult.Success] with the instance, or [InitResult.Failure] with the reason.
         */
        public suspend fun initialize(context: Context, config: SignalDockConfig): InitResult =
            InitCoordinator.default.initialize(context.applicationContext, config)
    }
}
