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

    @Test
    fun `persists and normalizes per-app configs and N global regex rules while decoding legacy JSON`() = runTest {
        val file = File(tempFolder.newFolder(), "notif_rules_v2.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreNotificationSettingsRepository(dataStore = dataStore)

        repo.updateSettings { current ->
            current.copy(
                allowedPackages = setOf("com.google.android.gm"),
                excludeKeywordFilter = " promo , newsletter ",
                requireGlobalRegexRuleMatch = true,
                globalRegexRules = listOf(
                    NotificationRegexRule(
                        id = " global-1 ",
                        name = " Flight Alert ",
                        matchPattern = " Flight\\s+[A-Z0-9]+ ",
                        titleRegexPattern = " Flight\\s+([A-Z0-9]+) ",
                    ),
                ),
                appRules = mapOf(
                    " com.google.android.gm " to AppNotificationRuleConfig(
                        packageName = "com.google.android.gm",
                        keywordFilter = " calendar , flight ",
                        excludeKeywordFilter = " unsubscribe ",
                        requireRegexRuleMatch = true,
                        regexRules = listOf(
                            NotificationRegexRule(
                                id = "gm-1",
                                name = " Ignore Digests ",
                                action = NotificationRuleAction.IGNORE,
                                matchPattern = " digest ",
                            ),
                            NotificationRegexRule(
                                id = "gm-2",
                                name = " Subject as Title ",
                                titleSource = NotificationTextField.BODY,
                                descriptionSource = NotificationTextField.BODY,
                            ),
                        ),
                        defaultIconUri = PinIcon.GENERIC_EMAIL.uri,
                    ),
                ),
            )
        }

        val saved = repo.observeSettings().first()
        assertEquals("promo , newsletter", saved.excludeKeywordFilter)
        assertEquals(listOf("promo", "newsletter"), saved.parsedExcludeKeywords())
        assertTrue(saved.requireGlobalRegexRuleMatch)
        assertEquals(1, saved.globalRegexRules.size)
        assertEquals("global-1", saved.globalRegexRules[0].id)
        assertEquals("Flight Alert", saved.globalRegexRules[0].name)

        val gmailConfig = saved.appRuleFor("com.google.android.gm")
        assertTrue(gmailConfig != null)
        assertEquals(listOf("calendar", "flight"), gmailConfig!!.parsedKeywords())
        assertEquals(listOf("unsubscribe"), gmailConfig.parsedExcludeKeywords())
        assertTrue(gmailConfig.requireRegexRuleMatch)
        assertEquals(2, gmailConfig.regexRules.size)
        assertEquals(NotificationRuleAction.IGNORE, gmailConfig.regexRules[0].action)
        assertEquals(NotificationTextField.BODY, gmailConfig.regexRules[1].titleSource)
        assertEquals(1, saved.configuredAppRuleCount())
    }
}
