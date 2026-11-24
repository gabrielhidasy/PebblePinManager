package xyz.hidasy.pebblepinmanager.ui

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme

class NotificationAccessDisclosureUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun notificationSettingsScreen_requiresProminentDisclosureBeforeOpeningSettings() {
        var openSettingsCount = 0

        composeRule.setContent {
            PebblePinManagerTheme {
                NotificationSettingsScreen(
                    uiState = NotificationSettingsUiState(
                        isNotificationAccessEnabled = false,
                        isDevModeAvailable = false,
                    ),
                    onOpenNotificationAccessSettings = { openSettingsCount++ },
                    onRefreshStatus = {},
                    onAutoPinEnabledChange = {},
                    onExtractTimeFromTextChange = {},
                    onIgnoreOngoingChange = {},
                    onIgnoreGroupSummaryChange = {},
                    onDeletePinOnDismissChange = {},
                    onTogglePackageAllowed = {},
                    onClearAllowedPackages = {},
                    onAppSearchQueryChange = {},
                    onShowSelectedAppsOnlyChange = {},
                    onManualPackageInputChange = {},
                    onAddManualPackage = {},
                    onKeywordFilterInputChange = {},
                    onTitleRegexInputChange = {},
                    onDescriptionRegexInputChange = {},
                    onTimeRegexInputChange = {},
                    onSaveParsingRules = {},
                    onDefaultIconSelected = {},
                    onDefaultBackgroundColorSelected = {},
                )
            }
        }

        // Disclosure modal is not visible until requested
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertFalse(NotificationAccessDisclosureDialogProperties.dismissOnClickOutside)

        // Tapping Open Notification Access Settings opens disclosure modal without launching Settings
        composeRule.onNodeWithText("Open Notification Access Settings").performClick()
        assertEquals(0, openSettingsCount)
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(NotificationAccessDisclosureCopy.TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("What data is accessed").assertIsDisplayed()
        composeRule.onNodeWithText("How it is used").assertIsDisplayed()
        composeRule.onNodeWithText("Zero off-device transmission").assertIsDisplayed()
        composeRule.onNodeWithText("Developer Mode", substring = true).assertDoesNotExist()

        // Tapping Cancel dismisses modal without navigating to Settings
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.CANCEL_BUTTON).performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertEquals(0, openSettingsCount)

        // Re-open and press Back -> dismisses modal without navigating to Settings
        composeRule.onNodeWithText("Open Notification Access Settings").performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertEquals(0, openSettingsCount)

        // Re-open and affirmatively tap Agree & Open Settings -> navigates to Settings once
        composeRule.onNodeWithText("Open Notification Access Settings").performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.AGREE_BUTTON).performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertEquals(1, openSettingsCount)
    }

    @Test
    fun notificationSettingsScreen_manageAccessButtonAlsoRequiresDisclosure() {
        var openSettingsCount = 0

        composeRule.setContent {
            PebblePinManagerTheme {
                NotificationSettingsScreen(
                    uiState = NotificationSettingsUiState(
                        isNotificationAccessEnabled = true,
                        isDevModeAvailable = true,
                    ),
                    onOpenNotificationAccessSettings = { openSettingsCount++ },
                    onRefreshStatus = {},
                    onAutoPinEnabledChange = {},
                    onExtractTimeFromTextChange = {},
                    onIgnoreOngoingChange = {},
                    onIgnoreGroupSummaryChange = {},
                    onDeletePinOnDismissChange = {},
                    onTogglePackageAllowed = {},
                    onClearAllowedPackages = {},
                    onAppSearchQueryChange = {},
                    onShowSelectedAppsOnlyChange = {},
                    onManualPackageInputChange = {},
                    onAddManualPackage = {},
                    onKeywordFilterInputChange = {},
                    onTitleRegexInputChange = {},
                    onDescriptionRegexInputChange = {},
                    onTimeRegexInputChange = {},
                    onSaveParsingRules = {},
                    onDefaultIconSelected = {},
                    onDefaultBackgroundColorSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Manage Android Notification Access").performClick()
        assertEquals(0, openSettingsCount)
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Developer Mode", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.AGREE_BUTTON).performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertEquals(1, openSettingsCount)
    }

    @Test
    fun devModeScreen_requiresProminentDisclosureAndAffirmativeConsentBeforeOpeningSettings() {
        var openSettingsCount = 0

        composeRule.setContent {
            PebblePinManagerTheme {
                DevModeScreen(
                    uiState = DevModeUiState(isNotificationAccessEnabled = false),
                    onOpenNotificationAccessSettings = { openSettingsCount++ },
                    onSnapshotActiveNotifications = {},
                    onSearchQueryChange = {},
                    onHideOngoingChange = {},
                    onHideGroupSummaryChange = {},
                    onAllowlistedOnlyChange = {},
                    onSelectNotification = {},
                    onClearSelection = {},
                    onDeleteNotification = {},
                    onClearAllNotifications = {},
                    onScratchpadPatternChange = {},
                    onScratchpadFieldChange = {},
                    onScratchpadIgnoreCaseChange = {},
                    onApplyScratchpadToMatch = {},
                    onApplyScratchpadToExclude = {},
                    onApplyScratchpadToTitle = {},
                    onApplyScratchpadToDescription = {},
                    onApplyScratchpadToTime = {},
                    onDraftRuleChange = {},
                    onApplySubjectAsTitlePreset = {},
                    onLoadExistingRule = {},
                    onResetDraftRule = {},
                    onAllowlistSelectedPackage = {},
                    onSaveDraftAsAppRule = {},
                    onSaveDraftAsGlobalRule = {},
                    onDismissStatusMessage = {},
                )
            }
        }

        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()

        // Tapping Open Notification Access Settings presents disclosure including Developer Mode details
        composeRule.onNodeWithText("Open Notification Access Settings").performClick()
        assertEquals(0, openSettingsCount)
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Developer Mode", substring = true).assertIsDisplayed()

        // Cancel closes without opening Settings
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.CANCEL_BUTTON).performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertEquals(0, openSettingsCount)

        // Agree & Open Settings proceeds to Settings
        composeRule.onNodeWithText("Open Notification Access Settings").performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.AGREE_BUTTON).performClick()
        composeRule.onNodeWithTag(NotificationAccessDisclosureTags.DIALOG).assertDoesNotExist()
        assertEquals(1, openSettingsCount)
    }
}
