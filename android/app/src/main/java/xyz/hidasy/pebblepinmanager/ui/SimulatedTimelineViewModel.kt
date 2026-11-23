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
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineRepository
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Clock

class SimulatedTimelineViewModel(
    private val simulatedTimelineRepository: SimulatedTimelineRepository,
    private val sentPinRepository: SentPinRepository,
    private val pinSender: TimelinePinSender,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SimulatedTimelineUiState(
            zoneId = clock.zone,
            nowMillis = clock.millis(),
        ),
    )
    val uiState: StateFlow<SimulatedTimelineUiState> = _uiState.asStateFlow()

    init {
        observeSimulatedTimeline()
    }

    private fun observeSimulatedTimeline() {
        viewModelScope.launch {
            simulatedTimelineRepository.observeSnapshot().collect { snapshot ->
                _uiState.update { state ->
                    val activeIdSet = snapshot.activePins.mapTo(HashSet()) { it.id }
                    val logIdSet = snapshot.actionLog.mapTo(HashSet()) { it.id }
                    state.copy(
                        activePins = snapshot.activePins,
                        actionLog = snapshot.actionLog,
                        expandedPinIds = state.expandedPinIds.intersect(activeIdSet),
                        expandedLogIds = state.expandedLogIds.intersect(logIdSet),
                        nowMillis = clock.millis(),
                    )
                }
            }
        }
    }

    fun refreshClock() {
        _uiState.update {
            it.copy(
                zoneId = clock.zone,
                nowMillis = clock.millis(),
            )
        }
    }

    fun onSelectViewMode(mode: TimelineViewMode) {
        _uiState.update { it.copy(selectedViewMode = mode) }
    }

    fun onSelectSourceFilter(filter: TimelineSourceFilter) {
        _uiState.update { it.copy(sourceFilter = filter) }
    }

    fun onTogglePinExpanded(pinId: String) {
        _uiState.update { state ->
            val updated = if (pinId in state.expandedPinIds) {
                state.expandedPinIds - pinId
            } else {
                state.expandedPinIds + pinId
            }
            state.copy(expandedPinIds = updated)
        }
    }

    fun onToggleLogExpanded(actionId: String) {
        _uiState.update { state ->
            val updated = if (actionId in state.expandedLogIds) {
                state.expandedLogIds - actionId
            } else {
                state.expandedLogIds + actionId
            }
            state.copy(expandedLogIds = updated)
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    fun deletePin(pinId: String) {
        val current = _uiState.value
        if (current.deletingPinId != null) return

        val sanitizedId = pinId.trim()
        if (sanitizedId.isEmpty()) return

        val cachedTitle = current.activePins.firstOrNull { it.id == sanitizedId }?.title
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
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Success(
                                pinId = sanitizedId,
                                headline = "Pin removed from Simulated Timeline",
                                details = "$displayName ($sanitizedId) was deleted and recorded in the simulated delivery log.",
                            ),
                        )
                    }
                }

                PinDeleteResult.NoPebbleApp -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Watchless mode is turned off",
                                details = "Re-enable \"Run without a watch\" to manage simulated timeline pins.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.NoPermissions -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Permission denied",
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
                                headline = "Delete unsupported",
                                details = "The active timeline transport does not support deleting pins.",
                            ),
                        )
                    }
                }

                is PinDeleteResult.UnexpectedError -> {
                    _uiState.update {
                        it.copy(
                            deletingPinId = null,
                            feedback = SendFeedback.Error(
                                headline = "Failed to delete simulated pin",
                                details = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            val clearedIds = simulatedTimelineRepository.clearAll()
            clearedIds.forEach { id ->
                sentPinRepository.deletePinRecord(id)
            }
            _uiState.update {
                it.copy(
                    expandedPinIds = emptySet(),
                    expandedLogIds = emptySet(),
                    feedback = SendFeedback.Success(
                        pinId = "clear-all",
                        headline = "Simulated timeline cleared",
                        details = "Cleared ${clearedIds.size} simulated pin(s) and the delivery log.",
                    ),
                )
            }
        }
    }

    fun clearActionLog() {
        viewModelScope.launch {
            simulatedTimelineRepository.clearActionLog()
            _uiState.update {
                it.copy(
                    expandedLogIds = emptySet(),
                    feedback = SendFeedback.Success(
                        pinId = "clear-log",
                        headline = "Delivery log cleared",
                        details = "Active pins remain on the simulated timeline.",
                    ),
                )
            }
        }
    }
}

class SimulatedTimelineViewModelFactory(
    private val simulatedTimelineRepository: SimulatedTimelineRepository,
    private val sentPinRepository: SentPinRepository,
    private val pinSender: TimelinePinSender,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SimulatedTimelineViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SimulatedTimelineViewModel(
            simulatedTimelineRepository = simulatedTimelineRepository,
            sentPinRepository = sentPinRepository,
            pinSender = pinSender,
        ) as T
    }
}
