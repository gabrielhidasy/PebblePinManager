package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.pebble.ConnectedPebbleWatch
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Instant

class NotificationPinPipelineTest {
    private val fixedNow = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()

    @Test
    fun `delivers new notification pin and reuses pin ID on notification update`() = runTest {
        val sender = RecordingPinSender()
        val repo = InMemoryPinRepository()
        val pipeline = NotificationPinPipeline(
            mapper = NotificationPinMapper { candidate, existingPinId ->
                if (candidate.sourcePackage != "com.example.flights") return@NotificationPinMapper null
                TimelineEvent(
                    id = existingPinId ?: "ppm-notif-1",
                    title = candidate.title,
                    description = candidate.text,
                    startTimeMillis = candidate.eventTimeMillis,
                )
            },
            pinSender = sender,
            repository = repo,
            clockMillis = { fixedNow },
        )

        val ignored = pipeline.process(
            NotificationPinCandidate(
                sourcePackage = "com.unlisted.app",
                notificationKey = "0|com.unlisted.app|1",
                title = "Promo",
                text = null,
                eventTimeMillis = fixedNow + 3_600_000L,
            ),
        )
        assertEquals(NotificationPipelineResult.Ignored, ignored)
        assertTrue(sender.sentEvents.isEmpty())

        val first = pipeline.process(
            NotificationPinCandidate(
                sourcePackage = "com.example.flights",
                notificationKey = "0|com.example.flights|42",
                title = "Flight LH123 Boarding",
                text = "Gate A12",
                eventTimeMillis = fixedNow + 7_200_000L,
            ),
        )
        assertEquals(NotificationPipelineResult.Delivered("ppm-notif-1"), first)
        assertEquals(EventSource.NOTIFICATION, sender.sentEvents.last().source)

        val updated = pipeline.process(
            NotificationPinCandidate(
                sourcePackage = "com.example.flights",
                notificationKey = "0|com.example.flights|42",
                title = "Flight LH123 Gate Change",
                text = "Gate B04",
                eventTimeMillis = fixedNow + 7_200_000L,
            ),
        )
        assertEquals(NotificationPipelineResult.Delivered("ppm-notif-1"), updated)
        assertEquals(2, sender.sentEvents.size)
        assertEquals("ppm-notif-1", sender.sentEvents[1].id)
        assertEquals("Gate B04", repo.getPinById("ppm-notif-1")?.description)
    }

    @Test
    fun `skips redundant delivery when re-posted notification has identical content`() = runTest {
        val sender = RecordingPinSender()
        val repo = InMemoryPinRepository()
        val pipeline = NotificationPinPipeline(
            mapper = RuleBasedNotificationPinMapper(
                settings = NotificationRuleSettings(
                    allowedPackages = setOf("com.example.delivery"),
                    extractTimeFromText = false,
                ),
                clockMillis = { fixedNow },
                pinIdGenerator = { "ppm-notif-dedup" },
            ),
            pinSender = sender,
            repository = repo,
            clockMillis = { fixedNow },
        )

        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.delivery",
            notificationKey = "0|com.example.delivery|7|pkg-tag",
            title = "Out for delivery",
            text = "Expected today",
            eventTimeMillis = fixedNow + 1_800_000L,
        )

