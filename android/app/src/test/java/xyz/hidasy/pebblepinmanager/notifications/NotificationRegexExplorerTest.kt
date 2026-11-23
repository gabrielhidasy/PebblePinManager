package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NotificationRegexExplorerTest {
    private val utc = ZoneId.of("UTC")
    private val fixedNow = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()

    private val gmailFlightRecord = RawNotificationRecord(
        id = "raw-gmail-1",
        capturedAtMillis = fixedNow,
        sourcePackage = "com.google.android.gm",
        appLabel = "Gmail",
        notificationKey = "0|com.google.android.gm|42|null|1000",
        title = "Delta Air Lines",
        text = "Flight DL456 departs 16:30",
        bigText = "Flight DL456 departs 16:30\nGate B12 • Seat 14A",
        subText = "gabriel@example.com",
        eventTimeMillis = fixedNow,
        postTimeMillis = fixedNow,
    )

    @Test
    fun `evaluates regex patterns with multiple matches, capture groups, case sensitivity, and invalid syntax`() {
        val textFields = gmailFlightRecord.textFields()

        val groupResult = NotificationRegexExplorer.evaluatePattern(
            pattern = """Flight\s+([A-Z0-9]+)\s+departs\s+(\d{1,2}:\d{2})""",
            targetField = NotificationTextField.BODY,
            textFields = textFields,
        )
        assertTrue(groupResult.isValid)
        assertTrue(groupResult.isMatched)
        assertEquals(1, groupResult.matchCount)
        assertEquals("Flight DL456 departs 16:30", groupResult.firstMatch?.value)
        assertEquals(listOf("DL456", "16:30"), groupResult.firstMatch?.capturingGroups)
        assertEquals("DL456", groupResult.extractedValue)

        // Case-sensitive mismatch vs case-insensitive match
        val sensitiveMismatch = NotificationRegexExplorer.evaluatePattern(
            pattern = "delta air",
            targetField = NotificationTextField.TITLE,
            textFields = textFields,
            ignoreCase = false,
        )
        assertFalse(sensitiveMismatch.isMatched)

        val insensitiveMatch = NotificationRegexExplorer.evaluatePattern(
            pattern = "delta air",
            targetField = NotificationTextField.TITLE,
            textFields = textFields,
            ignoreCase = true,
        )
        assertTrue(insensitiveMatch.isMatched)
        assertEquals("Delta Air", insensitiveMatch.extractedValue)

        // Invalid regex returns clear error without throwing
        val invalid = NotificationRegexExplorer.evaluatePattern(
            pattern = "[unclosed",
            targetField = NotificationTextField.ANY,
            textFields = textFields,
        )
        assertFalse(invalid.isValid)
        assertNotNull(invalid.errorMessage)
        assertFalse(invalid.isMatched)
    }

    @Test
    fun `evaluates draft rule in isolation even before app is allowlisted while reporting live pipeline warnings`() = runTest {
        val draftRule = NotificationRegexRule(
            id = "draft-1",
            name = "Delta Flights",
            matchPattern = "Delta",
            matchTarget = NotificationTextField.TITLE,
            titleRegexPattern = """(Flight\s+[A-Z0-9]+)""",
            titleSource = NotificationTextField.BODY,
            descriptionRegexPattern = """(Gate\s+[A-Z0-9]+)""",
            descriptionSource = NotificationTextField.BODY,
            timeRegexPattern = """departs\s+(\d{1,2}:\d{2})""",
        )

        // App is NOT allowlisted yet in current settings
        val currentSettings = NotificationRuleSettings(
            isAutoPinEnabled = true,
            allowedPackages = emptySet(),
        )

        val exploration = NotificationRegexExplorer.evaluateDraftRule(
            record = gmailFlightRecord,
            draftRule = draftRule,
            currentSettings = currentSettings,
            zoneId = utc,
            clockMillis = fixedNow,
        )

        // Current saved rules ignore it because app is not allowlisted
        assertNull(exploration.currentSettingsEvaluation.event)
        assertTrue(exploration.pipelineWarnings.any { it.contains("not allowlisted", ignoreCase = true) })

        // Draft rule preview still shows exact pin extraction!
        assertTrue(exploration.matchResult.isMatched)
        assertEquals("Flight DL456", exploration.titleResult.extractedValue)
        assertEquals("Gate B12", exploration.descriptionResult.extractedValue)
        assertEquals("16:30", exploration.timeResult.extractedValue)
        assertEquals(
            Instant.parse("2026-11-23T16:30:00Z").toEpochMilli(),
            exploration.parsedEventTimeMillis,
        )

        val previewEvent = exploration.draftEvaluation.event
        assertNotNull(previewEvent)
        assertEquals("Flight DL456", previewEvent!!.title)
        assertEquals("Gate B12", previewEvent.description)
        assertEquals(Instant.parse("2026-11-23T16:30:00Z").toEpochMilli(), previewEvent.startTimeMillis)
    }

    @Test
    fun `evaluates exclude and ignore draft rules accurately`() = runTest {
        val excludedRule = NotificationRegexRule(
            id = "draft-excl",
            name = "Exclude Gate B12",
            matchPattern = "Flight",
            excludePattern = "Gate B12",
        )
        val excludedResult = NotificationRegexExplorer.evaluateDraftRule(
            record = gmailFlightRecord,
            draftRule = excludedRule,
            currentSettings = NotificationRuleSettings(allowedPackages = setOf("com.google.android.gm")),
            zoneId = utc,
            clockMillis = fixedNow,
        )
        assertTrue(excludedResult.excludeResult.isMatched)
        assertNull(excludedResult.draftEvaluation.event)

        val ignoreRule = NotificationRegexRule(
            id = "draft-ign",
            name = "Ignore Delta",
            action = NotificationRuleAction.IGNORE,
            matchPattern = "Delta",
            matchTarget = NotificationTextField.TITLE,
        )
        val ignoreResult = NotificationRegexExplorer.evaluateDraftRule(
            record = gmailFlightRecord,
            draftRule = ignoreRule,
            currentSettings = NotificationRuleSettings(allowedPackages = setOf("com.google.android.gm")),
            zoneId = utc,
            clockMillis = fixedNow,
        )
        assertNull(ignoreResult.draftEvaluation.event)
        assertTrue(ignoreResult.draftEvaluation.reason.contains("Ignored", ignoreCase = true))
    }
}
