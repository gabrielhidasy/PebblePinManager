package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Notifications as NotificationsOutlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinDurationFormatter
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.pebble.ConnectedPebbleWatch
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PhoneHandoffCapabilities
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@Composable
fun PinComposerRoute(
    viewModel: PinComposerViewModel,
    handoffCapabilities: PhoneHandoffCapabilities? = null,
    isHandoffBannerDismissed: Boolean = false,
    onDismissHandoffBanner: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PinComposerScreen(
        uiState = uiState,
        onTitleChange = viewModel::onTitleChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onDateSelected = viewModel::onDateSelected,
        onTimeSelected = viewModel::onTimeSelected,
        onIconSelected = viewModel::onIconSelected,
        onDurationInputChange = viewModel::onDurationInputChange,
        onDurationPresetSelected = viewModel::onDurationPresetSelected,
        onBackgroundColorSelected = viewModel::onBackgroundColorSelected,
        onSetNow = viewModel::applyNow,
        onQuickOffsetMinutes = viewModel::applyQuickOffsetMinutes,
        onTomorrowMorning = { viewModel.applyTomorrowMorning() },
        onRefreshStatus = viewModel::refreshCompanionStatus,
        onSendPin = viewModel::sendPin,
        onEditPin = viewModel::startEditingPin,
        onCancelEdit = viewModel::cancelEditing,
        onDeletePin = viewModel::deletePin,
        handoffCapabilities = handoffCapabilities,
        isHandoffBannerDismissed = isHandoffBannerDismissed,
        onDismissHandoffBanner = onDismissHandoffBanner,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenNotificationSettings = onOpenNotificationSettings,
        onOpenOverlaySettings = onOpenOverlaySettings,
        bottomBar = bottomBar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PinComposerScreen(
    uiState: PinComposerUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onTimeSelected: (LocalTime) -> Unit,
    onIconSelected: (PinIcon) -> Unit,
    onDurationInputChange: (String) -> Unit,
    onDurationPresetSelected: (Int?) -> Unit,
    onBackgroundColorSelected: (PinAccentColor?) -> Unit,
    onSetNow: () -> Unit,
    onQuickOffsetMinutes: (Long) -> Unit,
    onTomorrowMorning: () -> Unit,
    onRefreshStatus: () -> Unit,
    onSendPin: () -> Unit,
    onEditPin: (SentPinRecord) -> Unit,
    onCancelEdit: () -> Unit,
    onDeletePin: (String) -> Unit,
    handoffCapabilities: PhoneHandoffCapabilities? = null,
    isHandoffBannerDismissed: Boolean = false,
    onDismissHandoffBanner: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showWatchHandoffDialog by rememberSaveable { mutableStateOf(false) }
    var pinPendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val pinPendingDelete = pinPendingDeleteId?.let { pendingId ->
        uiState.sentPins.firstOrNull { it.id == pendingId }
    }
    val composerScrollState = rememberScrollState()
    val sentPinsScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val isBusy = uiState.isSending || uiState.deletingPinId != null

    val handleEditPin: (SentPinRecord) -> Unit = { record ->
        onEditPin(record)
        coroutineScope.launch { composerScrollState.animateScrollTo(0) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "PebblePinManager",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Pebble Time & Pebble Time 2",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (handoffCapabilities != null) {
                        val isBellFilled = handoffCapabilities.isHandoffBellFilled
                        IconButton(onClick = { showWatchHandoffDialog = true }) {
                            Icon(
                                imageVector = if (isBellFilled) {
                                    Icons.Default.Notifications
                                } else {
                                    Icons.Outlined.NotificationsOutlined
                                },
                                contentDescription = if (isBellFilled) {
                                    "Watch handoff settings (enabled)"
                                } else {
                                    "Watch handoff settings (permissions needed)"
                                },
                            )
                        }
                    }
                    IconButton(onClick = onRefreshStatus) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Pebble companion status",
                        )
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            val layoutMode = PinComposerLayoutPolicy.resolveLayoutMode(
                availableWidthDp = maxWidth.value,
                availableHeightDp = maxHeight.value,
            )

            when (layoutMode) {
                PinComposerLayoutMode.SINGLE_PANE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(composerScrollState)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PebbleStatusCard(
                            companionStatus = uiState.companionStatus,
                            connectedWatches = uiState.connectedWatches,
                        )

                        if (handoffCapabilities?.shouldShowPermissionBanner(isHandoffBannerDismissed) == true) {
                            WatchHandoffWarningCard(
                                onDismiss = onDismissHandoffBanner,
                                onEnableNotifications = onRequestNotificationPermission,
                                onEnableInstantOpen = onOpenOverlaySettings,
                            )
                        }

                        PinComposerCard(
                            uiState = uiState,
                            isBusy = isBusy,
                            isTwoPane = false,
                            onTitleChange = onTitleChange,
                            onDescriptionChange = onDescriptionChange,
                            onOpenDatePicker = { showDatePicker = true },
                            onOpenTimePicker = { showTimePicker = true },
                            onIconSelected = onIconSelected,
                            onDurationInputChange = onDurationInputChange,
                            onDurationPresetSelected = onDurationPresetSelected,
                            onBackgroundColorSelected = onBackgroundColorSelected,
                            onSetNow = onSetNow,
                            onQuickOffsetMinutes = onQuickOffsetMinutes,
                            onTomorrowMorning = onTomorrowMorning,
                            onSendPin = onSendPin,
                            onCancelEdit = onCancelEdit,
                        )

                        if (uiState.sentPins.isNotEmpty()) {
                            RecentSentPinsSection(
                                sentPins = uiState.sentPins,
                                zoneId = uiState.zoneId,
                                editingPinId = uiState.editingPinId,
                                deletingPinId = uiState.deletingPinId,
                                isBusy = isBusy,
                                isTwoPane = false,
                                onEditPin = handleEditPin,
                                onRequestDeletePin = { record -> pinPendingDeleteId = record.id },
                            )
                        }
                    }
                }

                PinComposerLayoutMode.TWO_PANE -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(composerScrollState)
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PebbleStatusCard(
                                companionStatus = uiState.companionStatus,
                                connectedWatches = uiState.connectedWatches,
                            )

                            if (handoffCapabilities?.shouldShowPermissionBanner(isHandoffBannerDismissed) == true) {
                                WatchHandoffWarningCard(
                                    onDismiss = onDismissHandoffBanner,
                                    onEnableNotifications = onRequestNotificationPermission,
                                    onEnableInstantOpen = onOpenOverlaySettings,
                                )
                            }

                            PinComposerCard(
                                uiState = uiState,
                                isBusy = isBusy,
                                isTwoPane = true,
                                onTitleChange = onTitleChange,
                                onDescriptionChange = onDescriptionChange,
                                onOpenDatePicker = { showDatePicker = true },
                                onOpenTimePicker = { showTimePicker = true },
                                onIconSelected = onIconSelected,
                                onDurationInputChange = onDurationInputChange,
                                onDurationPresetSelected = onDurationPresetSelected,
                                onBackgroundColorSelected = onBackgroundColorSelected,
                                onSetNow = onSetNow,
                                onQuickOffsetMinutes = onQuickOffsetMinutes,
                                onTomorrowMorning = onTomorrowMorning,
                                onSendPin = onSendPin,
                                onCancelEdit = onCancelEdit,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(sentPinsScrollState)
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            RecentSentPinsSection(
                                sentPins = uiState.sentPins,
                                zoneId = uiState.zoneId,
                                editingPinId = uiState.editingPinId,
                                deletingPinId = uiState.deletingPinId,
                                isBusy = isBusy,
                                isTwoPane = true,
                                onEditPin = handleEditPin,
                                onRequestDeletePin = { record -> pinPendingDeleteId = record.id },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showWatchHandoffDialog && handoffCapabilities != null) {
        WatchHandoffDialog(
            capabilities = handoffCapabilities,
            onDismiss = { showWatchHandoffDialog = false },
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
        )
    }

    pinPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pinPendingDeleteId = null },
            title = { Text("Delete Timeline Pin?") },
            text = {
                Text(
                    "Remove \"${pending.title}\" (${pending.id}) from your Pebble Timeline and recent history?",
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

    if (showDatePicker) {
        val initialUtcMillis = uiState.selectedDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { utcMillis ->
                            val picked = Instant.ofEpochMilli(utcMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            onDateSelected(picked)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = uiState.selectedTime.hour,
            initialMinute = uiState.selectedTime.minute,
            is24Hour = true,
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Event Time") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTimeSelected(LocalTime.of(timePickerState.hour, timePickerState.minute))
                        showTimePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinComposerCard(
    uiState: PinComposerUiState,
    isBusy: Boolean,
    isTwoPane: Boolean,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onOpenTimePicker: () -> Unit,
    onIconSelected: (PinIcon) -> Unit,
    onDurationInputChange: (String) -> Unit,
    onDurationPresetSelected: (Int?) -> Unit,
    onBackgroundColorSelected: (PinAccentColor?) -> Unit,
    onSetNow: () -> Unit,
    onQuickOffsetMinutes: (Long) -> Unit,
    onTomorrowMorning: () -> Unit,
    onSendPin: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val dateTimeButtonPadding = if (isTwoPane) {
        PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    } else {
        ButtonDefaults.ContentPadding
    }
    val dateButtonWeight = if (isTwoPane) 1.35f else 1.3f
    val timeButtonWeight = if (isTwoPane) 0.85f else 0.9f

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (uiState.isEditing) "Edit Timeline Pin" else "New Timeline Pin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (uiState.isEditing) {
                    TextButton(
                        onClick = onCancelEdit,
                        enabled = !uiState.isSending,
                    ) {
                        Text("Cancel Edit")
                    }
                }
            }

            uiState.editingPin?.let { editing ->
                val editingLabel = if (editing.isAutoPin) {
                    "Updating existing Auto-Pin ID: ${editing.id}"
                } else {
                    "Updating existing Pin ID: ${editing.id}"
                }
                Text(
                    text = editingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = { Text("Event Title *") },
                placeholder = { Text("e.g. Dentist appointment") },
                singleLine = true,
                isError = uiState.titleError != null,
                supportingText = {
                    val err = uiState.titleError
                    if (err != null) {
                        Text(err)
                    } else {
                        Text("${uiState.title.trim().length}/${TimelineEvent.MAX_TITLE_LENGTH}")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.description,
                onValueChange = onDescriptionChange,
                label = { Text("Description (optional)") },
                placeholder = { Text("Notes, location, or details shown on your watch") },
                minLines = 3,
                maxLines = 5,
                isError = uiState.descriptionError != null,
                supportingText = {
                    val err = uiState.descriptionError
                    if (err != null) {
                        Text(err)
                    } else {
                        Text("${uiState.description.trim().length}/${TimelineEvent.MAX_DESCRIPTION_LENGTH}")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Date & Time (${uiState.formattedZoneLabel})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenDatePicker,
                    contentPadding = dateTimeButtonPadding,
                    modifier = Modifier.weight(dateButtonWeight),
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = uiState.formattedLocalDate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = onOpenTimePicker,
                    contentPadding = dateTimeButtonPadding,
                    modifier = Modifier.weight(timeButtonWeight),
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = uiState.formattedLocalTime,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssistChip(
                    onClick = onSetNow,
                    label = { Text("Now") },
                )
                AssistChip(
                    onClick = { onQuickOffsetMinutes(15) },
                    label = { Text("+15 min") },
                )
                AssistChip(
                    onClick = { onQuickOffsetMinutes(60) },
                    label = { Text("+1 hour") },
                )
                AssistChip(
                    onClick = onTomorrowMorning,
                    label = { Text("Tomorrow 09:00") },
                )
            }

            LocalPayloadTimeBox(
                formattedLocalDateTime = uiState.formattedLocalPayloadTime,
            )

            if (uiState.timeError != null) {
                Text(
                    text = uiState.timeError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            PinIconSelectorSection(
                selectedIcon = uiState.selectedIcon,
                onIconSelected = onIconSelected,
            )

            DurationSelectorSection(
                durationInput = uiState.durationInput,
                parsedDurationMinutes = uiState.parsedDurationMinutes,
                durationError = uiState.durationError,
                onDurationInputChange = onDurationInputChange,
                onDurationPresetSelected = onDurationPresetSelected,
            )

            BackgroundColorSelectorSection(
                supportsColorBackground = uiState.supportsColorBackground,
                selectedColor = uiState.selectedBackgroundColor,
                onColorSelected = onBackgroundColorSelected,
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onSendPin,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.isEditing) "Updating on Pebble..." else "Sending to Pebble...")
                } else {
                    Icon(
                        imageVector = if (uiState.isEditing) {
                            Icons.Default.Edit
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.isEditing) "Update Timeline Pin" else "Send to Pebble Timeline")
                }
            }

            uiState.feedback?.let { feedback ->
                FeedbackBanner(feedback = feedback)
            }
        }
    }
}

private const val PIN_ICON_GRID_COLUMNS = 4

@Composable
private fun LocalPayloadTimeBox(
    formattedLocalDateTime: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Pin time",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formattedLocalDateTime,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PinIconSelectorSection(
    selectedIcon: PinIcon,
    onIconSelected: (PinIcon) -> Unit,
) {
    val iconRows = PinIcon.entries.chunked(PIN_ICON_GRID_COLUMNS)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Pin Icon",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            iconRows.forEach { rowIcons ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowIcons.forEach { icon ->
                        val isSelected = icon == selectedIcon
                        Surface(
                            selected = isSelected,
                            onClick = { onIconSelected(icon) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .defaultMinSize(minHeight = 56.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = icon.toImageVector(),
                                    contentDescription = if (isSelected) null else icon.label,
                                    modifier = Modifier.size(if (isSelected) 20.dp else 22.dp),
                                )
                                if (isSelected) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = icon.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    repeat(PIN_ICON_GRID_COLUMNS - rowIcons.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationSelectorSection(
    durationInput: String,
    parsedDurationMinutes: Int?,
    durationError: String?,
    onDurationInputChange: (String) -> Unit,
    onDurationPresetSelected: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Duration (optional)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = durationInput.isBlank(),
                onClick = { onDurationPresetSelected(null) },
                label = { Text("None") },
            )
            PinDurationFormatter.PRESET_MINUTES.forEach { preset ->
                FilterChip(
                    selected = parsedDurationMinutes == preset && durationInput.trim() == preset.toString(),
                    onClick = { onDurationPresetSelected(preset) },
                    label = { Text(PinDurationFormatter.formatMinutes(preset)) },
                )
            }
        }

        OutlinedTextField(
            value = durationInput,
            onValueChange = onDurationInputChange,
            label = { Text("Custom duration (minutes)") },
            placeholder = { Text("Leave empty for instant event") },
            suffix = {
                if (durationInput.isNotBlank()) {
                    Text("min")
                }
            },
            singleLine = true,
            isError = durationError != null,
            supportingText = {
                when {
                    durationError != null -> Text(durationError)
                    parsedDurationMinutes != null -> Text(
                        "Event length: ${PinDurationFormatter.formatMinutes(parsedDurationMinutes)}",
                    )
                    else -> Text(
                        "Default: instant event (1–${TimelineEvent.MAX_DURATION_MINUTES} min)",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BackgroundColorSelectorSection(
    supportsColorBackground: Boolean,
    selectedColor: PinAccentColor?,
    onColorSelected: (PinAccentColor?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Accent Color (optional)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (supportsColorBackground) {
                    selectedColor?.let { "${it.label} (${it.hexCode})" } ?: "Watch default"
                } else {
                    "Color watches only"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (supportsColorBackground) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "default") {
                    FilterChip(
                        selected = selectedColor == null,
                        onClick = { onColorSelected(null) },
                        label = { Text("Default") },
                    )
                }
                items(PinAccentColor.entries, key = { it.hexCode }) { accent ->
                    val swatchColor = accent.toComposeColor()
                    val isSelected = accent == selectedColor
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .semantics {
                                contentDescription = "${accent.label} accent color"
                                selected = isSelected
                            }
                            .clickable(role = Role.RadioButton) {
                                onColorSelected(if (isSelected) null else accent)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = swatchColor.contrastingCheckColor(),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Text(
                text = "Applied as backgroundColor on color-screen watches (basalt, chalk, emery, gabbro).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Connected watch has a monochrome display; backgroundColor is only used on color-screen watches (basalt, chalk, emery, gabbro).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PebbleStatusCard(
    companionStatus: PebbleCompanionStatus?,
    connectedWatches: List<ConnectedPebbleWatch>,
) {
    val isAvailable = companionStatus is PebbleCompanionStatus.Available
    val containerColor = when {
        connectedWatches.isNotEmpty() -> MaterialTheme.colorScheme.secondaryContainer
        isAvailable -> MaterialTheme.colorScheme.surfaceVariant
        companionStatus is PebbleCompanionStatus.Unavailable -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        connectedWatches.isNotEmpty() -> MaterialTheme.colorScheme.onSecondaryContainer
        isAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
        companionStatus is PebbleCompanionStatus.Unavailable -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
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
                imageVector = if (companionStatus is PebbleCompanionStatus.Unavailable) {
                    Icons.Default.ErrorOutline
                } else {
                    Icons.Default.Watch
                },
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val headline = when {
                    connectedWatches.isNotEmpty() -> "Watch connected"
                    companionStatus is PebbleCompanionStatus.Available -> "Pebble companion app ready"
                    companionStatus is PebbleCompanionStatus.Unavailable -> "Pebble companion app unavailable"
                    else -> "Checking Pebble connection..."
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                when {
                    connectedWatches.isNotEmpty() -> {
                        connectedWatches.forEach { watch ->
                            Text(
                                text = "${watch.displayLabel} (FW ${watch.firmwareVersion})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    companionStatus is PebbleCompanionStatus.Available -> {
                        Text(
                            text = "Using ${companionStatus.selectedPackage}. No watch reported connected yet; pins can still be queued.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    companionStatus is PebbleCompanionStatus.Unavailable -> {
                        Text(
                            text = companionStatus.reason,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchHandoffWarningCard(
    onDismiss: () -> Unit,
    onEnableNotifications: () -> Unit,
    onEnableInstantOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOutlined,
                    contentDescription = null,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Watch \"Open Phone App\" needs permission",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Android blocks background app launches. Enable notifications for a tap-to-open alert, or allow Display over other apps to open immediately.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                TextButton(onClick = onEnableInstantOpen) {
                    Text("Instant Open")
                }
                Button(onClick = onEnableNotifications) {
                    Text("Enable Notifications")
                }
            }
        }
    }
}

@Composable
private fun WatchHandoffDialog(
    capabilities: PhoneHandoffCapabilities,
    onDismiss: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open from Pebble Watch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (capabilities.requiresBackgroundHandoff) {
                        "Android restricts opening apps directly from the background. Choose how \"Open Phone App\" on your Pebble reaches this phone:"
                    } else {
                        "This Android version allows opening PebblePinManager directly from your Pebble watch."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (capabilities.canPostNotifications) {
                            "Tap-to-Open Notification: Enabled"
                        } else {
                            "Tap-to-Open Notification: Disabled"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Posts a high-priority notification when the app is in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            if (capabilities.canPostNotifications) {
                                onOpenNotificationSettings()
                            } else {
                                onRequestNotificationPermission()
                            }
                        },
                    ) {
                        Text(
                            if (capabilities.canPostNotifications) {
                                "Notification Settings"
                            } else {
                                "Enable Notifications"
                            },
                        )
                    }
                }
                if (capabilities.requiresBackgroundHandoff) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (capabilities.canDrawOverlays) {
                                "Instant Background Open: Enabled"
                            } else {
                                "Instant Background Open: Optional"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Granting \"Display over other apps\" lets the watch open this screen immediately without tapping a notification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenOverlaySettings) {
                            Text(
                                if (capabilities.canDrawOverlays) {
                                    "Manage Overlay Permission"
                                } else {
                                    "Enable Instant Open"
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
internal fun FeedbackBanner(feedback: SendFeedback) {
    val isSuccess = feedback is SendFeedback.Success
    val containerColor = if (isSuccess) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isSuccess) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = feedback.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = feedback.details,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RecentSentPinsSection(
    sentPins: List<SentPinRecord>,
    zoneId: java.time.ZoneId,
    editingPinId: String?,
    deletingPinId: String?,
    isBusy: Boolean,
    isTwoPane: Boolean,
    onEditPin: (SentPinRecord) -> Unit,
    onRequestDeletePin: (SentPinRecord) -> Unit,
) {
    val editButtonPadding = if (isTwoPane) {
        PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    } else {
        ButtonDefaults.ContentPadding
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Recent Sent Pins (${sentPins.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (sentPins.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Watch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "No timeline pins sent yet",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Pins you send will appear here so you can review, edit, or delete them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            sentPins.take(10).forEachIndexed { index, record ->
                if (index > 0) {
                    HorizontalDivider()
                }
                val pinIcon = PinIcon.fromUri(record.iconUri)
                val accentColor = parseComposeHexColor(record.backgroundColor)
                val timestampText = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                    epochMillis = record.startTimeMillis,
                    zoneId = zoneId,
                )
                val durationSuffix = record.durationMinutes
                    ?.let { " • ${PinDurationFormatter.formatMinutes(it)}" }
                    .orEmpty()
                val isCurrentlyEditing = record.id == editingPinId
                val isCurrentlyDeleting = record.id == deletingPinId

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    accentColor ?: MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = pinIcon.toImageVector(),
                                contentDescription = pinIcon.label,
                                tint = accentColor?.contrastingCheckColor()
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = record.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isCurrentlyEditing) {
                                    Text(
                                        text = "Editing",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            if (!record.description.isNullOrBlank()) {
                                Text(
                                    text = record.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "$timestampText$durationSuffix",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Pin ID: ${record.id} • ${pinIcon.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { onEditPin(record) },
                            enabled = !isBusy,
                            contentPadding = editButtonPadding,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Edit", maxLines = 1)
                        }
                        TextButton(
                            onClick = { onRequestDeletePin(record) },
                            enabled = !isBusy,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            if (isCurrentlyDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Deleting...", maxLines = 1)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Delete", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun previewUiState(): PinComposerUiState = PinComposerUiState(
    title = "Dentist appointment",
    description = "Building B, 3rd floor",
    selectedDate = LocalDate.of(2026, 11, 22),
    selectedTime = LocalTime.of(14, 30),
    zoneId = ZoneOffset.UTC,
    sentPins = listOf(
        SentPinRecord(
            id = "ppm-preview-1",
            title = "Team Standup",
            description = "Daily sync",
            startTimeMillis = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli(),
            durationMinutes = 15,
            iconUri = PinIcon.TIMELINE_CALENDAR.uri,
            backgroundColor = PinAccentColor.COBALT.hexCode,
            sentAtMillis = Instant.parse("2026-11-22T08:00:00Z").toEpochMilli(),
        ),
        SentPinRecord(
            id = "ppm-preview-2",
            title = "Flight to NYC",
            description = "Gate B12",
            startTimeMillis = Instant.parse("2026-11-23T18:15:00Z").toEpochMilli(),
            durationMinutes = 120,
            iconUri = PinIcon.SCHEDULED_FLIGHT.uri,
            backgroundColor = PinAccentColor.ORANGE.hexCode,
            sentAtMillis = Instant.parse("2026-11-22T07:30:00Z").toEpochMilli(),
        ),
    ),
)

@Preview(name = "Phone Portrait (Closed)", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Foldable Unfolded (Z Fold)", widthDp = 750, heightDp = 832, showBackground = true)
@Preview(name = "Phone Landscape", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun PinComposerScreenPreviews() {
    PebblePinManagerTheme {
        PinComposerScreen(
            uiState = previewUiState(),
            onTitleChange = {},
            onDescriptionChange = {},
            onDateSelected = {},
            onTimeSelected = {},
            onIconSelected = {},
            onDurationInputChange = {},
            onDurationPresetSelected = {},
            onBackgroundColorSelected = {},
            onSetNow = {},
            onQuickOffsetMinutes = {},
            onTomorrowMorning = {},
            onRefreshStatus = {},
            onSendPin = {},
            onEditPin = {},
            onCancelEdit = {},
            onDeletePin = {},
        )
    }
}
