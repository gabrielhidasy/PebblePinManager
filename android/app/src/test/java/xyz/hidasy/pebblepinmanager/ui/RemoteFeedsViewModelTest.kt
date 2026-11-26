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

    @Test
    fun `instant HTTPS QR pairing saves encrypted token, syncs pins, and updates existing feed on re-pair`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val pinSender = RecordingTimelinePinSender()
        val scheduledSnapshots = mutableListOf<List<RemotePinFeedConfig>>()
        val scheduler = RemoteFeedSyncScheduler { feeds -> scheduledSnapshots.add(feeds) }
        val receivedTokens = mutableListOf<String?>()

        val httpClient = RemoteFeedHttpClient { req ->
            receivedTokens.add(req.secret?.token)
            RemoteFeedFetchResult.Success(
                body = """
                    {
                      "schemaVersion": 1,
                      "pins": [
                        {
                          "id": "qr-pin-1",
                          "title": "Paired Event",
                          "startTime": "2026-11-26T15:00:00Z"
                        }
                      ]
                    }
                """.trimIndent(),
                etag = "\"qr-etag-1\"",
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

        // Invalid QR returns error and leaves state unchanged.
        val invalidErr = viewModel.onPairingPayloadScanned("https://example.com/unrelated")
        assertNotNull(invalidErr)
        assertTrue(viewModel.uiState.value.feeds.isEmpty())

        // Valid HTTPS QR pairs immediately and runs initial sync.
        val firstQr =
            "pebblepin://pair?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=tok_qr_1&name=Home%20Server"
        assertNull(viewModel.onPairingPayloadScanned(firstQr, fromDeepLink = false))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingPairing)
        assertEquals(1, viewModel.uiState.value.feeds.size)
        val pairedFeed = viewModel.uiState.value.feeds.single()
        assertEquals("Home Server", pairedFeed.name)
        assertEquals("https://pins.example.com/pins.json", pairedFeed.url)
        assertEquals("tok_qr_1", secretStore.getSecret(pairedFeed.id)?.token)
        assertEquals(listOf("qr-pin-1"), viewModel.uiState.value.remotePins.map { it.id })
        assertTrue(viewModel.uiState.value.feedback is SendFeedback.Success)

        // Scanning a rotated token QR for the same URL updates in place instead of duplicating.
        val rotatedQr =
            "pebblepin://pair?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=tok_qr_2&name=Home%20Server%20v2"
        assertNull(viewModel.onPairingPayloadScanned(rotatedQr, fromDeepLink = false))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.feeds.size)
        assertEquals(pairedFeed.id, viewModel.uiState.value.feeds.single().id)
        assertEquals("Home Server v2", viewModel.uiState.value.feeds.single().name)
        assertEquals("tok_qr_2", secretStore.getSecret(pairedFeed.id)?.token)
        assertEquals(listOf("tok_qr_1", "tok_qr_2"), receivedTokens)
    }

    @Test
    fun `cleartext HTTP QR and deep links require confirmation before saving`() = runTest {
        val settingsRepo = InMemoryRemoteFeedSettingsRepository()
        val secretStore = InMemoryRemoteFeedSecretStore()
        val sentPinRepo = InMemorySentPinRepository()
        val pinSender = RecordingTimelinePinSender()
        val scheduler = RemoteFeedSyncScheduler { }

        val httpClient = RemoteFeedHttpClient { _ ->
            RemoteFeedFetchResult.Success(
                body = """{"schemaVersion": 1, "pins": []}""",
                etag = "\"e-empty\"",
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

        // HTTP LAN QR opens confirmation dialog instead of saving silently.
        val httpQr = "pebblepin://pair?v=1&url=http%3A%2F%2F10.0.0.20%3A8080%2Fpins.json&auth=bearer&token=lan_tok&allowHttp=1"
        assertNull(viewModel.onPairingPayloadScanned(httpQr, fromDeepLink = false))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.feeds.isEmpty())
        val pendingHttp = viewModel.uiState.value.pendingPairing
        assertNotNull(pendingHttp)
        assertTrue(pendingHttp!!.payload.isCleartextHttp)

        // Confirming saves with allowCleartextHttp = true and syncs.
        viewModel.confirmPendingPairing()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingPairing)
        assertEquals(1, viewModel.uiState.value.feeds.size)
        assertTrue(viewModel.uiState.value.feeds.single().allowCleartextHttp)

        // Deep link HTTPS URI also shows confirmation, and Customize opens pre-filled editor.
        val deepLink = "pebblepin://pair?v=1&url=https%3A%2F%2Fdl.example.com%2Fpins.json&auth=bearer&token=dl_tok&name=DeepLink"
        assertNull(viewModel.onPairingPayloadScanned(deepLink, fromDeepLink = true))
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingPairing)

        viewModel.customizePendingPairing()
        assertNull(viewModel.uiState.value.pendingPairing)
        val editor = viewModel.uiState.value.editorState
        assertNotNull(editor)
        assertEquals("DeepLink", editor!!.name)
        assertEquals("https://dl.example.com/pins.json", editor.url)
        assertEquals("dl_tok", editor.secretInput)
    }
}
