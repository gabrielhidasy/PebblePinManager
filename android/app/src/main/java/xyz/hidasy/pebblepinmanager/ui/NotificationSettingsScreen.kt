package xyz.hidasy.pebblepinmanager.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinDurationFormatter
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.notifications.AppNotificationRuleConfig
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule

@Composable
fun NotificationSettingsRoute(
    viewModel: NotificationSettingsViewModel,
    onOpenNotificationAccessSettings: () -> Unit,
    onEditAutoPin: (SentPinRecord) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissionStatus()
    }

    NotificationSettingsScreen(
        uiState = uiState,
        onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
        onRefreshStatus = {
            viewModel.refreshPermissionStatus()
            viewModel.refreshInstalledApps()
        },
        onAutoPinEnabledChange = viewModel::onAutoPinEnabledChange,
        onExtractTimeFromTextChange = viewModel::onExtractTimeFromTextChange,
        onIgnoreOngoingChange = viewModel::onIgnoreOngoingChange,
        onIgnoreGroupSummaryChange = viewModel::onIgnoreGroupSummaryChange,
        onDeletePinOnDismissChange = viewModel::onDeletePinOnDismissChange,
        onDevModeEnabledChange = viewModel::onDevModeEnabledChange,
        onTogglePackageAllowed = viewModel::onTogglePackageAllowed,
        onClearAllowedPackages = viewModel::onClearAllowedPackages,
        onAppSearchQueryChange = viewModel::onAppSearchQueryChange,
        onShowSelectedAppsOnlyChange = viewModel::onShowSelectedAppsOnlyChange,
        onShowCustomRulesOnlyChange = viewModel::onShowCustomRulesOnlyChange,
        onManualPackageInputChange = viewModel::onManualPackageInputChange,
        onAddManualPackage = viewModel::onAddManualPackage,
        onKeywordFilterInputChange = viewModel::onKeywordFilterInputChange,
        onExcludeKeywordFilterInputChange = viewModel::onExcludeKeywordFilterInputChange,
        onTitleRegexInputChange = viewModel::onTitleRegexInputChange,
        onDescriptionRegexInputChange = viewModel::onDescriptionRegexInputChange,
        onTimeRegexInputChange = viewModel::onTimeRegexInputChange,
        onSaveParsingRules = viewModel::onSaveParsingRules,
        onRequireGlobalRegexRuleMatchChange = viewModel::onRequireGlobalRegexRuleMatchChange,
        onUpsertGlobalRegexRule = viewModel::onUpsertGlobalRegexRule,
        onDeleteGlobalRegexRule = viewModel::onDeleteGlobalRegexRule,
        onToggleGlobalRegexRuleEnabled = viewModel::onToggleGlobalRegexRuleEnabled,
        onMoveGlobalRegexRule = viewModel::onMoveGlobalRegexRule,
        onSaveAppRuleConfig = viewModel::onSaveAppRuleConfig,
        onResetAppRuleConfig = viewModel::onResetAppRuleConfig,
        onRuleTesterPackageChange = viewModel::onRuleTesterPackageChange,
        onRuleTesterTitleChange = viewModel::onRuleTesterTitleChange,
        onRuleTesterBodyChange = viewModel::onRuleTesterBodyChange,
        onDefaultIconSelected = viewModel::onDefaultIconSelected,
        onDefaultBackgroundColorSelected = viewModel::onDefaultBackgroundColorSelected,
        onEditAutoPin = onEditAutoPin,
        onDeleteAutoPin = viewModel::deleteAutoPin,
        onOpenSettings = onOpenSettings,
        bottomBar = bottomBar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsScreen(
    uiState: NotificationSettingsUiState,
    onOpenNotificationAccessSettings: () -> Unit,
    onRefreshStatus: () -> Unit,
    onAutoPinEnabledChange: (Boolean) -> Unit,
    onExtractTimeFromTextChange: (Boolean) -> Unit,
    onIgnoreOngoingChange: (Boolean) -> Unit,
    onIgnoreGroupSummaryChange: (Boolean) -> Unit,
    onDeletePinOnDismissChange: (Boolean) -> Unit,
    onDevModeEnabledChange: (Boolean) -> Unit = {},
    onTogglePackageAllowed: (String) -> Unit,
    onClearAllowedPackages: () -> Unit,
    onAppSearchQueryChange: (String) -> Unit,
    onShowSelectedAppsOnlyChange: (Boolean) -> Unit,
    onShowCustomRulesOnlyChange: (Boolean) -> Unit = {},
    onManualPackageInputChange: (String) -> Unit,
    onAddManualPackage: () -> Unit,
    onKeywordFilterInputChange: (String) -> Unit,
    onExcludeKeywordFilterInputChange: (String) -> Unit = {},
    onTitleRegexInputChange: (String) -> Unit,
    onDescriptionRegexInputChange: (String) -> Unit,
    onTimeRegexInputChange: (String) -> Unit,
    onSaveParsingRules: () -> Unit,
    onRequireGlobalRegexRuleMatchChange: (Boolean) -> Unit = {},
    onUpsertGlobalRegexRule: (NotificationRegexRule) -> Unit = {},
    onDeleteGlobalRegexRule: (String) -> Unit = {},
    onToggleGlobalRegexRuleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onMoveGlobalRegexRule: (String, Int) -> Unit = { _, _ -> },
    onSaveAppRuleConfig: (AppNotificationRuleConfig) -> Unit = {},
    onResetAppRuleConfig: (String) -> Unit = {},
    onRuleTesterPackageChange: (String?) -> Unit = {},
    onRuleTesterTitleChange: (String) -> Unit = {},
    onRuleTesterBodyChange: (String) -> Unit = {},
    onDefaultIconSelected: (PinIcon) -> Unit,
    onDefaultBackgroundColorSelected: (PinAccentColor?) -> Unit,
    onEditAutoPin: (SentPinRecord) -> Unit = {},
    onDeleteAutoPin: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var pinPendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingAppRulesPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var editingGlobalRule by remember { mutableStateOf<NotificationRegexRule?>(null) }
    var isAddingGlobalRule by remember { mutableStateOf(false) }
    val notificationAccessDisclosure = rememberNotificationAccessDisclosureState()

    val pinPendingDelete = pinPendingDeleteId?.let { pendingId ->
        uiState.autoPins.firstOrNull { it.id == pendingId }
    }
    val isDeletingPin = uiState.deletingPinId != null

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Notification Auto-Pins",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "100% On-Device • Opt-In Only",
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
                    IconButton(onClick = onRefreshStatus) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh notification access status and installed apps",
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NotificationAccessOnboardingCard(
                isAccessEnabled = uiState.isNotificationAccessEnabled,
                isListenerConnected = uiState.isListenerConnected,
                allowedCount = uiState.allowedCount,
                configuredAppRulesCount = uiState.configuredAppRulesCount,
                autoPinCount = uiState.autoPinCount,
                onOpenSettings = notificationAccessDisclosure::requestSettingsAccess,
            )

            RecentAutoPinsSection(
                uiState = uiState,
                isBusy = isDeletingPin,
                onEditAutoPin = onEditAutoPin,
                onRequestDeleteAutoPin = { record -> pinPendingDeleteId = record.id },
            )

            AutoPinBehaviorCard(
                isAutoPinEnabled = uiState.settings.isAutoPinEnabled,
                extractTimeFromText = uiState.settings.extractTimeFromText,
                ignoreOngoing = uiState.settings.ignoreOngoing,
                ignoreGroupSummary = uiState.settings.ignoreGroupSummary,
                deletePinOnDismiss = uiState.settings.deletePinOnDismiss,
                isDevModeAvailable = uiState.isDevModeAvailable,
                isDevModeEnabled = uiState.isDevModeEffectivelyEnabled,
                onAutoPinEnabledChange = onAutoPinEnabledChange,
                onExtractTimeFromTextChange = onExtractTimeFromTextChange,
                onIgnoreOngoingChange = onIgnoreOngoingChange,
                onIgnoreGroupSummaryChange = onIgnoreGroupSummaryChange,
                onDeletePinOnDismissChange = onDeletePinOnDismissChange,
                onDevModeEnabledChange = onDevModeEnabledChange,
            )

            AllowedAppsAllowlistCard(
                uiState = uiState,
                onSearchQueryChange = onAppSearchQueryChange,
                onShowSelectedOnlyChange = onShowSelectedAppsOnlyChange,
                onShowCustomRulesOnlyChange = onShowCustomRulesOnlyChange,
                onTogglePackageAllowed = onTogglePackageAllowed,
                onOpenAppRules = { pkg -> editingAppRulesPackage = pkg },
                onClearAllowedPackages = onClearAllowedPackages,
                onManualPackageInputChange = onManualPackageInputChange,
                onAddManualPackage = onAddManualPackage,
            )

            ParsingAndAppearanceCard(
                uiState = uiState,
                onKeywordFilterInputChange = onKeywordFilterInputChange,
                onExcludeKeywordFilterInputChange = onExcludeKeywordFilterInputChange,
                onTitleRegexInputChange = onTitleRegexInputChange,
                onDescriptionRegexInputChange = onDescriptionRegexInputChange,
                onTimeRegexInputChange = onTimeRegexInputChange,
                onSaveParsingRules = onSaveParsingRules,
                onRequireGlobalRegexRuleMatchChange = onRequireGlobalRegexRuleMatchChange,
                onAddGlobalRegexRule = {
                    isAddingGlobalRule = true
                    editingGlobalRule = NotificationRegexRule()
                },
                onEditGlobalRegexRule = { rule ->
                    isAddingGlobalRule = false
                    editingGlobalRule = rule
                },
                onDeleteGlobalRegexRule = onDeleteGlobalRegexRule,
                onToggleGlobalRegexRuleEnabled = onToggleGlobalRegexRuleEnabled,
                onMoveGlobalRegexRule = onMoveGlobalRegexRule,
                onDefaultIconSelected = onDefaultIconSelected,
                onDefaultBackgroundColorSelected = onDefaultBackgroundColorSelected,
            )

            RuleTesterCard(
                uiState = uiState,
                onPackageSelected = onRuleTesterPackageChange,
                onTitleChange = onRuleTesterTitleChange,
                onBodyChange = onRuleTesterBodyChange,
            )
        }
    }

    editingAppRulesPackage?.let { pkg ->
        AppRulesEditorDialog(
            packageName = pkg,
            appName = uiState.displayNameForPackage(pkg),
            initialConfig = uiState.appRuleConfigOrDefault(pkg),
            globalSettings = uiState.settings,
            zoneId = uiState.zoneId,
            onSave = { config ->
                onSaveAppRuleConfig(config)
                editingAppRulesPackage = null
            },
            onResetToGlobal = {
                onResetAppRuleConfig(pkg)
                editingAppRulesPackage = null
            },
            onDismiss = { editingAppRulesPackage = null },
        )
    }

    editingGlobalRule?.let { rule ->
        RegexRuleEditorDialog(
            initialRule = rule,
            isNew = isAddingGlobalRule,
            scopeLabel = "Global Defaults",
            onSave = { saved ->
                onUpsertGlobalRegexRule(saved)
                editingGlobalRule = null
            },
            onDismiss = { editingGlobalRule = null },
        )
    }

    pinPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pinPendingDeleteId = null },
            title = { Text("Delete Auto-Pin?") },
            text = {
                Text(
                    "Remove \"${pending.title}\" (${pending.id}) from your Pebble Timeline and recent auto-pin history?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pinPendingDeleteId = null
                        onDeleteAutoPin(pending.id)
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

    if (notificationAccessDisclosure.isVisible) {
        NotificationAccessDisclosureDialog(
            includeDevModeDisclosure = uiState.isDevModeAvailable,
            onAgreeAndOpenSettings = {
                notificationAccessDisclosure.confirmAndOpenSettings(onOpenNotificationAccessSettings)
            },
            onCancel = notificationAccessDisclosure::cancel,
            onDismissRequest = notificationAccessDisclosure::dismissWithoutConsent,
        )
    }
}

@Composable
private fun RecentAutoPinsSection(
    uiState: NotificationSettingsUiState,
    isBusy: Boolean,
    onEditAutoPin: (SentPinRecord) -> Unit,
    onRequestDeleteAutoPin: (SentPinRecord) -> Unit,
) {
    val autoPins = uiState.autoPins
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Recent Auto-Pins (${autoPins.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            uiState.pinActionFeedback?.let { feedback ->
                FeedbackBanner(feedback = feedback)
            }

            if (autoPins.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "No auto-created pins yet",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Pins generated from allowlisted notifications will appear here so you can review, edit, or delete them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            autoPins.take(10).forEachIndexed { index, record ->
                if (index > 0) {
                    HorizontalDivider()
                }
                val pinIcon = PinIcon.fromUri(record.iconUri)
                val accentColor = parseComposeHexColor(record.backgroundColor)
                val timestampText = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                    epochMillis = record.startTimeMillis,
                    zoneId = uiState.zoneId,
                )
                val durationSuffix = record.durationMinutes
                    ?.let { " • ${PinDurationFormatter.formatMinutes(it)}" }
                    .orEmpty()
                val isCurrentlyDeleting = record.id == uiState.deletingPinId

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
                            Text(
                                text = record.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
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
                            Text(
                                text = uiState.formatAutoPinSource(record),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { onEditAutoPin(record) },
                            enabled = !isBusy,
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
                            onClick = { onRequestDeleteAutoPin(record) },
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

@Composable
private fun NotificationAccessOnboardingCard(
    isAccessEnabled: Boolean,
    isListenerConnected: Boolean,
    allowedCount: Int,
    configuredAppRulesCount: Int,
    autoPinCount: Int,
    onOpenSettings: () -> Unit,
) {
    val containerColor = if (isAccessEnabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isAccessEnabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (isAccessEnabled) {
                        Icons.Default.NotificationsActive
                    } else {
                        Icons.Default.NotificationsOff
                    },
                    contentDescription = null,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isAccessEnabled) {
                            "Notification Access Granted"
                        } else {
                            "Grant Notification Access"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (isAccessEnabled) {
                            val connectionNote = if (isListenerConnected) {
                                "Listener service active."
                            } else {
                                "System listener ready."
                            }
                            val customNote = if (configuredAppRulesCount > 0) {
                                " • $configuredAppRulesCount custom app rule set(s)"
                            } else {
                                ""
                            }
                            "$connectionNote $allowedCount allowed app(s)$customNote • $autoPinCount auto-created pin(s) in history."
                        } else {
                            "Enable Notification Access in Android Settings to turn notifications from selected apps into Pebble Timeline pins. Manual pin creation always works without this permission."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Processed 100% locally on your device — no external servers.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (isAccessEnabled) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Manage Android Notification Access")
                }
            } else {
                Button(
                    onClick = onOpenSettings,
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

@Composable
private fun AutoPinBehaviorCard(
    isAutoPinEnabled: Boolean,
    extractTimeFromText: Boolean,
    ignoreOngoing: Boolean,
    ignoreGroupSummary: Boolean,
    deletePinOnDismiss: Boolean,
    isDevModeAvailable: Boolean,
    isDevModeEnabled: Boolean,
    onAutoPinEnabledChange: (Boolean) -> Unit,
    onExtractTimeFromTextChange: (Boolean) -> Unit,
    onIgnoreOngoingChange: (Boolean) -> Unit,
    onIgnoreGroupSummaryChange: (Boolean) -> Unit,
    onDeletePinOnDismissChange: (Boolean) -> Unit,
    onDevModeEnabledChange: (Boolean) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Auto-Pin Behavior",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            SettingSwitchRow(
                title = "Auto-create timeline pins",
                subtitle = "Convert notifications from allowlisted apps into Pebble pins",
                checked = isAutoPinEnabled,
                onCheckedChange = onAutoPinEnabledChange,
            )
            HorizontalDivider()
            SettingSwitchRow(
                title = "Extract event time from text",
                subtitle = "Detect times like \"14:30\", \"2:30 PM\", \"tomorrow at 9:00\", or \"in 30 min\"",
                checked = extractTimeFromText,
                onCheckedChange = onExtractTimeFromTextChange,
            )
            HorizontalDivider()
            SettingSwitchRow(
                title = "Ignore ongoing notifications",
                subtitle = "Skip persistent status, VPN, and media playback notifications",
                checked = ignoreOngoing,
                onCheckedChange = onIgnoreOngoingChange,
            )
            HorizontalDivider()
            SettingSwitchRow(
                title = "Ignore group summary notifications",
                subtitle = "Avoid duplicate pins from Android bundled notification summaries",
                checked = ignoreGroupSummary,
                onCheckedChange = onIgnoreGroupSummaryChange,
            )
            HorizontalDivider()
            SettingSwitchRow(
                title = "Delete pin when notification dismissed",
                subtitle = "Keep pins on your watch timeline by default even after swiping away",
                checked = deletePinOnDismiss,
                onCheckedChange = onDeletePinOnDismissChange,
            )
            if (isDevModeAvailable) {
                HorizontalDivider()
                SettingSwitchRow(
                    title = "Developer mode",
                    subtitle = "Show the Dev tab to inspect raw incoming notifications and explore regex rules interactively",
                    checked = isDevModeEnabled,
                    onCheckedChange = onDevModeEnabledChange,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowedAppsAllowlistCard(
    uiState: NotificationSettingsUiState,
    onSearchQueryChange: (String) -> Unit,
    onShowSelectedOnlyChange: (Boolean) -> Unit,
    onShowCustomRulesOnlyChange: (Boolean) -> Unit,
    onTogglePackageAllowed: (String) -> Unit,
    onOpenAppRules: (String) -> Unit,
    onClearAllowedPackages: () -> Unit,
    onManualPackageInputChange: (String) -> Unit,
    onAddManualPackage: () -> Unit,
) {
    val allowedPackages = uiState.settings.allowedPackages
    val filteredApps = uiState.filteredInstalledApps
    val unlistedAllowedPackages = uiState.unlistedAllowedPackages

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Allowed Source Apps (${allowedPackages.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Quick setup: check apps to allowlist with global defaults, or tap Rules for per-app regexes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (allowedPackages.isNotEmpty()) {
                    TextButton(onClick = onClearAllowedPackages) {
                        Text("Clear")
                    }
                }
            }

            if (unlistedAllowedPackages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Custom / Non-launcher packages",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        unlistedAllowedPackages.forEach { pkg ->
                            val badge = uiState.appRuleBadgeLabel(pkg)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                InputChip(
                                    selected = true,
                                    onClick = { onOpenAppRules(pkg) },
                                    label = {
                                        Text(
                                            if (badge != null) "$pkg ($badge)" else pkg,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "Edit rules for $pkg",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { onTogglePackageAllowed(pkg) },
                                            modifier = Modifier.size(18.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove $pkg",
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = uiState.appSearchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search installed apps") },
                placeholder = { Text("Name or package (e.g. Gmail, Calendar)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.appSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "filter-all") {
                    FilterChip(
                        selected = !uiState.showSelectedAppsOnly && !uiState.showCustomRulesOnly,
                        onClick = {
                            onShowSelectedOnlyChange(false)
                            onShowCustomRulesOnlyChange(false)
                        },
                        label = { Text("All Apps") },
                    )
                }
                item(key = "filter-selected") {
                    FilterChip(
                        selected = uiState.showSelectedAppsOnly,
                        onClick = { onShowSelectedOnlyChange(!uiState.showSelectedAppsOnly) },
                        label = { Text("Selected (${allowedPackages.size})") },
                    )
                }
                item(key = "filter-custom") {
                    FilterChip(
                        selected = uiState.showCustomRulesOnly,
                        onClick = { onShowCustomRulesOnlyChange(!uiState.showCustomRulesOnly) },
                        label = { Text("Custom Rules (${uiState.configuredAppRulesCount})") },
                    )
                }
            }

            when {
                uiState.isLoadingApps -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                filteredApps.isEmpty() -> {
                    Text(
                        text = when {
                            uiState.showCustomRulesOnly -> "No apps have custom rules yet. Select an app and tap Rules to add per-app regexes."
                            uiState.showSelectedAppsOnly -> "No installed launcher apps selected yet."
                            else -> "No matching apps found."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in allowedPackages
                            val badge = uiState.appRuleBadgeLabel(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTogglePackageAllowed(app.packageName) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onTogglePackageAllowed(app.packageName) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (badge != null) {
                                            "${app.packageName} • $badge"
                                        } else {
                                            app.packageName
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (badge != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (checked || badge != null) {
                                    OutlinedButton(
                                        onClick = { onOpenAppRules(app.packageName) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "Configure rules for ${app.appName}",
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (badge != null) "Edit" else "Rules",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.manualPackageInput,
                    onValueChange = onManualPackageInputChange,
                    label = { Text("Add package manually") },
                    placeholder = { Text("com.google.android.gm") },
                    isError = uiState.manualPackageError != null,
                    supportingText = uiState.manualPackageError?.let { err -> { Text(err) } },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onAddManualPackage,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun ParsingAndAppearanceCard(
    uiState: NotificationSettingsUiState,
    onKeywordFilterInputChange: (String) -> Unit,
    onExcludeKeywordFilterInputChange: (String) -> Unit,
    onTitleRegexInputChange: (String) -> Unit,
    onDescriptionRegexInputChange: (String) -> Unit,
    onTimeRegexInputChange: (String) -> Unit,
    onSaveParsingRules: () -> Unit,
    onRequireGlobalRegexRuleMatchChange: (Boolean) -> Unit,
    onAddGlobalRegexRule: () -> Unit,
    onEditGlobalRegexRule: (NotificationRegexRule) -> Unit,
    onDeleteGlobalRegexRule: (String) -> Unit,
    onToggleGlobalRegexRuleEnabled: (String, Boolean) -> Unit,
    onMoveGlobalRegexRule: (String, Int) -> Unit,
    onDefaultIconSelected: (PinIcon) -> Unit,
    onDefaultBackgroundColorSelected: (PinAccentColor?) -> Unit,
) {
    val globalRules = uiState.settings.globalRegexRules

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Global Defaults & Fallback Rules",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Applies to allowlisted apps without custom rules, and optionally as fallback for per-app rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = uiState.keywordFilterInput,
                onValueChange = onKeywordFilterInputChange,
                label = { Text("Global include keywords (optional)") },
                placeholder = { Text("flight, gate, delivery, appointment") },
                supportingText = {
                    Text("Comma-separated. If non-empty, only notifications containing a keyword create a pin.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.excludeKeywordFilterInput,
                onValueChange = onExcludeKeywordFilterInputChange,
                label = { Text("Global exclude keywords (optional)") },
                placeholder = { Text("promo, newsletter, unsubscribe") },
                supportingText = {
                    Text("Comma-separated. Notifications containing any excluded keyword are ignored.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.hasLegacyGlobalRegexes) {
                Text(
                    text = "Legacy Fallback Extraction Patterns",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = uiState.titleRegexInput,
                    onValueChange = onTitleRegexInputChange,
                    label = { Text("Title extraction regex (optional)") },
                    placeholder = { Text("e.g. Flight\\s+([A-Z0-9]+)") },
                    isError = uiState.titleRegexError != null,
                    supportingText = {
                        Text(
                            uiState.titleRegexError
                                ?: "Uses capturing group 1 (or full match) when matched; otherwise uses notification title.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.descriptionRegexInput,
                    onValueChange = onDescriptionRegexInputChange,
                    label = { Text("Description extraction regex (optional)") },
                    placeholder = { Text("e.g. Gate\\s+([A-Z0-9]+)") },
                    isError = uiState.descriptionRegexError != null,
                    supportingText = {
                        Text(
                            uiState.descriptionRegexError
                                ?: "Uses capturing group 1 (or full match) when matched; otherwise uses notification text.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.timeRegexInput,
                    onValueChange = onTimeRegexInputChange,
                    label = { Text("Time extraction regex (optional)") },
                    placeholder = { Text("e.g. Departs\\s+(\\d{1,2}:\\d{2})") },
                    isError = uiState.timeRegexError != null,
                    supportingText = {
                        Text(
                            uiState.timeRegexError
                                ?: "Optional regex to isolate event time before parsing; falls back to notification timestamp.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = onSaveParsingRules,
                enabled = uiState.hasUnsavedRuleEdits &&
                    uiState.titleRegexError == null &&
                    uiState.descriptionRegexError == null &&
                    uiState.timeRegexError == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiState.hasUnsavedRuleEdits) "Save Global Filters" else "Global Filters Up to Date")
            }

            if (uiState.rulesSavedMessage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = uiState.rulesSavedMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Global Regex Rules (${globalRules.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Optional shared regex rules evaluated top-to-bottom across apps without overriding per-app rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                globalRules.forEachIndexed { index, rule ->
                    RegexRuleSummaryCard(
                        rule = rule,
                        index = index,
                        totalCount = globalRules.size,
                        onToggleEnabled = { enabled -> onToggleGlobalRegexRuleEnabled(rule.id, enabled) },
                        onMoveUp = { onMoveGlobalRegexRule(rule.id, -1) },
                        onMoveDown = { onMoveGlobalRegexRule(rule.id, +1) },
                        onEdit = { onEditGlobalRegexRule(rule) },
                        onDelete = { onDeleteGlobalRegexRule(rule.id) },
                    )
                }

                OutlinedButton(
                    onClick = onAddGlobalRegexRule,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Global Regex Rule")
                }

                if (globalRules.isNotEmpty()) {
                    SettingSwitchRow(
                        title = "Only pin when a global regex rule matches",
                        subtitle = "Skip notifications falling through to global defaults unless one of the rules above matches",
                        checked = uiState.settings.requireGlobalRegexRuleMatch,
                        onCheckedChange = onRequireGlobalRegexRuleMatchChange,
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Default Notification Pin Icon",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(PinIcon.entries, key = { it.systemName }) { icon ->
                    FilterChip(
                        selected = icon == uiState.selectedDefaultIcon,
                        onClick = { onDefaultIconSelected(icon) },
                        label = { Text(icon.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = icon.toImageVector(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            Text(
                text = "Default Accent Color (basalt / emery)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "notif-color-default") {
                    FilterChip(
                        selected = uiState.selectedDefaultBackgroundColor == null,
                        onClick = { onDefaultBackgroundColorSelected(null) },
                        label = { Text("Default") },
                    )
                }
                items(PinAccentColor.entries, key = { it.hexCode }) { accent ->
                    AccentColorSwatch(
                        accent = accent,
                        isSelected = accent == uiState.selectedDefaultBackgroundColor,
                        contentDescPrefix = "notification pin",
                        onClick = {
                            onDefaultBackgroundColorSelected(
                                if (accent == uiState.selectedDefaultBackgroundColor) null else accent,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
