package xyz.hidasy.pebblepinmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedAuthType
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSecret
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSecretStore
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSettingsRepository
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSyncEngine
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSyncOutcome
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedSyncScheduler
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedUrlValidator
import xyz.hidasy.pebblepinmanager.remote.RemotePinFeedConfig
import xyz.hidasy.pebblepinmanager.remote.UrlValidationResult
import java.time.Clock

class RemoteFeedsViewModel(
    private val settingsRepository: RemoteFeedSettingsRepository,
    private val secretStore: RemoteFeedSecretStore,
    private val syncEngine: RemoteFeedSyncEngine,
    private val syncScheduler: RemoteFeedSyncScheduler,
    private val sentPinRepository: SentPinRepository,
    private val pinSender: TimelinePinSender,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val onPinsChanged: (suspend () -> Unit)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteFeedsUiState(zoneId = clock.zone))
    val uiState: StateFlow<RemoteFeedsUiState> = _uiState.asStateFlow()

    init {
        observeFeeds()
        observeSecretIds()
        observeRemotePins()
    }

    private fun observeFeeds() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.update { state ->
                    val existingIds = settings.feeds.mapTo(HashSet()) { it.id }
                    state.copy(
                        feeds = settings.feeds,
                        expandedDiagnosticsFeedIds = state.expandedDiagnosticsFeedIds.intersect(existingIds),
                    )
                }
                syncScheduler.updateSchedule(settings.feeds)
            }
        }
    }

    private fun observeSecretIds() {
        viewModelScope.launch {
            secretStore.observeFeedIdsWithSecrets().collect { ids ->
                _uiState.update { it.copy(feedIdsWithSecrets = ids) }
            }
        }
    }

    private fun observeRemotePins() {
        viewModelScope.launch {
            sentPinRepository.observeSentPins().collect { allPins ->
                val remotePins = allPins
                    .filter { it.isRemotePin }
                    .sortedBy { it.startTimeMillis }
                _uiState.update { it.copy(remotePins = remotePins) }
            }
        }
    }

    fun openAddFeedDialog() {
        _uiState.update {
            it.copy(
                editorState = RemoteFeedEditorState(),
                feedback = null,
            )
        }
    }

    fun openEditFeedDialog(feed: RemotePinFeedConfig) {
        val hasSecret = feed.id in _uiState.value.feedIdsWithSecrets
        _uiState.update {
            it.copy(
                editorState = RemoteFeedEditorState(
                    editingFeedId = feed.id,
                    name = feed.name,
                    url = feed.url,
                    authType = feed.authType,
                    apiKeyHeaderName = feed.apiKeyHeaderName,
                    basicUsername = feed.basicUsername,
                    secretInput = "",
                    hasStoredSecret = hasSecret,
                    clearStoredSecret = false,
                    allowCleartextHttp = feed.allowCleartextHttp,
                    isEnabled = feed.isEnabled,
                    pollingIntervalMinutes = feed.pollingIntervalMinutes,
                ),
                feedback = null,
            )
        }
    }

    fun dismissEditorDialog() {
        _uiState.update { it.copy(editorState = null) }
    }

    fun onEditorNameChange(value: String) {
        updateEditor { it.copy(name = value) }
    }

    fun onEditorUrlChange(value: String) {
        updateEditor {
            it.copy(
                url = value,
                urlError = null,
                testResult = null,
            )
        }
    }

    fun onEditorAuthTypeChange(authType: RemoteFeedAuthType) {
        updateEditor {
            it.copy(
                authType = authType,
                authError = null,
                headerNameError = null,
                testResult = null,
            )
        }
    }

    fun onEditorApiKeyHeaderNameChange(value: String) {
        updateEditor {
            it.copy(
                apiKeyHeaderName = value,
                headerNameError = null,
                testResult = null,
            )
        }
    }

    fun onEditorBasicUsernameChange(value: String) {
        updateEditor {
            it.copy(
                basicUsername = value,
                authError = null,
                testResult = null,
            )
        }
    }

    fun onEditorSecretInputChange(value: String) {
        updateEditor {
            it.copy(
                secretInput = value,
                clearStoredSecret = false,
                authError = null,
                testResult = null,
            )
        }
    }

    fun onEditorAllowCleartextHttpChange(allowed: Boolean) {
        updateEditor {
            it.copy(
                allowCleartextHttp = allowed,
                urlError = null,
                testResult = null,
            )
        }
    }

    fun onEditorEnabledChange(enabled: Boolean) {
        updateEditor { it.copy(isEnabled = enabled) }
    }

    fun onEditorPollingIntervalChange(minutes: Int) {
        updateEditor { it.copy(pollingIntervalMinutes = minutes) }
    }

    fun testEditorConnection() {
        val editor = _uiState.value.editorState ?: return
        if (editor.isTesting) return

        val validated = validateEditor(editor) ?: return
        updateEditor { it.copy(isTesting = true, testResult = null) }

        viewModelScope.launch {
            val effectiveSecret = resolveEffectiveSecret(editor)
            val result = syncEngine.testConnection(
                config = validated.draftConfig,
                secret = effectiveSecret,
            )
            updateEditor {
                it.copy(
                    isTesting = false,
                    testResult = result,
                )
            }
        }
    }

    fun saveEditorFeed() {
        val editor = _uiState.value.editorState ?: return
        if (editor.isTesting) return

        val validated = validateEditor(editor) ?: return

        viewModelScope.launch {
            val existing = editor.editingFeedId?.let { settingsRepository.getFeed(it) }
            val urlOrAuthChanged = existing == null ||
                existing.url != validated.draftConfig.url ||
                existing.authType != validated.draftConfig.authType ||
                existing.apiKeyHeaderName != validated.draftConfig.apiKeyHeaderName ||
                existing.basicUsername != validated.draftConfig.basicUsername ||
                editor.secretInput.isNotBlank()

            val feedToSave = if (existing != null) {
                existing.copy(
                    name = validated.draftConfig.name,
                    url = validated.draftConfig.url,
                    authType = validated.draftConfig.authType,
                    apiKeyHeaderName = validated.draftConfig.apiKeyHeaderName,
                    basicUsername = validated.draftConfig.basicUsername,
                    allowCleartextHttp = validated.draftConfig.allowCleartextHttp,
                    isEnabled = validated.draftConfig.isEnabled,
                    pollingIntervalMinutes = validated.draftConfig.pollingIntervalMinutes,
                    etag = if (urlOrAuthChanged) null else existing.etag,
                    lastModified = if (urlOrAuthChanged) null else existing.lastModified,
                    lastError = if (urlOrAuthChanged) null else existing.lastError,
                )
            } else {
                validated.draftConfig
            }

            val saved = settingsRepository.upsertFeed(feedToSave)

            when (saved.authType) {
                RemoteFeedAuthType.NONE -> {
                    secretStore.deleteSecret(saved.id)
                }

                RemoteFeedAuthType.BEARER_TOKEN,
                RemoteFeedAuthType.API_KEY_HEADER,
                -> {
                    if (editor.secretInput.isNotBlank()) {
                        secretStore.putSecret(
                            saved.id,
                            RemoteFeedSecret(token = editor.secretInput.trim()),
                        )
                    } else if (editor.clearStoredSecret) {
                        secretStore.deleteSecret(saved.id)
                    }
                }

                RemoteFeedAuthType.BASIC -> {
                    if (editor.secretInput.isNotBlank()) {
                        secretStore.putSecret(
                            saved.id,
                            RemoteFeedSecret(basicPassword = editor.secretInput),
                        )
                    } else if (editor.clearStoredSecret) {
                        secretStore.deleteSecret(saved.id)
                    }
                }
            }

            val updatedFeeds = settingsRepository.getSettings().feeds
            syncScheduler.updateSchedule(updatedFeeds)

            _uiState.update {
                it.copy(
                    editorState = null,
                    feedback = SendFeedback.Success(
                        pinId = saved.id,
                        headline = if (editor.isEditing) "Remote feed updated" else "Remote feed added",
                        details = "\"${saved.displayName}\" saved locally. Credentials are encrypted on-device and excluded from Android Auto Backup.",
                    ),
                )
            }
        }
    }

    fun toggleFeedEnabled(feedId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateFeed(feedId) { it.copy(isEnabled = enabled) }
            syncScheduler.updateSchedule(settingsRepository.getSettings().feeds)
        }
    }

    fun toggleFeedDiagnostics(feedId: String) {
        _uiState.update { state ->
            val updated = if (feedId in state.expandedDiagnosticsFeedIds) {
                state.expandedDiagnosticsFeedIds - feedId
            } else {
                state.expandedDiagnosticsFeedIds + feedId
            }
            state.copy(expandedDiagnosticsFeedIds = updated)
        }
    }

    fun syncFeed(feedId: String, forceFullFetch: Boolean = false) {
        if (feedId in _uiState.value.syncingFeedIds || _uiState.value.isSyncingAll) return
        _uiState.update {
            it.copy(
                syncingFeedIds = it.syncingFeedIds + feedId,
                feedback = null,
            )
        }

        viewModelScope.launch {
            val outcome = syncEngine.syncFeed(feedId = feedId, forceFullFetch = forceFullFetch)
            _uiState.update { state ->
                state.copy(
                    syncingFeedIds = state.syncingFeedIds - feedId,
                    feedback = formatOutcomeFeedback(outcome),
                )
            }
        }
    }

    fun syncAllEnabledFeeds() {
        if (_uiState.value.isAnySyncInProgress) return
        val enabled = _uiState.value.feeds.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            _uiState.update {
                it.copy(
                    feedback = SendFeedback.Error(
                        headline = "No enabled remote feeds",
                        details = "Add or enable at least one remote JSON feed before syncing.",
                    ),
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isSyncingAll = true,
                feedback = null,
            )
        }

        viewModelScope.launch {
            val outcomes = syncEngine.syncAllEnabledFeeds(onlyDue = false)
            val errors = outcomes.filterIsInstance<RemoteFeedSyncOutcome.Error>()
            val synced = outcomes.filterIsInstance<RemoteFeedSyncOutcome.Synced>()
            val notModified = outcomes.filterIsInstance<RemoteFeedSyncOutcome.NotModified>()

            val feedback = if (errors.isEmpty()) {
                val added = synced.sumOf { it.insertedCount }
                val updated = synced.sumOf { it.updatedCount }
                val deleted = synced.sumOf { it.deletedCount }
                SendFeedback.Success(
                    pinId = "sync-all",
                    headline = "Synced ${outcomes.size} remote feed(s)",
                    details = "+$added added, ~$updated updated, -$deleted deleted (${notModified.size} up to date via 304).",
                )
            } else {
                SendFeedback.Error(
                    headline = "${errors.size} of ${outcomes.size} feed(s) failed to sync",
                    details = errors.joinToString(" • ") { "${it.feedName}: ${it.message}" },
                )
            }

            _uiState.update {
                it.copy(
                    isSyncingAll = false,
                    feedback = feedback,
                )
            }
        }
    }

    fun deleteFeed(feedId: String, removeSyncedPins: Boolean) {
        viewModelScope.launch {
            val feedName = _uiState.value.feeds.firstOrNull { it.id == feedId }?.displayName ?: feedId
            val result = syncEngine.deleteFeed(feedId = feedId, removeSyncedPins = removeSyncedPins)
            syncScheduler.updateSchedule(settingsRepository.getSettings().feeds)

            val feedback = if (result.errors.isEmpty()) {
                val pinNote = if (removeSyncedPins) {
                    " Removed ${result.deletedPinCount} synced pin(s) from your timeline."
                } else {
                    ""
                }
                SendFeedback.Success(
                    pinId = feedId,
                    headline = "Remote feed removed",
                    details = "\"$feedName\" and its stored credentials were deleted.$pinNote",
                )
            } else {
                SendFeedback.Error(
                    headline = "Feed removed with warnings",
                    details = "\"$feedName\" was removed, but ${result.errors.size} pin delete(s) failed: ${result.errors.first()}",
                )
            }

            _uiState.update { it.copy(feedback = feedback) }
        }
    }

    fun deleteRemotePin(pinId: String) {
        val current = _uiState.value
        if (current.deletingPinId != null) return
        val sanitizedId = pinId.trim()
        if (sanitizedId.isEmpty()) return

        val cachedTitle = current.remotePins.firstOrNull { it.id == sanitizedId }?.title
        val displayName = cachedTitle?.let { "\"$it\"" } ?: "Pin $sanitizedId"

        _uiState.update {
            it.copy(
                deletingPinId = sanitizedId,
                feedback = null,
            )
        }

        viewModelScope.launch {
            when (val result = pinSender.deletePin(sanitizedId)) {
                is PinDeleteResult.Success,
                is PinDeleteResult.UnknownPin,
                -> {
                    sentPinRepository.deletePinRecord(sanitizedId)
                    onPinsChanged?.let { runCatching { it() } }
                    val isSimulated = pinSender.getCompanionStatus() is PebbleCompanionStatus.SimulatedNoWatch
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Success(
                                pinId = sanitizedId,
                                headline = if (isSimulated) {
                                    "Remote pin removed from Simulated Timeline"
                                } else {
                                    "Remote pin removed from Pebble Timeline"
                                },
                                details = "$displayName ($sanitizedId) was deleted locally. Note: it may reappear on the next full sync if still present in the remote JSON feed.",
                            ),
                        )
                    }
                }

                PinDeleteResult.NoPebbleApp -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Pebble companion app not available",
                                details = "Install or open the Core Devices Pebble app or enable \"Run without a watch\" in Settings.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.NoPermissions -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Companion app permission denied",
                                details = "Rejected delete for watchapp UUID ${result.watchappUuid}.",
                            ),
                        )
                    }
                }

                PinDeleteResult.UnsupportedAction -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Timeline delete unsupported",
                                details = "Update your Pebble companion app to support PebbleKit 2 pin deletion.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.UnexpectedError -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Failed to delete remote pin",
                                details = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    private fun updateEditor(transform: (RemoteFeedEditorState) -> RemoteFeedEditorState) {
        _uiState.update { state ->
            val current = state.editorState ?: return@update state
            state.copy(editorState = transform(current))
        }
    }

    private suspend fun resolveEffectiveSecret(editor: RemoteFeedEditorState): RemoteFeedSecret? {
        return when (editor.authType) {
            RemoteFeedAuthType.NONE -> null
            RemoteFeedAuthType.BEARER_TOKEN,
            RemoteFeedAuthType.API_KEY_HEADER,
            -> {
                if (editor.secretInput.isNotBlank()) {
                    RemoteFeedSecret(token = editor.secretInput.trim())
                } else if (editor.editingFeedId != null && !editor.clearStoredSecret) {
                    secretStore.getSecret(editor.editingFeedId)
                } else {
                    null
                }
            }

            RemoteFeedAuthType.BASIC -> {
                if (editor.secretInput.isNotBlank()) {
                    RemoteFeedSecret(basicPassword = editor.secretInput)
                } else if (editor.editingFeedId != null && !editor.clearStoredSecret) {
                    secretStore.getSecret(editor.editingFeedId)
                } else {
                    null
                }
            }
        }
    }

    private data class ValidatedDraft(
        val draftConfig: RemotePinFeedConfig,
    )

    private fun validateEditor(editor: RemoteFeedEditorState): ValidatedDraft? {
        val urlError = when (
            val check = RemoteFeedUrlValidator.validate(
                rawUrl = editor.url,
                allowCleartextHttp = editor.allowCleartextHttp,
            )
        ) {
            is UrlValidationResult.Valid -> null
            is UrlValidationResult.Invalid -> check.message
        }

        val headerNameError = if (editor.authType == RemoteFeedAuthType.API_KEY_HEADER) {
            RemoteFeedUrlValidator.validateHeaderName(editor.apiKeyHeaderName)
        } else {
            null
        }

        val hasExistingUsableSecret = editor.isEditing && editor.hasStoredSecret && !editor.clearStoredSecret
        val authError = when (editor.authType) {
            RemoteFeedAuthType.NONE -> null
            RemoteFeedAuthType.BEARER_TOKEN -> {
                if (editor.secretInput.isBlank() && !hasExistingUsableSecret) {
                    "Bearer token is required."
                } else {
                    null
                }
            }
            RemoteFeedAuthType.API_KEY_HEADER -> {
                if (editor.secretInput.isBlank() && !hasExistingUsableSecret) {
                    "API key value is required."
                } else {
                    null
                }
            }
            RemoteFeedAuthType.BASIC -> {
                when {
                    editor.basicUsername.isBlank() -> "Username is required for HTTP Basic auth."
                    editor.basicUsername.contains(':') -> "Username must not contain ':'."
                    editor.secretInput.isBlank() && !hasExistingUsableSecret -> "Password is required for HTTP Basic auth."
                    else -> null
                }
            }
        }

        if (urlError != null || headerNameError != null || authError != null) {
            updateEditor {
                it.copy(
                    urlError = urlError,
                    headerNameError = headerNameError,
                    authError = authError,
                )
            }
            return null
        }

        val draftConfig = RemotePinFeedConfig(
            id = editor.editingFeedId ?: RemotePinFeedConfig.generateFeedId(),
            name = editor.name.trim(),
            url = editor.url.trim(),
            authType = editor.authType,
            apiKeyHeaderName = editor.apiKeyHeaderName.trim().ifEmpty { RemotePinFeedConfig.DEFAULT_API_KEY_HEADER },
            basicUsername = editor.basicUsername.trim(),
            allowCleartextHttp = editor.allowCleartextHttp,
            isEnabled = editor.isEnabled,
            pollingIntervalMinutes = RemotePinFeedConfig.normalizePollingInterval(editor.pollingIntervalMinutes),
        )
        return ValidatedDraft(draftConfig = draftConfig)
    }

    private fun formatOutcomeFeedback(outcome: RemoteFeedSyncOutcome): SendFeedback {
        return when (outcome) {
            is RemoteFeedSyncOutcome.NotModified -> SendFeedback.Success(
                pinId = outcome.feedId,
                headline = "\"${outcome.feedName}\" is up to date",
                details = "Server returned 304 Not Modified (${outcome.activePinCount} active remote pin(s)).",
            )

            is RemoteFeedSyncOutcome.Synced -> {
                val counts = "+${outcome.insertedCount} added, ~${outcome.updatedCount} updated, -${outcome.deletedCount} deleted"
                val extra = buildList {
                    if (outcome.skippedCount > 0) add("${outcome.skippedCount} invalid skipped")
                    if (outcome.deliveryFailureCount > 0) add("${outcome.deliveryFailureCount} watch delivery error(s)")
                }
                val suffix = if (extra.isNotEmpty()) " (${extra.joinToString(", ")})" else ""
                if (outcome.deliveryFailureCount > 0) {
                    SendFeedback.Error(
                        headline = "\"${outcome.feedName}\" synced with delivery errors",
                        details = "$counts$suffix. Check Pebble companion app status or expand feed diagnostics.",
                    )
                } else {
                    SendFeedback.Success(
                        pinId = outcome.feedId,
                        headline = "\"${outcome.feedName}\" synced",
                        details = "$counts$suffix • ${outcome.activePinCount} active pin(s).",
                    )
                }
            }

            is RemoteFeedSyncOutcome.Error -> SendFeedback.Error(
                headline = "Failed to sync \"${outcome.feedName}\"",
                details = outcome.message,
            )
        }
    }
}

class RemoteFeedsViewModelFactory(
    private val settingsRepository: RemoteFeedSettingsRepository,
    private val secretStore: RemoteFeedSecretStore,
    private val syncEngine: RemoteFeedSyncEngine,
    private val syncScheduler: RemoteFeedSyncScheduler,
    private val sentPinRepository: SentPinRepository,
    private val pinSender: TimelinePinSender,
    private val onPinsChanged: (suspend () -> Unit)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RemoteFeedsViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return RemoteFeedsViewModel(
            settingsRepository = settingsRepository,
            secretStore = secretStore,
            syncEngine = syncEngine,
            syncScheduler = syncScheduler,
            sentPinRepository = sentPinRepository,
            pinSender = pinSender,
            onPinsChanged = onPinsChanged,
        ) as T
    }
}
