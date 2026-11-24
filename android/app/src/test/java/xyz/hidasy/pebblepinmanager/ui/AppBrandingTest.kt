package xyz.hidasy.pebblepinmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import xyz.hidasy.pebblepinmanager.BuildConfig
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppBrandingTest {

    @Test
    fun `display name complies with Google Play length and non-impersonation policies`() {
        assertEquals("Pin Manager for Pebble", AppBranding.APP_DISPLAY_NAME)
        assertTrue(
            "App title must fit within Google Play's 30-character limit",
            AppBranding.APP_DISPLAY_NAME.length <= 30,
        )
        assertFalse(
            "App title must not lead with the Pebble trademark",
            AppBranding.APP_DISPLAY_NAME.startsWith("Pebble", ignoreCase = true),
        )
    }

    @Test
    fun `non-affiliation disclaimer explicitly disavows Core Devices and Pebble affiliation`() {
        val disclaimer = AppBranding.NON_AFFILIATION_DISCLAIMER
        assertTrue(disclaimer.contains("Independent", ignoreCase = true))
        assertTrue(disclaimer.contains("open-source", ignoreCase = true))
        assertTrue(disclaimer.contains("not affiliated", ignoreCase = true))
        assertTrue(disclaimer.contains("Core Devices"))
        assertTrue(disclaimer.contains("Pebble"))
    }

    @Test
    fun `applicationId stays unchanged as xyz hidasy pebblepinmanager`() {
        val baseApplicationId = BuildConfig.APPLICATION_ID.removeSuffix(".debug")
        assertEquals("xyz.hidasy.pebblepinmanager", baseApplicationId)
    }

    @Test
    fun `strings xml uses Pin Manager for Pebble for all user-facing app name strings`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("Expected strings.xml at ${stringsFile.absolutePath}", stringsFile.isFile)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val strings = buildMap {
            val nodes = doc.getElementsByTagName("string")
            for (idx in 0 until nodes.length) {
                val el = nodes.item(idx) as Element
                put(el.getAttribute("name"), el.textContent.trim())
            }
        }

        assertEquals(AppBranding.APP_DISPLAY_NAME, strings["app_name"])
        assertEquals("Open ${AppBranding.APP_DISPLAY_NAME}", strings["watch_handoff_notification_title"])
        assertEquals("${AppBranding.APP_DISPLAY_NAME} Notification Sync", strings["notification_listener_label"])

        strings.forEach { (name, value) ->
            assertFalse(
                "String resource '$name' must not expose old PebblePinManager branding: $value",
                value.contains("PebblePinManager", ignoreCase = true),
            )
        }
    }

    @Test
    fun `in-app policy and disclosure copy use updated display name`() {
        val privacyText = PrivacyPolicyInfo.summaryHighlights.joinToString("\n") { "${it.title}: ${it.detail}" }
        assertTrue(privacyText.contains(AppBranding.APP_DISPLAY_NAME))
        assertFalse(privacyText.contains("PebblePinManager"))

        val disclosure = NotificationAccessDisclosureCopy.create(includeDevModeDisclosure = false)
        assertTrue(disclosure.intro.contains(AppBranding.APP_DISPLAY_NAME))
        assertFalse(disclosure.intro.contains("PebblePinManager"))
    }
}
