package xyz.hidasy.pebblepinmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFeedUrlValidatorTest {

    @Test
    fun `accepts valid https urls by default`() {
        val result = RemoteFeedUrlValidator.validate("https://pins.example.com/api/v1/pins.json")
        assertTrue(result is UrlValidationResult.Valid)
        assertEquals(
            "https://pins.example.com/api/v1/pins.json",
            (result as UrlValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `rejects http urls unless allowCleartextHttp is enabled`() {
        val denied = RemoteFeedUrlValidator.validate(
            rawUrl = "http://pins.local:8080/pins.json",
            allowCleartextHttp = false,
        )
        assertTrue(denied is UrlValidationResult.Invalid)
        assertTrue((denied as UrlValidationResult.Invalid).message.contains("HTTPS is required"))

        val allowed = RemoteFeedUrlValidator.validate(
            rawUrl = "http://pins.local:8080/pins.json",
            allowCleartextHttp = true,
        )
        assertTrue(allowed is UrlValidationResult.Valid)
    }

    @Test
    fun `rejects embedded credentials and unsupported schemes`() {
        val embedded = RemoteFeedUrlValidator.validate("https://user:secret@example.com/pins.json")
        assertTrue(embedded is UrlValidationResult.Invalid)
        assertTrue((embedded as UrlValidationResult.Invalid).message.contains("Do not embed credentials"))

        val ftp = RemoteFeedUrlValidator.validate("ftp://example.com/pins.json", allowCleartextHttp = true)
        assertTrue(ftp is UrlValidationResult.Invalid)

        val spaces = RemoteFeedUrlValidator.validate("https://example.com/my pins.json")
        assertTrue(spaces is UrlValidationResult.Invalid)
    }

    @Test
    fun `validates custom api key header names and derives feed names`() {
        assertNull(RemoteFeedUrlValidator.validateHeaderName("X-API-Key"))
        assertNotNull(RemoteFeedUrlValidator.validateHeaderName(""))
        assertNotNull(RemoteFeedUrlValidator.validateHeaderName("Bad Header:Name"))

        assertEquals(
            "pins.example.com/pins.json",
            RemoteFeedUrlValidator.deriveDefaultFeedName("https://pins.example.com/api/v1/pins.json"),
        )
    }
}
