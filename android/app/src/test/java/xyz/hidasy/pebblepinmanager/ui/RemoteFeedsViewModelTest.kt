package xyz.hidasy.pebblepinmanager.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.remote.InMemoryRemoteFeedSecretStore
import xyz.hidasy.pebblepinmanager.remote.InMemoryRemoteFeedSettingsRepository
import xyz.hidasy.pebblepinmanager.remote.InMemorySentPinRepository
import xyz.hidasy.pebblepinmanager.remote.RecordingTimelinePinSender
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedAuthType
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedFetchResult
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedHttpClient
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSyncEngine
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSyncScheduler
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedTestResult
import xyz.hidasy.pebblepinmanager.remote.RemotePinFeedConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFeedsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fixedClock = Clock.fixed(Instant.parse("2026-11-25T22:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `add, test, edit keeping stored secret, sync, and isolate remote pins from manual pins`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val pinSender = RecordingTimelinePinSender()
        val scheduledSnapshots = mutableListOf<List<RemotePinFeedConfig>>()
        val scheduler = RemoteFeedSyncScheduler { feeds -> scheduledSnapshots.add(feeds) }

        // Pre-existing manual pin must not appear in remotePins
        sentPinRepo.saveSentPin(
            SentPinRecord(
                id = "manual-1",
                title = "Manual Event",
                startTimeMillis = fixedClock.millis() + 3_600_000L,
                source = EventSource.MANUAL.name,
                sentAtMillis = fixedClock.millis(),
            ),
        )

        val httpClient = RemoteFeedHttpClient { req ->
            assertEquals("tok_abc", req.secret?.token)
            RemoteFeedFetchResult.Success(
                body = """
                    {
                      "schemaVersion": 1,
                      "pins": [
                        {
                          "id": "rem-1",
                          "title": "Remote Sync Pin",
                          "startTime": "2026-11-26T14:00:00Z"
                        }
                      ]
                    }
                """.trimIndent(),
                etag = "\"e1\"",
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

        val viewModel = RemoteFeedsViewModel(
            settingsRepository = settingsRepo,
            secretStore = secretStore,
            syncEngine = engine,
            syncScheduler = scheduler,
            sentPinRepository = sentPinRepo,
            pinSender = pinSender,
            clock = fixedClock,
        )
        advanceUntilIdle()

        // Open Add Feed dialog and verify validation blocks missing URL / token
        viewModel.openAddFeedDialog()
        viewModel.saveEditorFeed()
        assertNotNull(viewModel.uiState.value.editorState?.urlError)
        assertNotNull(viewModel.uiState.value.editorState?.authError)

        // Fill valid HTTPS feed + Bearer token and run Test Connection
        viewModel.onEditorNameChange("My Server")
        viewModel.onEditorUrlChange("https://pins.example.com/pins.json")
        viewModel.onEditorAuthTypeChange(RemoteFeedAuthType.BEARER_TOKEN)
        viewModel.onEditorSecretInputChange("tok_abc")
        viewModel.onEditorPollingIntervalChange(30)

        viewModel.testEditorConnection()
        advanceUntilIdle()
        val testResult = viewModel.uiState.value.editorState?.testResult
        assertTrue(testResult is RemoteFeedTestResult.Success)
        assertEquals(1, (testResult as RemoteFeedTestResult.Success).validPinCount)

        // Save feed
        viewModel.saveEditorFeed()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.editorState)
        assertEquals(1, viewModel.uiState.value.feeds.size)
        val savedFeed = viewModel.uiState.value.feeds.single()
        assertEquals("tok_abc", secretStore.getSecret(savedFeed.id)?.token)
        assertTrue(scheduledSnapshots.last().any { it.id == savedFeed.id && it.pollingIntervalMinutes == 30 })

        // Edit feed name without re-typing secret -> stored secret must remain intact
        viewModel.openEditFeedDialog(savedFeed)
        assertTrue(viewModel.uiState.value.editorState?.hasStoredSecret == true)
        viewModel.onEditorNameChange("Renamed Server")
        viewModel.saveEditorFeed()
        advanceUntilIdle()
        assertEquals("Renamed Server", viewModel.uiState.value.feeds.single().name)
        assertEquals("tok_abc", secretStore.getSecret(savedFeed.id)?.token)

        // Sync now -> remote pin appears in remotePins, manual pin stays separate
        viewModel.syncFeed(savedFeed.id)
        advanceUntilIdle()
        assertEquals(listOf("rem-1"), viewModel.uiState.value.remotePins.map { it.id })

        // Verify PinComposerViewModel excludes REMOTE_URL pins from manual recent pins
        val composerVm = PinComposerViewModel(
            pinSender = pinSender,
            repository = sentPinRepo,
            clock = fixedClock,
        )
        advanceUntilIdle()
        assertEquals(listOf("manual-1"), composerVm.uiState.value.sentPins.map { it.id })
    }
}
