package local.signaldock.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import local.signaldock.ingestion.RequestFailure
import java.security.MessageDigest

/** Contract version header on a business API (Q105, Q107). Returns the accepted version. */
internal fun ApplicationCall.requireContractVersion(): String {
    val version = request.headers.getAll("SignalDock-Contract-Version")
        ?: throw RequestFailure("CONTRACT_HEADER_MISSING")
    if (version != listOf("1")) throw RequestFailure("CONTRACT_VERSION_UNSUPPORTED")
    return version.single()
}

internal fun ApplicationCall.requireCollectionKey(collectionKey: String) = requireBearer(collectionKey, "COLLECTION_KEY_INVALID")

/** Query endpoints take only the query key; the error never says whether a value is a valid collection key (Q130). */
internal fun ApplicationCall.requireQueryKey(queryKey: String) = requireBearer(queryKey, "QUERY_KEY_INVALID")

/** One `Authorization: Bearer <key>` value, compared in constant time. The error code follows the role. */
private fun ApplicationCall.requireBearer(key: String, errorCode: String) {
    val provided = request.headers.getAll(HttpHeaders.Authorization)
    val authorized = provided?.singleOrNull()?.let {
        MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), "Bearer $key".toByteArray(Charsets.UTF_8))
    } ?: false
    if (!authorized) throw RequestFailure(errorCode)
}
