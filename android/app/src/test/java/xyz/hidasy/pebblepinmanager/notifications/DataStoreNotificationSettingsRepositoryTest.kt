package xyz.hidasy.pebblepinmanager.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import java.io.File

class DataStoreNotificationSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `returns opt-in empty allowlist defaults and persists rule updates cleanly`() = runTest {
        val file = File(tempFolder.newFolder(), "notif_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreNotificationSettingsRepository(dataStore = dataStore)

        val initial = repo.getSettings()
        assertTrue(initial.isAutoPinEnabled)
        assertTrue(initial.allowedPackages.isEmpty())
        assertTrue(initial.extractTimeFromText)
        assertTrue(initial.ignoreOngoing)
        assertTrue(initial.ignoreGroupSummary)
        assertFalse(initial.deletePinOnDismiss)

        repo.updateSettings { current ->
            current.copy(
                allowedPackages = setOf(" com.example.flights ", "com.example.delivery", ""),
                keywordFilter = " flight, delivery ",
                titleRegexPattern = " Flight\\s+([A-Z0-9]+) ",
                defaultIconUri = PinIcon.SCHEDULED_FLIGHT.uri,
                defaultBackgroundColor = PinAccentColor.TEAL.hexCode,
                deletePinOnDismiss = true,
            )
        }

        val persisted = repo.observeSettings().first()
        assertEquals(setOf("com.example.flights", "com.example.delivery"), persisted.allowedPackages)
        assertEquals("flight, delivery", persisted.keywordFilter)
        assertEquals(listOf("flight", "delivery"), persisted.parsedKeywords())
        assertEquals("""Flight\s+([A-Z0-9]+)""", persisted.titleRegexPattern)
        assertEquals(PinIcon.SCHEDULED_FLIGHT.uri, persisted.defaultIconUri)
        assertEquals(PinAccentColor.TEAL.hexCode, persisted.defaultBackgroundColor)
        assertTrue(persisted.deletePinOnDismiss)
    }
}
