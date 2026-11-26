package xyz.hidasy.pebblepinmanager.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64

class HttpUrlConnectionRemoteFeedClientTest {

    private lateinit var server: HttpServer
    private lateinit var secondaryServer: HttpServer
    private lateinit var baseUrl: String
    private lateinit var secondaryBaseUrl: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"

        secondaryServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        secondaryServer.start()
        secondaryBaseUrl = "http://127.0.0.1:${secondaryServer.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        secondaryServer.stop(0)
    }

    @Test
    fun `sends bearer token and handles ETag 304 Not Modified`() = runTest {
        var receivedAuth: String? = null
        var receivedIfNoneMatch: String? = null
        server.createContext("/pins.json") { exchange ->
            receivedAuth = exchange.requestHeaders.getFirst("Authorization")
            receivedIfNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (receivedAuth != "Bearer my-secret-token") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            if (receivedIfNoneMatch == "\"v1\"") {
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
                return@createContext
            }
            val body = """{"schemaVersion":1,"pins":[]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.responseHeaders.add("Last-Modified", "Wed, 25 Nov 2026 22:00:00 GMT")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client = HttpUrlConnectionRemoteFeedClient()
        val first = client.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/pins.json",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.BEARER_TOKEN,
                secret = RemoteFeedSecret(token = "my-secret-token"),
            ),
        )
        assertTrue(first is RemoteFeedFetchResult.Success)
        val success = first as RemoteFeedFetchResult.Success
        assertEquals("\"v1\"", success.etag)
        assertEquals("Wed, 25 Nov 2026 22:00:00 GMT", success.lastModified)
        assertEquals("Bearer my-secret-token", receivedAuth)

        val second = client.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/pins.json",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.BEARER_TOKEN,
                secret = RemoteFeedSecret(token = "my-secret-token"),
                ifNoneMatch = success.etag,
                ifModifiedSince = success.lastModified,
            ),
        )
        assertTrue(second is RemoteFeedFetchResult.NotModified)
        assertEquals("\"v1\"", receivedIfNoneMatch)
    }

    @Test
    fun `supports custom api key header and HTTP Basic auth`() = runTest {
        var apiKeySeen: String? = null
        server.createContext("/apikey") { exchange ->
            apiKeySeen = exchange.requestHeaders.getFirst("X-Custom-Key")
            val body = """{"schemaVersion":1,"pins":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        var basicSeen: String? = null
        server.createContext("/basic") { exchange ->
            basicSeen = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"schemaVersion":1,"pins":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client = HttpUrlConnectionRemoteFeedClient()
        val apiRes = client.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/apikey",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.API_KEY_HEADER,
                apiKeyHeaderName = "X-Custom-Key",
                secret = RemoteFeedSecret(token = "k_12345"),
            ),
        )
        assertTrue(apiRes is RemoteFeedFetchResult.Success)
        assertEquals("k_12345", apiKeySeen)

        val basicRes = client.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/basic",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.BASIC,
                basicUsername = "alice",
                secret = RemoteFeedSecret(basicPassword = "s3cr3t"),
            ),
        )
        assertTrue(basicRes is RemoteFeedFetchResult.Success)
        val expectedBasic = "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t".toByteArray())
        assertEquals(expectedBasic, basicSeen)
    }

    @Test
    fun `returns Unauthorized on 401 and 403 and strips auth header on cross-origin redirect`() = runTest {
        server.createContext("/forbidden") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }

        var secondaryAuthHeader: String? = "NOT_CALLED"
        secondaryServer.createContext("/target") { exchange ->
            secondaryAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"schemaVersion":1,"pins":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$secondaryBaseUrl/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        val client = HttpUrlConnectionRemoteFeedClient()
        val forbidden = client.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/forbidden",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.BEARER_TOKEN,
                secret = RemoteFeedSecret(token = "bad-token"),
            ),
        )
        assertTrue(forbidden is RemoteFeedFetchResult.Unauthorized)
        assertEquals(403, (forbidden as RemoteFeedFetchResult.Unauthorized).httpStatus)

        val redirected = client.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/redirect",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.BEARER_TOKEN,
                secret = RemoteFeedSecret(token = "secret-must-not-leak"),
            ),
        )
        assertTrue(redirected is RemoteFeedFetchResult.Success)
        assertNull("Authorization header must be stripped on cross-origin redirect", secondaryAuthHeader)
    }

    @Test
    fun `rejects cleartext http by default and enforces max response size`() = runTest {
        server.createContext("/big") { exchange ->
            val body = ByteArray(2048) { 'a'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val defaultClient = HttpUrlConnectionRemoteFeedClient()
        val blockedHttp = defaultClient.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/big",
                allowCleartextHttp = false,
                authType = RemoteFeedAuthType.NONE,
            ),
        )
        assertTrue(blockedHttp is RemoteFeedFetchResult.InvalidRequest)

        val tinyCapClient = HttpUrlConnectionRemoteFeedClient(maxResponseBytes = 512)
        val tooBig = tinyCapClient.fetch(
            RemoteFeedHttpRequest(
                url = "$baseUrl/big",
                allowCleartextHttp = true,
                authType = RemoteFeedAuthType.NONE,
            ),
        )
        assertTrue(tooBig is RemoteFeedFetchResult.HttpError)
    }
}
