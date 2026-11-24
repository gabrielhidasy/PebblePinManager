package xyz.hidasy.pebblepinmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class PrivacyPolicyInfoTest {

    @Test
    fun `privacy policy url points to live hosted static policy over https`() {
        val url = PrivacyPolicyInfo.PRIVACY_POLICY_URL
        assertEquals("https://pebblepinmanager.hidasy.xyz/privacy", url)
        assertTrue("Expected https URL, got: $url", url.startsWith("https://"))
        assertFalse("URL must not contain whitespace", url.any { it.isWhitespace() })
        assertFalse("URL must not reference internal host", url.contains("gitea.", ignoreCase = true))

        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertFalse("Host must not be blank", uri.host.isNullOrBlank())
        assertFalse("Path must not be blank", uri.path.isNullOrBlank())
    }

    @Test
    fun `developer identity and privacy contact match Google Play listing`() {
        assertEquals("Gabriel Hidasy", PrivacyPolicyInfo.DEVELOPER_NAME)
        assertEquals("gabrielhidasy+play@gmail.com", PrivacyPolicyInfo.CONTACT_EMAIL)
    }

    @Test
    fun `offline summary covers local processing and installed apps allowlist disclosure`() {
        val combinedText = PrivacyPolicyInfo.summaryHighlights.joinToString("\n") {
            "${it.title}: ${it.detail}"
        }
        assertTrue(
            "Summary should mention zero INTERNET permission",
            combinedText.contains("INTERNET"),
        )
        assertTrue(
            "Summary should disclose local-only installed apps query",
            combinedText.contains("ACTION_MAIN") && combinedText.contains("CATEGORY_LAUNCHER"),
        )
        assertTrue(
            "Summary should clarify installed apps list never leaves device",
            combinedText.contains("never transmits your app list off-device", ignoreCase = true),
        )
        assertTrue(
            "Summary should mention data deletion controls",
            combinedText.contains("Clear storage", ignoreCase = true),
        )
    }
}
