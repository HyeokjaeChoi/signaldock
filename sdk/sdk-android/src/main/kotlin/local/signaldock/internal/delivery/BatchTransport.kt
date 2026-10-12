package local.signaldock.internal.delivery

import local.signaldock.DeliveryErrorKind
import local.signaldock.SignalDockConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

internal class BatchHttpRequest(val contractVersion: String, val body: ByteArray)

/** Final result of one Call. OkHttp's own connection recovery and follow-ups already happened inside. */
internal sealed interface TransportResult {
    /**
     * An HTTP response. [body] is read only for status 200 (null if it is too large or unreadable);
     * [retryAfter] is the raw Retry-After header value.
     */
    class Http(val status: Int, val body: String?, val retryAfter: String?) : TransportResult

    /** No response: [kind] is [DeliveryErrorKind.NETWORK] or [DeliveryErrorKind.TIMEOUT]. */
    class Failed(val kind: DeliveryErrorKind) : TransportResult
}

/** HTTP boundary of the sender (Q88). Retrofit implementation below; fakes in tests. */
internal interface BatchTransport {
    suspend fun send(request: BatchHttpRequest): TransportResult
}

internal interface IngestApi {
    @Streaming
    @POST("v1/events:batch")
    suspend fun ingest(
        @Header("Authorization") authorization: String,
        @Header("SignalDock-Contract-Version") contractVersion: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}

internal const val MAX_ACK_BYTES = 1L shl 20

internal class RetrofitBatchTransport(config: SignalDockConfig) : BatchTransport {
    private val authorization = "Bearer ${config.collectionKey}"
    private val json = "application/json".toMediaType()

    /** Internal so tests can check the timeout and redirect settings (Q119 to Q123). */
    internal val client: OkHttpClient = OkHttpClient.Builder()
        // Redirects are not followed, same-origin ones included (Q123).
        .followRedirects(false)
        .followSslRedirects(false)
        // Connection recovery inside one Call is allowed (Q122).
        .retryOnConnectionFailure(true)
        .callTimeout(config.callTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .writeTimeout(config.writeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    private val api: IngestApi = Retrofit.Builder()
        .baseUrl(config.endpoint.trimEnd('/') + "/")
        .client(client)
        .build()
        .create(IngestApi::class.java)

    override suspend fun send(request: BatchHttpRequest): TransportResult {
        try {
            // ByteArray.toRequestBody keeps the media type exactly "application/json".
            val response = api.ingest(authorization, request.contractVersion, request.body.toRequestBody(json))
            val retryAfter = response.headers()["Retry-After"]
            val responseBody = response.body() ?: response.errorBody()
            responseBody.use { rb ->
                val text = if (response.code() == 200 && rb != null) readCapped(rb) else null
                return TransportResult.Http(response.code(), text, retryAfter)
            }
        } catch (e: InterruptedIOException) {
            // SocketTimeoutException and the Call timeout.
            return TransportResult.Failed(DeliveryErrorKind.TIMEOUT)
        } catch (e: IOException) {
            return TransportResult.Failed(DeliveryErrorKind.NETWORK)
        }
    }

    private fun readCapped(body: ResponseBody): String? {
        val source = body.source()
        source.request(MAX_ACK_BYTES + 1)
        return if (source.buffer.size > MAX_ACK_BYTES) null else source.buffer.readUtf8()
    }
}
