package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo

@Composable
fun NotificationSettingsRoute(
    viewModel: NotificationSettingsViewModel,
    onOpenNotificationAccessSettings: () -> Unit,
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
        onTogglePackageAllowed = viewModel::onTogglePackageAllowed,
        onClearAllowedPackages = viewModel::onClearAllowedPackages,
        onAppSearchQueryChange = viewModel::onAppSearchQueryChange,
        onShowSelectedAppsOnlyChange = viewModel::onShowSelectedAppsOnlyChange,
        onManualPackageInputChange = viewModel::onManualPackageInputChange,
        onAddManualPackage = viewModel::onAddManualPackage,
        onKeywordFilterInputChange = viewModel::onKeywordFilterInputChange,
        onTitleRegexInputChange = viewModel::onTitleRegexInputChange,
        onDescriptionRegexInputChange = viewModel::onDescriptionRegexInputChange,
        onTimeRegexInputChange = viewModel::onTimeRegexInputChange,
        onSaveParsingRules = viewModel::onSaveParsingRules,
        onDefaultIconSelected = viewModel::onDefaultIconSelected,
        onDefaultBackgroundColorSelected = viewModel::onDefaultBackgroundColorSelected,
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
    onTogglePackageAllowed: (String) -> Unit,
    onClearAllowedPackages: () -> Unit,
    onAppSearchQueryChange: (String) -> Unit,
    onShowSelectedAppsOnlyChange: (Boolean) -> Unit,
    onManualPackageInputChange: (String) -> Unit,
    onAddManualPackage: () -> Unit,
    onKeywordFilterInputChange: (String) -> Unit,
    onTitleRegexInputChange: (String) -> Unit,
    onDescriptionRegexInputChange: (String) -> Unit,
    onTimeRegexInputChange: (String) -> Unit,
    onSaveParsingRules: () -> Unit,
    onDefaultIconSelected: (PinIcon) -> Unit,
    onDefaultBackgroundColorSelected: (PinAccentColor?) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

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
                autoPinCount = uiState.autoPinCount,
                onOpenSettings = onOpenNotificationAccessSettings,
            )

            AutoPinBehaviorCard(
                isAutoPinEnabled = uiState.settings.isAutoPinEnabled,
                extractTimeFromText = uiState.settings.extractTimeFromText,
                ignoreOngoing = uiState.settings.ignoreOngoing,
                ignoreGroupSummary = uiState.settings.ignoreGroupSummary,
                deletePinOnDismiss = uiState.settings.deletePinOnDismiss,
                onAutoPinEnabledChange = onAutoPinEnabledChange,
                onExtractTimeFromTextChange = onExtractTimeFromTextChange,
                onIgnoreOngoingChange = onIgnoreOngoingChange,
                onIgnoreGroupSummaryChange = onIgnoreGroupSummaryChange,
                onDeletePinOnDismissChange = onDeletePinOnDismissChange,
            )

            AllowedAppsAllowlistCard(
                allowedPackages = uiState.settings.allowedPackages,
                filteredApps = uiState.filteredInstalledApps,
                unlistedAllowedPackages = uiState.unlistedAllowedPackages,
                isLoadingApps = uiState.isLoadingApps,
                searchQuery = uiState.appSearchQuery,
                showSelectedOnly = uiState.showSelectedAppsOnly,
                manualPackageInput = uiState.manualPackageInput,
                manualPackageError = uiState.manualPackageError,
                onSearchQueryChange = onAppSearchQueryChange,
                onShowSelectedOnlyChange = onShowSelectedAppsOnlyChange,
                onTogglePackageAllowed = onTogglePackageAllowed,
                onClearAllowedPackages = onClearAllowedPackages,
                onManualPackageInputChange = onManualPackageInputChange,
                onAddManualPackage = onAddManualPackage,
            )

            ParsingAndAppearanceCard(
                keywordFilterInput = uiState.keywordFilterInput,
                titleRegexInput = uiState.titleRegexInput,
                titleRegexError = uiState.titleRegexError,
                descriptionRegexInput = uiState.descriptionRegexInput,
                descriptionRegexError = uiState.descriptionRegexError,
                timeRegexInput = uiState.timeRegexInput,
                timeRegexError = uiState.timeRegexError,
                hasUnsavedEdits = uiState.hasUnsavedRuleEdits,
                rulesSavedMessage = uiState.rulesSavedMessage,
                selectedIcon = uiState.selectedDefaultIcon,
                selectedBackgroundColor = uiState.selectedDefaultBackgroundColor,
                onKeywordFilterInputChange = onKeywordFilterInputChange,
                onTitleRegexInputChange = onTitleRegexInputChange,
                onDescriptionRegexInputChange = onDescriptionRegexInputChange,
                onTimeRegexInputChange = onTimeRegexInputChange,
                onSaveParsingRules = onSaveParsingRules,
                onDefaultIconSelected = onDefaultIconSelected,
                onDefaultBackgroundColorSelected = onDefaultBackgroundColorSelected,
            )
        }
    }
}

