package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}

private class RecordingPinSender : TimelinePinSender {
    val sentEvents = mutableListOf<TimelineEvent>()
    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        sentEvents += event
        return PinSendResult.Success(event.id)
    }
    override suspend fun deletePin(pinId: String): PinDeleteResult = PinDeleteResult.Success(pinId)
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
