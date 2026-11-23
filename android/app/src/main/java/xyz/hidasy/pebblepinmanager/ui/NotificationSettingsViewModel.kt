package xyz.hidasy.pebblepinmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.notifications.AppNotificationRuleConfig
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.NotificationAccessStatusChecker
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinCandidate
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinEvaluation
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule
import xyz.hidasy.pebblepinmanager.notifications.NotificationRulePresets
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleValidator
import xyz.hidasy.pebblepinmanager.notifications.NotificationSettingsRepository
import xyz.hidasy.pebblepinmanager.notifications.RuleBasedNotificationPinMapper
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Clock

class NotificationSettingsViewModel(
    private val settingsRepository: NotificationSettingsRepository,
    private val accessStatusChecker: NotificationAccessStatusChecker,
    private val installedAppsProvider: InstalledAppsProvider,
    private val sentPinRepository: SentPinRepository,
    private val pinSender: TimelinePinSender,
    private val listenerConnectedFlow: Flow<Boolean> = flowOf(false),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val onPinsChanged: (suspend () -> Unit)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        NotificationSettingsUiState(
            isNotificationAccessEnabled = accessStatusChecker.isNotificationAccessEnabled(),
            zoneId = clock.zone,
        ),
    )
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    private var hasInitializedRuleInputs = false

    init {
        observeSettings()
        observeListenerConnection()
        observeAutoPins()
        refreshInstalledApps()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.update { state ->
                    val shouldSyncInputs = !hasInitializedRuleInputs || !state.hasUnsavedRuleEdits
                    hasInitializedRuleInputs = true
                    val validTesterPackage = state.ruleTesterPackage
                        ?.takeIf { it in settings.allowedPackages }
                    state.copy(
                        settings = settings,
                        keywordFilterInput = if (shouldSyncInputs) settings.keywordFilter else state.keywordFilterInput,
                        excludeKeywordFilterInput = if (shouldSyncInputs) {
                            settings.excludeKeywordFilter
                        } else {
                            state.excludeKeywordFilterInput
                        },
                        titleRegexInput = if (shouldSyncInputs) settings.titleRegexPattern else state.titleRegexInput,
                        descriptionRegexInput = if (shouldSyncInputs) {
                            settings.descriptionRegexPattern
                        } else {
                            state.descriptionRegexInput
                        },
                        timeRegexInput = if (shouldSyncInputs) settings.timeRegexPattern else state.timeRegexInput,
                        ruleTesterPackage = validTesterPackage,
                    )
                }
                refreshRuleTesterPreview()
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

    private fun observeAutoPins() {
        viewModelScope.launch {
            sentPinRepository.observeSentPins().collect { pins ->
                val autoPins = pins.filter { it.isAutoPin }
                _uiState.update { it.copy(autoPins = autoPins) }
            }
        }
    }

    fun refreshPermissionStatus() {
        val enabled = accessStatusChecker.isNotificationAccessEnabled()
        _uiState.update { it.copy(isNotificationAccessEnabled = enabled) }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true) }
            val apps = runCatching { installedAppsProvider.loadInstalledApps() }
                .getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    installedApps = apps,
                    isLoadingApps = false,
                )
            }
        }
    }

    fun onAutoPinEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(isAutoPinEnabled = enabled) }
        }
    }

    fun onExtractTimeFromTextChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(extractTimeFromText = enabled) }
        }
    }

    fun onIgnoreOngoingChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(ignoreOngoing = enabled) }
        }
    }

    fun onIgnoreGroupSummaryChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(ignoreGroupSummary = enabled) }
        }
    }

    fun onDeletePinOnDismissChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(deletePinOnDismiss = enabled) }
        }
    }

    fun onTogglePackageAllowed(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val updated = if (normalized in current.allowedPackages) {
                    current.allowedPackages - normalized
                } else {
                    current.allowedPackages + normalized
                }
                current.copy(allowedPackages = updated)
            }
        }
    }

    fun onClearAllowedPackages() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(allowedPackages = emptySet()) }
        }
    }

    fun onAppSearchQueryChange(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun onShowSelectedAppsOnlyChange(selectedOnly: Boolean) {
        _uiState.update {
            it.copy(
                showSelectedAppsOnly = selectedOnly,
                showCustomRulesOnly = if (selectedOnly) false else it.showCustomRulesOnly,
            )
        }
    }

    fun onShowCustomRulesOnlyChange(customOnly: Boolean) {
        _uiState.update {
            it.copy(
                showCustomRulesOnly = customOnly,
                showSelectedAppsOnly = if (customOnly) false else it.showSelectedAppsOnly,
            )
        }
    }

    fun onManualPackageInputChange(value: String) {
        _uiState.update {
            it.copy(
                manualPackageInput = value,
                manualPackageError = null,
            )
        }
    }

    fun onAddManualPackage() {
        val candidate = _uiState.value.manualPackageInput.trim()
        val error = NotificationRuleValidator.validatePackageName(candidate)
        if (error != null) {
            _uiState.update { it.copy(manualPackageError = error) }
            return
        }
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(allowedPackages = current.allowedPackages + candidate)
            }
            _uiState.update {
                it.copy(
                    manualPackageInput = "",
                    manualPackageError = null,
                )
            }
        }
    }

    fun onKeywordFilterInputChange(value: String) {
        _uiState.update {
            it.copy(
                keywordFilterInput = value,
                rulesSavedMessage = null,
            )
        }
    }

    fun onExcludeKeywordFilterInputChange(value: String) {
        _uiState.update {
            it.copy(
                excludeKeywordFilterInput = value,
                rulesSavedMessage = null,
            )
        }
    }

    fun onTitleRegexInputChange(value: String) {
        _uiState.update {
            it.copy(
                titleRegexInput = value,
                titleRegexError = NotificationRuleValidator.validateRegex(value),
                rulesSavedMessage = null,
            )
        }
    }

    fun onDescriptionRegexInputChange(value: String) {
        _uiState.update {
            it.copy(
                descriptionRegexInput = value,
                descriptionRegexError = NotificationRuleValidator.validateRegex(value),
                rulesSavedMessage = null,
            )
        }
    }

    fun onTimeRegexInputChange(value: String) {
        _uiState.update {
            it.copy(
                timeRegexInput = value,
                timeRegexError = NotificationRuleValidator.validateRegex(value),
                rulesSavedMessage = null,
            )
        }
    }

    fun onSaveParsingRules() {
        val current = _uiState.value
        val titleError = NotificationRuleValidator.validateRegex(current.titleRegexInput)
        val descError = NotificationRuleValidator.validateRegex(current.descriptionRegexInput)
        val timeError = NotificationRuleValidator.validateRegex(current.timeRegexInput)

        if (titleError != null || descError != null || timeError != null) {
            _uiState.update {
                it.copy(
                    titleRegexError = titleError,
                    descriptionRegexError = descError,
                    timeRegexError = timeError,
                    rulesSavedMessage = null,
                )
            }
            return
        }

        val keywords = current.keywordFilterInput.trim()
        val excludeKeywords = current.excludeKeywordFilterInput.trim()
        val titleRegex = current.titleRegexInput.trim()
        val descRegex = current.descriptionRegexInput.trim()
        val timeRegex = current.timeRegexInput.trim()

        viewModelScope.launch {
            settingsRepository.updateSettings { settings ->
                settings.copy(
                    keywordFilter = keywords,
                    excludeKeywordFilter = excludeKeywords,
                    titleRegexPattern = titleRegex,
                    descriptionRegexPattern = descRegex,
                    timeRegexPattern = timeRegex,
                )
            }
            _uiState.update {
                it.copy(
                    keywordFilterInput = keywords,
                    excludeKeywordFilterInput = excludeKeywords,
                    titleRegexInput = titleRegex,
                    descriptionRegexInput = descRegex,
                    timeRegexInput = timeRegex,
                    titleRegexError = null,
                    descriptionRegexError = null,
                    timeRegexError = null,
                    rulesSavedMessage = "Notification parsing rules saved locally.",
                )
            }
        }
    }

    fun onRequireGlobalRegexRuleMatchChange(required: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(requireGlobalRegexRuleMatch = required) }
        }
    }

    fun onUpsertGlobalRegexRule(rule: NotificationRegexRule) {
        val normalized = rule.normalized()
        if (!NotificationRuleValidator.validateRule(normalized).isValid) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(globalRegexRules = upsertRuleInList(current.globalRegexRules, normalized))
            }
        }
    }

    fun onDeleteGlobalRegexRule(ruleId: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(globalRegexRules = current.globalRegexRules.filterNot { it.id == ruleId })
            }
        }
    }

    fun onToggleGlobalRegexRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    globalRegexRules = current.globalRegexRules.map { rule ->
                        if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
                    },
                )
            }
        }
    }

    fun onMoveGlobalRegexRule(ruleId: String, direction: Int) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(globalRegexRules = moveRuleInList(current.globalRegexRules, ruleId, direction))
            }
        }
    }

    fun onSaveAppRuleConfig(config: AppNotificationRuleConfig) {
        val normalized = config.normalized()
        if (normalized.packageName.isEmpty()) return
        if (normalized.regexRules.any { !NotificationRuleValidator.validateRule(it).isValid }) return

        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val updatedMap = if (normalized.hasCustomizations()) {
                    current.appRules + (normalized.packageName to normalized)
                } else {
                    current.appRules - normalized.packageName
                }
                current.copy(
                    allowedPackages = current.allowedPackages + normalized.packageName,
                    appRules = updatedMap,
                )
            }
        }
    }

    fun onResetAppRuleConfig(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(appRules = current.appRules - pkg)
            }
        }
    }

    fun onUpsertAppRegexRule(packageName: String, rule: NotificationRegexRule) {
        val pkg = packageName.trim()
        val normalizedRule = rule.normalized()
        if (pkg.isEmpty()) return
        if (!NotificationRuleValidator.validateRule(normalizedRule).isValid) return

        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val existing = current.appRules[pkg] ?: AppNotificationRuleConfig(packageName = pkg)
                val updatedConfig = existing.copy(
                    regexRules = upsertRuleInList(existing.regexRules, normalizedRule),
                )
                current.copy(
                    allowedPackages = current.allowedPackages + pkg,
                    appRules = current.appRules + (pkg to updatedConfig),
                )
            }
        }
    }

    fun onDeleteAppRegexRule(packageName: String, ruleId: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val existing = current.appRules[pkg] ?: return@updateSettings current
                val updatedConfig = existing.copy(
                    regexRules = existing.regexRules.filterNot { it.id == ruleId },
                )
                val updatedMap = if (updatedConfig.hasCustomizations()) {
                    current.appRules + (pkg to updatedConfig)
                } else {
                    current.appRules - pkg
                }
                current.copy(appRules = updatedMap)
            }
        }
    }

    fun onToggleAppRegexRuleEnabled(packageName: String, ruleId: String, enabled: Boolean) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val existing = current.appRules[pkg] ?: return@updateSettings current
                val updatedConfig = existing.copy(
                    regexRules = existing.regexRules.map { rule ->
                        if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
                    },
                )
                current.copy(appRules = current.appRules + (pkg to updatedConfig))
            }
        }
    }

    fun onMoveAppRegexRule(packageName: String, ruleId: String, direction: Int) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val existing = current.appRules[pkg] ?: return@updateSettings current
                val updatedConfig = existing.copy(
                    regexRules = moveRuleInList(existing.regexRules, ruleId, direction),
                )
                current.copy(appRules = current.appRules + (pkg to updatedConfig))
            }
        }
    }

    fun onAddSubjectAsTitlePreset(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        onUpsertAppRegexRule(
            packageName = pkg,
            rule = NotificationRulePresets.subjectAsTitleRule(),
        )
    }

    fun onRuleTesterPackageChange(packageName: String?) {
        _uiState.update { it.copy(ruleTesterPackage = packageName?.trim()?.takeIf { p -> p.isNotEmpty() }) }
        refreshRuleTesterPreview()
    }

    fun onRuleTesterTitleChange(title: String) {
        _uiState.update { it.copy(ruleTesterTitle = title) }
        refreshRuleTesterPreview()
    }

    fun onRuleTesterBodyChange(body: String) {
        _uiState.update { it.copy(ruleTesterBody = body) }
        refreshRuleTesterPreview()
    }

    /**
     * Evaluates a sample notification synchronously against a provided [settingsOverride]
     * (useful inside the Per-App Rules editor before saving, as well as the main tester card).
     */
    suspend fun evaluateSampleNotification(
        packageName: String?,
        title: String,
        body: String,
        settingsOverride: NotificationRuleSettings? = null,
    ): NotificationPinEvaluation? {
        if (title.isBlank() && body.isBlank()) return null
        val baseSettings = settingsOverride ?: _uiState.value.settings
        val targetPackage = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: PREVIEW_GLOBAL_PACKAGE
        val effectiveSettings = if (packageName.isNullOrBlank()) {
            baseSettings.copy(
                allowedPackages = baseSettings.allowedPackages + targetPackage,
                appRules = baseSettings.appRules - targetPackage,
            )
        } else {
            baseSettings.copy(
                allowedPackages = baseSettings.allowedPackages + targetPackage,
            )
        }
        val nowMillis = clock.millis()
        val mapper = RuleBasedNotificationPinMapper(
            settings = effectiveSettings,
            clockMillis = { nowMillis },
            zoneIdProvider = { clock.zone },
            pinIdGenerator = { "ppm-preview" },
        )
        val candidate = NotificationPinCandidate(
            sourcePackage = targetPackage,
            notificationKey = "preview|$targetPackage|0",
            title = title,
            text = body.takeIf { it.isNotBlank() },
            bigText = body.takeIf { it.isNotBlank() },
            eventTimeMillis = nowMillis,
            appLabel = _uiState.value.displayNameForPackage(targetPackage),
        )
        return mapper.evaluate(candidate)
    }

    private fun refreshRuleTesterPreview() {
        val snapshot = _uiState.value
        if (snapshot.ruleTesterTitle.isBlank() && snapshot.ruleTesterBody.isBlank()) {
            _uiState.update { it.copy(ruleTesterEvaluation = null) }
            return
        }
        viewModelScope.launch {
            val evaluation = evaluateSampleNotification(
                packageName = snapshot.ruleTesterPackage,
                title = snapshot.ruleTesterTitle,
                body = snapshot.ruleTesterBody,
                settingsOverride = snapshot.settings,
            )
            _uiState.update { it.copy(ruleTesterEvaluation = evaluation) }
        }
    }

    fun onDefaultIconSelected(icon: PinIcon) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(defaultIconUri = icon.uri) }
        }
    }

    fun onDefaultBackgroundColorSelected(color: PinAccentColor?) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(defaultBackgroundColor = color?.hexCode) }
        }
    }

    fun dismissPinActionFeedback() {
        _uiState.update { it.copy(pinActionFeedback = null) }
    }

    fun deleteAutoPin(pinId: String) {
        val current = _uiState.value
        if (current.deletingPinId != null) return

        val sanitizedId = pinId.trim()
        if (sanitizedId.isEmpty()) return

        val cachedTitle = current.autoPins.firstOrNull { it.id == sanitizedId }?.title

        _uiState.update {
            it.copy(
                deletingPinId = sanitizedId,
                pinActionFeedback = null,
            )
        }

        viewModelScope.launch {
            val resolvedTitle = cachedTitle ?: sentPinRepository.getPinById(sanitizedId)?.title
            val displayName = resolvedTitle?.let { "\"$it\"" } ?: "Pin $sanitizedId"
            val result = pinSender.deletePin(sanitizedId)

            when (result) {
                is PinDeleteResult.Success -> {
                    sentPinRepository.deletePinRecord(sanitizedId)
                    notifyPinsChanged()
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            pinActionFeedback = SendFeedback.Success(
                                pinId = result.pinId,
                                headline = "Auto-pin deleted from Pebble Timeline",
                                details = "$displayName (${result.pinId}) was removed from your Pebble Timeline and local history.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.UnknownPin -> {
                    sentPinRepository.deletePinRecord(sanitizedId)
                    notifyPinsChanged()
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            pinActionFeedback = SendFeedback.Success(
                                pinId = result.pinId,
                                headline = "Auto-pin removed from history",
                                details = "$displayName (${result.pinId}) was no longer present in the Pebble companion app and has been removed from local history.",
                            ),
                        )
                    }
                }

                PinDeleteResult.NoPebbleApp -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            pinActionFeedback = SendFeedback.Error(
                                headline = "Pebble companion app not available",
                                details = "Install or open the Core Devices Pebble app (v1.0.7.7+) or microPebble and make sure PebbleKit 2 is reachable.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.NoPermissions -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            pinActionFeedback = SendFeedback.Error(
                                headline = "Companion app permission denied",
                                details = "The Pebble companion app rejected timeline delete access for watchapp UUID ${result.watchappUuid}. Verify the watchapp's package.json authorizes this Android package.",
                            ),
                        )
                    }
                }

                PinDeleteResult.UnsupportedAction -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            pinActionFeedback = SendFeedback.Error(
                                headline = "Timeline deletion unsupported by installed Pebble app",
                                details = "Update your Core Devices Pebble app or microPebble to a version supporting PebbleKit Android 2 timeline deletion.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.UnexpectedError -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            pinActionFeedback = SendFeedback.Error(
                                headline = "Failed to delete timeline pin",
                                details = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun upsertRuleInList(
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

    private fun moveRuleInList(
        rules: List<NotificationRegexRule>,
        ruleId: String,
        direction: Int,
    ): List<NotificationRegexRule> {
        val index = rules.indexOfFirst { it.id == ruleId }
        if (index == -1) return rules
        val targetIndex = index + direction
        if (targetIndex !in rules.indices) return rules
        return rules.toMutableList().apply {
            val item = removeAt(index)
            add(targetIndex, item)
        }
    }

    private fun notifyPinsChanged() {
        onPinsChanged?.let { callback ->
            viewModelScope.launch {
                runCatching { callback() }
            }
        }
    }

    companion object {
        private const val PREVIEW_GLOBAL_PACKAGE = "com.example.preview"
    }
}

class NotificationSettingsViewModelFactory(
    private val settingsRepository: NotificationSettingsRepository,
    private val accessStatusChecker: NotificationAccessStatusChecker,
    private val installedAppsProvider: InstalledAppsProvider,
    private val sentPinRepository: SentPinRepository,
    private val pinSender: TimelinePinSender,
    private val listenerConnectedFlow: Flow<Boolean>,
    private val onPinsChanged: (suspend () -> Unit)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotificationSettingsViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return NotificationSettingsViewModel(
            settingsRepository = settingsRepository,
            accessStatusChecker = accessStatusChecker,
            installedAppsProvider = installedAppsProvider,
            sentPinRepository = sentPinRepository,
            pinSender = pinSender,
            listenerConnectedFlow = listenerConnectedFlow,
            onPinsChanged = onPinsChanged,
        ) as T
    }
}
