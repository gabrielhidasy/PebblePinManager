package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessDisclosureTest {

    @Test
    fun `initial state is hidden and confirming while hidden never opens settings`() {
        val state = NotificationAccessDisclosureState()
        var openSettingsCalls = 0

        assertFalse(state.isVisible)
        assertFalse(state.confirmAndOpenSettings { openSettingsCalls++ })
        assertEquals(0, openSettingsCalls)
    }

    @Test
    fun `requesting settings shows disclosure and affirmative confirm opens settings exactly once`() {
        val state = NotificationAccessDisclosureState()
        var openSettingsCalls = 0

        state.requestSettingsAccess()
        assertTrue(state.isVisible)
        assertEquals(0, openSettingsCalls)

        assertTrue(state.confirmAndOpenSettings { openSettingsCalls++ })
        assertFalse(state.isVisible)
        assertEquals(1, openSettingsCalls)

        // Rapid second tap after dismissal must not trigger another Settings launch
        assertFalse(state.confirmAndOpenSettings { openSettingsCalls++ })
        assertEquals(1, openSettingsCalls)
    }

    @Test
    fun `cancelling disclosure hides modal without opening settings`() {
        val state = NotificationAccessDisclosureState()
        var openSettingsCalls = 0

        state.requestSettingsAccess()
        assertTrue(state.isVisible)

        state.cancel()
        assertFalse(state.isVisible)
        assertFalse(state.confirmAndOpenSettings { openSettingsCalls++ })
        assertEquals(0, openSettingsCalls)
    }

    @Test
    fun `dismissing via back press hides modal without opening settings`() {
        val state = NotificationAccessDisclosureState()
        var openSettingsCalls = 0

        state.requestSettingsAccess()
        assertTrue(state.isVisible)

        state.dismissWithoutConsent()
        assertFalse(state.isVisible)
        assertEquals(0, openSettingsCalls)

        // Re-requesting after cancel/back works cleanly
        state.requestSettingsAccess()
        assertTrue(state.isVisible)
        assertTrue(state.confirmAndOpenSettings { openSettingsCalls++ })
        assertEquals(1, openSettingsCalls)
    }

    @Test
    fun `dialog properties block outside tap dismissal and allow safe back dismissal`() {
        assertFalse(NotificationAccessDisclosureDialogProperties.dismissOnClickOutside)
        assertTrue(NotificationAccessDisclosureDialogProperties.dismissOnBackPress)
    }

    @Test
    fun `saver preserves disclosure visibility across configuration changes`() {
        val scope = SaverScope { true }

        val visibleState = NotificationAccessDisclosureState(initialVisible = true)
        val savedVisible = with(NotificationAccessDisclosureState.Saver) { scope.save(visibleState) }
        val restoredVisible = NotificationAccessDisclosureState.Saver.restore(savedVisible!!)
        assertTrue(restoredVisible!!.isVisible)

        val hiddenState = NotificationAccessDisclosureState(initialVisible = false)
        val savedHidden = with(NotificationAccessDisclosureState.Saver) { scope.save(hiddenState) }
        val restoredHidden = NotificationAccessDisclosureState.Saver.restore(savedHidden!!)
        assertFalse(restoredHidden!!.isVisible)
    }

    @Test
    fun `disclosure copy for standard release covers required Google Play user data disclosures`() {
        val copy = NotificationAccessDisclosureCopy.create(includeDevModeDisclosure = false)
        val allText = (listOf(copy.title, copy.intro, copy.footer) + copy.sections.flatMap { listOf(it.heading, it.body) })
            .joinToString("\n")

        assertEquals("Agree & Open Settings", copy.agreeButtonLabel)
        assertEquals("Cancel", copy.cancelButtonLabel)
        assertEquals(3, copy.sections.size)

        // What data is accessed (+ background disclosure)
        assertTrue(allText.contains("notification text", ignoreCase = true))
        assertTrue(allText.contains("apps you select", ignoreCase = true))
        assertTrue(allText.contains("launchable app names", ignoreCase = true))
        assertTrue(allText.contains("background", ignoreCase = true))
        assertFalse(
            "Standard release build disclosure should not mention compiled-out Developer Mode",
            allText.contains("Developer Mode", ignoreCase = true),
        )

        // How it is used
        assertTrue(allText.contains("100% on-device", ignoreCase = true))
        assertTrue(allText.contains("Pebble Timeline pins", ignoreCase = true))
        assertTrue(allText.contains("PebbleKit 2 IPC", ignoreCase = true))
        assertTrue(allText.contains("Pebble companion app", ignoreCase = true))

        // Zero off-device transmission
        assertTrue(allText.contains("no internet permission", ignoreCase = true))
        assertTrue(allText.contains("no external servers", ignoreCase = true))
        assertTrue(allText.contains("no third-party", ignoreCase = true))
        assertTrue(allText.contains("ads", ignoreCase = true))
    }

    @Test
    fun `disclosure copy for dev builds additionally discloses Developer Mode raw notification capture`() {
        val copy = NotificationAccessDisclosureCopy.create(includeDevModeDisclosure = true)
        val whatDataSection = copy.sections.first { it.heading == "What data is accessed" }

        assertTrue(whatDataSection.body.contains("raw notifications", ignoreCase = true))
        assertTrue(whatDataSection.body.contains("Developer Mode", ignoreCase = true))
        assertTrue(whatDataSection.body.contains("launchable app names", ignoreCase = true))
        assertTrue(whatDataSection.body.contains("background", ignoreCase = true))
    }
}
