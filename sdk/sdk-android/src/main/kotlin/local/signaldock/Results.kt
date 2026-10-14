package local.signaldock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class LogLevel { DEBUG, INFO, WARN, ERROR, NONE }

/**
 * Fixed for the SDK lifetime (Q117, Q121). Timeouts must be positive and finite.
 * [collectionKey] is excluded from [toString] (Q115).
 */
public data class SignalDockConfig(
    val endpoint: String,
    val collectionKey: String,
    val logLevel: LogLevel = LogLevel.WARN,
    val callTimeout: Duration = 30.seconds,
    val connectTimeout: Duration = 10.seconds,
    val readTimeout: Duration = 10.seconds,
    val writeTimeout: Duration = 10.seconds,
) {
    override fun toString(): String = "SignalDockConfig(endpoint=$endpoint, collectionKey=***, logLevel=$logLevel)"
}

public sealed interface InitResult {
    public data class Success(val sdk: SignalDock) : InitResult
    public data class Failure(val error: InitError) : InitResult
}

public sealed interface InitError {
    /** The configuration is invalid. No SDK state was created (Q118). */
    public data class InvalidConfiguration(val message: String) : InitError

    /** A different configuration is active or initializing (Q113, Q118). */
    public data object ConfigurationConflict : InitError

    /** Storage could not be prepared. The host may call initialize again (Q112). */
    public data class StorageFailure(val message: String) : InitError
}

public sealed interface TrackResult {
    /** The event is durably stored in Room. This is not server receipt. */
    public data class Success(val eventId: String) : TrackResult

    /** Rejected input. Nothing was stored. [field] is "name" or the property key; it is never logged. */
    public data class Invalid(val reason: InvalidReason, val field: String?) : TrackResult

    /** The queue is at its event or byte limit. Existing events are kept. */
    public data object QueueFull : TrackResult

    public data class StorageError(val message: String) : TrackResult
}

public enum class InvalidReason {
    EMPTY_NAME,
    LEADING_OR_TRAILING_WHITESPACE,
    FORBIDDEN_CHARACTER,
    DUPLICATE_KEY,
    NON_FINITE_NUMBER,
    NUMBER_OUT_OF_RANGE,
    EVENT_TOO_LARGE,
}

public data class RejectionDiagnostic(
    val eventId: String,
    val code: String,
    val rejectedAtEpochMillis: Long,
)

public data class AckDiagnostic(
    val atEpochMillis: Long,
    val accepted: Int,
    val duplicate: Int,
    val rejected: Int,
)

/** Final delivery failure classes (architecture "Retry and transport boundaries"). */
public enum class DeliveryErrorKind {
    NETWORK, TIMEOUT, RATE_LIMITED, SERVER_ERROR, AUTH_OR_CONFIGURATION, CONTRACT, PAYLOAD_TOO_LARGE, INVALID_ACK,
}

/** Last final delivery error. Content-free: a kind, an optional HTTP status and a time. */
public data class DeliveryErrorDiagnostic(
    val kind: DeliveryErrorKind,
    val httpStatus: Int?,
    val atEpochMillis: Long,
)

/**
 * Read-only snapshot of the device queue. It says nothing about server state. [storageAvailable] is
 * false when the queue could not be read; the other fields are then empty and mean nothing.
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
)
