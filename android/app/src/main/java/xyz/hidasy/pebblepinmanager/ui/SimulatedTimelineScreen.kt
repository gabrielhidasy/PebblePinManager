package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material.icons.filled.WatchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineAction
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineActionType
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelinePin
import xyz.hidasy.pebblepinmanager.domain.PinDurationFormatter
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import java.time.Instant
import java.time.ZoneId

@Composable
fun SimulatedTimelineRoute(
    viewModel: SimulatedTimelineViewModel,
    onEditPin: (SentPinRecord) -> Unit,
    onCreatePin: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshClock()
    }

    SimulatedTimelineScreen(
        uiState = uiState,
        onSelectViewMode = viewModel::onSelectViewMode,
        onSelectSourceFilter = viewModel::onSelectSourceFilter,
        onTogglePinExpanded = viewModel::onTogglePinExpanded,
        onToggleLogExpanded = viewModel::onToggleLogExpanded,
        onRefresh = viewModel::refreshClock,
        onEditPin = onEditPin,
        onDeletePin = viewModel::deletePin,
        onClearAll = viewModel::clearAll,
        onClearLog = viewModel::clearActionLog,
        onCreatePin = onCreatePin,
        onOpenSettings = onOpenSettings,
        bottomBar = bottomBar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SimulatedTimelineScreen(
    uiState: SimulatedTimelineUiState,
    onSelectViewMode: (TimelineViewMode) -> Unit,
    onSelectSourceFilter: (TimelineSourceFilter) -> Unit,
    onTogglePinExpanded: (String) -> Unit,
    onToggleLogExpanded: (String) -> Unit,
    onRefresh: () -> Unit,
    onEditPin: (SentPinRecord) -> Unit,
    onDeletePin: (String) -> Unit,
    onClearAll: () -> Unit,
    onClearLog: () -> Unit,
    onCreatePin: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var pinPendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }

    val pinPendingDelete = pinPendingDeleteId?.let { pendingId ->
        uiState.activePins.firstOrNull { it.id == pendingId }
    }
    val hasAnythingToClear = uiState.activePinCount > 0 || uiState.actionLogCount > 0

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Simulated Timeline",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Watchless Mode • Local Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "App settings",
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh timeline time grouping",
                        )
                    }
                    if (hasAnythingToClear) {
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear simulated timeline",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WatchlessSummaryCard(
                activePinCount = uiState.activePinCount,
                manualCount = uiState.manualPinCount,
                autoPinCount = uiState.autoPinCount,
                remotePinCount = uiState.remotePinCount,
                actionLogCount = uiState.actionLogCount,
            )

            uiState.feedback?.let { feedback ->
                FeedbackBanner(feedback = feedback)
            }

            // View mode switcher (Timeline vs Sent Log)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = uiState.selectedViewMode == TimelineViewMode.TIMELINE,
                    onClick = { onSelectViewMode(TimelineViewMode.TIMELINE) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ViewTimeline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text("Timeline (${uiState.activePinCount})") },
                )
                FilterChip(
                    selected = uiState.selectedViewMode == TimelineViewMode.ACTIVITY_LOG,
                    onClick = { onSelectViewMode(TimelineViewMode.ACTIVITY_LOG) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text("Sent Log (${uiState.actionLogCount})") },
                )
            }

            // Source filter chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = uiState.sourceFilter == TimelineSourceFilter.ALL,
                    onClick = { onSelectSourceFilter(TimelineSourceFilter.ALL) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = uiState.sourceFilter == TimelineSourceFilter.MANUAL,
                    onClick = { onSelectSourceFilter(TimelineSourceFilter.MANUAL) },
                    label = { Text("Manual (${uiState.manualPinCount})") },
                )
                FilterChip(
                    selected = uiState.sourceFilter == TimelineSourceFilter.AUTO_PIN,
                    onClick = { onSelectSourceFilter(TimelineSourceFilter.AUTO_PIN) },
                    label = { Text("Auto-Pins (${uiState.autoPinCount})") },
                )
                FilterChip(
                    selected = uiState.sourceFilter == TimelineSourceFilter.REMOTE_URL,
                    onClick = { onSelectSourceFilter(TimelineSourceFilter.REMOTE_URL) },
                    label = { Text("Remote (${uiState.remotePinCount})") },
                )
            }

            when (uiState.selectedViewMode) {
                TimelineViewMode.TIMELINE -> {
                    val sections = uiState.timelineSections
                    if (sections.isEmpty()) {
                        EmptySimulatedTimelineCard(
                            isFiltered = uiState.activePinCount > 0,
                            onCreatePin = onCreatePin,
                            onResetFilter = { onSelectSourceFilter(TimelineSourceFilter.ALL) },
                        )
                    } else {
                        Text(
                            text = "${uiState.upcomingCount} upcoming • ${uiState.pastCount} past",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sections.forEach { section ->
                            TimelineSectionBlock(
                                section = section,
                                uiState = uiState,
                                onToggleExpanded = onTogglePinExpanded,
                                onEditPin = onEditPin,
                                onRequestDeletePin = { pin -> pinPendingDeleteId = pin.id },
                            )
                        }
                    }
                }

                TimelineViewMode.ACTIVITY_LOG -> {
                    val entries = uiState.filteredActionLog
                    if (entries.isEmpty()) {
                        EmptyDeliveryLogCard(
                            isFiltered = uiState.actionLogCount > 0,
                            onResetFilter = { onSelectSourceFilter(TimelineSourceFilter.ALL) },
                        )
                    } else {
                        entries.forEach { action ->
                            DeliveryLogEntryCard(
                                action = action,
                                zoneId = uiState.zoneId,
                                isExpanded = action.id in uiState.expandedLogIds,
                                onToggleExpanded = { onToggleLogExpanded(action.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    pinPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pinPendingDeleteId = null },
            title = { Text("Remove Simulated Pin?") },
            text = {
                Text(
                    "Remove \"${pending.title}\" (${pending.id}) from the simulated watch timeline? This will also record a DELETE call in the Sent Log.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pinPendingDeleteId = null
                        onDeletePin(pending.id)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pinPendingDeleteId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear Simulated Timeline?") },
            text = {
                Text(
                    "Clear all captured simulated pins (${uiState.activePinCount}) and delivery log entries (${uiState.actionLogCount})?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearAll()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uiState.actionLogCount > 0 && uiState.activePinCount > 0) {
                        TextButton(
                            onClick = {
                                showClearConfirmDialog = false
                                onClearLog()
                            },
                        ) {
                            Text("Log Only")
                        }
                    }
                    TextButton(onClick = { showClearConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
private fun WatchlessSummaryCard(
    activePinCount: Int,
    manualCount: Int,
    autoPinCount: Int,
    remotePinCount: Int,
    actionLogCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.WatchOff,
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Simulated Watch Timeline",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Showing pins that would have been sent to PebbleKit 2 while running without a watch.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "$activePinCount active ($manualCount manual, $autoPinCount auto, $remotePinCount remote) • $actionLogCount operation(s) captured",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun EmptySimulatedTimelineCard(
    isFiltered: Boolean,
    onCreatePin: () -> Unit,
    onResetFilter: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ViewTimeline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = if (isFiltered) {
                    "No pins match this filter"
                } else {
                    "No simulated timeline pins yet"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (isFiltered) {
                    "Switch back to \"All\" to see all captured timeline pins."
                } else {
                    "Pins you create in the Pins tab or generate via Notification Auto-Pins will appear here in chronological order with their exact PebbleKit 2 payloads."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (isFiltered) {
                OutlinedButton(onClick = onResetFilter) {
                    Text("Show All Pins")
                }
            } else {
                Button(onClick = onCreatePin) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Create a Pin")
                }
            }
        }
    }
}

@Composable
private fun EmptyDeliveryLogCard(
    isFiltered: Boolean,
    onResetFilter: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = if (isFiltered) "No log entries match this filter" else "No operations logged yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Every simulated insertTimelinePin and deleteTimelinePin call will be logged here with its timestamp and payload.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (isFiltered) {
                OutlinedButton(onClick = onResetFilter) {
                    Text("Show All Entries")
                }
            }
        }
    }
}

@Composable
private fun TimelineSectionBlock(
    section: SimulatedTimelineSection,
    uiState: SimulatedTimelineUiState,
    onToggleExpanded: (String) -> Unit,
    onEditPin: (SentPinRecord) -> Unit,
    onRequestDeletePin: (SimulatedTimelinePin) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (section.isPast) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = "${section.pins.size} pin${if (section.pins.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        section.pins.forEach { pin ->
            SimulatedTimelinePinCard(
                pin = pin,
                zoneId = uiState.zoneId,
                isOngoing = uiState.isPinOngoing(pin),
                isPast = section.isPast,
                isExpanded = pin.id in uiState.expandedPinIds,
                isDeleting = uiState.deletingPinId == pin.id,
                isBusy = uiState.deletingPinId != null,
                onToggleExpanded = { onToggleExpanded(pin.id) },
                onEdit = { onEditPin(pin.toSentPinRecord()) },
                onRequestDelete = { onRequestDeletePin(pin) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulatedTimelinePinCard(
    pin: SimulatedTimelinePin,
    zoneId: ZoneId,
    isOngoing: Boolean,
    isPast: Boolean,
    isExpanded: Boolean,
    isDeleting: Boolean,
    isBusy: Boolean,
    onToggleExpanded: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val pinIcon = PinIcon.fromUri(pin.iconUri)
    val accentColor = parseComposeHexColor(pin.backgroundColor)
    val localZoned = Instant.ofEpochMilli(pin.startTimeMillis).atZone(zoneId)
    val timeOnly = TimelineTimestampFormatter.formatLocalTime(localZoned.toLocalTime())
    val fullLocalTime = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
        epochMillis = pin.startTimeMillis,
        zoneId = zoneId,
    )
    val utcIso = TimelineTimestampFormatter.toUtcIso8601(pin.startTimeMillis)
    val durationText = pin.durationMinutes?.let { PinDurationFormatter.formatMinutes(it) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            width = if (isOngoing) 1.5.dp else 1.dp,
            color = if (isOngoing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            accentColor ?: MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = pinIcon.toImageVector(),
                        contentDescription = pinIcon.label,
                        tint = accentColor?.contrastingCheckColor()
                            ?: MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = buildString {
                                append(timeOnly)
                                if (durationText != null) {
                                    append(" • ")
                                    append(durationText)
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPast) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isOngoing) {
                                SmallTagChip(
                                    label = "Now",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            val sourceLabel = when {
                                pin.isAutoPin -> "Auto-Pin"
                                pin.isRemotePin -> "Remote"
                                else -> "Manual"
                            }
                            SmallTagChip(
                                label = sourceLabel,
                                containerColor = when {
                                    pin.isAutoPin -> MaterialTheme.colorScheme.tertiaryContainer
                                    pin.isRemotePin -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = when {
                                    pin.isAutoPin -> MaterialTheme.colorScheme.onTertiaryContainer
                                    pin.isRemotePin -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                        }
                    }

                    Text(
                        text = pin.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (!pin.description.isNullOrBlank()) {
                        Text(
                            text = pin.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Text(
                        text = "$fullLocalTime • ${pinIcon.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isExpanded) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "PebbleKit 2 Payload (UTC: $utcIso)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!pin.sourceKey.isNullOrBlank()) {
                        Text(
                            text = "Source Key: ${pin.sourceKey}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PayloadJsonBox(json = pin.payloadJson)
                    Text(
                        text = "Captured: ${TimelineTimestampFormatter.formatLocalDateTimeWithZone(pin.updatedAtMillis, zoneId)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleExpanded) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isExpanded) "Hide Payload" else "Payload")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        enabled = !isBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Edit")
                    }
                    TextButton(
                        onClick = onRequestDelete,
                        enabled = !isBusy,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (isDeleting) "Deleting..." else "Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryLogEntryCard(
    action: SimulatedTimelineAction,
    zoneId: ZoneId,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val badgeColors = when (action.type) {
        SimulatedTimelineActionType.INSERT -> {
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
        SimulatedTimelineActionType.UPDATE -> {
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        }
        SimulatedTimelineActionType.DELETE -> {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
    }
    val actionLabel = when (action.type) {
        SimulatedTimelineActionType.INSERT -> "INSERT"
        SimulatedTimelineActionType.UPDATE -> "UPDATE"
        SimulatedTimelineActionType.DELETE -> "DELETE"
    }
    val whenFormatted = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
        epochMillis = action.timestampMillis,
        zoneId = zoneId,
    )

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !action.payloadJson.isNullOrBlank(), onClick = onToggleExpanded)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    SmallTagChip(
                        label = actionLabel,
                        containerColor = badgeColors.first,
                        contentColor = badgeColors.second,
                    )
                    Text(
                        text = action.title ?: action.pinId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!action.payloadJson.isNullOrBlank()) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse payload" else "Expand payload",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val sourceSuffix = when {
                action.isAutoPin -> " • Auto-Pin"
                action.isRemotePin -> " • Remote"
                else -> ""
            }
            Text(
                text = "Pin ID: ${action.pinId}$sourceSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = whenFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isExpanded && !action.payloadJson.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                PayloadJsonBox(json = action.payloadJson)
            }
        }
    }
}

@Composable
private fun PayloadJsonBox(json: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = json,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

@Composable
private fun SmallTagChip(
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
