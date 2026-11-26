package xyz.hidasy.pebblepinmanager.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLException

/**
 * Outgoing HTTP request specification for fetching a remote JSON pin feed.
 */
data class RemoteFeedHttpRequest(
    val url: String,
    val allowCleartextHttp: Boolean = false,
    val authType: RemoteFeedAuthType = RemoteFeedAuthType.BEARER_TOKEN,
    val apiKeyHeaderName: String = RemotePinFeedConfig.DEFAULT_API_KEY_HEADER,
    val basicUsername: String = "",
    val secret: RemoteFeedSecret? = null,
    val ifNoneMatch: String? = null,
    val ifModifiedSince: String? = null,
)

/**
 * Outcome of fetching a remote JSON pin feed over HTTP(S).
 */
sealed interface RemoteFeedFetchResult {
    data class Success(
        val body: String,
        val etag: String? = null,
        val lastModified: String? = null,
        val httpStatus: Int = 200,
    ) : RemoteFeedFetchResult

    data class NotModified(
        val etag: String? = null,
        val lastModified: String? = null,
    ) : RemoteFeedFetchResult

    data class Unauthorized(
        val httpStatus: Int,
        val message: String,
    ) : RemoteFeedFetchResult

    data class HttpError(
        val httpStatus: Int,
        val message: String,
    ) : RemoteFeedFetchResult

    data class InvalidRequest(
        val message: String,
    ) : RemoteFeedFetchResult

    data class NetworkError(
        val message: String,
    ) : RemoteFeedFetchResult
}

/**
 * Transport contract for fetching remote JSON pin feeds.
 */
fun interface RemoteFeedHttpClient {
    suspend fun fetch(request: RemoteFeedHttpRequest): RemoteFeedFetchResult
}

/**
 * Pure JDK [HttpURLConnection] implementation of [RemoteFeedHttpClient] with:
 * - HTTPS-by-default enforcement (opt-in HTTP only when `allowCleartextHttp = true`)
 * - Bearer token, custom API key header, and HTTP Basic authentication
 * - Conditional caching (`If-None-Match` / `If-Modified-Since` -> `304 Not Modified`)
 * - Safe manual redirect handling (blocks HTTPS->HTTP downgrade; strips auth headers across origins)
 * - Bounded response size (default 2 MiB) to protect memory
 * - Zero credential leakage in error messages
 */
class HttpUrlConnectionRemoteFeedClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : RemoteFeedHttpClient {

    override suspend fun fetch(request: RemoteFeedHttpRequest): RemoteFeedFetchResult =
        withContext(ioDispatcher) {
            val initialUrlCheck = RemoteFeedUrlValidator.validate(
                rawUrl = request.url,
                allowCleartextHttp = request.allowCleartextHttp,
            )
            val initialUri = when (initialUrlCheck) {
                is UrlValidationResult.Valid -> initialUrlCheck.uri
                is UrlValidationResult.Invalid -> {
                    return@withContext RemoteFeedFetchResult.InvalidRequest(initialUrlCheck.message)
                }
            }

            val authHeader = when (val built = buildAuthHeader(request)) {
                is AuthHeaderResult.Header -> built
                is AuthHeaderResult.Invalid -> {
                    return@withContext RemoteFeedFetchResult.InvalidRequest(built.message)
                }
            }

            executeWithRedirects(
                initialUri = initialUri,
                request = request,
                authHeader = authHeader,
            )
        }

    private fun executeWithRedirects(
        initialUri: URI,
        request: RemoteFeedHttpRequest,
        authHeader: AuthHeaderResult.Header,
    ): RemoteFeedFetchResult {
        var currentUri = initialUri
        var sendAuthHeader = true

        for (hop in 0..maxRedirects) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(currentUri.toASCIIString()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", userAgent)

                    if (sendAuthHeader && authHeader.name != null && authHeader.value != null) {
                        setRequestProperty(authHeader.name, authHeader.value)
                    }
                    if (!request.ifNoneMatch.isNullOrBlank()) {
                        setRequestProperty("If-None-Match", request.ifNoneMatch.trim())
                    }
                    if (!request.ifModifiedSince.isNullOrBlank()) {
                        setRequestProperty("If-Modified-Since", request.ifModifiedSince.trim())
                    }
                }

                val statusCode = connection.responseCode
                val etag = connection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                val lastModified = connection.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }

