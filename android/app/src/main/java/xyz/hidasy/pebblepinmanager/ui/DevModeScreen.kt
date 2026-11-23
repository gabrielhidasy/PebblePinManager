package xyz.hidasy.pebblepinmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.notifications.DraftRuleExplorationResult
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleAction
import xyz.hidasy.pebblepinmanager.notifications.NotificationTextField
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRecord
import xyz.hidasy.pebblepinmanager.notifications.RegexExplorationResult
import java.time.ZoneId

@Composable
fun DevModeRoute(
    viewModel: DevModeViewModel,
    onOpenNotificationAccessSettings: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissionStatus()
    }

    DevModeScreen(
        uiState = uiState,
        onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
        onSnapshotActiveNotifications = viewModel::snapshotActiveNotifications,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onHideOngoingChange = viewModel::onHideOngoingChange,
        onHideGroupSummaryChange = viewModel::onHideGroupSummaryChange,
        onAllowlistedOnlyChange = viewModel::onAllowlistedOnlyChange,
        onSelectNotification = viewModel::onSelectNotification,
        onClearSelection = viewModel::onClearSelection,
        onDeleteNotification = viewModel::onDeleteNotification,
        onClearAllNotifications = viewModel::onClearAllNotifications,
        onScratchpadPatternChange = viewModel::onScratchpadPatternChange,
        onScratchpadFieldChange = viewModel::onScratchpadFieldChange,
        onScratchpadIgnoreCaseChange = viewModel::onScratchpadIgnoreCaseChange,
        onApplyScratchpadToMatch = viewModel::onApplyScratchpadToMatch,
        onApplyScratchpadToExclude = viewModel::onApplyScratchpadToExclude,
        onApplyScratchpadToTitle = viewModel::onApplyScratchpadToTitle,
        onApplyScratchpadToDescription = viewModel::onApplyScratchpadToDescription,
        onApplyScratchpadToTime = viewModel::onApplyScratchpadToTime,
        onDraftRuleChange = viewModel::onDraftRuleChange,
        onApplySubjectAsTitlePreset = viewModel::onApplySubjectAsTitlePreset,
        onLoadExistingRule = viewModel::onLoadExistingRule,
        onResetDraftRule = viewModel::onResetDraftRule,
        onAllowlistSelectedPackage = viewModel::onAllowlistSelectedPackage,
        onSaveDraftAsAppRule = viewModel::onSaveDraftAsAppRule,
        onSaveDraftAsGlobalRule = viewModel::onSaveDraftAsGlobalRule,
        onDismissStatusMessage = viewModel::onDismissStatusMessage,
        bottomBar = bottomBar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevModeScreen(
    uiState: DevModeUiState,
    onOpenNotificationAccessSettings: () -> Unit,
    onSnapshotActiveNotifications: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onHideOngoingChange: (Boolean) -> Unit,
    onHideGroupSummaryChange: (Boolean) -> Unit,
    onAllowlistedOnlyChange: (Boolean) -> Unit,
    onSelectNotification: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteNotification: (String) -> Unit,
    onClearAllNotifications: () -> Unit,
    onScratchpadPatternChange: (String) -> Unit,
    onScratchpadFieldChange: (NotificationTextField) -> Unit,
    onScratchpadIgnoreCaseChange: (Boolean) -> Unit,
    onApplyScratchpadToMatch: () -> Unit,
    onApplyScratchpadToExclude: () -> Unit,
    onApplyScratchpadToTitle: () -> Unit,
    onApplyScratchpadToDescription: () -> Unit,
    onApplyScratchpadToTime: () -> Unit,
    onDraftRuleChange: ((NotificationRegexRule) -> NotificationRegexRule) -> Unit,
    onApplySubjectAsTitlePreset: () -> Unit,
    onLoadExistingRule: (NotificationRegexRule) -> Unit,
    onResetDraftRule: () -> Unit,
    onAllowlistSelectedPackage: () -> Unit,
    onSaveDraftAsAppRule: () -> Unit,
    onSaveDraftAsGlobalRule: () -> Unit,
    onDismissStatusMessage: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selected = uiState.selectedNotification
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selected != null) {
        onClearSelection()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = onClearSelection) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to raw notifications",
                            )
                        }
                    }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (selected != null) {
                                uiState.displayNameFor(selected)
                            } else {
                                "Dev Inspector"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (selected != null) {
                                selected.sourcePackage
                            } else {
                                "Raw Notifications & Regex Explorer"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = onSnapshotActiveNotifications) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Load active notifications",
                            )
                        }
                        if (uiState.rawNotifications.isNotEmpty()) {
                            IconButton(onClick = { showClearConfirm = true }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Clear raw notifications",
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        if (selected == null) {
            RawNotificationsListContent(
                uiState = uiState,
                onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
                onSnapshotActiveNotifications = onSnapshotActiveNotifications,
                onSearchQueryChange = onSearchQueryChange,
                onHideOngoingChange = onHideOngoingChange,
                onHideGroupSummaryChange = onHideGroupSummaryChange,
                onAllowlistedOnlyChange = onAllowlistedOnlyChange,
                onSelectNotification = onSelectNotification,
                onDeleteNotification = onDeleteNotification,
                onDismissStatusMessage = onDismissStatusMessage,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            RawNotificationRegexExplorerContent(
                record = selected,
                uiState = uiState,
                onScratchpadPatternChange = onScratchpadPatternChange,
                onScratchpadFieldChange = onScratchpadFieldChange,
                onScratchpadIgnoreCaseChange = onScratchpadIgnoreCaseChange,
                onApplyScratchpadToMatch = onApplyScratchpadToMatch,
                onApplyScratchpadToExclude = onApplyScratchpadToExclude,
                onApplyScratchpadToTitle = onApplyScratchpadToTitle,
                onApplyScratchpadToDescription = onApplyScratchpadToDescription,
                onApplyScratchpadToTime = onApplyScratchpadToTime,
                onDraftRuleChange = onDraftRuleChange,
                onApplySubjectAsTitlePreset = onApplySubjectAsTitlePreset,
                onLoadExistingRule = onLoadExistingRule,
                onResetDraftRule = onResetDraftRule,
                onAllowlistSelectedPackage = onAllowlistSelectedPackage,
                onSaveDraftAsAppRule = onSaveDraftAsAppRule,
                onSaveDraftAsGlobalRule = onSaveDraftAsGlobalRule,
                onDismissStatusMessage = onDismissStatusMessage,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Raw Notifications?") },
            text = {
                Text("Remove all ${uiState.rawNotifications.size} captured raw notification(s) from the local Dev Inspector log?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearAllNotifications()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RawNotificationsListContent(
    uiState: DevModeUiState,
    onOpenNotificationAccessSettings: () -> Unit,
    onSnapshotActiveNotifications: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onHideOngoingChange: (Boolean) -> Unit,
    onHideGroupSummaryChange: (Boolean) -> Unit,
    onAllowlistedOnlyChange: (Boolean) -> Unit,
    onSelectNotification: (String) -> Unit,
    onDeleteNotification: (String) -> Unit,
    onDismissStatusMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = uiState.filteredNotifications

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!uiState.isNotificationAccessEnabled) {
            item(key = "dev-access-warning") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Notification Access Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Grant Android Notification Access so Dev Inspector can capture raw incoming notifications on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onOpenNotificationAccessSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Open Notification Access Settings")
                        }
                    }
                }
            }
        }

        uiState.statusMessage?.let { message ->
            item(key = "dev-status-banner") {
                DevStatusBanner(message = message, onDismiss = onDismissStatusMessage)
            }
        }

        item(key = "dev-search-and-filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("Search raw notifications") },
                    placeholder = { Text("App, package, title, or text") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "filter-hide-ongoing") {
                        FilterChip(
                            selected = uiState.hideOngoing,
                            onClick = { onHideOngoingChange(!uiState.hideOngoing) },
                            label = { Text("Hide Ongoing") },
                        )
                    }
                    item(key = "filter-hide-summary") {
                        FilterChip(
                            selected = uiState.hideGroupSummary,
                            onClick = { onHideGroupSummaryChange(!uiState.hideGroupSummary) },
                            label = { Text("Hide Summaries") },
                        )
                    }
                    item(key = "filter-allowlisted") {
                        FilterChip(
                            selected = uiState.allowlistedOnly,
                            onClick = { onAllowlistedOnlyChange(!uiState.allowlistedOnly) },
                            label = { Text("Allowlisted Only") },
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "dev-empty-state") {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = if (uiState.rawNotifications.isEmpty()) {
                                "No raw notifications captured yet"
                            } else {
                                "No notifications match your current filters"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = if (uiState.rawNotifications.isEmpty()) {
                                "While Developer Mode is enabled, incoming notifications from all apps are logged locally here so you can tap one and explore regexes."
                            } else {
                                "Try clearing your search query or adjusting the filter chips above."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (uiState.rawNotifications.isEmpty()) {
                            OutlinedButton(onClick = onSnapshotActiveNotifications) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Load Active Notifications")
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "dev-count-header") {
                Text(
                    text = "Captured Notifications (${filtered.size}) • Tap any item to explore regexes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(filtered, key = { it.id }) { record ->
                RawNotificationListCard(
                    record = record,
                    appDisplayName = uiState.displayNameFor(record),
                    isAllowlisted = uiState.isAllowlisted(record.sourcePackage),
                    zoneId = uiState.zoneId,
                    onClick = { onSelectNotification(record.id) },
                    onDelete = { onDeleteNotification(record.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RawNotificationListCard(
    record: RawNotificationRecord,
    appDisplayName: String,
    isAllowlisted: Boolean,
    zoneId: ZoneId,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val bodyPreview = record.textFields().rawBody
    val formattedTime = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
        epochMillis = record.capturedAtMillis,
        zoneId = zoneId,
    )

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appDisplayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${record.sourcePackage} • $formattedTime",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove captured notification",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Text(
                text = record.title.ifBlank { "(empty title)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (!bodyPreview.isNullOrBlank()) {
                Text(
                    text = bodyPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DevTagPill(
                    text = if (isAllowlisted) "Allowlisted" else "Not Allowlisted",
                    containerColor = if (isAllowlisted) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isAllowlisted) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                record.pipelineOutcome?.let { outcome ->
                    DevTagPill(
                        text = outcome,
                        containerColor = if (outcome.startsWith("Delivered")) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (outcome.startsWith("Delivered")) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (record.isOngoing) {
                    DevTagPill(
                        text = "Ongoing",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (record.isGroupSummary) {
                    DevTagPill(
                        text = "Group Summary",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevTagPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DevStatusBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss message",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RawNotificationRegexExplorerContent(
    record: RawNotificationRecord,
    uiState: DevModeUiState,
    onScratchpadPatternChange: (String) -> Unit,
    onScratchpadFieldChange: (NotificationTextField) -> Unit,
    onScratchpadIgnoreCaseChange: (Boolean) -> Unit,
    onApplyScratchpadToMatch: () -> Unit,
    onApplyScratchpadToExclude: () -> Unit,
    onApplyScratchpadToTitle: () -> Unit,
    onApplyScratchpadToDescription: () -> Unit,
    onApplyScratchpadToTime: () -> Unit,
    onDraftRuleChange: ((NotificationRegexRule) -> NotificationRegexRule) -> Unit,
    onApplySubjectAsTitlePreset: () -> Unit,
    onLoadExistingRule: (NotificationRegexRule) -> Unit,
    onResetDraftRule: () -> Unit,
    onAllowlistSelectedPackage: () -> Unit,
    onSaveDraftAsAppRule: () -> Unit,
    onSaveDraftAsGlobalRule: () -> Unit,
    onDismissStatusMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val appDisplayName = uiState.displayNameFor(record)
    val isAllowlisted = uiState.isAllowlisted(record.sourcePackage)

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        uiState.statusMessage?.let { message ->
            DevStatusBanner(message = message, onDismiss = onDismissStatusMessage)
        }

        RawNotificationPayloadCard(
            record = record,
            appDisplayName = appDisplayName,
            isAllowlisted = isAllowlisted,
            zoneId = uiState.zoneId,
            onAllowlistPackage = onAllowlistSelectedPackage,
        )

        uiState.draftExploration?.let { exploration ->
            CurrentRulesStatusCard(
                exploration = exploration,
                existingAppRules = uiState.existingAppRulesForSelected,
                existingGlobalRules = uiState.existingGlobalRules,
                appDisplayName = appDisplayName,
                zoneId = uiState.zoneId,
                onLoadExistingRule = onLoadExistingRule,
            )
        }

        RegexScratchpadCard(
            pattern = uiState.scratchpadPattern,
            selectedField = uiState.scratchpadField,
            ignoreCase = uiState.scratchpadIgnoreCase,
            result = uiState.scratchpadResult,
            onPatternChange = onScratchpadPatternChange,
            onFieldChange = onScratchpadFieldChange,
            onIgnoreCaseChange = onScratchpadIgnoreCaseChange,
            onApplyToMatch = onApplyScratchpadToMatch,
            onApplyToExclude = onApplyScratchpadToExclude,
            onApplyToTitle = onApplyScratchpadToTitle,
            onApplyToDescription = onApplyScratchpadToDescription,
            onApplyToTime = onApplyScratchpadToTime,
        )

        DraftRuleExplorerCard(
            appDisplayName = appDisplayName,
            draftRule = uiState.draftRule,
            validation = uiState.draftRuleValidation,
            exploration = uiState.draftExploration,
            zoneId = uiState.zoneId,
            onDraftRuleChange = onDraftRuleChange,
            onApplySubjectAsTitlePreset = onApplySubjectAsTitlePreset,
            onResetDraftRule = onResetDraftRule,
            onSaveAsAppRule = onSaveDraftAsAppRule,
            onSaveAsGlobalRule = onSaveDraftAsGlobalRule,
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RawNotificationPayloadCard(
    record: RawNotificationRecord,
    appDisplayName: String,
    isAllowlisted: Boolean,
    zoneId: ZoneId,
    onAllowlistPackage: () -> Unit,
) {
    var showMetadata by rememberSaveable(record.id) { mutableStateOf(false) }
    val textFields = remember(record) { record.textFields() }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Raw Notification Fields",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isAllowlisted) {
                    OutlinedButton(
                        onClick = onAllowlistPackage,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Allowlist", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Text(
                text = "Tap and hold any field value below to copy exact text for your regex.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RawFieldBlock(label = "EXTRA_TITLE (raw)", value = record.title.ifEmpty { null })
                    RawFieldBlock(label = "EXTRA_TEXT (raw)", value = record.text)
                    RawFieldBlock(label = "EXTRA_BIG_TEXT / LINES (raw)", value = record.bigText)
                    RawFieldBlock(label = "EXTRA_SUB_TEXT (raw)", value = record.subText)
                    HorizontalDivider()
                    Text(
                        text = "Normalized Mapper Targets",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    RawFieldBlock(label = "Title target", value = textFields.rawTitle.ifEmpty { null })
                    RawFieldBlock(label = "Body target", value = textFields.rawBody)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showMetadata = !showMetadata }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Notification Metadata",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (showMetadata) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showMetadata) "Hide metadata" else "Show metadata",
                )
            }

            if (showMetadata) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val eventTimeFormatted = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                            epochMillis = record.eventTimeMillis,
                            zoneId = zoneId,
                        )
                        val postTimeFormatted = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                            epochMillis = record.postTimeMillis,
                            zoneId = zoneId,
                        )
                        MetadataRow("App", "$appDisplayName (${record.sourcePackage})")
                        MetadataRow("Key", record.notificationKey.ifBlank { "(empty)" })
                        MetadataRow("ID / Tag", "${record.notificationId} / ${record.tag ?: "(null)"}")
                        MetadataRow("Channel", record.channelId ?: "(null)")
                        MetadataRow("Category", record.category ?: "(null)")
                        MetadataRow("Event Time", "$eventTimeFormatted (${record.eventTimeMillis})")
                        MetadataRow("Post Time", "$postTimeFormatted (${record.postTimeMillis})")
                        MetadataRow("Flags", "ongoing=${record.isOngoing}, groupSummary=${record.isGroupSummary}")
                        record.pipelineOutcome?.let { MetadataRow("Last Outcome", it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawFieldBlock(
    label: String,
    value: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = value ?: "(null)",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (value != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentRulesStatusCard(
    exploration: DraftRuleExplorationResult,
    existingAppRules: List<NotificationRegexRule>,
    existingGlobalRules: List<NotificationRegexRule>,
    appDisplayName: String,
    zoneId: ZoneId,
    onLoadExistingRule: (NotificationRegexRule) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Current Saved Rules Result",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            RuleEvaluationPreviewBox(
                evaluation = exploration.currentSettingsEvaluation,
                zoneId = zoneId,
            )

            if (existingAppRules.isNotEmpty() || existingGlobalRules.isNotEmpty()) {
                Text(
                    text = "Load an existing rule into the explorer:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    existingAppRules.forEachIndexed { idx, rule ->
                        AssistChip(
                            onClick = { onLoadExistingRule(rule) },
                            label = { Text("$appDisplayName: ${rule.displayName(idx)}") },
                        )
                    }
                    existingGlobalRules.forEachIndexed { idx, rule ->
                        AssistChip(
                            onClick = { onLoadExistingRule(rule) },
                            label = { Text("Global: ${rule.displayName(idx)}") },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegexScratchpadCard(
    pattern: String,
    selectedField: NotificationTextField,
    ignoreCase: Boolean,
    result: RegexExplorationResult?,
    onPatternChange: (String) -> Unit,
    onFieldChange: (NotificationTextField) -> Unit,
    onIgnoreCaseChange: (Boolean) -> Unit,
    onApplyToMatch: () -> Unit,
    onApplyToExclude: () -> Unit,
    onApplyToTitle: () -> Unit,
    onApplyToDescription: () -> Unit,
    onApplyToTime: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Regex Scratchpad",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Experiment with any regex against this notification and inspect live highlighted matches & capturing groups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FieldSelectorRow(
                label = "Target field",
                selectedField = selectedField,
                onFieldSelected = onFieldChange,
            )

            OutlinedTextField(
                value = pattern,
                onValueChange = onPatternChange,
                label = { Text("Test regex pattern") },
                placeholder = { Text("""e.g. Flight\s+([A-Z0-9]+) or (\d{1,2}:\d{2})""") },
                isError = result?.errorMessage != null,
                supportingText = {
                    Text(
                        result?.errorMessage
                            ?: "Auto-pin rules match case-insensitively and extract capturing group 1 (or full match).",
                    )
                },
                trailingIcon = {
                    if (pattern.isNotEmpty()) {
                        IconButton(onClick = { onPatternChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear pattern")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = ignoreCase,
                    onClick = { onIgnoreCaseChange(!ignoreCase) },
                    label = { Text(if (ignoreCase) "Ignore Case (default)" else "Case Sensitive") },
                )
                if (result != null && !result.isBlankPattern && result.isValid) {
                    DevTagPill(
                        text = if (result.isMatched) {
                            "${result.matchCount} match${if (result.matchCount == 1) "" else "es"}"
                        } else {
                            "No match"
                        },
                        containerColor = if (result.isMatched) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (result.isMatched) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (result != null) {
                val highlightBg = MaterialTheme.colorScheme.primaryContainer
                val highlightFg = MaterialTheme.colorScheme.onPrimaryContainer
                val annotated = remember(result, highlightBg, highlightFg) {
                    buildHighlightedText(
                        text = result.targetText,
                        spans = result.matches.map { it.range },
                        highlightBackground = highlightBg,
                        highlightForeground = highlightFg,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(12.dp),
                ) {
                    Text(
                        text = if (result.targetText.isEmpty()) {
                            AnnotatedString("(target field is empty)")
                        } else {
                            annotated
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }

                val firstMatch = result.firstMatch
                if (firstMatch != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "First Match Breakdown",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            MetadataRow("Full (Group 0)", "\"${firstMatch.value}\"")
                            firstMatch.capturingGroups.forEachIndexed { idx, groupValue ->
                                val tag = if (idx == 0) "Group 1 ★" else "Group ${idx + 1}"
                                MetadataRow(tag, groupValue?.let { "\"$it\"" } ?: "(unmatched)")
                            }
                            MetadataRow(
                                "Extracted",
                                result.extractedValue?.let { "\"$it\"" } ?: "(empty)",
                            )
                        }
                    }
                }

                if (!result.isBlankPattern && result.isValid) {
                    Text(
                        text = "Copy regex to Draft Rule:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AssistChip(onClick = onApplyToMatch, label = { Text("-> Match") })
                        AssistChip(onClick = onApplyToExclude, label = { Text("-> Exclude") })
                        AssistChip(onClick = onApplyToTitle, label = { Text("-> Title") })
                        AssistChip(onClick = onApplyToDescription, label = { Text("-> Description") })
                        AssistChip(onClick = onApplyToTime, label = { Text("-> Time") })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftRuleExplorerCard(
    appDisplayName: String,
    draftRule: NotificationRegexRule,
    validation: xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRuleValidation,
    exploration: DraftRuleExplorationResult?,
    zoneId: ZoneId,
    onDraftRuleChange: ((NotificationRegexRule) -> NotificationRegexRule) -> Unit,
    onApplySubjectAsTitlePreset: () -> Unit,
    onResetDraftRule: () -> Unit,
    onSaveAsAppRule: () -> Unit,
    onSaveAsGlobalRule: () -> Unit,
) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rule Builder & Live Pin Preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Build a complete regex rule against this notification, preview the pin live, and save it directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onResetDraftRule) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset draft rule",
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NotificationRuleAction.entries.forEach { action ->
                    FilterChip(
                        selected = draftRule.action == action,
                        onClick = { onDraftRuleChange { it.copy(action = action) } },
                        label = { Text(action.label) },
                    )
                }
                AssistChip(
                    onClick = onApplySubjectAsTitlePreset,
                    label = { Text("Preset: Body as Title") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            OutlinedTextField(
                value = draftRule.name,
                onValueChange = { value -> onDraftRuleChange { it.copy(name = value) } },
                label = { Text("Rule name (optional)") },
                placeholder = { Text("e.g. $appDisplayName Alerts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FieldSelectorRow(
                label = "Match against",
                selectedField = draftRule.matchTarget,
                onFieldSelected = { field -> onDraftRuleChange { it.copy(matchTarget = field) } },
            )
            OutlinedTextField(
                value = draftRule.matchPattern,
                onValueChange = { value -> onDraftRuleChange { it.copy(matchPattern = value) } },
                label = {
                    Text(
                        if (draftRule.action == NotificationRuleAction.IGNORE) {
                            "Match regex (required)"
                        } else {
                            "Match regex (optional)"
                        },
                    )
                },
                placeholder = { Text("Blank matches all notifications for this scope") },
                isError = validation.matchPatternError != null,
                supportingText = {
                    Text(
                        validation.matchPatternError
                            ?: formatFieldMatchStatus(exploration?.matchResult, blankHint = "Blank = matches all"),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (draftRule.action == NotificationRuleAction.CREATE_PIN) {
                FieldSelectorRow(
                    label = "Exclude against",
                    selectedField = draftRule.excludeTarget,
                    onFieldSelected = { field -> onDraftRuleChange { it.copy(excludeTarget = field) } },
                )
                OutlinedTextField(
                    value = draftRule.excludePattern,
                    onValueChange = { value -> onDraftRuleChange { it.copy(excludePattern = value) } },
                    label = { Text("Exclude regex (optional)") },
                    placeholder = { Text("e.g. promo|newsletter") },
                    isError = validation.excludePatternError != null,
                    supportingText = {
                        Text(
                            validation.excludePatternError
                                ?: formatExcludeStatus(exploration?.excludeResult),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                FieldSelectorRow(
                    label = "Pin title source",
                    selectedField = draftRule.titleSource,
                    onFieldSelected = { field -> onDraftRuleChange { it.copy(titleSource = field) } },
                )
                OutlinedTextField(
                    value = draftRule.titleRegexPattern,
                    onValueChange = { value -> onDraftRuleChange { it.copy(titleRegexPattern = value) } },
                    label = { Text("Title extraction regex (optional)") },
                    placeholder = { Text("""e.g. Flight\s+([A-Z0-9]+)""") },
                    isError = validation.titleRegexError != null,
                    supportingText = {
                        Text(
                            validation.titleRegexError
                                ?: formatExtractionStatus(
                                    result = exploration?.titleResult,
                                    blankHint = if (draftRule.titleSource == NotificationTextField.BODY) {
                                        "Blank = 1st line of Body"
                                    } else {
                                        "Blank = notification title"
                                    },
                                ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldSelectorRow(
                    label = "Pin description source",
                    selectedField = draftRule.descriptionSource,
                    onFieldSelected = { field -> onDraftRuleChange { it.copy(descriptionSource = field) } },
                )
                OutlinedTextField(
                    value = draftRule.descriptionRegexPattern,
                    onValueChange = { value -> onDraftRuleChange { it.copy(descriptionRegexPattern = value) } },
                    label = { Text("Description extraction regex (optional)") },
                    placeholder = { Text("""e.g. Gate\s+([A-Z0-9]+)""") },
                    isError = validation.descriptionRegexError != null,
                    supportingText = {
                        Text(
                            validation.descriptionRegexError
                                ?: formatExtractionStatus(
                                    result = exploration?.descriptionResult,
                                    blankHint = "Blank = default description from chosen source",
                                ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = draftRule.timeRegexPattern,
                    onValueChange = { value -> onDraftRuleChange { it.copy(timeRegexPattern = value) } },
                    label = { Text("Time extraction regex (optional)") },
                    placeholder = { Text("""e.g. Departs\s+(\d{1,2}:\d{2})""") },
                    isError = validation.timeRegexError != null,
                    supportingText = {
                        Text(
                            validation.timeRegexError
                                ?: formatTimeStatus(
                                    result = exploration?.timeResult,
                                    parsedTimeMillis = exploration?.parsedEventTimeMillis,
                                    zoneId = zoneId,
                                ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (exploration != null) {
                HorizontalDivider()
                Text(
                    text = "Draft Rule Preview",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                RuleEvaluationPreviewBox(
                    evaluation = exploration.draftEvaluation,
                    zoneId = zoneId,
                )

                if (exploration.pipelineWarnings.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Live Pipeline Notes",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            exploration.pipelineWarnings.forEach { warning ->
                                Text(
                                    text = "• $warning",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaveAsAppRule,
                    enabled = validation.isValid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Save as $appDisplayName Rule", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onSaveAsGlobalRule,
                    enabled = validation.isValid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save as Global Regex Rule")
                }
            }
        }
    }
}

private fun formatFieldMatchStatus(
    result: RegexExplorationResult?,
    blankHint: String,
): String {
    if (result == null || result.isBlankPattern) return blankHint
    return if (result.isMatched) {
        "Matched (${result.matchCount}): \"${result.firstMatch?.value.orEmpty()}\""
    } else {
        "No match on ${result.targetField.label}"
    }
}

private fun formatExcludeStatus(result: RegexExplorationResult?): String {
    if (result == null || result.isBlankPattern) return "Blank = no exclusion"
    return if (result.isMatched) {
        "Excluded! Matched \"${result.firstMatch?.value.orEmpty()}\""
    } else {
        "Not excluded (no match on ${result.targetField.label})"
    }
}

private fun formatExtractionStatus(
    result: RegexExplorationResult?,
    blankHint: String,
): String {
    if (result == null || result.isBlankPattern) return blankHint
    return if (result.isMatched) {
        "Extracted: \"${result.extractedValue.orEmpty()}\""
    } else {
        "No match — falls back to default"
    }
}

private fun formatTimeStatus(
    result: RegexExplorationResult?,
    parsedTimeMillis: Long?,
    zoneId: ZoneId,
): String {
    val parsedText = parsedTimeMillis?.let {
        TimelineTimestampFormatter.formatLocalDateTimeWithZone(epochMillis = it, zoneId = zoneId)
    }
    if (result == null || result.isBlankPattern) {
        return if (parsedText != null) {
            "Auto-detected time: $parsedText"
        } else {
            "Blank = auto time detection / notification post time"
        }
    }
    if (!result.isMatched) {
        return "No regex match — falls back to notification post time"
    }
    return if (parsedText != null) {
        "Extracted \"${result.extractedValue.orEmpty()}\" -> $parsedText"
    } else {
        "Matched \"${result.extractedValue.orEmpty()}\", but could not parse as a timeline timestamp"
    }
}

internal fun buildHighlightedText(
    text: String,
    spans: List<IntRange>,
    highlightBackground: Color,
    highlightForeground: Color,
): AnnotatedString {
    if (text.isEmpty() || spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        spans.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (start < end) {
                addStyle(
                    style = SpanStyle(
                        background = highlightBackground,
                        color = highlightForeground,
                        fontWeight = FontWeight.Bold,
                    ),
                    start = start,
                    end = end,
                )
            }
        }
    }
}
