package local.signaldock.server

import local.signaldock.ingestion.SdkPlatform
import local.signaldock.ingestion.ValidatedBatch
import local.signaldock.ingestion.ValidatedItem

/** Rejection code of an event that the operator blocked (ADR-0037). The SDK deletes it and counts `server_rejected`. */
internal const val COLLECTION_BLOCKED = "COLLECTION_BLOCKED"

/**
 * Operator remote control (ADR-0037), read once at start. Default: nothing blocked, no shed.
 * Block by exact event name or by the (platform, `sdkVersion`) pair of the event (ADR-0046); shed answers 503 for the whole ingest.
 */
internal class CollectionControl(
    private val blockedEvents: Set<String> = emptySet(),
    val blockedSdkVersions: Set<Pair<SdkPlatform, String>> = emptySet(),
    val shed: Boolean = false,
    val shedRetryAfterSeconds: Int = DEFAULT_RETRY_AFTER_SECONDS,
) {
    /** Blocked valid events become per-item rejections (ADR-0027 path). Items that are already rejected stay as they are. */
    fun apply(batch: ValidatedBatch): ValidatedBatch {
        if (blockedEvents.isEmpty() && blockedSdkVersions.isEmpty()) return batch
        fun blocked(item: ValidatedItem) =
            item.event?.let { it.name in blockedEvents || (it.sdkPlatform to it.sdkVersion) in blockedSdkVersions } == true
        // Same-ID group with a rejected member: the whole group is rejected (ADR-0004 atomic group), like resolveEventIdConflicts.
        val blockedIds = batch.items.filter(::blocked).map { it.eventId }.toSet()
        return ValidatedBatch(batch.items.map { item ->
            when {
                blocked(item) -> item.copy(event = null, rejectionCode = COLLECTION_BLOCKED)
                item.event != null && item.eventId in blockedIds -> item.copy(event = null, rejectionCode = "EVENT_ID_CONFLICT")
                else -> item
            }
        })
    }

    companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 1
        private const val MAX_RETRY_AFTER_SECONDS = 86_400 // the SDK never caps Retry-After (ADR-0006)

        /** A variable that is set but malformed fails the start, like the other variables. */
        fun fromEnv(env: (String) -> String?): CollectionControl {
            fun list(name: String) = env(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            // `platform:version`; no prefix means `android`. The prefix is exact and case-sensitive (ADR-0046).
            // A version holds no `:` of its own in practice, so the first one splits. `:1.0` or `ios:` is malformed.
            fun versionPair(item: String): Pair<SdkPlatform, String> {
                val colon = item.indexOf(':')
                val platform = if (colon < 0) SdkPlatform.ANDROID else SdkPlatform.fromWire(item.substring(0, colon))
                val version = if (colon < 0) item else item.substring(colon + 1)
                require(platform != null && version.isNotEmpty()) {
                    "SIGNALDOCK_BLOCKED_SDK_VERSIONS item must be android:<version>, ios:<version>, or <version>"
                }
                return platform to version
            }
            val shed = when (env("SIGNALDOCK_INGEST_SHED")?.trim()?.lowercase()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("SIGNALDOCK_INGEST_SHED must be true or false")
            }
            val retryAfter = env("SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS")?.takeIf { it.isNotBlank() }?.let {
                requireNotNull(it.trim().toIntOrNull()?.takeIf { s -> s in 1..MAX_RETRY_AFTER_SECONDS }) {
                    "SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS is not an integer from 1 to $MAX_RETRY_AFTER_SECONDS"
                }
            } ?: DEFAULT_RETRY_AFTER_SECONDS
            return CollectionControl(list("SIGNALDOCK_BLOCKED_EVENTS"), list("SIGNALDOCK_BLOCKED_SDK_VERSIONS").map(::versionPair).toSet(), shed, retryAfter)
        }
    }
}
