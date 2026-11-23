package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.Instant
import java.time.ZoneId

class RuleBasedNotificationPinMapperTest {
    private val fixedNow = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test
    fun `strictly enforces opt-in package allowlist and master toggle`() = runTest {
        val defaultMapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(), // empty allowlist by default
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )
        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.delivery",
            notificationKey = "0|com.example.delivery|1",
            title = "Package arriving",
            text = "Arriving at 14:00",
            eventTimeMillis = fixedNow,
        )
        assertNull(defaultMapper.mapOrNull(candidate, existingPinId = null))

        val disabledMapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                isAutoPinEnabled = false,
                allowedPackages = setOf("com.example.delivery"),
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )
        assertNull(disabledMapper.mapOrNull(candidate, existingPinId = null))

        val enabledMapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                isAutoPinEnabled = true,
                allowedPackages = setOf("com.example.delivery"),
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-notif-1" },
        )
        val mapped = enabledMapper.mapOrNull(candidate, existingPinId = null)
        assertNotNull(mapped)
        assertEquals("ppm-notif-1", mapped!!.id)
        assertEquals("Package arriving", mapped.title)
        assertEquals("Arriving at 14:00", mapped.description)
        assertEquals(Instant.parse("2026-11-22T14:00:00Z").toEpochMilli(), mapped.startTimeMillis)
        assertEquals(EventSource.NOTIFICATION, mapped.source)
        assertEquals("0|com.example.delivery|1", mapped.sourceKey)
    }

    @Test
    fun `filters out ongoing and group summary notifications by default`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.chat"),
                ignoreOngoing = true,
                ignoreGroupSummary = true,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val ongoing = NotificationPinCandidate(
            sourcePackage = "com.example.chat",
            notificationKey = "0|com.example.chat|1",
            title = "Active call",
            text = "00:12",
            eventTimeMillis = fixedNow,
            isOngoing = true,
        )
        val summary = NotificationPinCandidate(
            sourcePackage = "com.example.chat",
            notificationKey = "0|com.example.chat|2",
            title = "3 new messages",
            text = "Alice, Bob",
            eventTimeMillis = fixedNow,
            isGroupSummary = true,
        )

        assertNull(mapper.mapOrNull(ongoing, null))
        assertNull(mapper.mapOrNull(summary, null))
    }

    @Test
    fun `applies case-insensitive keyword filter across title and body`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.mail"),
                keywordFilter = "flight, delivery, gate",
                extractTimeFromText = false,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-notif-kw" },
        )

        val nonMatching = NotificationPinCandidate(
            sourcePackage = "com.example.mail",
            notificationKey = "0|com.example.mail|10",
            title = "Weekly newsletter",
            text = "Top 5 productivity tips",
            eventTimeMillis = fixedNow + 60_000L,
        )
        assertNull(mapper.mapOrNull(nonMatching, null))

        val matching = NotificationPinCandidate(
            sourcePackage = "com.example.mail",
            notificationKey = "0|com.example.mail|11",
            title = "Carrier update",
            text = "Out for DELIVERY today",
            eventTimeMillis = fixedNow + 60_000L,
        )
        val event = mapper.mapOrNull(matching, null)
        assertNotNull(event)
        assertEquals("Carrier update", event!!.title)
    }

    @Test
    fun `extracts title, description, and time with custom regexes and falls back safely`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.airline"),
                titleRegexPattern = """Flight\s+([A-Z0-9]+)""",
                descriptionRegexPattern = """(Gate\s+[A-Z0-9]+,\s*Seat\s+[A-Z0-9]+)""",
                timeRegexPattern = """Boards\s+(\d{1,2}:\d{2})""",
                defaultIconUri = PinIcon.SCHEDULED_FLIGHT.uri,
                defaultBackgroundColor = PinAccentColor.SKY.hexCode,
                defaultDurationMinutes = 45,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-notif-flight" },
        )

        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.airline",
            notificationKey = "0|com.example.airline|99",
            title = "Alert: Flight LH456 Update",
            text = "Short summary",
            bigText = "Updated 10:05. Gate B12, Seat 14A. Boards 16:30",
            eventTimeMillis = fixedNow,
        )

        val event = mapper.mapOrNull(candidate, existingPinId = "ppm-notif-existing")
        assertNotNull(event)
        assertEquals("ppm-notif-existing", event!!.id)
        assertEquals("LH456", event.title)
        assertEquals("Gate B12, Seat 14A", event.description)
        assertEquals(Instant.parse("2026-11-22T16:30:00Z").toEpochMilli(), event.startTimeMillis)
        assertEquals(PinIcon.SCHEDULED_FLIGHT.uri, event.iconUri)
        assertEquals(PinAccentColor.SKY.hexCode, event.backgroundColor)
        assertEquals(45, event.durationMinutes)
    }

    @Test
    fun `truncates oversized notification titles and bigText to Pebble Timeline limits`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.notes"),
                extractTimeFromText = false,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val longTitle = "A".repeat(200)
        val longBody = "B".repeat(700)
        val event = mapper.mapOrNull(
            NotificationPinCandidate(
                sourcePackage = "com.example.notes",
                notificationKey = "0|com.example.notes|1",
                title = longTitle,
                text = "short",
                bigText = longBody,
                eventTimeMillis = fixedNow,
            ),
            existingPinId = null,
        )

        assertNotNull(event)
        assertEquals(TimelineEvent.MAX_TITLE_LENGTH, event!!.title.length)
        assertTrue(event.title.endsWith("…"))
        assertEquals(TimelineEvent.MAX_DESCRIPTION_LENGTH, event.description!!.length)
        assertTrue(event.description!!.endsWith("…"))
    }

    @Test
    fun `ignores invalid regex syntax without crashing and falls back to raw fields`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.app"),
                titleRegexPattern = "[broken",
                descriptionRegexPattern = "(unclosed",
                timeRegexPattern = "*invalid",
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val event = mapper.mapOrNull(
            NotificationPinCandidate(
                sourcePackage = "com.example.app",
                notificationKey = "0|com.example.app|5",
                title = "Package Delivered",
                text = "Front porch",
                eventTimeMillis = fixedNow,
            ),
            existingPinId = null,
        )

        assertNotNull(event)
        assertEquals("Package Delivered", event!!.title)
        assertEquals("Front porch", event.description)
        assertEquals(fixedNow, event.startTimeMillis)
    }

    @Test
    fun `evaluates N ordered per-app regex rules for Gmail sender-as-title and ignores unmatched emails when required`() = runTest {
        val gmailPkg = "com.google.android.gm"
        val settings = NotificationRuleSettings(
            allowedPackages = setOf(gmailPkg),
            keywordFilter = "unrelated-global-keyword", // should NOT block matching Gmail per-app rules
            appRules = mapOf(
                gmailPkg to AppNotificationRuleConfig(
                    packageName = gmailPkg,
                    requireRegexRuleMatch = true,
                    defaultIconUri = PinIcon.GENERIC_EMAIL.uri,
                    defaultBackgroundColor = PinAccentColor.SUNSET.hexCode,
                    regexRules = listOf(
                        NotificationRegexRule(
                            id = "r-ignore-promo",
                            name = "Ignore Newsletters",
                            action = NotificationRuleAction.IGNORE,
                            matchPattern = """unsubscribe|weekly\s+digest""",
                            matchTarget = NotificationTextField.BODY,
                        ),
                        NotificationRegexRule(
                            id = "r-disabled",
                            name = "Disabled Catch-All",
                            enabled = false,
                            matchPattern = ".*",
                        ),
                        NotificationRegexRule(
                            id = "r-flight",
                            name = "Flight Updates",
                            matchPattern = """Delta|United""",
                            matchTarget = NotificationTextField.TITLE,
                            titleRegexPattern = """(Flight\s+[A-Z0-9]+)""",
                            titleSource = NotificationTextField.BODY,
                            descriptionRegexPattern = """(Gate\s+[A-Z0-9]+)""",
                            descriptionSource = NotificationTextField.BODY,
                            timeRegexPattern = """departs\s+(\d{1,2}:\d{2})""",
                            iconUri = PinIcon.SCHEDULED_FLIGHT.uri,
                            backgroundColor = PinAccentColor.SKY.hexCode,
                        ),
                        NotificationRegexRule(
                            id = "r-calendar-email",
                            name = "Calendar Invites",
                            matchPattern = """^Google Calendar$""",
                            matchTarget = NotificationTextField.TITLE,
                            titleSource = NotificationTextField.BODY,
                            descriptionSource = NotificationTextField.BODY,
                        ),
                    ),
                ),
            ),
        )
        val mapper = RuleBasedNotificationPinMapper(
            settings = settings,
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-gmail-1" },
        )

        // 1. Newsletter from Delta gets dropped by Rule 1 (IGNORE) before Rule 3 runs
        val promo = NotificationPinCandidate(
            sourcePackage = gmailPkg,
            notificationKey = "0|$gmailPkg|1",
            title = "Delta Air Lines",
            text = "Flight deals! Click unsubscribe anytime.",
            eventTimeMillis = fixedNow,
        )
        val promoEval = mapper.evaluate(promo)
        assertNull(promoEval.event)
        assertEquals(RuleMatchScope.APP_RULE, promoEval.matchedScope)
        assertEquals("Ignore Newsletters", promoEval.matchedRuleName)

        // 2. Real Delta flight email matches Rule 3 with rule-level icon/color overrides
        val flight = NotificationPinCandidate(
            sourcePackage = gmailPkg,
            notificationKey = "0|$gmailPkg|2",
            title = "Delta Air Lines",
            text = "Flight DL456 departs 16:30",
            bigText = "Flight DL456 departs 16:30\nProceed to Gate B12 for boarding.",
            eventTimeMillis = fixedNow,
        )
        val flightEval = mapper.evaluate(flight)
        assertNotNull(flightEval.event)
        assertEquals(RuleMatchScope.APP_RULE, flightEval.matchedScope)
        assertEquals("Flight Updates", flightEval.matchedRuleName)
        assertEquals("Flight DL456", flightEval.event!!.title)
        assertEquals("Gate B12", flightEval.event!!.description)
        assertEquals(Instant.parse("2026-11-22T16:30:00Z").toEpochMilli(), flightEval.event!!.startTimeMillis)
        assertEquals(PinIcon.SCHEDULED_FLIGHT.uri, flightEval.event!!.iconUri)
        assertEquals(PinAccentColor.SKY.hexCode, flightEval.event!!.backgroundColor)

        // 3. Calendar invite email promotes 1st body line to title and strips duplicate line from body
        val calEmail = NotificationPinCandidate(
            sourcePackage = gmailPkg,
            notificationKey = "0|$gmailPkg|3",
            title = "Google Calendar",
            text = "Dentist Checkup",
            bigText = "Dentist Checkup\nTomorrow at 09:30 in Suite 402",
            eventTimeMillis = fixedNow,
        )
        val calEval = mapper.evaluate(calEmail)
        assertNotNull(calEval.event)
        assertEquals("Calendar Invites", calEval.matchedRuleName)
        assertEquals("Dentist Checkup", calEval.event!!.title)
        assertEquals("Tomorrow at 09:30 in Suite 402", calEval.event!!.description)
        assertEquals(Instant.parse("2026-11-23T09:30:00Z").toEpochMilli(), calEval.event!!.startTimeMillis)
        // Inherits app-level default icon & color when rule does not override
        assertEquals(PinIcon.GENERIC_EMAIL.uri, calEval.event!!.iconUri)
        assertEquals(PinAccentColor.SUNSET.hexCode, calEval.event!!.backgroundColor)

        // 4. Unmatched random email is ignored because requireRegexRuleMatch = true
        val randomEmail = NotificationPinCandidate(
            sourcePackage = gmailPkg,
            notificationKey = "0|$gmailPkg|4",
            title = "Alice Smith",
            text = "Hey, are you free later?",
            eventTimeMillis = fixedNow,
        )
        val randomEval = mapper.evaluate(randomEmail)
        assertNull(randomEval.event)
        assertEquals(RuleMatchScope.NONE, randomEval.matchedScope)
    }

    @Test
    fun `supports using Gmail sender title as pin description when body is promoted to title`() = runTest {
        val gmailPkg = "com.google.android.gm"
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf(gmailPkg),
                extractTimeFromText = false,
                appRules = mapOf(
                    gmailPkg to AppNotificationRuleConfig(
                        packageName = gmailPkg,
                        regexRules = listOf(
                            NotificationRegexRule(
                                id = "r-swap",
                                name = "Subject Title + Sender Desc",
                                titleSource = NotificationTextField.BODY,
                                descriptionSource = NotificationTextField.TITLE,
                            ),
                        ),
                    ),
                ),
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val event = mapper.mapOrNull(
            NotificationPinCandidate(
                sourcePackage = gmailPkg,
                notificationKey = "0|$gmailPkg|10",
                title = "Alice Smith",
                text = "Lunch meeting",
                eventTimeMillis = fixedNow,
            ),
            existingPinId = null,
        )
        assertNotNull(event)
        assertEquals("Lunch meeting", event!!.title)
        assertEquals("Alice Smith", event.description)
    }

    @Test
    fun `falls back from per-app rules to global N regex rules when configured and respects exclude keywords`() = runTest {
        val deliveryPkg = "com.example.delivery"
        val settings = NotificationRuleSettings(
            allowedPackages = setOf(deliveryPkg, "com.example.quickapp"),
            excludeKeywordFilter = "spam, survey",
            globalRegexRules = listOf(
                NotificationRegexRule(
                    id = "g-1",
                    name = "Global Parcel ETA",
                    matchPattern = """parcel|package""",
                    titleRegexPattern = """(Parcel\s+#\d+)""",
                    titleSource = NotificationTextField.ANY,
                    iconUri = PinIcon.CAR_RENTAL.uri,
                ),
            ),
            appRules = mapOf(
                deliveryPkg to AppNotificationRuleConfig(
                    packageName = deliveryPkg,
                    excludeKeywordFilter = "rating",
                    requireRegexRuleMatch = false,
                    fallbackToGlobalRules = true,
                    defaultBackgroundColor = PinAccentColor.TEAL.hexCode,
                    regexRules = listOf(
                        NotificationRegexRule(
                            id = "a-1",
                            name = "Courier Arrived",
                            matchPattern = """at\s+your\s+door""",
                        ),
                    ),
                ),
            ),
        )
        val mapper = RuleBasedNotificationPinMapper(
            settings = settings,
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        // Excluded by app exclude keyword
        assertNull(
            mapper.mapOrNull(
                NotificationPinCandidate(
                    sourcePackage = deliveryPkg,
                    notificationKey = "k1",
                    title = "Parcel #42",
                    text = "Leave a rating for your driver",
                    eventTimeMillis = fixedNow,
                ),
                null,
            ),
        )

        // App rule didn't match, falls back to global regex rule while preserving app accent color
        val eval = mapper.evaluate(
            NotificationPinCandidate(
                sourcePackage = deliveryPkg,
                notificationKey = "k2",
                title = "Parcel #42 out for delivery",
                text = "Arriving at 15:00",
                eventTimeMillis = fixedNow,
            ),
        )
        assertNotNull(eval.event)
        assertEquals(RuleMatchScope.GLOBAL_RULE, eval.matchedScope)
        assertEquals("Global Parcel ETA", eval.matchedRuleName)
        assertEquals("Parcel #42", eval.event!!.title)
        assertEquals(PinIcon.CAR_RENTAL.uri, eval.event!!.iconUri)
        assertEquals(PinAccentColor.TEAL.hexCode, eval.event!!.backgroundColor)

        // Quick-setup app with no custom rules uses global exclude keywords
        assertNull(
            mapper.mapOrNull(
                NotificationPinCandidate(
                    sourcePackage = "com.example.quickapp",
                    notificationKey = "k3",
                    title = "Parcel #99",
                    text = "Quick customer survey",
                    eventTimeMillis = fixedNow,
                ),
                null,
            ),
        )
    }

    @Test
    fun `respects requireGlobalRegexRuleMatch and blank IGNORE rule guardrail`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.app"),
                requireGlobalRegexRuleMatch = true,
                globalRegexRules = listOf(
                    NotificationRegexRule(
                        id = "g-blank-ignore",
                        name = "Blank Ignore Guardrail",
                        action = NotificationRuleAction.IGNORE,
                        matchPattern = "   ",
                    ),
                    NotificationRegexRule(
                        id = "g-urgent",
                        name = "Urgent Only",
                        matchPattern = """URGENT:\s*(.+)""",
                        titleRegexPattern = """URGENT:\s*(.+)""",
                    ),
                ),
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        // Blank IGNORE rule does NOT swallow the urgent alert
        val urgent = mapper.evaluate(
            NotificationPinCandidate(
                sourcePackage = "com.example.app",
                notificationKey = "k1",
                title = "URGENT: Server Reboot",
                text = "Scheduled at 18:00",
                eventTimeMillis = fixedNow,
            ),
        )
        assertNotNull(urgent.event)
        assertEquals("Server Reboot", urgent.event!!.title)
        assertEquals("Urgent Only", urgent.matchedRuleName)

        // Non-urgent message is ignored because requireGlobalRegexRuleMatch = true
        val normal = mapper.evaluate(
            NotificationPinCandidate(
                sourcePackage = "com.example.app",
                notificationKey = "k2",
                title = "Daily Status",
                text = "All good",
                eventTimeMillis = fixedNow,
            ),
        )
        assertNull(normal.event)
        assertEquals(RuleMatchScope.NONE, normal.matchedScope)
    }
}
