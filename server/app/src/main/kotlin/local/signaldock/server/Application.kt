package local.signaldock.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.ErrorResponse
import java.io.ByteArrayOutputStream
import local.signaldock.ingestion.RequestFailure
import local.signaldock.persistence.Database
import local.signaldock.persistence.DatabaseObserver
import local.signaldock.persistence.DatabaseUnavailable
import local.signaldock.query.parseProjectZone

/** Callback is a test seam for response loss after a real commit; production uses the default. */
internal fun Application.ingestionModule(
    database: Database,
    collectionKey: String,
    afterCommit: suspend (BatchAckResponse) -> Unit = {},
) {
    require(collectionKey.isNotBlank())
    val ingestion = Ingestion(database)
    routing {
        get("/health") { call.respondText("ok") }
        post("/v1/events:batch") {
            val budget = database.budget()
            try {
                withTimeout(budget.remainingMs()) {
                    val requestContractVersion = call.requireContractVersion()
                    call.requireCollectionKey(collectionKey)
                    if (!call.request.contentType().match(ContentType.Application.Json)) {
                        throw RequestFailure("CONTENT_TYPE_UNSUPPORTED")
                    }
                    if (call.request.headers[HttpHeaders.ContentEncoding]?.lowercase()?.let { it != "identity" } == true) {
                        throw RequestFailure("CONTENT_ENCODING_UNSUPPORTED")
                    }
                    val channel = call.receiveChannel()
                    val body = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, 262145 - body.size()))
                        if (count < 0) break
                        body.write(buffer, 0, count)
                        if (body.size() > 262144) throw RequestFailure("PAYLOAD_TOO_LARGE")
                    }
                    val ack = ingestion.ingest(parseBatch(body.toByteArray(), requestContractVersion), budget)
                    afterCommit(ack)
                    call.respondText(Json.encodeToString(ack), ContentType.Application.Json)
                }
            } catch (error: RequestFailure) {
                call.respondText(Json.encodeToString(ErrorResponse(error.code, error.code)), ContentType.Application.Json,
                    HttpStatusCode.fromValue(httpStatusOf(error.code)))
            } catch (_: DatabaseUnavailable) {
                call.response.headers.append(HttpHeaders.RetryAfter, "1")
                call.respondText(Json.encodeToString(ErrorResponse("TEMPORARILY_UNAVAILABLE", "Retry with the same event IDs")),
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            } catch (_: TimeoutCancellationException) {
                call.response.headers.append(HttpHeaders.RetryAfter, "1")
                call.respondText(Json.encodeToString(ErrorResponse("REQUEST_TIMEOUT", "Storage outcome can be uncertain; retry with the same event IDs")),
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

/** Q131: both keys are required and non-blank, and they differ. Errors name variables, never values. */
internal fun resolveKeys(collectionKey: String?, queryKey: String?): Pair<String, String> {
    require(!collectionKey.isNullOrBlank()) { "SIGNALDOCK_COLLECTION_KEY is missing or blank" }
    require(!queryKey.isNullOrBlank()) { "SIGNALDOCK_QUERY_KEY is missing or blank" }
    require(collectionKey != queryKey) { "SIGNALDOCK_QUERY_KEY must differ from SIGNALDOCK_COLLECTION_KEY" }
    return collectionKey to queryKey
}

fun main() {
    fun required(name: String) = requireNotNull(System.getenv(name)) { "Missing $name" }
    val (collectionKey, queryKey) = resolveKeys(System.getenv("SIGNALDOCK_COLLECTION_KEY"), System.getenv("SIGNALDOCK_QUERY_KEY"))
    val database = Database(required("SIGNALDOCK_JDBC_URL"), required("SIGNALDOCK_DB_USER"), required("SIGNALDOCK_DB_PASSWORD"),
        observer = DatabaseObserver { stage, millis ->
            org.slf4j.LoggerFactory.getLogger("ingestion.database").info("stage={} elapsedMs={}", stage, millis)
        })
    try {
        database.migrate()
        embeddedServer(Netty, host = "127.0.0.1", port = System.getenv("PORT")?.toInt() ?: 8080) {
            ingestionModule(database, collectionKey)
            queryModule(database, queryKey, parseProjectZone(System.getenv("SIGNALDOCK_TIME_ZONE") ?: "Asia/Seoul"))
            monitor.subscribe(ApplicationStopped) { database.close() }
        }.start(wait = true)
    } finally {
        database.close()
    }
}