                if (statusCode in REDIRECT_STATUS_CODES) {
                    val location = connection.getHeaderField("Location")?.trim()
                    if (location.isNullOrEmpty()) {
                        return RemoteFeedFetchResult.HttpError(
                            httpStatus = statusCode,
                            message = "Server returned redirect ($statusCode) without a Location header.",
                        )
                    }
                    if (hop == maxRedirects) {
                        return RemoteFeedFetchResult.HttpError(
                            httpStatus = statusCode,
                            message = "Too many redirects (exceeded $maxRedirects).",
                        )
                    }
                    val resolvedUri = runCatching { currentUri.resolve(location) }.getOrNull()
                        ?: return RemoteFeedFetchResult.HttpError(
                            httpStatus = statusCode,
                            message = "Server returned an invalid redirect Location.",
                        )
                    val redirectValidation = RemoteFeedUrlValidator.validate(
                        rawUrl = resolvedUri.toASCIIString(),
                        allowCleartextHttp = request.allowCleartextHttp,
                    )
                    if (redirectValidation is UrlValidationResult.Invalid) {
                        return RemoteFeedFetchResult.InvalidRequest(
                            "Blocked unsafe redirect: ${redirectValidation.message}",
                        )
                    }
                    if (currentUri.scheme.equals("https", ignoreCase = true) &&
                        resolvedUri.scheme.equals("http", ignoreCase = true) &&
                        !request.allowCleartextHttp
                    ) {
                        return RemoteFeedFetchResult.InvalidRequest(
                            "Blocked insecure redirect from HTTPS to HTTP.",
                        )
                    }
                    // Strip auth headers if redirected to a different origin (scheme + host + port).
                    if (!isSameOrigin(initialUri, resolvedUri)) {
                        sendAuthHeader = false
                    }
                    currentUri = resolvedUri
                    continue
                }

                return when (statusCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> RemoteFeedFetchResult.NotModified(
                        etag = etag,
                        lastModified = lastModified,
                    )

                    in 200..299 -> {
                        val contentLength = connection.contentLengthLong
                        if (contentLength > maxResponseBytes) {
                            return RemoteFeedFetchResult.HttpError(
                                httpStatus = statusCode,
                                message = "Feed response exceeds the ${maxResponseBytes / (1024 * 1024)} MiB safety limit.",
                            )
                        }
                        val bodyBytes = readBounded(connection.inputStream, maxResponseBytes)
                            ?: return RemoteFeedFetchResult.HttpError(
                                httpStatus = statusCode,
                                message = "Feed response exceeds the ${maxResponseBytes / (1024 * 1024)} MiB safety limit.",
                            )
                        RemoteFeedFetchResult.Success(
                            body = bodyBytes.toString(StandardCharsets.UTF_8),
                            etag = etag,
                            lastModified = lastModified,
                            httpStatus = statusCode,
                        )
                    }

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> RemoteFeedFetchResult.Unauthorized(
                        httpStatus = statusCode,
                        message = if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                            "Authentication failed (HTTP 401 Unauthorized). Check your token or credentials."
                        } else {
                            "Access denied (HTTP 403 Forbidden). Verify your credentials have read access."
                        },
                    )

