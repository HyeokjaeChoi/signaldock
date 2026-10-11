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
import java.security.MessageDigest

/** Callback is a test seam for response loss after a real commit; production uses the default. */
fun Application.ingestionModule(
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
                    val version = call.request.headers.getAll("SignalDock-Contract-Version")
                    if (version == null) throw RequestFailure("CONTRACT_HEADER_MISSING")
                    if (version != listOf("1")) throw RequestFailure("CONTRACT_VERSION_UNSUPPORTED")
                    val provided = call.request.headers.getAll(HttpHeaders.Authorization)
                    val authorized = provided?.singleOrNull()?.let {
                        MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), "Bearer $collectionKey".toByteArray(Charsets.UTF_8))
                    } ?: false
                    if (!authorized) throw RequestFailure("COLLECTION_KEY_INVALID", 401)
                    if (!call.request.contentType().match(ContentType.Application.Json)) {
                        throw RequestFailure("CONTENT_TYPE_UNSUPPORTED", 415)
                    }
                    if (call.request.headers[HttpHeaders.ContentEncoding]?.lowercase()?.let { it != "identity" } == true) {
                        throw RequestFailure("CONTENT_ENCODING_UNSUPPORTED", 415)
                    }
                    val channel = call.receiveChannel()
                    val body = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, 262145 - body.size()))
                        if (count < 0) break
                        body.write(buffer, 0, count)
                        if (body.size() > 262144) throw RequestFailure("PAYLOAD_TOO_LARGE", 413)
                    }
                    val ack = ingestion.ingest(parseBatch(body.toByteArray()), budget)
                    afterCommit(ack)
                    call.respondText(Json.encodeToString(ack), ContentType.Application.Json)
                }
            } catch (error: RequestFailure) {
                call.respondText(Json.encodeToString(ErrorResponse(error.code, error.code)), ContentType.Application.Json,
                    HttpStatusCode.fromValue(error.httpStatus))
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

fun main() {
    fun required(name: String) = requireNotNull(System.getenv(name)) { "Missing $name" }
    val key = required("SIGNALDOCK_COLLECTION_KEY").also { require(it.isNotBlank()) }
    val database = Database(required("SIGNALDOCK_JDBC_URL"), required("SIGNALDOCK_DB_USER"), required("SIGNALDOCK_DB_PASSWORD"),
        observer = DatabaseObserver { stage, millis ->
            org.slf4j.LoggerFactory.getLogger("ingestion.database").info("stage={} elapsedMs={}", stage, millis)
        })
    try {
        database.migrate()
        embeddedServer(Netty, host = "127.0.0.1", port = System.getenv("PORT")?.toInt() ?: 8080) {
            ingestionModule(database, key)
            monitor.subscribe(ApplicationStopped) { database.close() }
        }.start(wait = true)
    } finally {
        database.close()
    }
}