        assertEquals(NotificationPipelineResult.Delivered("ppm-notif-dedup"), pipeline.process(candidate))
        assertEquals(NotificationPipelineResult.Unchanged("ppm-notif-dedup"), pipeline.process(candidate))
        assertEquals(1, sender.sentEvents.size)
    }

    @Test
    fun `serializes concurrent updates for same notification key so only one pin ID is created`() = runTest {
        val sender = RecordingPinSender(sendDelayMillis = 25L)
        val repo = InMemoryPinRepository()
        var counter = 0
        val pipeline = NotificationPinPipeline(
            mapper = RuleBasedNotificationPinMapper(
                settings = NotificationRuleSettings(
                    allowedPackages = setOf("com.example.flights"),
                    extractTimeFromText = false,
                ),
                clockMillis = { fixedNow },
                pinIdGenerator = { "ppm-notif-${++counter}" },
            ),
            pinSender = sender,
            repository = repo,
            clockMillis = { fixedNow },
        )

        val first = NotificationPinCandidate(
            sourcePackage = "com.example.flights",
            notificationKey = "0|com.example.flights|88",
            title = "Boarding soon",
            text = "Gate A1",
            eventTimeMillis = fixedNow + 3_600_000L,
        )
        val second = first.copy(text = "Gate B2")

        val results = listOf(
            async { pipeline.process(first) },
            async { pipeline.process(second) },
        ).awaitAll()

        assertEquals(1, counter)
        assertEquals(2, results.size)
        assertTrue(results.all { it == NotificationPipelineResult.Delivered("ppm-notif-1") })
        assertEquals(listOf("ppm-notif-1", "ppm-notif-1"), sender.sentEvents.map { it.id })
    }

    @Test
    fun `processRemoval deletes tracked notification pin and ignores unknown keys`() = runTest {
        val sender = RecordingPinSender()
        val repo = InMemoryPinRepository()
        val pipeline = NotificationPinPipeline(
            mapper = RuleBasedNotificationPinMapper(
                settings = NotificationRuleSettings(
                    allowedPackages = setOf("com.example.calendar"),
                    extractTimeFromText = false,
                ),
                clockMillis = { fixedNow },
                pinIdGenerator = { "ppm-notif-rem" },
            ),
            pinSender = sender,
            repository = repo,
            clockMillis = { fixedNow },
        )

        assertEquals(
            NotificationRemovalResult.NotTracked,
            pipeline.processRemoval("0|com.example.calendar|999"),
        )

        pipeline.process(
            NotificationPinCandidate(
                sourcePackage = "com.example.calendar",
                notificationKey = "0|com.example.calendar|12",
                title = "Standup",
                text = "Room 2",
                eventTimeMillis = fixedNow + 900_000L,
            ),
        )
        assertEquals("ppm-notif-rem", repo.getPinBySourceKey("0|com.example.calendar|12")?.id)

        val removed = pipeline.processRemoval("0|com.example.calendar|12")
        assertEquals(NotificationRemovalResult.Deleted("ppm-notif-rem"), removed)
        assertEquals(listOf("ppm-notif-rem"), sender.deletedPinIds)
        assertNull(repo.getPinBySourceKey("0|com.example.calendar|12"))
    }

    @Test
    fun `deduplication key falls back to package, tag, and notificationId when key is blank`() {
        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.app",
            notificationKey = "   ",
            title = "Alert",
            text = "Body",
            eventTimeMillis = fixedNow,
            tag = "flight-lh123",
            notificationId = 77,
        )
        assertEquals("com.example.app|flight-lh123|77", candidate.deduplicationKey())
    }

    @Test
    fun `notification access parser distinguishes release and debug package names accurately`() {
        val enabledSetting =
            "com.other.app/.Listener:xyz.hidasy.pebblepinmanager.debug/xyz.hidasy.pebblepinmanager.notifications.PebbleNotificationListenerService"

        assertTrue(
            NotificationAccessParser.isPackageEnabled(
                enabledListeners = enabledSetting,
                packageName = "xyz.hidasy.pebblepinmanager.debug",
            ),
        )
        assertFalse(
            NotificationAccessParser.isPackageEnabled(
                enabledListeners = enabledSetting,
                packageName = "xyz.hidasy.pebblepinmanager",
            ),
        )
        assertFalse(NotificationAccessParser.isPackageEnabled(null, "xyz.hidasy.pebblepinmanager"))
        assertFalse(NotificationAccessParser.isPackageEnabled("", "xyz.hidasy.pebblepinmanager"))
    }
}

private class RecordingPinSender(
    private val sendDelayMillis: Long = 0L,
) : TimelinePinSender {
    val sentEvents = mutableListOf<TimelineEvent>()
    val deletedPinIds = mutableListOf<String>()
    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        if (sendDelayMillis > 0L) delay(sendDelayMillis)
        sentEvents += event
        return PinSendResult.Success(event.id)
    }
    override suspend fun deletePin(pinId: String): PinDeleteResult {
        deletedPinIds += pinId
        return PinDeleteResult.Success(pinId)
    }
    override suspend fun getCompanionStatus(): PebbleCompanionStatus =
        PebbleCompanionStatus.Available("coredevices.coreapp", listOf("coredevices.coreapp"))
    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> = flowOf(emptyList())
}

private class InMemoryPinRepository : SentPinRepository {
    private val pins = MutableStateFlow<List<SentPinRecord>>(emptyList())
    override fun observeSentPins(): Flow<List<SentPinRecord>> = pins
    override suspend fun saveSentPin(record: SentPinRecord) {
        pins.value = listOf(record) + pins.value.filterNot { it.id == record.id }
    }
    override suspend fun getPinById(id: String): SentPinRecord? = pins.value.firstOrNull { it.id == id }
    override suspend fun getPinBySourceKey(sourceKey: String): SentPinRecord? =
        pins.value.firstOrNull { it.sourceKey == sourceKey }
    override suspend fun deletePinRecord(id: String) {
        pins.value = pins.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        pins.value = emptyList()
    }
}
