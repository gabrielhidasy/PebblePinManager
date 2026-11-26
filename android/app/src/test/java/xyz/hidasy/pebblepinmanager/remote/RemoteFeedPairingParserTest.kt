package xyz.hidasy.pebblepinmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFeedPairingParserTest {

    @Test
    fun `parses canonical companion server HTTPS pebblepin URI`() {
        val raw = "pebblepin://pair?auth=bearer&name=Home%20Pin%20Server&token=read_tok_123456&url=https%3A%2F%2Fpins.example.com%2Fpins.json&v=1"
        val result = RemoteFeedPairingParser.parse(raw)
        assertTrue("Expected Valid, got $result", result is RemoteFeedPairingParseResult.Valid)

        val payload = (result as RemoteFeedPairingParseResult.Valid).payload
        assertEquals("https://pins.example.com/pins.json", payload.url)
        assertEquals("Home Pin Server", payload.name)
        assertEquals(RemoteFeedAuthType.BEARER_TOKEN, payload.authType)
        assertEquals("read_tok_123456", payload.secret?.token)
        assertFalse(payload.isCleartextHttp)
        assertEquals(RemotePinFeedConfig.DEFAULT_POLLING_INTERVAL_MINUTES, payload.pollingIntervalMinutes)
    }

    @Test
    fun `parses HTTP LAN URI and marks isCleartextHttp true`() {
        val raw = "pebblepin://pair?v=1&url=http%3A%2F%2F10.0.0.15%3A8080%2Fpins.json&auth=apikey&header=X-Pin-Key&token=lan_key&interval=15&allowHttp=1"
        val result = RemoteFeedPairingParser.parse(raw)
        assertTrue("Expected Valid, got $result", result is RemoteFeedPairingParseResult.Valid)

        val payload = (result as RemoteFeedPairingParseResult.Valid).payload
        assertEquals("http://10.0.0.15:8080/pins.json", payload.url)
        assertEquals(RemoteFeedAuthType.API_KEY_HEADER, payload.authType)
        assertEquals("X-Pin-Key", payload.apiKeyHeaderName)
        assertEquals("lan_key", payload.secret?.token)
        assertTrue(payload.isCleartextHttp)
        assertEquals(15, payload.pollingIntervalMinutes)
    }

    @Test
    fun `parses JSON pairing payload`() {
        val raw = """
            {
              "schemaVersion": 1,
              "type": "pebble-pin-server-pairing",
              "name": "Office Feed",
              "url": "https://pins.example.org/api/v1/pins.json",
              "authType": "BEARER_TOKEN",
              "token": "json_secret_token",
              "pollingIntervalMinutes": 30
            }
        """.trimIndent()
        val result = RemoteFeedPairingParser.parse(raw)
        assertTrue("Expected Valid, got $result", result is RemoteFeedPairingParseResult.Valid)

        val payload = (result as RemoteFeedPairingParseResult.Valid).payload
        assertEquals("https://pins.example.org/api/v1/pins.json", payload.url)
        assertEquals("Office Feed", payload.name)
        assertEquals(RemoteFeedAuthType.BEARER_TOKEN, payload.authType)
        assertEquals("json_secret_token", payload.secret?.token)
        assertEquals(30, payload.pollingIntervalMinutes)
    }

    @Test
    fun `rejects invalid, unrelated, unsupported version, or missing credential payloads without leaking secrets`() {
        val cases = listOf(
            "",
            "https://example.com/not-a-pairing-qr",
            "pebblepin://other?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json",
            "pebblepin://pair?v=2&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=tok",
            "pebblepin://pair?url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=tok",
            "pebblepin://pair?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer",
            "pebblepin://pair?v=1&url=ftp%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=tok",
            "pebblepin://pair?v=1&url=https%3A%2F%2Fuser%3Apass%40pins.example.com%2Fpins.json&auth=bearer&token=tok",
            "pebblepin://pair?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=unknown&token=secret_value_xyz",
            """{"schemaVersion":99,"url":"https://pins.example.com/pins.json","token":"secret_value_xyz"}""",
        )

        for (raw in cases) {
            val result = RemoteFeedPairingParser.parse(raw)
            assertTrue("Expected Invalid for '$raw', got $result", result is RemoteFeedPairingParseResult.Invalid)
            val message = (result as RemoteFeedPairingParseResult.Invalid).message
            assertNotNull(message)
            assertFalse("Error message must never leak secret value", message.contains("secret_value_xyz"))
        }
    }

    @Test
    fun `parses no-auth feed cleanly`() {
        val raw = "pebblepin://pair?v=1&url=https%3A%2F%2Fpublic.example.com%2Fpins.json&auth=none"
        val result = RemoteFeedPairingParser.parse(raw) as RemoteFeedPairingParseResult.Valid
        assertEquals(RemoteFeedAuthType.NONE, result.payload.authType)
        assertNull(result.payload.secret)
    }
}
