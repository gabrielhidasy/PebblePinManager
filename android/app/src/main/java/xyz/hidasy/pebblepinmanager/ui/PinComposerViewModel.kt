package xyz.hidasy.pebblepinmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.DurationParseResult
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinDurationFormatter
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.domain.TimelineEventValidator
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.domain.ValidationError
import xyz.hidasy.pebblepinmanager.domain.ValidationResult
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class PinComposerViewModel(
    private val pinSender: TimelinePinSender,
    private val repository: SentPinRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val onPinsChanged: (suspend () -> Unit)? = null,
    private val pinIdGenerator: () -> String = { TimelineEvent.generatePinId() },
) : ViewModel() {

    private val _uiState: MutableStateFlow<PinComposerUiState> = MutableStateFlow(initialState())
    val uiState: StateFlow<PinComposerUiState> = _uiState.asStateFlow()

    init {
        refreshCompanionStatus()
        observeConnectedWatches()
        observeSentPins()
    }

    private fun defaultZonedDateTime(): ZonedDateTime = ZonedDateTime.now(clock)
        .plusMinutes(DEFAULT_OFFSET_MINUTES)
        .truncatedTo(ChronoUnit.MINUTES)

    private fun initialState(): PinComposerUiState {
        val defaultTime = defaultZonedDateTime()
        return PinComposerUiState(
            selectedDate = defaultTime.toLocalDate(),
            selectedTime = defaultTime.toLocalTime(),
            zoneId = clock.zone,
        )
    }

    private fun observeConnectedWatches() {
        viewModelScope.launch {
            pinSender.observeConnectedWatches().collect { watches ->
                _uiState.update { it.copy(connectedWatches = watches) }
            }
        }
    }

    private fun observeSentPins() {
        viewModelScope.launch {
            repository.observeSentPins().collect { pins ->
                _uiState.update { it.copy(sentPins = pins) }
            }
        }
    }

    fun refreshCompanionStatus() {
        viewModelScope.launch {
            val status = pinSender.getCompanionStatus()
            _uiState.update { it.copy(companionStatus = status) }
        }
    }

    fun onTitleChange(newTitle: String) {
        _uiState.update {
            it.copy(
                title = newTitle,
                titleError = null,
                feedback = null,
            )
        }
    }

    fun onDescriptionChange(newDescription: String) {
        _uiState.update {
            it.copy(
                description = newDescription,
                descriptionError = null,
                feedback = null,
            )
        }
    }

    fun onDateSelected(date: LocalDate) {
        _uiState.update {
            it.copy(
                selectedDate = date,
                timeError = null,
                feedback = null,
            )
        }
    }

    fun onTimeSelected(time: LocalTime) {
        _uiState.update {
            it.copy(
                selectedTime = time.truncatedTo(ChronoUnit.MINUTES),
                timeError = null,
                feedback = null,
            )
        }
    }

    fun onIconSelected(icon: PinIcon) {
        _uiState.update {
            it.copy(
                selectedIcon = icon,
                feedback = null,
            )
        }
    }

    fun onDurationInputChange(newDuration: String) {
        _uiState.update {
            it.copy(
                durationInput = newDuration,
                durationError = null,
                feedback = null,
            )
        }
    }

    fun onDurationPresetSelected(minutes: Int?) {
        _uiState.update {
            it.copy(
                durationInput = minutes?.toString().orEmpty(),
                durationError = null,
                feedback = null,
            )
        }
    }

    fun onBackgroundColorSelected(color: PinAccentColor?) {
        _uiState.update {
            it.copy(
                selectedBackgroundColor = color,
                feedback = null,
            )
        }
    }

    fun applyNow() {
        _uiState.update { state ->
            val now = Instant.now(clock)
                .atZone(state.zoneId)
                .truncatedTo(ChronoUnit.MINUTES)
            state.copy(
                selectedDate = now.toLocalDate(),
                selectedTime = now.toLocalTime(),
                timeError = null,
                feedback = null,
            )
        }
    }

    fun applyQuickOffsetMinutes(minutes: Long) {
        _uiState.update { state ->
            val target = Instant.ofEpochMilli(state.selectedEpochMillis)
                .plus(minutes, ChronoUnit.MINUTES)
                .atZone(state.zoneId)
                .truncatedTo(ChronoUnit.MINUTES)
            state.copy(
                selectedDate = target.toLocalDate(),
                selectedTime = target.toLocalTime(),
                timeError = null,
                feedback = null,
            )
        }
    }

    fun applyTomorrowMorning(hour: Int = 9, minute: Int = 0) {
        val tomorrow = ZonedDateTime.now(clock).toLocalDate().plusDays(1)
        _uiState.update {
            it.copy(
                selectedDate = tomorrow,
                selectedTime = LocalTime.of(hour, minute),
                timeError = null,
                feedback = null,
            )
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    fun startEditingPin(record: SentPinRecord) {
        val current = _uiState.value
        if (current.isSending || current.deletingPinId != null) return

        val zoned = Instant.ofEpochMilli(record.startTimeMillis).atZone(current.zoneId)
        _uiState.update {
            it.copy(
                title = record.title,
                description = record.description.orEmpty(),
                selectedDate = zoned.toLocalDate(),
                selectedTime = zoned.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
                selectedIcon = PinIcon.fromUri(record.iconUri),
                durationInput = record.durationMinutes?.toString().orEmpty(),
                selectedBackgroundColor = PinAccentColor.fromHex(record.backgroundColor),
                editingPin = record,
                titleError = null,
                descriptionError = null,
                timeError = null,
                durationError = null,
                feedback = null,
            )
        }
    }

    fun cancelEditing() {
        if (_uiState.value.isSending) return

        val defaultTime = defaultZonedDateTime()
        _uiState.update {
            it.copy(
                title = "",
                description = "",
                selectedDate = defaultTime.toLocalDate(),
                selectedTime = defaultTime.toLocalTime(),
                selectedIcon = PinIcon.DEFAULT,
                durationInput = "",
                selectedBackgroundColor = null,
                editingPin = null,
                titleError = null,
                descriptionError = null,
                timeError = null,
                durationError = null,
                feedback = null,
            )
        }
    }

    fun sendPin() {
        val current = _uiState.value
        if (current.isSending || current.deletingPinId != null) return

        val durationParse = PinDurationFormatter.parseDurationInput(current.durationInput)
        val parsedDurationMinutes: Int?
        val durationParseError: String?
        when (durationParse) {
            DurationParseResult.None -> {
                parsedDurationMinutes = null
                durationParseError = null
            }
            is DurationParseResult.Valid -> {
                parsedDurationMinutes = durationParse.minutes
                durationParseError = null
            }
            is DurationParseResult.Invalid -> {
                parsedDurationMinutes = null
                durationParseError = durationParse.message
            }
        }

        val nowMillis = clock.millis()
        val editingPin = current.editingPin
        val isUpdate = editingPin != null
        val candidate = if (editingPin != null) {
            editingPin.toTimelineEvent().copy(
                title = current.title,
                description = current.description,
                startTimeMillis = current.selectedEpochMillis,
                durationMinutes = parsedDurationMinutes,
                iconUri = current.selectedIcon.uri,
                backgroundColor = current.effectiveBackgroundColor?.hexCode,
            )
        } else {
            TimelineEvent(
                id = pinIdGenerator(),
                title = current.title,
                description = current.description,
                startTimeMillis = current.selectedEpochMillis,
                durationMinutes = parsedDurationMinutes,
                iconUri = current.selectedIcon.uri,
                backgroundColor = current.effectiveBackgroundColor?.hexCode,
            )
        }

        val validation = TimelineEventValidator.validate(candidate, nowMillis)
        val validationErrors = when (validation) {
            is ValidationResult.Valid -> emptyList()
            is ValidationResult.Invalid -> validation.errors
        }
        if (durationParseError != null || validationErrors.isNotEmpty()) {
            applyValidationErrors(validationErrors, durationParseError)
            return
        }

        val validatedEvent = (validation as ValidationResult.Valid).event

        _uiState.update {
            it.copy(
                isSending = true,
                titleError = null,
                descriptionError = null,
                timeError = null,
                durationError = null,
                feedback = null,
            )
        }

        viewModelScope.launch {
            val result = pinSender.sendPin(validatedEvent)
            val updatedStatus = pinSender.getCompanionStatus()

            when (result) {
                is PinSendResult.Success -> {
                    repository.saveSentPin(
                        SentPinRecord.fromTimelineEvent(
                            event = validatedEvent,
                            sentAtMillis = clock.millis(),
                        ),
                    )
                    notifyPinsChanged()
                    val localWhen = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                        epochMillis = validatedEvent.startTimeMillis,
                        zoneId = current.zoneId,
                    )
                    val durationSuffix = validatedEvent.durationMinutes
                        ?.let { " (${PinDurationFormatter.formatMinutes(it)})" }
                        .orEmpty()
                    val watchSummary = _uiState.value.connectedWatches
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString { it.displayLabel }
                        ?.let { "Synced via Pebble app (connected: $it)." }
                        ?: "Queued in the Pebble companion app. It will appear on your watch once connected."

                    val resetTime = if (isUpdate) defaultZonedDateTime() else null
                    val headline = if (isUpdate) {
                        "Pin updated on Pebble Timeline"
                    } else {
                        "Pin sent to Pebble Timeline"
                    }
                    val details = if (isUpdate) {
                        "\"${validatedEvent.title}\" (${result.pinId}) updated for $localWhen$durationSuffix. $watchSummary"
                    } else {
                        "\"${validatedEvent.title}\" scheduled for $localWhen$durationSuffix. $watchSummary"
                    }

                    _uiState.update {
                        it.copy(
                            title = "",
                            description = "",
                            selectedDate = resetTime?.toLocalDate() ?: it.selectedDate,
                            selectedTime = resetTime?.toLocalTime() ?: it.selectedTime,
                            selectedIcon = PinIcon.DEFAULT,
                            durationInput = "",
                            selectedBackgroundColor = null,
                            durationError = null,
                            editingPin = null,
                            isSending = false,
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Success(
                                pinId = result.pinId,
                                headline = headline,
                                details = details,
                            ),
                        )
                    }
                }

                PinSendResult.NoPebbleApp -> {
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
                                headline = "Pebble companion app not available",
                                details = "Install or open the Core Devices Pebble app (v1.0.7.7+) or microPebble and make sure PebbleKit 2 is reachable.",
                            ),
                        )
                    }
                }

                is PinSendResult.NoPermissions -> {
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
                                headline = "Companion app permission denied",
                                details = "The Pebble companion app rejected timeline access for watchapp UUID ${result.watchappUuid}. Verify the watchapp's package.json authorizes this Android package.",
                            ),
                        )
                    }
                }

                PinSendResult.UnsupportedAction -> {
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
                                headline = "Timeline pins unsupported by installed Pebble app",
                                details = "Update your Core Devices Pebble app or microPebble to a version supporting PebbleKit Android 2 timeline pins.",
                            ),
                        )
                    }
                }

                is PinSendResult.UnexpectedError -> {
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
                                headline = if (isUpdate) {
                                    "Failed to update timeline pin"
                                } else {
                                    "Failed to send timeline pin"
                                },
                                details = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun deletePin(pinId: String) {
        val current = _uiState.value
        if (current.isSending || current.deletingPinId != null) return

        val sanitizedId = pinId.trim()
        if (sanitizedId.isEmpty()) return

        val cachedTitle = current.sentPins.firstOrNull { it.id == sanitizedId }?.title

        _uiState.update {
            it.copy(
                deletingPinId = sanitizedId,
                feedback = null,
            )
        }

        viewModelScope.launch {
            val resolvedTitle = cachedTitle ?: repository.getPinById(sanitizedId)?.title
            val displayName = resolvedTitle?.let { "\"$it\"" } ?: "Pin $sanitizedId"
            val result = pinSender.deletePin(sanitizedId)
            val updatedStatus = pinSender.getCompanionStatus()

            when (result) {
                is PinDeleteResult.Success -> {
                    repository.deletePinRecord(sanitizedId)
                    notifyPinsChanged()
                    clearDeletedPinState(
                        deletedPinId = sanitizedId,
                        updatedStatus = updatedStatus,
                        feedback = SendFeedback.Success(
                            pinId = result.pinId,
                            headline = "Pin deleted from Pebble Timeline",
                            details = "$displayName (${result.pinId}) was removed from your Pebble Timeline and local history.",
                        ),
                    )
                }

                is PinDeleteResult.UnknownPin -> {
                    repository.deletePinRecord(sanitizedId)
                    notifyPinsChanged()
                    clearDeletedPinState(
                        deletedPinId = sanitizedId,
                        updatedStatus = updatedStatus,
                        feedback = SendFeedback.Success(
                            pinId = result.pinId,
                            headline = "Pin removed from history",
                            details = "$displayName (${result.pinId}) was no longer present in the Pebble companion app and has been removed from local history.",
                        ),
                    )
                }

                PinDeleteResult.NoPebbleApp -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
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
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
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
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
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
                            companionStatus = updatedStatus,
                            feedback = SendFeedback.Error(
                                headline = "Failed to delete timeline pin",
                                details = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun notifyPinsChanged() {
        onPinsChanged?.let { callback ->
            viewModelScope.launch {
                runCatching { callback() }
            }
        }
    }

    private fun clearDeletedPinState(
        deletedPinId: String,
        updatedStatus: PebbleCompanionStatus,
        feedback: SendFeedback,
    ) {
        val resetTime = defaultZonedDateTime()
        _uiState.update { state ->
            val wasEditingDeleted = state.editingPin?.id == deletedPinId
            state.copy(
                title = if (wasEditingDeleted) "" else state.title,
                description = if (wasEditingDeleted) "" else state.description,
                selectedDate = if (wasEditingDeleted) resetTime.toLocalDate() else state.selectedDate,
                selectedTime = if (wasEditingDeleted) resetTime.toLocalTime() else state.selectedTime,
                selectedIcon = if (wasEditingDeleted) PinIcon.DEFAULT else state.selectedIcon,
                durationInput = if (wasEditingDeleted) "" else state.durationInput,
                selectedBackgroundColor = if (wasEditingDeleted) null else state.selectedBackgroundColor,
                editingPin = if (wasEditingDeleted) null else state.editingPin,
                titleError = if (wasEditingDeleted) null else state.titleError,
                descriptionError = if (wasEditingDeleted) null else state.descriptionError,
                timeError = if (wasEditingDeleted) null else state.timeError,
                durationError = if (wasEditingDeleted) null else state.durationError,
                deletingPinId = null,
                companionStatus = updatedStatus,
                feedback = feedback,
            )
        }
    }

    private fun applyValidationErrors(
        errors: List<ValidationError>,
        extraDurationError: String? = null,
    ) {
        val titleError = errors.firstOrNull {
            it is ValidationError.TitleBlank || it is ValidationError.TitleTooLong
        }?.message
        val descriptionError = errors.firstOrNull {
            it is ValidationError.DescriptionTooLong
        }?.message
        val timeError = errors.firstOrNull {
            it is ValidationError.TimestampTooFarInPast || it is ValidationError.TimestampTooFarInFuture
        }?.message
        val durationError = extraDurationError ?: errors.firstOrNull {
            it is ValidationError.DurationOutOfRange
        }?.message
        val fallbackError = errors.firstOrNull()?.message
            ?: extraDurationError
            ?: "Please check the event details."

        _uiState.update {
            it.copy(
                titleError = titleError,
                descriptionError = descriptionError,
                timeError = timeError,
                durationError = durationError,
                feedback = SendFeedback.Error(
                    headline = "Check event details",
                    details = fallbackError,
                ),
            )
        }
    }

    companion object {
        private const val DEFAULT_OFFSET_MINUTES: Long = 15L
    }
}

class PinComposerViewModelFactory(
    private val pinSender: TimelinePinSender,
    private val repository: SentPinRepository,
    private val onPinsChanged: (suspend () -> Unit)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PinComposerViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return PinComposerViewModel(
            pinSender = pinSender,
            repository = repository,
            onPinsChanged = onPinsChanged,
        ) as T
    }
}
