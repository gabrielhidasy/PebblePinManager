package xyz.hidasy.pebblepinmanager.ui

import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineAction
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelinePin
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import java.time.Instant
import java.time.ZoneId

enum class TimelineViewMode {
    TIMELINE,
    ACTIVITY_LOG,
}

enum class TimelineSourceFilter {
    ALL,
    MANUAL,
    AUTO_PIN,
}

data class SimulatedTimelineSection(
    val key: String,
    val label: String,
    val isPast: Boolean,
    val pins: List<SimulatedTimelinePin>,
)

data class SimulatedTimelineUiState(
    val activePins: List<SimulatedTimelinePin> = emptyList(),
    val actionLog: List<SimulatedTimelineAction> = emptyList(),
    val selectedViewMode: TimelineViewMode = TimelineViewMode.TIMELINE,
    val sourceFilter: TimelineSourceFilter = TimelineSourceFilter.ALL,
    val expandedPinIds: Set<String> = emptySet(),
    val expandedLogIds: Set<String> = emptySet(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val nowMillis: Long = System.currentTimeMillis(),
    val deletingPinId: String? = null,
    val feedback: SendFeedback? = null,
) {
    val activePinCount: Int
        get() = activePins.size

    val actionLogCount: Int
        get() = actionLog.size

    val manualPinCount: Int
        get() = activePins.count { !it.isAutoPin }

    val autoPinCount: Int
        get() = activePins.count { it.isAutoPin }

    val filteredActivePins: List<SimulatedTimelinePin>
        get() = activePins
            .filter { pin ->
                when (sourceFilter) {
                    TimelineSourceFilter.ALL -> true
                    TimelineSourceFilter.MANUAL -> !pin.isAutoPin
                    TimelineSourceFilter.AUTO_PIN -> pin.isAutoPin
                }
            }
            .sortedWith(compareBy<SimulatedTimelinePin> { it.startTimeMillis }.thenBy { it.updatedAtMillis })

    val filteredActionLog: List<SimulatedTimelineAction>
        get() = actionLog.filter { action ->
            when (sourceFilter) {
                TimelineSourceFilter.ALL -> true
                TimelineSourceFilter.MANUAL -> action.source != null && !action.isAutoPin
                TimelineSourceFilter.AUTO_PIN -> action.isAutoPin
            }
        }

    val upcomingCount: Int
        get() = filteredActivePins.count { !isPinInPast(it) }

    val pastCount: Int
        get() = filteredActivePins.count { isPinInPast(it) }

    val timelineSections: List<SimulatedTimelineSection>
        get() {
            val pins = filteredActivePins
            if (pins.isEmpty()) return emptyList()

            val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            val tomorrow = today.plusDays(1)
            val (pastPins, upcomingPins) = pins.partition { isPinInPast(it) }

            return buildList {
                upcomingPins
                    .groupBy { Instant.ofEpochMilli(it.startTimeMillis).atZone(zoneId).toLocalDate() }
                    .toSortedMap()
                    .forEach { (date, dayPins) ->
                        val label = when (date) {
                            today -> "Today"
                            tomorrow -> "Tomorrow"
                            else -> TimelineTimestampFormatter.formatLocalDate(date)
                        }
                        add(
                            SimulatedTimelineSection(
                                key = "upcoming-$date",
                                label = label,
                                isPast = false,
                                pins = dayPins,
                            ),
                        )
                    }

                if (pastPins.isNotEmpty()) {
                    add(
                        SimulatedTimelineSection(
                            key = "past",
                            label = "Past",
                            isPast = true,
                            pins = pastPins.sortedByDescending { it.startTimeMillis },
                        ),
                    )
                }
            }
        }

    fun isPinInPast(pin: SimulatedTimelinePin): Boolean {
        val endMillis = pin.startTimeMillis + (pin.durationMinutes ?: 0).toLong() * 60_000L
        return endMillis < nowMillis
    }

    fun isPinOngoing(pin: SimulatedTimelinePin): Boolean {
        val duration = pin.durationMinutes ?: return false
        if (duration <= 0) return false
        val endMillis = pin.startTimeMillis + duration.toLong() * 60_000L
        return nowMillis in pin.startTimeMillis..endMillis
    }
}
