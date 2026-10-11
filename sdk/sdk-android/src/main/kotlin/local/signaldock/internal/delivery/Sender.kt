package local.signaldock.internal.delivery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.signaldock.DeliveryErrorKind
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.Logger
import local.signaldock.internal.storage.QueueDao
import local.signaldock.internal.storage.RejectionEntity
import local.signaldock.internal.storage.RetryStateEntity
import org.openapitools.client.models.ItemAck

/** Soft budget of one Worker run (Q72), checked between batches. */
internal class RunBudget(val maxBatches: Int, val softMillis: Long)

internal enum class RunEnd { QUEUE_EMPTY, WAITING, BUDGET_EXHAUSTED }

/** [nextAttemptMillis] is set when [end] is [RunEnd.WAITING]. */
internal class RunResult(val batches: Int, val end: RunEnd, val nextAttemptMillis: Long? = null)

/**
 * The one sender, shared by foreground triggers and the Worker (spec section 4). One batch is
 * selected, sent, validated and applied while holding [mutex]; the lock is released between
 * batches. No Room transaction is open during HTTP. New `track` writes never touch this lock.
 * The mutex is per process: the design has one SDK instance in the default process (ADR-0006).
 */
internal class Sender(
    private val dao: QueueDao,
    private val transport: BatchTransport,
    private val clock: Clock,
    private val jitter: Jitter,
    private val logger: Logger,
) {
    private val mutex = Mutex()

    private sealed interface Step {
        data object Empty : Step
        data object Continue : Step
        class Wait(val atMillis: Long) : Step
    }

    /** Sends batches until the queue is empty, a wait applies or [budget] is used up. */
    suspend fun run(budget: RunBudget? = null): RunResult {
        val start = clock.nowMillis()
        var batches = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val step = mutex.withLock { sendOne() }) {
                Step.Empty -> return RunResult(batches, RunEnd.QUEUE_EMPTY)
                is Step.Wait -> return RunResult(batches, RunEnd.WAITING, step.atMillis)
                Step.Continue -> {
                    batches++
                    if (budget != null && (batches >= budget.maxBatches || clock.nowMillis() - start >= budget.softMillis)) {
                        return RunResult(batches, RunEnd.BUDGET_EXHAUSTED)
                    }
                }
            }
        }
    }

    private suspend fun sendOne(): Step {
        val head = dao.oldest(1).firstOrNull() ?: return Step.Empty // empty queue: no HTTP
        val now = clock.nowMillis()
        val state = dao.retryState() ?: RetryStateEntity()
        state.nextAttemptAtMillis?.let { if (now < it) return Step.Wait(it) }

        // Batches are grouped by stored contract version (Q66); the request also carries one installationId.
        val cap = minOf(MAX_BATCH_EVENTS, state.maxBatchEvents ?: MAX_BATCH_EVENTS)
        val rows = dao.oldestOfGroup(head.contractVersion, head.installationId, cap)
        val batch = buildBatch(rows, cap)

        logger.debug("delivery attempt started")
        // A cancellation here leaves the queue untouched; the same ids are sent again later.
        val result = transport.send(BatchHttpRequest(batch.contractVersion, batch.body))
        val after = clock.nowMillis()
        logger.debug("delivery attempt finished in ${after - now} ms")
        return when (result) {
            is TransportResult.Failed -> fail(state, after, result.kind, null, null)
            is TransportResult.Http -> onHttp(state, batch, result, after)
        }
    }

    private suspend fun onHttp(state: RetryStateEntity, batch: Batch, r: TransportResult.Http, now: Long): Step {
        val retryAfter = parseRetryAfterMillis(r.retryAfter, now)
        val s = r.status
        return when {
            s == 200 -> applyResponse(state, batch, r, now)
            s == 413 -> if (batch.events.size > 1) split(state, batch.events.size, retryAfter, now)
            else hold(state, now, DeliveryErrorKind.PAYLOAD_TOO_LARGE, s, retryAfter, singleEvent = true)
            s == 408 -> fail(state, now, DeliveryErrorKind.TIMEOUT, s, retryAfter)
            s == 429 -> fail(state, now, DeliveryErrorKind.RATE_LIMITED, s, retryAfter)
            s == 501 || s == 505 -> hold(state, now, DeliveryErrorKind.AUTH_OR_CONFIGURATION, s, retryAfter)
            s in 500..599 -> fail(state, now, DeliveryErrorKind.SERVER_ERROR, s, retryAfter)
            s == 400 -> hold(state, now, DeliveryErrorKind.CONTRACT, s, retryAfter)
            // 401, 403, other 4xx (404, 415 ...) and redirects (not followed): configuration or authentication.
            s in 300..499 -> hold(state, now, DeliveryErrorKind.AUTH_OR_CONFIGURATION, s, retryAfter)
            // 1xx or a 2xx other than 200: not a valid ACK.
            else -> fail(state, now, DeliveryErrorKind.INVALID_ACK, s, retryAfter)
        }
    }

    private suspend fun applyResponse(state: RetryStateEntity, batch: Batch, r: TransportResult.Http, now: Long): Step {
        val items = validateAck(batch.events.map { it.eventId }, r.body)
            ?: return fail(state, now, DeliveryErrorKind.INVALID_ACK, r.status, null) // applies nothing (Q59)
        val rejected = items.filter { it.status == ItemAck.Status.REJECTED }
        val rejections = rejected.map { RejectionEntity(eventId = batch.events[it.index].eventId, code = codeOf(it), rejectedAtMillis = now) }
        val grown = state.maxBatchEvents?.let { (it * 2).takeIf { v -> v < MAX_BATCH_EVENTS } }
        val next = state.copy(
            failureCount = 0, nextAttemptAtMillis = null, hold = false, maxBatchEvents = grown,
            lastAckAtMillis = now,
            lastAckAccepted = items.count { it.status == ItemAck.Status.ACCEPTED },
            lastAckDuplicate = items.count { it.status == ItemAck.Status.DUPLICATE },
            lastAckRejected = rejected.size,
        )
        // One transaction: delete settled events, store rejection diagnostics, reset retry state.
        dao.applyAck(batch.events.map { it.eventId }, rejections, next)
        if (state.hold) logger.warn("delivery resumed after a successful ACK")
        return Step.Continue
    }

    /** Temporary failure: persist failureCount + 1 and one Full Jitter draw, once per failure (Q74). */
    private suspend fun fail(state: RetryStateEntity, now: Long, kind: DeliveryErrorKind, status: Int?, retryAfter: Long?): Step {
        val count = if (state.failureCount == Int.MAX_VALUE) Int.MAX_VALUE else state.failureCount + 1
        val bound = backoffBaseMillis(count)
        val wait = maxOf(jitter.nextMillis(bound).coerceIn(0, bound), retryAfter ?: 0L, if (state.hold) HOLD_RECHECK_MILLIS else 0L)
        val at = satAdd(now, wait)
        dao.saveRetryState(state.withError(kind, status, now).copy(failureCount = count, nextAttemptAtMillis = at))
        logger.debug("delivery failed: $kind")
        return Step.Wait(at)
    }

    /** Authentication, configuration or contract error (or a single oversized event): stop normal sending. */
    private suspend fun hold(
        state: RetryStateEntity, now: Long, kind: DeliveryErrorKind, status: Int, retryAfter: Long?, singleEvent: Boolean = false,
    ): Step {
        val at = satAdd(now, maxOf(HOLD_RECHECK_MILLIS, retryAfter ?: 0L))
        dao.saveRetryState(
            state.withError(kind, status, now).copy(
                hold = true, nextAttemptAtMillis = at, maxBatchEvents = if (singleEvent) 1 else state.maxBatchEvents,
            ),
        )
        if (!state.hold) logger.warn("delivery paused by a configuration, authentication or contract error; rechecking every 30 minutes")
        return Step.Wait(at)
    }

    /** 413 for a multi-event batch: smaller batches, same ids, no earlier retry than a valid Retry-After. */
    private suspend fun split(state: RetryStateEntity, size: Int, retryAfter: Long?, now: Long): Step {
        val at = retryAfter?.let { satAdd(now, it) }
        dao.saveRetryState(
            state.withError(DeliveryErrorKind.PAYLOAD_TOO_LARGE, 413, now)
                .copy(maxBatchEvents = maxOf(1, size / 2), nextAttemptAtMillis = at),
        )
        return if (at != null) Step.Wait(at) else Step.Continue
    }

    private fun RetryStateEntity.withError(kind: DeliveryErrorKind, status: Int?, now: Long) =
        copy(lastErrorKind = kind.name, lastErrorHttpStatus = status, lastErrorAtMillis = now)

    private fun codeOf(item: ItemAck): String = (item.code?.takeIf { it.isNotBlank() } ?: "UNSPECIFIED").take(MAX_CODE_LENGTH)

    private companion object {
        const val MAX_CODE_LENGTH = 64
    }
}
