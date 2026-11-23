package xyz.hidasy.pebblepinmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.notifications.AppNotificationRuleConfig
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.NotificationAccessStatusChecker
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexExplorer
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule
import xyz.hidasy.pebblepinmanager.notifications.NotificationRulePresets
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleValidator
import xyz.hidasy.pebblepinmanager.notifications.NotificationSettingsRepository
import xyz.hidasy.pebblepinmanager.notifications.NotificationTextField
import xyz.hidasy.pebblepinmanager.notifications.PebbleNotificationListenerService
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRecord
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRepository
import java.time.Clock

class DevModeViewModel(
    private val rawNotificationRepository: RawNotificationRepository,
    private val settingsRepository: NotificationSettingsRepository,
    private val accessStatusChecker: NotificationAccessStatusChecker,
    private val installedAppsProvider: InstalledAppsProvider,
    private val listenerConnectedFlow: Flow<Boolean> = flowOf(false),
    private val activeNotificationsProvider: () -> List<RawNotificationRecord> = {
        PebbleNotificationListenerService.snapshotActiveNotifications()
    },
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DevModeUiState(
            isNotificationAccessEnabled = accessStatusChecker.isNotificationAccessEnabled(),
            zoneId = clock.zone,
        ),
    )
    val uiState: StateFlow<DevModeUiState> = _uiState.asStateFlow()

    private var draftEvaluationJob: Job? = null

    init {
        observeRawNotifications()
        observeSettings()
        observeListenerConnection()
        refreshInstalledApps()
    }

    private fun observeRawNotifications() {
        viewModelScope.launch {
            rawNotificationRepository.observeNotifications().collect { records ->
                _uiState.update { state ->
                    val validSelectedId = state.selectedNotificationId
                        ?.takeIf { id -> records.any { it.id == id } }
                    state.copy(
                        rawNotifications = records,
                        selectedNotificationId = validSelectedId,
                    )
                }
                refreshExploration()
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.update { it.copy(settings = settings) }
                refreshExploration()
            }
        }
    }

    private fun observeListenerConnection() {
        viewModelScope.launch {
            listenerConnectedFlow.collect { connected ->
                _uiState.update { it.copy(isListenerConnected = connected) }
            }
        }
    }

    fun refreshPermissionStatus() {
        _uiState.update {
            it.copy(isNotificationAccessEnabled = accessStatusChecker.isNotificationAccessEnabled())
        }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            val apps = runCatching { installedAppsProvider.loadInstalledApps() }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(installedApps = apps) }
        }
    }

    fun snapshotActiveNotifications() {
        viewModelScope.launch {
            val active = runCatching { activeNotificationsProvider() }.getOrDefault(emptyList())
            if (active.isEmpty()) {
                val hint = if (!_uiState.value.isNotificationAccessEnabled) {
                    "Grant Notification Access first to snapshot active notifications."
                } else if (!_uiState.value.isListenerConnected) {
                    "Notification listener service is not connected yet."
                } else {
                    "No active notifications currently in the status bar."
                }
                _uiState.update { it.copy(statusMessage = hint) }
                return@launch
            }
            rawNotificationRepository.recordAll(active)
            _uiState.update {
                it.copy(statusMessage = "Loaded ${active.size} active notification(s) into Dev Inspector.")
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onHideOngoingChange(hide: Boolean) {
        _uiState.update { it.copy(hideOngoing = hide) }
    }

    fun onHideGroupSummaryChange(hide: Boolean) {
        _uiState.update { it.copy(hideGroupSummary = hide) }
    }

    fun onAllowlistedOnlyChange(allowlistedOnly: Boolean) {
        _uiState.update { it.copy(allowlistedOnly = allowlistedOnly) }
    }

    fun onSelectNotification(recordId: String) {
        _uiState.update {
            it.copy(
                selectedNotificationId = recordId,
                statusMessage = null,
            )
        }
        refreshExploration()
    }

    fun onClearSelection() {
        _uiState.update {
            it.copy(
                selectedNotificationId = null,
                scratchpadResult = null,
                draftExploration = null,
                statusMessage = null,
            )
        }
    }

    fun onDeleteNotification(recordId: String) {
        viewModelScope.launch {
            rawNotificationRepository.deleteNotification(recordId)
        }
    }

    fun onClearAllNotifications() {
        viewModelScope.launch {
            rawNotificationRepository.clearAll()
            _uiState.update {
                it.copy(
                    selectedNotificationId = null,
                    scratchpadResult = null,
                    draftExploration = null,
                    statusMessage = "Cleared captured raw notifications.",
                )
            }
        }
    }

    fun onScratchpadPatternChange(pattern: String) {
        _uiState.update { it.copy(scratchpadPattern = pattern, statusMessage = null) }
        refreshScratchpadResult()
    }

    fun onScratchpadFieldChange(field: NotificationTextField) {
        _uiState.update { it.copy(scratchpadField = field) }
        refreshScratchpadResult()
    }

    fun onScratchpadIgnoreCaseChange(ignoreCase: Boolean) {
        _uiState.update { it.copy(scratchpadIgnoreCase = ignoreCase) }
        refreshScratchpadResult()
    }

    fun onApplyScratchpadToMatch() {
        val state = _uiState.value
        val pattern = state.scratchpadPattern.trim()
        if (pattern.isEmpty()) return
        onDraftRuleChange {
            it.copy(
                matchPattern = pattern,
                matchTarget = state.scratchpadField,
            )
        }
    }

    fun onApplyScratchpadToExclude() {
        val state = _uiState.value
        val pattern = state.scratchpadPattern.trim()
        if (pattern.isEmpty()) return
        onDraftRuleChange {
            it.copy(
                excludePattern = pattern,
                excludeTarget = state.scratchpadField,
            )
        }
    }

    fun onApplyScratchpadToTitle() {
        val state = _uiState.value
        val pattern = state.scratchpadPattern.trim()
        if (pattern.isEmpty()) return
        onDraftRuleChange {
            it.copy(
                titleRegexPattern = pattern,
                titleSource = state.scratchpadField,
            )
        }
    }

    fun onApplyScratchpadToDescription() {
        val state = _uiState.value
        val pattern = state.scratchpadPattern.trim()
        if (pattern.isEmpty()) return
        onDraftRuleChange {
            it.copy(
                descriptionRegexPattern = pattern,
                descriptionSource = state.scratchpadField,
            )
        }
    }

    fun onApplyScratchpadToTime() {
        val pattern = _uiState.value.scratchpadPattern.trim()
        if (pattern.isEmpty()) return
        onDraftRuleChange { it.copy(timeRegexPattern = pattern) }
    }

    fun onDraftRuleChange(transform: (NotificationRegexRule) -> NotificationRegexRule) {
        _uiState.update { state ->
            val updated = transform(state.draftRule)
            state.copy(
                draftRule = updated,
                draftRuleValidation = NotificationRuleValidator.validateRule(updated),
                statusMessage = null,
            )
        }
        refreshDraftExploration()
    }

    fun onApplySubjectAsTitlePreset() {
        val preset = NotificationRulePresets.subjectAsTitleRule()
        onDraftRuleChange { current ->
            preset.copy(
                id = current.id,
                matchPattern = current.matchPattern,
                matchTarget = current.matchTarget,
            )
        }
    }

    fun onLoadExistingRule(rule: NotificationRegexRule) {
        _uiState.update { state ->
            state.copy(
                draftRule = rule,
                draftRuleValidation = NotificationRuleValidator.validateRule(rule),
                statusMessage = "Loaded rule \"${rule.displayName()}\" into explorer.",
            )
        }
        refreshDraftExploration()
    }

    fun onResetDraftRule() {
        val fresh = NotificationRegexRule()
        _uiState.update {
            it.copy(
                draftRule = fresh,
                draftRuleValidation = NotificationRuleValidator.validateRule(fresh),
                statusMessage = null,
            )
        }
        refreshDraftExploration()
    }

    fun onAllowlistSelectedPackage() {
        val selected = _uiState.value.selectedNotification ?: return
        val pkg = selected.sourcePackage.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(allowedPackages = current.allowedPackages + pkg)
            }
            _uiState.update {
                it.copy(statusMessage = "Added ${_uiState.value.displayNameFor(selected)} to allowed apps.")
            }
        }
    }

    fun onSaveDraftAsAppRule() {
        val state = _uiState.value
        val selected = state.selectedNotification ?: return
        val pkg = selected.sourcePackage.trim()
        if (pkg.isEmpty()) return
        val normalized = state.draftRule.normalized()
        val validation = NotificationRuleValidator.validateRule(normalized)
        if (!validation.isValid) {
            _uiState.update { it.copy(draftRuleValidation = validation) }
            return
        }

        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val existing = current.appRules[pkg] ?: AppNotificationRuleConfig(packageName = pkg)
                val updatedRules = upsertRule(existing.regexRules, normalized)
                val updatedConfig = existing.copy(regexRules = updatedRules)
                current.copy(
                    allowedPackages = current.allowedPackages + pkg,
                    appRules = current.appRules + (pkg to updatedConfig),
                )
            }
            _uiState.update {
                it.copy(
                    draftRule = normalized,
                    statusMessage = "Saved \"${normalized.displayName()}\" to ${state.displayNameFor(selected)} rules.",
                )
            }
        }
    }

    fun onSaveDraftAsGlobalRule() {
        val state = _uiState.value
        val normalized = state.draftRule.normalized()
        val validation = NotificationRuleValidator.validateRule(normalized)
        if (!validation.isValid) {
            _uiState.update { it.copy(draftRuleValidation = validation) }
            return
        }

        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(globalRegexRules = upsertRule(current.globalRegexRules, normalized))
            }
            _uiState.update {
                it.copy(
                    draftRule = normalized,
                    statusMessage = "Saved \"${normalized.displayName()}\" to global regex rules.",
                )
            }
        }
    }

    fun onDismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun refreshExploration() {
        refreshScratchpadResult()
        refreshDraftExploration()
    }

    private fun refreshScratchpadResult() {
        val state = _uiState.value
        val selected = state.selectedNotification
        if (selected == null) {
            _uiState.update { it.copy(scratchpadResult = null) }
            return
        }
        val result = NotificationRegexExplorer.evaluatePattern(
            pattern = state.scratchpadPattern,
            targetField = state.scratchpadField,
            textFields = selected.textFields(),
            ignoreCase = state.scratchpadIgnoreCase,
        )
        _uiState.update { it.copy(scratchpadResult = result) }
    }

    private fun refreshDraftExploration() {
        draftEvaluationJob?.cancel()
        val state = _uiState.value
        val selected = state.selectedNotification
        if (selected == null) {
            _uiState.update { it.copy(draftExploration = null) }
            return
        }
        draftEvaluationJob = viewModelScope.launch {
            val exploration = NotificationRegexExplorer.evaluateDraftRule(
                record = selected,
                draftRule = state.draftRule,
                currentSettings = state.settings,
                zoneId = state.zoneId,
                clockMillis = clock.millis(),
            )
            _uiState.update { it.copy(draftExploration = exploration) }
        }
    }

    private fun upsertRule(
        rules: List<NotificationRegexRule>,
        rule: NotificationRegexRule,
    ): List<NotificationRegexRule> {
        val index = rules.indexOfFirst { it.id == rule.id }
        return if (index >= 0) {
            rules.toMutableList().apply { set(index, rule) }
        } else {
            rules + rule
        }
    }
}

class DevModeViewModelFactory(
    private val rawNotificationRepository: RawNotificationRepository,
    private val settingsRepository: NotificationSettingsRepository,
    private val accessStatusChecker: NotificationAccessStatusChecker,
    private val installedAppsProvider: InstalledAppsProvider,
    private val listenerConnectedFlow: Flow<Boolean>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DevModeViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return DevModeViewModel(
            rawNotificationRepository = rawNotificationRepository,
            settingsRepository = settingsRepository,
            accessStatusChecker = accessStatusChecker,
            installedAppsProvider = installedAppsProvider,
            listenerConnectedFlow = listenerConnectedFlow,
        ) as T
    }
}
