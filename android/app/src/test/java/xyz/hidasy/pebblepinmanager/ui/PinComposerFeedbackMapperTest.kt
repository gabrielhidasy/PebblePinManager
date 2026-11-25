package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class PinComposerFeedbackMapperTest {

    private val samplePin = SentPinRecord(
        id = "ppm-1",
        title = "Dentist Visit",
        startTimeMillis = Instant.parse("2026-11-21T15:30:00Z").toEpochMilli(),
        sentAtMillis = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli(),
    )

    private fun baseState() = PinComposerUiState(
        selectedDate = LocalDate.of(2026, 11, 21),
        selectedTime = LocalTime.of(15, 30),
        zoneId = ZoneId.of("UTC"),
        sentPins = listOf(samplePin),
    )

    @Test
    fun `watch connected confirmation maps to watch success snackbar with View action`() {
        val confirmation = PinSendConfirmation(
            eventId = 1L,
            pinId = "ppm-1",
            title = "Dentist Visit",
            formattedWhen = "Nov 21, 15:30",
            action = PinConfirmationAction.CREATED,
            deliveryStatus = PinDeliveryStatus.SENT_TO_WATCH,
            connectedWatchSummary = "My Pebble Time 2",
            headline = "Pin sent to Pebble Timeline",
            snackbarTitle = "Pin sent to Pebble",
            snackbarDetails = "\"Dentist Visit\" • Nov 21, 15:30 • My Pebble Time 2",
            buttonLabel = "Sent to Pebble",
        )
        val state = baseState().copy(
            feedbackEventId = 1L,
            sendConfirmation = confirmation,
        )

        val visuals = PinComposerFeedbackMapper.toSnackbarVisuals(
            uiState = state,
            canOpenSimulatedTimeline = false,
        )

        assertEquals(PinFeedbackSnackbarTone.WATCH_SUCCESS, visuals?.tone)
        assertEquals("Pin sent to Pebble", visuals?.headline)
        assertEquals("\"Dentist Visit\" • Nov 21, 15:30 • My Pebble Time 2", visuals?.details)
        assertEquals(PinComposerFeedbackMapper.ACTION_VIEW, visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, visuals?.duration)
    }

    @Test
    fun `queued confirmation maps to queued tone and omits View action for auto-pin updates`() {
        val confirmation = PinSendConfirmation(
            eventId = 2L,
            pinId = "ppm-auto-1",
            title = "Flight LH123",
            formattedWhen = "Nov 21, 18:00",
            action = PinConfirmationAction.UPDATED,
            deliveryStatus = PinDeliveryStatus.QUEUED_FOR_WATCH,
            isAutoPin = true,
            headline = "Pin updated (queued for Pebble)",
            snackbarTitle = "Pin updated (queued for Pebble)",
            snackbarDetails = "\"Flight LH123\" • Nov 21, 18:00 • Syncs when watch connects",
            buttonLabel = "Update Queued",
        )
        val state = baseState().copy(
            feedbackEventId = 2L,
            sendConfirmation = confirmation,
        )

        val visuals = PinComposerFeedbackMapper.toSnackbarVisuals(
            uiState = state,
            canOpenSimulatedTimeline = false,
        )

        assertEquals(PinFeedbackSnackbarTone.QUEUED_SUCCESS, visuals?.tone)
        assertNull(visuals?.actionLabel)
    }

    @Test
    fun `simulated confirmation maps to simulated tone and shows View only when timeline tab callback exists`() {
        val confirmation = PinSendConfirmation(
            eventId = 3L,
            pinId = "ppm-sim-1",
            title = "Dev Pin",
            formattedWhen = "Nov 21, 14:15",
            action = PinConfirmationAction.CREATED,
            deliveryStatus = PinDeliveryStatus.SIMULATED,
            headline = "Pin added to Simulated Timeline",
            snackbarTitle = "Pin added to Simulated Timeline",
            snackbarDetails = "\"Dev Pin\" • Nov 21, 14:15 • Watchless mode",
            buttonLabel = "Added to Timeline",
        )
        val state = baseState().copy(
            isRunWithoutWatch = true,
            feedbackEventId = 3L,
            sendConfirmation = confirmation,
        )

        val withTab = PinComposerFeedbackMapper.toSnackbarVisuals(
            uiState = state,
            canOpenSimulatedTimeline = true,
        )
        val withoutTab = PinComposerFeedbackMapper.toSnackbarVisuals(
            uiState = state,
            canOpenSimulatedTimeline = false,
        )

        assertEquals(PinFeedbackSnackbarTone.SIMULATED_SUCCESS, withTab?.tone)
        assertEquals(PinComposerFeedbackMapper.ACTION_VIEW, withTab?.actionLabel)
        assertNull(withoutTab?.actionLabel)
    }

    @Test
    fun `error feedback maps to error tone without action and null feedback returns null`() {
        val errorState = baseState().copy(
            feedbackEventId = 4L,
            feedback = SendFeedback.Error(
                headline = "Pebble companion app not available",
                details = "Install or open the Core Devices Pebble app.",
            ),
        )

        val visuals = PinComposerFeedbackMapper.toSnackbarVisuals(
            uiState = errorState,
            canOpenSimulatedTimeline = true,
        )

        assertEquals(PinFeedbackSnackbarTone.ERROR, visuals?.tone)
        assertEquals("Pebble companion app not available", visuals?.headline)
        assertNull(visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, visuals?.duration)

        assertNull(
            PinComposerFeedbackMapper.toSnackbarVisuals(
                uiState = baseState(),
                canOpenSimulatedTimeline = true,
            ),
        )
    }

    @Test
    fun `formatCompactLocalDateTime formats concise month day and 24h time in target zone`() {
        val epoch = Instant.parse("2026-11-21T15:30:00Z").toEpochMilli()
        assertEquals(
            "Nov 21, 15:30",
            TimelineTimestampFormatter.formatCompactLocalDateTime(epoch, ZoneId.of("UTC"), Locale.US),
        )
        assertEquals(
            "Nov 21, 10:30",
            TimelineTimestampFormatter.formatCompactLocalDateTime(epoch, ZoneId.of("America/New_York"), Locale.US),
        )
    }
}
