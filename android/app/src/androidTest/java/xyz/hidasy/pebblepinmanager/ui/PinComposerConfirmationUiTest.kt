package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class PinComposerConfirmationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun baseUiState() = PinComposerUiState(
        selectedDate = LocalDate.of(2026, 11, 21),
        selectedTime = LocalTime.of(15, 30),
        zoneId = ZoneOffset.UTC,
        sentPins = listOf(
            SentPinRecord(
                id = "ppm-ui-1",
                title = "Dentist Visit",
                startTimeMillis = Instant.parse("2026-11-21T15:30:00Z").toEpochMilli(),
                sentAtMillis = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli(),
            ),
        ),
    )

    @Test
    fun watchConnectedSend_showsAlwaysVisibleSnackbarButtonConfirmationAndTriggersHaptic() {
        var hapticCount = 0
        val confirmation = PinSendConfirmation(
            eventId = 1L,
            pinId = "ppm-ui-1",
            title = "Dentist Visit",
            formattedWhen = "Nov 21, 15:30",
            durationLabel = "30 min",
            action = PinConfirmationAction.CREATED,
            deliveryStatus = PinDeliveryStatus.SENT_TO_WATCH,
            connectedWatchSummary = "My Pebble Time 2",
            headline = "Pin sent to Pebble Timeline",
            snackbarTitle = "Pin sent to Pebble",
            snackbarDetails = "\"Dentist Visit\" • Nov 21, 15:30 (30 min) • My Pebble Time 2",
            buttonLabel = "Sent to Pebble",
        )
        var state by mutableStateOf(
            baseUiState().copy(
                feedbackEventId = 1L,
                sendConfirmation = confirmation,
                feedback = SendFeedback.Success(
                    pinId = "ppm-ui-1",
                    headline = "Pin sent to Pebble Timeline",
                    details = "\"Dentist Visit\" scheduled for Sat, Nov 21, 2026 at 15:30 (UTC, UTC+00:00) (30 min).",
                ),
            ),
        )

        composeRule.setContent {
            PebblePinManagerTheme {
                PinComposerScreen(
                    uiState = state,
                    onTitleChange = { newTitle ->
                        state = state.copy(title = newTitle, feedback = null, sendConfirmation = null)
                    },
                    onDescriptionChange = {},
                    onDateSelected = {},
                    onTimeSelected = {},
                    onIconSelected = {},
                    onDurationInputChange = {},
                    onDurationPresetSelected = {},
                    onBackgroundColorSelected = {},
                    onSetNow = {},
                    onQuickOffsetMinutes = {},
                    onTomorrowMorning = {},
                    onRefreshStatus = {},
                    onSendPin = {},
                    onEditPin = {},
                    onCancelEdit = {},
                    onDeletePin = {},
                    onConfirmationHaptic = { hapticCount++ },
                )
            }
        }

        // Snackbar is immediately visible without scrolling and shows title, time, and watch name
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR).assertIsDisplayed()
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_HEADLINE)
            .assertTextContains("Pin sent to Pebble")
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_DETAILS)
            .assertTextContains("\"Dentist Visit\" • Nov 21, 15:30 (30 min) • My Pebble Time 2")
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_ACTION).assertIsDisplayed()
        assertEquals(1, hapticCount)

        // Submit button displays brief confirmed state
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SUBMIT_BUTTON)
            .performScrollTo()
            .assertTextContains("Sent to Pebble")

        // Typing a new pin immediately resets the button label while keeping the snackbar visible
        state = state.copy(title = "Coffee", feedback = null, sendConfirmation = null)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PinComposerFeedbackTags.SUBMIT_BUTTON)
            .performScrollTo()
            .assertTextContains("Send to Pebble Timeline")
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR).assertIsDisplayed()
        assertEquals(1, hapticCount)
    }

    @Test
    fun updateConfirmation_showsUpdatedSnackbarAndButtonLabel() {
        var hapticCount = 0
        val confirmation = PinSendConfirmation(
            eventId = 2L,
            pinId = "ppm-ui-1",
            title = "Dentist (Moved)",
            formattedWhen = "Nov 21, 16:45",
            action = PinConfirmationAction.UPDATED,
            deliveryStatus = PinDeliveryStatus.SENT_TO_WATCH,
            connectedWatchSummary = "My Pebble Time 2",
            headline = "Pin updated on Pebble Timeline",
            snackbarTitle = "Pin updated on Pebble",
            snackbarDetails = "\"Dentist (Moved)\" • Nov 21, 16:45 • My Pebble Time 2",
            buttonLabel = "Updated on Pebble",
        )

        composeRule.setContent {
            PebblePinManagerTheme {
                PinComposerScreen(
                    uiState = baseUiState().copy(
                        feedbackEventId = 2L,
                        sendConfirmation = confirmation,
                        feedback = SendFeedback.Success(
                            pinId = "ppm-ui-1",
                            headline = "Pin updated on Pebble Timeline",
                            details = "\"Dentist (Moved)\" (ppm-ui-1) updated.",
                        ),
                    ),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    onDateSelected = {},
                    onTimeSelected = {},
                    onIconSelected = {},
                    onDurationInputChange = {},
                    onDurationPresetSelected = {},
                    onBackgroundColorSelected = {},
                    onSetNow = {},
                    onQuickOffsetMinutes = {},
                    onTomorrowMorning = {},
                    onRefreshStatus = {},
                    onSendPin = {},
                    onEditPin = {},
                    onCancelEdit = {},
                    onDeletePin = {},
                    onConfirmationHaptic = { hapticCount++ },
                )
            }
        }

        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR).assertIsDisplayed()
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_HEADLINE)
            .assertTextContains("Pin updated on Pebble")
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_DETAILS)
            .assertTextContains("\"Dentist (Moved)\"")
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SUBMIT_BUTTON)
            .performScrollTo()
            .assertTextContains("Updated on Pebble")
        assertEquals(1, hapticCount)
    }

    @Test
    fun simulatedModeConfirmation_displaysWatchlessStatusAndViewOpensSimulatedTimeline() {
        var hapticCount = 0
        var openTimelineCount = 0
        val confirmation = PinSendConfirmation(
            eventId = 3L,
            pinId = "ppm-sim-1",
            title = "Dev Demo",
            formattedWhen = "Nov 21, 14:15",
            action = PinConfirmationAction.CREATED,
            deliveryStatus = PinDeliveryStatus.SIMULATED,
            headline = "Pin added to Simulated Timeline",
            snackbarTitle = "Pin added to Simulated Timeline",
            snackbarDetails = "\"Dev Demo\" • Nov 21, 14:15 • Watchless mode",
            buttonLabel = "Added to Timeline",
        )

        composeRule.setContent {
            PebblePinManagerTheme {
                PinComposerScreen(
                    uiState = baseUiState().copy(
                        isRunWithoutWatch = true,
                        companionStatus = PebbleCompanionStatus.SimulatedNoWatch,
                        feedbackEventId = 3L,
                        sendConfirmation = confirmation,
                        feedback = SendFeedback.Success(
                            pinId = "ppm-sim-1",
                            headline = "Pin added to Simulated Timeline",
                            details = "\"Dev Demo\" scheduled in watchless mode.",
                        ),
                    ),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    onDateSelected = {},
                    onTimeSelected = {},
                    onIconSelected = {},
                    onDurationInputChange = {},
                    onDurationPresetSelected = {},
                    onBackgroundColorSelected = {},
                    onSetNow = {},
                    onQuickOffsetMinutes = {},
                    onTomorrowMorning = {},
                    onRefreshStatus = {},
                    onSendPin = {},
                    onEditPin = {},
                    onCancelEdit = {},
                    onDeletePin = {},
                    onOpenSimulatedTimeline = { openTimelineCount++ },
                    onConfirmationHaptic = { hapticCount++ },
                )
            }
        }

        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR).assertIsDisplayed()
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_HEADLINE)
            .assertTextContains("Pin added to Simulated Timeline")
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_DETAILS)
            .assertTextContains("Watchless mode")
        assertEquals(1, hapticCount)

        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_ACTION).performClick()
        assertEquals(1, openTimelineCount)
    }

    @Test
    fun errorState_showsErrorSnackbarPreservesInputsAndDoesNotTriggerConfirmationHaptic() {
        var hapticCount = 0

        composeRule.setContent {
            PebblePinManagerTheme {
                PinComposerScreen(
                    uiState = baseUiState().copy(
                        title = "Catch train",
                        description = "Platform 4",
                        feedbackEventId = 4L,
                        sendConfirmation = null,
                        feedback = SendFeedback.Error(
                            headline = "Pebble companion app not available",
                            details = "Install or open the Core Devices Pebble app.",
                        ),
                    ),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    onDateSelected = {},
                    onTimeSelected = {},
                    onIconSelected = {},
                    onDurationInputChange = {},
                    onDurationPresetSelected = {},
                    onBackgroundColorSelected = {},
                    onSetNow = {},
                    onQuickOffsetMinutes = {},
                    onTomorrowMorning = {},
                    onRefreshStatus = {},
                    onSendPin = {},
                    onEditPin = {},
                    onCancelEdit = {},
                    onDeletePin = {},
                    onConfirmationHaptic = { hapticCount++ },
                )
            }
        }

        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR).assertIsDisplayed()
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SNACKBAR_HEADLINE)
            .assertTextContains("Pebble companion app not available")
        composeRule.onNodeWithText("Catch train").assertIsDisplayed()
        composeRule.onNodeWithText("Platform 4").assertIsDisplayed()
        composeRule.onNodeWithTag(PinComposerFeedbackTags.SUBMIT_BUTTON)
            .performScrollTo()
            .assertTextContains("Send to Pebble Timeline")
        assertEquals(0, hapticCount)
    }
}
