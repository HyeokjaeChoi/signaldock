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

/** One `Authorization: Bearer <collection key>` value, compared in constant time. */
internal fun ApplicationCall.requireCollectionKey(collectionKey: String) {
    val provided = request.headers.getAll(HttpHeaders.Authorization)
    val authorized = provided?.singleOrNull()?.let {
        MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), "Bearer $collectionKey".toByteArray(Charsets.UTF_8))
    } ?: false
    if (!authorized) throw RequestFailure("COLLECTION_KEY_INVALID")
}