                    else -> RemoteFeedFetchResult.HttpError(
                        httpStatus = statusCode,
                        message = "Server returned HTTP $statusCode${connection.responseMessage?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}.",
                    )
                }
            } catch (_: UnknownHostException) {
                return RemoteFeedFetchResult.NetworkError(
                    "Unable to resolve host '${currentUri.host}'. Check the URL and network connection.",
                )
            } catch (_: SocketTimeoutException) {
                return RemoteFeedFetchResult.NetworkError(
                    "Connection timed out while contacting '${currentUri.host}'.",
                )
            } catch (ssl: SSLException) {
                return RemoteFeedFetchResult.NetworkError(
                    "TLS/HTTPS error: ${ssl.message ?: "certificate verification failed"}.",
                )
            } catch (io: IOException) {
                return RemoteFeedFetchResult.NetworkError(
                    "Network error: ${io.message ?: "unable to reach server"}.",
                )
            } finally {
                connection?.disconnect()
            }
        }

        return RemoteFeedFetchResult.HttpError(
            httpStatus = 310,
            message = "Too many redirects.",
        )
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray? {
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun isSameOrigin(a: URI, b: URI): Boolean {
        val schemeA = a.scheme?.lowercase()
        val schemeB = b.scheme?.lowercase()
        if (schemeA != schemeB) return false
        val hostA = a.host?.lowercase()
        val hostB = b.host?.lowercase()
        if (hostA != hostB) return false
        val portA = effectivePort(a)
        val portB = effectivePort(b)
        return portA == portB
    }

    private fun effectivePort(uri: URI): Int {
        if (uri.port != -1) return uri.port
        return if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    }

    internal sealed interface AuthHeaderResult {
        data class Header(val name: String?, val value: String?) : AuthHeaderResult
        data class Invalid(val message: String) : AuthHeaderResult
    }

    internal fun buildAuthHeader(request: RemoteFeedHttpRequest): AuthHeaderResult {
        return when (request.authType) {
            RemoteFeedAuthType.NONE -> AuthHeaderResult.Header(null, null)

            RemoteFeedAuthType.BEARER_TOKEN -> {
                val token = request.secret?.token?.trim().orEmpty()
                if (token.isEmpty()) {
                    return AuthHeaderResult.Invalid("Bearer token is missing. Edit the feed to set a token.")
                }
                if (token.any { it == '\r' || it == '\n' }) {
                    return AuthHeaderResult.Invalid("Bearer token must not contain line breaks.")
                }
                AuthHeaderResult.Header("Authorization", "Bearer $token")
            }

            RemoteFeedAuthType.API_KEY_HEADER -> {
                val headerName = request.apiKeyHeaderName.trim()
                val headerErr = RemoteFeedUrlValidator.validateHeaderName(headerName)
                if (headerErr != null) {
                    return AuthHeaderResult.Invalid(headerErr)
                }
                val apiKey = request.secret?.token?.trim().orEmpty()
                if (apiKey.isEmpty()) {
                    return AuthHeaderResult.Invalid("API key is missing. Edit the feed to set a key.")
                }
                if (apiKey.any { it == '\r' || it == '\n' }) {
                    return AuthHeaderResult.Invalid("API key must not contain line breaks.")
                }
                AuthHeaderResult.Header(headerName, apiKey)
            }

            RemoteFeedAuthType.BASIC -> {
                val username = request.basicUsername.trim()
                val password = request.secret?.basicPassword.orEmpty()
                if (username.isEmpty() || password.isEmpty()) {
                    return AuthHeaderResult.Invalid(
                        "HTTP Basic username and password are required. Edit the feed to configure credentials.",
                    )
                }
                if (username.contains(':') || username.any { it == '\r' || it == '\n' } ||
                    password.any { it == '\r' || it == '\n' }
                ) {
                    return AuthHeaderResult.Invalid("HTTP Basic credentials contain invalid characters.")
                }
                val encoded = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                AuthHeaderResult.Header("Authorization", "Basic $encoded")
            }
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 15_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 20_000
        const val DEFAULT_MAX_RESPONSE_BYTES: Int = 2 * 1024 * 1024
        const val DEFAULT_MAX_REDIRECTS: Int = 5
        const val DEFAULT_USER_AGENT: String = "PinManagerForPebble/1.0"

        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
