package local.signaldock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How much the SDK writes to Logcat under the tag `SignalDock`. The SDK never logs event content,
 * event names, installation IDs, credentials or HTTP bodies, at any level (architecture "SDK
 * lifecycle and cancellation").
 */
public enum class LogLevel {
    /** Writes DEBUG, WARN and ERROR messages (initialization, send start and finish, final delivery failure kind). */
    DEBUG,

    /** Writes WARN and ERROR messages. The SDK has no INFO messages. */
    INFO,

    /** Writes WARN and ERROR messages. This is the default of [SignalDockConfig.logLevel]. */
    WARN,

    /** Writes ERROR messages only. */
    ERROR,

    /** Writes nothing. */
    NONE,
}

/**
 * Fixed for the SDK lifetime (Q117, Q121). Timeouts must be positive and finite.
 * [collectionKey] is excluded from [toString] (Q115).
 *
 * @property endpoint absolute `https` URL of the SignalDock server. The SDK does not change `http`
 *   to `https` and does not follow redirects. An `http` URL is rejected at initialization, unless
 *   [allowInsecureEndpoint] is `true` (ADR-0035).
 * @property collectionKey the collection key for this project. It is write-only and not a secret
 *   that apps can keep from users (spec section 5). It must not be blank.
 * @property logLevel the Logcat level; the default is [LogLevel.WARN].
 * @property callTimeout timeout of one whole HTTP call; the default is 30 seconds.
 * @property connectTimeout timeout to connect; the default is 10 seconds.
 * @property readTimeout timeout of one read; the default is 10 seconds.
 * @property writeTimeout timeout of one write; the default is 10 seconds.
 * @property allowInsecureEndpoint `true` lets [endpoint] use `http`. The default is `false`: the SDK
 *   sends the collection key in every request, so a plain-text endpoint is for development only
 *   (ADR-0035). The new constructor parameter changes the JVM constructor and `copy` signatures
 *   (source compatible; allowed before 1.0, docs/versioning.md).
 */
public data class SignalDockConfig(
    val endpoint: String,
    val collectionKey: String,
    val logLevel: LogLevel = LogLevel.WARN,
    val callTimeout: Duration = 30.seconds,
    val connectTimeout: Duration = 10.seconds,
    val readTimeout: Duration = 10.seconds,
    val writeTimeout: Duration = 10.seconds,
    val allowInsecureEndpoint: Boolean = false,
) {
    override fun toString(): String = "SignalDockConfig(endpoint=$endpoint, collectionKey=***, logLevel=$logLevel)"
}

/** Result of [SignalDock.initialize]. Handle both cases. */
public sealed interface InitResult {
    /**
     * Initialization succeeded.
     *
     * @property sdk the instance. Call [SignalDock.track] on it.
     */
    public data class Success(val sdk: SignalDock) : InitResult

    /**
     * Initialization failed. The host may call [SignalDock.initialize] again (Q112).
     *
     * @property error the reason.
     */
    public data class Failure(val error: InitError) : InitResult
}

/** Why [SignalDock.initialize] failed. */
public sealed interface InitError {
    /**
     * The configuration is invalid. No SDK state was created (Q118).
     *
     * @property message what is wrong. It never contains the collection key.
     */
    public data class InvalidConfiguration(val message: String) : InitError

    /** A different configuration is active or initializing (Q113, Q118). */
    public data object ConfigurationConflict : InitError

    /**
     * Storage could not be prepared. The host may call initialize again (Q112).
     *
     * @property message the simple class name of the exception. It is no secret and no event content.
     */
    public data class StorageFailure(val message: String) : InitError
}

/** Result of [SignalDock.track]. Handle every case. */
public sealed interface TrackResult {
    /**
     * The event is durably stored in Room. This is not server receipt.
     *
     * @property eventId the SDK-generated UUIDv4 of the event. It stays the same on every resend.
     */
    public data class Success(val eventId: String) : TrackResult

    /**
     * Rejected input. Nothing was stored. [field] is "name" or the property key; it is never logged.
     *
     * @property reason which rule failed.
     * @property field `"name"` or the property key; `null` for [InvalidReason.EVENT_TOO_LARGE].
     */
    public data class Invalid(val reason: InvalidReason, val field: String?) : TrackResult

    /**
     * The host called [SignalDock.setOptOut] with `true`. Nothing was validated, stored or sent
     * (ADR-0034). This is not an error: handle it as "collection is off".
     */
    public data object OptedOut : TrackResult

    /** The queue is at its event or byte limit. Existing events are kept. */
    public data object QueueFull : TrackResult

    /**
     * Storage failed. Nothing is known to be stored. The SDK catches storage exceptions other
     * than caller cancellation (ADR-0017).
     *
     * @property message the simple class name of the exception. It is no event content.
     */
    public data class StorageError(val message: String) : TrackResult
}

/** The rule that [TrackResult.Invalid] reports. The SDK does not correct input (spec section 3, Q64, Q84, Q85). */
public enum class InvalidReason {
    /** The name or property key is empty or has only whitespace characters. */
    EMPTY_NAME,

    /** The name or property key starts or ends with a whitespace character. */
    LEADING_OR_TRAILING_WHITESPACE,

    /** Text contains a NUL character or a lone surrogate. */
    FORBIDDEN_CHARACTER,

    /** A property key was used twice in one call, even with the same value (Q63). */
    DUPLICATE_KEY,

    /** A decimal value is NaN or infinite. */
    NON_FINITE_NUMBER,

    /** An integer is outside plus or minus (2^53 - 1), also when it is passed as a Double. */
    NUMBER_OUT_OF_RANGE,

    /** The event is larger than 16 KiB of transport JSON (spec section 4). */
    EVENT_TOO_LARGE,
}

/**
 * One recent permanent rejection by the server. It holds no event content and no event name.
 *
 * @property eventId the ID of the rejected event.
 * @property code the server's rejection code, cut to 64 characters; `UNSPECIFIED` if none was sent.
 * @property rejectedAtEpochMillis when the SDK recorded the rejection, in epoch milliseconds.
 */
public data class RejectionDiagnostic(
    val eventId: String,
    val code: String,
    val rejectedAtEpochMillis: Long,
)

/**
 * Counts of the last valid ACK. A recent ACK does not mean the whole queue was sent.
 *
 * @property atEpochMillis when the SDK applied the ACK, in epoch milliseconds.
 * @property accepted how many events the server stored.
 * @property duplicate how many events the server already had.
 * @property rejected how many events the server rejected permanently.
 */
public data class AckDiagnostic(
    val atEpochMillis: Long,
    val accepted: Int,
    val duplicate: Int,
    val rejected: Int,
)

/** Final delivery failure classes (architecture "Retry and transport boundaries"). */
public enum class DeliveryErrorKind {
    /** A network error. The queue is kept and the SDK backs off. */
    NETWORK,

    /** A call timeout or HTTP 408. The queue is kept and the SDK backs off. */
    TIMEOUT,

    /** HTTP 429. The queue is kept and the SDK backs off. */
    RATE_LIMITED,

    /** A temporary HTTP 5xx. The queue is kept and the SDK backs off. */
    SERVER_ERROR,

    /** An authentication, configuration or redirect problem. Normal sending pauses; one batch is tried every 30 minutes. */
    AUTH_OR_CONFIGURATION,

    /** A contract error (HTTP 400). Normal sending pauses; one batch is tried every 30 minutes. */
    CONTRACT,

    /** HTTP 413 for a single event. Normal sending pauses; that request is tried every 30 minutes. */
    PAYLOAD_TOO_LARGE,

    /** The ACK was unreadable, incomplete or contradictory. The SDK applies none of it and backs off. */
    INVALID_ACK,
}

/**
 * Last final delivery error. Content-free: a kind, an optional HTTP status and a time.
 *
 * @property kind the failure class.
 * @property httpStatus the HTTP status, or `null` when there was none (for example a network error).
 * @property atEpochMillis when the SDK recorded the error, in epoch milliseconds.
 */
public data class DeliveryErrorDiagnostic(
    val kind: DeliveryErrorKind,
    val httpStatus: Int?,
    val atEpochMillis: Long,
)

/**
 * Read-only snapshot of the device queue. It says nothing about server state. [storageAvailable] is
 * false when the queue could not be read; the other fields are then empty and mean nothing.
 *
 * @property sdkVersion the SDK release version, for example `0.1.0`. It is not the contract version.
 * @property queuedEvents how many events wait in the queue.
 * @property queuedBytes the total transport JSON bytes of the queued events.
 * @property oldestPendingEpochMillis when the oldest queued event was collected, or `null` if the queue is empty.
 * @property recentRejections the most recent permanent rejections, at most 100.
 * @property lastAck the last valid ACK, or `null` if there was none.
 * @property lastDeliveryError the last final delivery error, or `null` if there was none. It stays as
 *   history after recovery.
 * @property retryFailureCount the count of temporary failures since the last valid ACK.
 * @property nextAttemptEpochMillis the earliest time of the next send, or `null` if none is waiting.
 * @property storageAvailable `false` when the queue could not be read.
 *
 * [contractDeprecation] is not a constructor property: a new constructor property would break
 * binary compatibility (ADR-0030). It is not part of [equals], [hashCode], [toString], `componentN`
 * or [copy]; a copy made with [copy] has `null` there.
 */
public data class Diagnostics(
    val sdkVersion: String,
    val queuedEvents: Int,
    val queuedBytes: Long,
    val oldestPendingEpochMillis: Long?,
    val recentRejections: List<RejectionDiagnostic>,
    val lastAck: AckDiagnostic?,
    val lastDeliveryError: DeliveryErrorDiagnostic?,
    val retryFailureCount: Int,
    val nextAttemptEpochMillis: Long?,
    val storageAvailable: Boolean = true,
) {
    /**
     * The server told this process that the contract version in use is deprecated, or `null` if it
     * did not (ADR-0030). It lives in memory for the process; it is not stored. Informational only:
     * delivery does not change.
     */
    public var contractDeprecation: ContractDeprecation? = null
        internal set
}

/**
 * A server warning that the contract version in use will be retired (RFC 9745 `Deprecation`,
 * RFC 8594 `Sunset`). Delivery does not change because of it. Valid headers only; a field is `null`
 * when its header was absent or invalid.
 *
 * @property deprecatedAtEpochMillis the `Deprecation` date in epoch milliseconds.
 * @property sunsetAtEpochMillis the `Sunset` date in epoch milliseconds.
 */
public class ContractDeprecation internal constructor(
    public val deprecatedAtEpochMillis: Long?,
    public val sunsetAtEpochMillis: Long?,
) {
    override fun equals(other: Any?): Boolean = other is ContractDeprecation &&
        deprecatedAtEpochMillis == other.deprecatedAtEpochMillis && sunsetAtEpochMillis == other.sunsetAtEpochMillis

    override fun hashCode(): Int = 31 * (deprecatedAtEpochMillis?.hashCode() ?: 0) + (sunsetAtEpochMillis?.hashCode() ?: 0)

    override fun toString(): String = "ContractDeprecation(deprecatedAt=$deprecatedAtEpochMillis, sunsetAt=$sunsetAtEpochMillis)"
}