@Composable
private fun NotificationAccessOnboardingCard(
    isAccessEnabled: Boolean,
    isListenerConnected: Boolean,
    allowedCount: Int,
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
                            "$connectionNote $allowedCount allowed app(s) selected • $autoPinCount auto-created pin(s) in history."
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
    onAutoPinEnabledChange: (Boolean) -> Unit,
    onExtractTimeFromTextChange: (Boolean) -> Unit,
    onIgnoreOngoingChange: (Boolean) -> Unit,
    onIgnoreGroupSummaryChange: (Boolean) -> Unit,
    onDeletePinOnDismissChange: (Boolean) -> Unit,
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
    allowedPackages: Set<String>,
    filteredApps: List<InstalledAppInfo>,
    unlistedAllowedPackages: List<String>,
    isLoadingApps: Boolean,
    searchQuery: String,
    showSelectedOnly: Boolean,
    manualPackageInput: String,
    manualPackageError: String?,
    onSearchQueryChange: (String) -> Unit,
    onShowSelectedOnlyChange: (Boolean) -> Unit,
    onTogglePackageAllowed: (String) -> Unit,
    onClearAllowedPackages: () -> Unit,
    onManualPackageInputChange: (String) -> Unit,
    onAddManualPackage: () -> Unit,
) {
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
                        text = "Opt-in only: notifications from unselected apps are ignored.",
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
                            InputChip(
                                selected = true,
                                onClick = { onTogglePackageAllowed(pkg) },
                                label = { Text(pkg) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove $pkg",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search installed apps") },
                placeholder = { Text("Name or package (e.g. Calendar, Delivery)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showSelectedOnly,
                    onClick = { onShowSelectedOnlyChange(false) },
                    label = { Text("All Apps") },
                )
                FilterChip(
                    selected = showSelectedOnly,
                    onClick = { onShowSelectedOnlyChange(true) },
                    label = { Text("Selected (${allowedPackages.size})") },
                )
            }

            when {
                isLoadingApps -> {
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
                        text = if (showSelectedOnly) {
                            "No installed launcher apps selected yet."
                        } else {
                            "No matching apps found."
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
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in allowedPackages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTogglePackageAllowed(app.packageName) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
                    value = manualPackageInput,
                    onValueChange = onManualPackageInputChange,
                    label = { Text("Add package manually") },
                    placeholder = { Text("com.example.flights") },
                    isError = manualPackageError != null,
                    supportingText = manualPackageError?.let { err -> { Text(err) } },
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
    keywordFilterInput: String,
    titleRegexInput: String,
    titleRegexError: String?,
    descriptionRegexInput: String,
    descriptionRegexError: String?,
    timeRegexInput: String,
    timeRegexError: String?,
    hasUnsavedEdits: Boolean,
    rulesSavedMessage: String?,
    selectedIcon: PinIcon,
    selectedBackgroundColor: PinAccentColor?,
    onKeywordFilterInputChange: (String) -> Unit,
    onTitleRegexInputChange: (String) -> Unit,
    onDescriptionRegexInputChange: (String) -> Unit,
    onTimeRegexInputChange: (String) -> Unit,
    onSaveParsingRules: () -> Unit,
    onDefaultIconSelected: (PinIcon) -> Unit,
    onDefaultBackgroundColorSelected: (PinAccentColor?) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Filtering, Regex & Pin Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = keywordFilterInput,
                onValueChange = onKeywordFilterInputChange,
                label = { Text("Keyword filter (optional)") },
                placeholder = { Text("flight, gate, delivery, appointment") },
                supportingText = {
                    Text("Comma-separated. If non-empty, only notifications containing a keyword create a pin.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = titleRegexInput,
                onValueChange = onTitleRegexInputChange,
                label = { Text("Title extraction regex (optional)") },
                placeholder = { Text("e.g. Flight\\s+([A-Z0-9]+)") },
                isError = titleRegexError != null,
                supportingText = {
                    Text(
                        titleRegexError
                            ?: "Uses capturing group 1 (or full match) when matched; otherwise uses notification title.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = descriptionRegexInput,
                onValueChange = onDescriptionRegexInputChange,
                label = { Text("Description extraction regex (optional)") },
                placeholder = { Text("e.g. Gate\\s+([A-Z0-9]+)") },
                isError = descriptionRegexError != null,
                supportingText = {
                    Text(
                        descriptionRegexError
                            ?: "Uses capturing group 1 (or full match) when matched; otherwise uses notification text.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = timeRegexInput,
                onValueChange = onTimeRegexInputChange,
                label = { Text("Time extraction regex (optional)") },
                placeholder = { Text("e.g. Departs\\s+(\\d{1,2}:\\d{2})") },
                isError = timeRegexError != null,
                supportingText = {
                    Text(
                        timeRegexError
                            ?: "Optional regex to isolate event time before parsing; falls back to notification timestamp.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSaveParsingRules,
                enabled = hasUnsavedEdits &&
                    titleRegexError == null &&
                    descriptionRegexError == null &&
                    timeRegexError == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (hasUnsavedEdits) "Save Parsing Rules" else "Parsing Rules Up to Date")
            }

            if (rulesSavedMessage != null) {
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
                        text = rulesSavedMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
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
                        selected = icon == selectedIcon,
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
                        selected = selectedBackgroundColor == null,
                        onClick = { onDefaultBackgroundColorSelected(null) },
                        label = { Text("Default") },
                    )
                }
                items(PinAccentColor.entries, key = { it.hexCode }) { accent ->
                    val swatchColor = accent.toComposeColor()
                    val isSelected = accent == selectedBackgroundColor
                    Box(
                        modifier = Modifier
                            .size(34.dp)
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
                                contentDescription = "${accent.label} notification pin accent color"
                                selected = isSelected
                            }
                            .clickable(role = Role.RadioButton) {
                                onDefaultBackgroundColorSelected(if (isSelected) null else accent)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = swatchColor.contrastingCheckColor(),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
