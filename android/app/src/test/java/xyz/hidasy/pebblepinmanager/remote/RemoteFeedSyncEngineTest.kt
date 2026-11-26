package xyz.hidasy.pebblepinmanager.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.hidasy.pebblepinmanager.data.DataStoreSimulatedTimelineRepository
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.pebble.ConnectedPebbleWatch
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.SimulatedTimelinePinSender
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RemoteFeedSyncEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fixedInstant = Instant.parse("2026-11-25T22:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @Test
    fun `reconciles insert, in-place update, unchanged skip, 304 not modified, and deletion`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val pinSender = RecordingTimelinePinSender()
        var watchRefreshCount = 0

        val feed = settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "feed-main",
                name = "Companion Server",
                url = "https://pins.example.com/pins.json",
                authType = RemoteFeedAuthType.BEARER_TOKEN,
            ),
        )
        secretStore.putSecret(feed.id, RemoteFeedSecret(token = "secret-123"))

        var nextResponse: RemoteFeedFetchResult = RemoteFeedFetchResult.Success(
            body = """
                {
                  "schemaVersion": 1,
                  "updatedAt": "2026-11-25T22:00:00Z",
                  "pins": [
                    {
                      "id": "ext-1",
                      "title": "Standup",
                      "description": "Room A",
                      "startTime": "2026-11-26T14:00:00Z",
                      "durationMinutes": 30,
                      "layoutType": "CALENDAR_PIN"
                    },
                    {
                      "id": "ext-2",
                      "title": "Dentist",
                      "startTime": "2026-11-27T09:00:00Z"
                    }
                  ]
                }
            """.trimIndent(),
            etag = "\"etag-1\"",
        )
        val requests = mutableListOf<RemoteFeedHttpRequest>()
        val httpClient = RemoteFeedHttpClient { req ->
            requests.add(req)
            nextResponse
        }

        val engine = RemoteFeedSyncEngine(
            settingsRepository = settingsRepo,
            secretStore = secretStore,
            httpClient = httpClient,
            pinSender = pinSender,
            sentPinRepository = sentPinRepo,
            clock = fixedClock,
            onPinsChanged = { watchRefreshCount++ },
        )

        // 1. Initial sync inserts both pins
        val sync1 = engine.syncFeed(feed.id) as RemoteFeedSyncOutcome.Synced
        assertEquals(2, sync1.insertedCount)
        assertEquals(0, sync1.updatedCount)
        assertEquals(0, sync1.deletedCount)
        assertEquals(2, sync1.activePinCount)
        assertEquals(1, watchRefreshCount)
        assertEquals("\"etag-1\"", settingsRepo.getFeed(feed.id)?.etag)
        assertEquals(RemoteSyncStatus.SUCCESS, settingsRepo.getFeed(feed.id)?.lastSyncStatus)

        // 2. Conditional 304 Not Modified sends ETag and skips all pin operations
        nextResponse = RemoteFeedFetchResult.NotModified(etag = "\"etag-1\"")
        val sync2 = engine.syncFeed(feed.id)
        assertTrue(sync2 is RemoteFeedSyncOutcome.NotModified)
        assertEquals("\"etag-1\"", requests.last().ifNoneMatch)
        assertEquals(2, pinSender.sentEvents.size)
        assertEquals(0, pinSender.deletedIds.size)
        assertEquals(RemoteSyncStatus.NOT_MODIFIED, settingsRepo.getFeed(feed.id)?.lastSyncStatus)

        // 3. Modify ext-1 in-place, leave ext-2 unchanged, add ext-3 -> only ext-1 & ext-3 sent
        nextResponse = RemoteFeedFetchResult.Success(
            body = """
                {
                  "schemaVersion": 1,
                  "pins": [
                    {
                      "id": "ext-1",
                      "title": "Standup (Moved)",
                      "description": "Room B",
                      "startTime": "2026-11-26T14:30:00Z",
                      "durationMinutes": 45,
                      "layoutType": "CALENDAR_PIN"
                    },
                    {
                      "id": "ext-2",
                      "title": "Dentist",
                      "startTime": "2026-11-27T09:00:00Z"
                    },
                    {
                      "id": "ext-3",
                      "title": "Flight",
                      "startTime": "2026-11-28T06:00:00Z"
                    }
                  ]
                }
            """.trimIndent(),
            etag = "\"etag-2\"",
        )
        val sync3 = engine.syncFeed(feed.id) as RemoteFeedSyncOutcome.Synced
        assertEquals(1, sync3.insertedCount)
        assertEquals(1, sync3.updatedCount)
        assertEquals(1, sync3.unchangedCount)
        assertEquals(0, sync3.deletedCount)
        assertEquals(3, sync3.activePinCount)
        assertEquals("Standup (Moved)", sentPinRepo.getPinById("ext-1")?.title)

        // 4. Remove ext-2 and ext-3 from feed -> both deleted from watch & repository
        nextResponse = RemoteFeedFetchResult.Success(
            body = """
                {
                  "schemaVersion": 1,
                  "pins": [
                    {
                      "id": "ext-1",
                      "title": "Standup (Moved)",
                      "description": "Room B",
                      "startTime": "2026-11-26T14:30:00Z",
                      "durationMinutes": 45,
                      "layoutType": "CALENDAR_PIN"
                    }
                  ]
                }
            """.trimIndent(),
            etag = "\"etag-3\"",
        )
        val sync4 = engine.syncFeed(feed.id) as RemoteFeedSyncOutcome.Synced
        assertEquals(0, sync4.insertedCount)
        assertEquals(0, sync4.updatedCount)
        assertEquals(1, sync4.unchangedCount)
        assertEquals(2, sync4.deletedCount)
        assertEquals(1, sync4.activePinCount)
        assertEquals(setOf("ext-2", "ext-3"), pinSender.deletedIds.toSet())
        assertNull(sentPinRepo.getPinById("ext-2"))
        assertNull(sentPinRepo.getPinById("ext-3"))
    }

    @Test
    fun `protects manual pins from ID collision and preserves existing remote pins on error`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val pinSender = RecordingTimelinePinSender()

        // Pre-existing manual pin with id "shared-id"
        sentPinRepo.saveSentPin(
            SentPinRecord(
                id = "shared-id",
                title = "My Manual Pin",
                startTimeMillis = fixedInstant.plusSeconds(3600).toEpochMilli(),
                source = EventSource.MANUAL.name,
                sentAtMillis = fixedInstant.toEpochMilli(),
            ),
        )

        val feed = settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "feed-conflict",
                url = "https://pins.example.com/pins.json",
                authType = RemoteFeedAuthType.NONE,
            ),
        )

        val httpClient = RemoteFeedHttpClient {
            RemoteFeedFetchResult.Success(
                body = """
                    {
                      "schemaVersion": 1,
                      "pins": [
                        {
                          "id": "shared-id",
                          "title": "Remote Attempt to Clobber",
                          "startTime": "2026-11-26T14:00:00Z"
                        },
                        {
                          "id": "remote-ok",
                          "title": "Safe Remote Pin",
                          "startTime": "2026-11-26T15:00:00Z"
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }

        val engine = RemoteFeedSyncEngine(
            settingsRepository = settingsRepo,
            secretStore = secretStore,
            httpClient = httpClient,
            pinSender = pinSender,
            sentPinRepository = sentPinRepo,
            clock = fixedClock,
        )

        val outcome = engine.syncFeed(feed.id) as RemoteFeedSyncOutcome.Synced
        assertEquals(1, outcome.insertedCount)
        assertEquals(1, outcome.skippedCount)
        assertEquals("My Manual Pin", sentPinRepo.getPinById("shared-id")?.title)
        assertEquals(EventSource.MANUAL, sentPinRepo.getPinById("shared-id")?.eventSource)
        assertEquals("Safe Remote Pin", sentPinRepo.getPinById("remote-ok")?.title)
        assertEquals(RemoteSyncStatus.PARTIAL_SUCCESS, settingsRepo.getFeed(feed.id)?.lastSyncStatus)
    }

    @Test
    fun `does not advance ETag when watch delivery fails so next sync retries`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val failingSender = RecordingTimelinePinSender(nextSendResult = PinSendResult.NoPebbleApp)

        val feed = settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "feed-retry",
                url = "https://pins.example.com/pins.json",
                authType = RemoteFeedAuthType.NONE,
            ),
        )
        val httpClient = RemoteFeedHttpClient {
            RemoteFeedFetchResult.Success(
                body = """
                    {
                      "schemaVersion": 1,
                      "pins": [
                        {"id": "p1", "title": "Will Retry", "startTime": "2026-11-26T14:00:00Z"}
                      ]
                    }
                """.trimIndent(),
                etag = "\"etag-new\"",
            )
        }

        val engine = RemoteFeedSyncEngine(
            settingsRepository = settingsRepo,
            secretStore = secretStore,
            httpClient = httpClient,
            pinSender = failingSender,
            sentPinRepository = sentPinRepo,
            clock = fixedClock,
        )

        val outcome = engine.syncFeed(feed.id) as RemoteFeedSyncOutcome.Synced
        assertEquals(1, outcome.deliveryFailureCount)
        assertNull("ETag must not be cached when watch delivery failed", settingsRepo.getFeed(feed.id)?.etag)
        assertEquals(RemoteSyncStatus.ERROR, settingsRepo.getFeed(feed.id)?.lastSyncStatus)
    }

    @Test
    fun `works end-to-end with SimulatedTimelinePinSender in watchless mode and deleteFeed cleans up`() = runTest {
        val simFile = File(tempFolder.newFolder(), "sim_timeline.preferences_pb")
        val simStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { simFile }
        val simRepo = DataStoreSimulatedTimelineRepository(dataStore = simStore)
        val simSender = SimulatedTimelinePinSender(
            simulatedRepository = simRepo,
            clockMillis = { fixedClock.millis() },
        )

        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()

        val feed = settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "feed-sim",
                name = "Simulated Feed",
                url = "https://pins.example.com/pins.json",
                authType = RemoteFeedAuthType.NONE,
            ),
        )
        val httpClient = RemoteFeedHttpClient {
            RemoteFeedFetchResult.Success(
                body = """
                    {
                      "schemaVersion": 1,
                      "pins": [
                        {"id": "sim-1", "title": "Watchless Pin", "startTime": "2026-11-26T14:00:00Z"}
                      ]
                    }
                """.trimIndent(),
            )
        }

        val engine = RemoteFeedSyncEngine(
            settingsRepository = settingsRepo,
            secretStore = secretStore,
            httpClient = httpClient,
            pinSender = simSender,
            sentPinRepository = sentPinRepo,
            clock = fixedClock,
        )

        val synced = engine.syncFeed(feed.id) as RemoteFeedSyncOutcome.Synced
        assertEquals(1, synced.insertedCount)
        val snapshot = simRepo.getSnapshot()
        assertEquals(1, snapshot.activePins.size)
        assertTrue(snapshot.activePins.single().isRemotePin)
        assertEquals(RemotePinSourceKey.forFeed(feed.id), snapshot.activePins.single().sourceKey)

        // Delete feed with removeSyncedPins = true removes simulated pin too
        val deleted = engine.deleteFeed(feed.id, removeSyncedPins = true)
        assertEquals(1, deleted.deletedPinCount)
        assertTrue(simRepo.getSnapshot().activePins.isEmpty())
        assertNull(settingsRepo.getFeed(feed.id))
    }

    @Test
    fun `syncAllEnabledFeeds respects onlyDue interval and RemoteFeedSchedulePolicy picks min interval`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val pinSender = RecordingTimelinePinSender()

        val dueFeed = settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "due",
                url = "https://a.example.com/pins.json",
                authType = RemoteFeedAuthType.NONE,
                pollingIntervalMinutes = 15,
                lastSyncAtMillis = fixedInstant.minusSeconds(20 * 60).toEpochMilli(),
            ),
        )
        settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "not-due",
                url = "https://b.example.com/pins.json",
                authType = RemoteFeedAuthType.NONE,
                pollingIntervalMinutes = 60,
                lastSyncAtMillis = fixedInstant.minusSeconds(10 * 60).toEpochMilli(),
            ),
        )
        settingsRepo.upsertFeed(
            RemotePinFeedConfig(
                id = "manual-only",
                url = "https://c.example.com/pins.json",
                authType = RemoteFeedAuthType.NONE,
                pollingIntervalMinutes = 0,
            ),
        )

        assertEquals(15L, RemoteFeedSchedulePolicy.computeMinIntervalMinutes(settingsRepo.getSettings().feeds))

        val fetchedUrls = mutableListOf<String>()
        val engine = RemoteFeedSyncEngine(
            settingsRepository = settingsRepo,
            secretStore = secretStore,
            httpClient = RemoteFeedHttpClient { req ->
                fetchedUrls.add(req.url)
                RemoteFeedFetchResult.Success(body = """{"schemaVersion":1,"pins":[]}""")
            },
            pinSender = pinSender,
            sentPinRepository = sentPinRepo,
            clock = fixedClock,
        )

        val results = engine.syncAllEnabledFeeds(onlyDue = true)
        assertEquals(listOf(dueFeed.id), results.map { it.feedId })
        assertEquals(listOf("https://a.example.com/pins.json"), fetchedUrls)
    }
}

internal class InMemoryRemoteFeedSettingsRepository : RemoteFeedSettingsRepository {
    private val state = MutableStateFlow(RemotePinFeedsSettings())

    override fun observeSettings(): Flow<RemotePinFeedsSettings> = state

    override suspend fun getSettings(): RemotePinFeedsSettings = state.value

    override suspend fun updateSettings(
        transform: (RemotePinFeedsSettings) -> RemotePinFeedsSettings,
    ): RemotePinFeedsSettings {
        val updated = transform(state.value).let { s -> s.copy(feeds = s.feeds.map { it.normalized() }) }
        state.value = updated
        return updated
    }

    override suspend fun upsertFeed(feed: RemotePinFeedConfig): RemotePinFeedConfig {
        val normalized = feed.normalized()
        updateSettings { current ->
            val idx = current.feeds.indexOfFirst { it.id == normalized.id }
            val next = if (idx >= 0) {
                current.feeds.toMutableList().apply { set(idx, normalized) }
            } else {
                current.feeds + normalized
            }
            current.copy(feeds = next)
        }
        return normalized
    }

    override suspend fun getFeed(feedId: String): RemotePinFeedConfig? =
        state.value.feeds.firstOrNull { it.id == feedId }

    override suspend fun updateFeed(
        feedId: String,
        transform: (RemotePinFeedConfig) -> RemotePinFeedConfig,
    ): RemotePinFeedConfig? {
        var result: RemotePinFeedConfig? = null
        updateSettings { current ->
            val idx = current.feeds.indexOfFirst { it.id == feedId }
            if (idx < 0) return@updateSettings current
            val transformed = transform(current.feeds[idx]).normalized()
            result = transformed
            current.copy(feeds = current.feeds.toMutableList().apply { set(idx, transformed) })
        }
        return result
    }

    override suspend fun deleteFeed(feedId: String) {
        updateSettings { current -> current.copy(feeds = current.feeds.filterNot { it.id == feedId }) }
    }
}

internal class InMemoryRemoteFeedSecretStore : RemoteFeedSecretStore {
    private val secrets = MutableStateFlow<Map<String, RemoteFeedSecret>>(emptyMap())

    override fun observeFeedIdsWithSecrets(): Flow<Set<String>> = secrets.map { it.keys }

    override suspend fun getSecret(feedId: String): RemoteFeedSecret? = secrets.value[feedId]

    override suspend fun putSecret(feedId: String, secret: RemoteFeedSecret) {
        if (secret.isBlank()) {
            deleteSecret(feedId)
        } else {
            secrets.value = secrets.value + (feedId to secret)
        }
    }

    override suspend fun deleteSecret(feedId: String) {
        secrets.value = secrets.value - feedId
    }

    override suspend fun clearAll() {
        secrets.value = emptyMap()
    }
}

internal class InMemorySentPinRepository : SentPinRepository {
    private val pins = MutableStateFlow<List<SentPinRecord>>(emptyList())

    override fun observeSentPins(): Flow<List<SentPinRecord>> = pins

    override suspend fun saveSentPin(record: SentPinRecord) {
        pins.value = (listOf(record) + pins.value.filterNot { it.id == record.id })
            .sortedByDescending { it.sentAtMillis }
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

internal class RecordingTimelinePinSender(
    var nextSendResult: PinSendResult? = null,
    var nextDeleteResult: PinDeleteResult? = null,
) : TimelinePinSender {
    val sentEvents = mutableListOf<TimelineEvent>()
    val deletedIds = mutableListOf<String>()

    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        sentEvents.add(event)
        return nextSendResult ?: PinSendResult.Success(event.id)
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        deletedIds.add(pinId)
        return nextDeleteResult ?: PinDeleteResult.Success(pinId)
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus =
        PebbleCompanionStatus.SimulatedNoWatch

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> =
        MutableStateFlow(emptyList())
}
