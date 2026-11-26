package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.PinDurationFormatter
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedAuthType
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedPollingOption
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedTestResult
import xyz.hidasy.pebblepinmanager.remote.RemotePinFeedConfig
import xyz.hidasy.pebblepinmanager.remote.RemoteSyncStatus
import java.time.ZoneId

@Composable
fun RemoteFeedsRoute(
    viewModel: RemoteFeedsViewModel,
    onEditRemotePin: (SentPinRecord) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RemoteFeedsScreen(
        uiState = uiState,
        onOpenAddFeed = viewModel::openAddFeedDialog,
        onOpenEditFeed = viewModel::openEditFeedDialog,
        onDismissEditor = viewModel::dismissEditorDialog,
        onPairingPayloadScanned = { raw -> viewModel.onPairingPayloadScanned(raw, fromDeepLink = false) },
        onApplyPairingToEditor = viewModel::applyPairingToEditor,
        onConfirmPendingPairing = viewModel::confirmPendingPairing,
        onCustomizePendingPairing = viewModel::customizePendingPairing,
        onDismissPendingPairing = viewModel::dismissPendingPairing,
        onEditorNameChange = viewModel::onEditorNameChange,
        onEditorUrlChange = viewModel::onEditorUrlChange,
        onEditorAuthTypeChange = viewModel::onEditorAuthTypeChange,
        onEditorApiKeyHeaderNameChange = viewModel::onEditorApiKeyHeaderNameChange,
        onEditorBasicUsernameChange = viewModel::onEditorBasicUsernameChange,
        onEditorSecretInputChange = viewModel::onEditorSecretInputChange,
        onEditorAllowCleartextHttpChange = viewModel::onEditorAllowCleartextHttpChange,
        onEditorEnabledChange = viewModel::onEditorEnabledChange,
        onEditorPollingIntervalChange = viewModel::onEditorPollingIntervalChange,
        onTestEditorConnection = viewModel::testEditorConnection,
        onSaveEditorFeed = viewModel::saveEditorFeed,
        onToggleFeedEnabled = viewModel::toggleFeedEnabled,
        onSyncFeed = viewModel::syncFeed,
        onSyncAll = viewModel::syncAllEnabledFeeds,
        onToggleFeedDiagnostics = viewModel::toggleFeedDiagnostics,
        onDeleteFeed = viewModel::deleteFeed,
        onEditRemotePin = onEditRemotePin,
        onDeleteRemotePin = viewModel::deleteRemotePin,
        onOpenSettings = onOpenSettings,
        bottomBar = bottomBar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteFeedsScreen(
    uiState: RemoteFeedsUiState,
    onOpenAddFeed: () -> Unit,
    onOpenEditFeed: (RemotePinFeedConfig) -> Unit,
    onDismissEditor: () -> Unit,
    onPairingPayloadScanned: (String) -> String? = { null },
    onApplyPairingToEditor: (String) -> String? = { null },
    onConfirmPendingPairing: () -> Unit = {},
    onCustomizePendingPairing: () -> Unit = {},
    onDismissPendingPairing: () -> Unit = {},
    onEditorNameChange: (String) -> Unit,
    onEditorUrlChange: (String) -> Unit,
    onEditorAuthTypeChange: (RemoteFeedAuthType) -> Unit,
    onEditorApiKeyHeaderNameChange: (String) -> Unit,
    onEditorBasicUsernameChange: (String) -> Unit,
    onEditorSecretInputChange: (String) -> Unit,
    onEditorAllowCleartextHttpChange: (Boolean) -> Unit,
    onEditorEnabledChange: (Boolean) -> Unit,
    onEditorPollingIntervalChange: (Int) -> Unit,
    onTestEditorConnection: () -> Unit,
    onSaveEditorFeed: () -> Unit,
    onToggleFeedEnabled: (String, Boolean) -> Unit,
    onSyncFeed: (String, Boolean) -> Unit,
    onSyncAll: () -> Unit,
    onToggleFeedDiagnostics: (String) -> Unit,
    onDeleteFeed: (String, Boolean) -> Unit,
    onEditRemotePin: (SentPinRecord) -> Unit,
    onDeleteRemotePin: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var feedPendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteFeedPinsToo by rememberSaveable { mutableStateOf(true) }
    var pinPendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeScannerMode by rememberSaveable { mutableStateOf<String?>(null) }

    val feedPendingDelete = feedPendingDeleteId?.let { id ->
        uiState.feeds.firstOrNull { it.id == id }
    }
    val pinPendingDelete = pinPendingDeleteId?.let { id ->
        uiState.remotePins.firstOrNull { it.id == id }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Remote Pin Feeds",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Opt-In Authenticated JSON Sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (uiState.enabledFeedCount > 0) {
                        IconButton(
                            onClick = onSyncAll,
                            enabled = !uiState.isAnySyncInProgress,
                        ) {
                            if (uiState.isSyncingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Sync all enabled feeds",
                                )
                            }
                        }
                    }
                    IconButton(onClick = { activeScannerMode = "instant" }) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan server pairing QR code",
                        )
                    }
                    IconButton(onClick = onOpenAddFeed) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add remote JSON feed",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "App settings",
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
            RemoteSyncOverviewCard(
                feedCount = uiState.feeds.size,
                enabledCount = uiState.enabledFeedCount,
                syncedPinCount = uiState.remotePinCount,
                isSyncing = uiState.isAnySyncInProgress,
                onScanQr = { activeScannerMode = "instant" },
                onAddFeed = onOpenAddFeed,
                onSyncAll = onSyncAll,
            )

            uiState.feedback?.let { feedback ->
                FeedbackBanner(feedback = feedback)
            }

            if (uiState.feeds.isEmpty()) {
                EmptyFeedsCard(
                    onScanQr = { activeScannerMode = "instant" },
                    onAddFeed = onOpenAddFeed,
                )
            } else {
                Text(
                    text = "Configured Feeds (${uiState.feeds.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                uiState.feeds.forEach { feed ->
                    RemoteFeedCard(
                        feed = feed,
                        syncedCount = uiState.syncedPinsCountForFeed(feed.id),
                        isSyncing = feed.id in uiState.syncingFeedIds || uiState.isSyncingAll,
                        isDiagnosticsExpanded = feed.id in uiState.expandedDiagnosticsFeedIds,
                        zoneId = uiState.zoneId,
                        onToggleEnabled = { enabled -> onToggleFeedEnabled(feed.id, enabled) },
                        onSyncNow = { onSyncFeed(feed.id, false) },
                        onEdit = { onOpenEditFeed(feed) },
                        onToggleDiagnostics = { onToggleFeedDiagnostics(feed.id) },
                        onRequestDelete = {
                            deleteFeedPinsToo = true
                            feedPendingDeleteId = feed.id
                        },
                    )
                }
            }

            SyncedRemotePinsSection(
                uiState = uiState,
                isBusy = uiState.deletingPinId != null,
                onEditRemotePin = onEditRemotePin,
                onRequestDeletePin = { record -> pinPendingDeleteId = record.id },
            )
        }
    }

    uiState.editorState?.let { editor ->
        RemoteFeedEditorDialog(
            editor = editor,
            onNameChange = onEditorNameChange,
            onUrlChange = onEditorUrlChange,
            onAuthTypeChange = onEditorAuthTypeChange,
            onApiKeyHeaderNameChange = onEditorApiKeyHeaderNameChange,
            onBasicUsernameChange = onEditorBasicUsernameChange,
            onSecretInputChange = onEditorSecretInputChange,
            onAllowCleartextHttpChange = onEditorAllowCleartextHttpChange,
            onEnabledChange = onEditorEnabledChange,
            onPollingIntervalChange = onEditorPollingIntervalChange,
            onScanQr = { activeScannerMode = "editor" },
            onTestConnection = onTestEditorConnection,
            onSave = onSaveEditorFeed,
            onDismiss = onDismissEditor,
        )
    }

    uiState.pendingPairing?.let { pending ->
        QrPairingConfirmationDialog(
            pending = pending,
            onConfirm = onConfirmPendingPairing,
            onCustomize = onCustomizePendingPairing,
            onDismiss = onDismissPendingPairing,
        )
    }

    activeScannerMode?.let { mode ->
        QrPairingScannerDialog(
            onPayloadDetected = { rawPayload ->
                if (mode == "editor") {
                    onApplyPairingToEditor(rawPayload)
                } else {
                    onPairingPayloadScanned(rawPayload)
                }
            },
            onDismiss = { activeScannerMode = null },
        )
    }

    feedPendingDelete?.let { feed ->
        val syncedCount = uiState.syncedPinsCountForFeed(feed.id)
        AlertDialog(
            onDismissRequest = { feedPendingDeleteId = null },
            title = { Text("Remove Remote Feed?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Remove \"${feed.displayName}\" and delete its stored credentials from this device?",
                    )
                    if (syncedCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = deleteFeedPinsToo,
                                onCheckedChange = { deleteFeedPinsToo = it },
                            )
                            Text(
                                text = "Also delete $syncedCount synced pin(s) from Pebble Timeline",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removePins = syncedCount > 0 && deleteFeedPinsToo
                        feedPendingDeleteId = null
                        onDeleteFeed(feed.id, removePins)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { feedPendingDeleteId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pinPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pinPendingDeleteId = null },
            title = { Text("Delete Synced Remote Pin?") },
            text = {
                Text(
                    "Remove \"${pending.title}\" (${pending.id}) from your Pebble Timeline? If the pin is still present in the remote JSON feed, it may be recreated on the next full sync.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pinPendingDeleteId = null
                        onDeleteRemotePin(pending.id)
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
}

@Composable
private fun RemoteSyncOverviewCard(
    feedCount: Int,
    enabledCount: Int,
    syncedPinCount: Int,
    isSyncing: Boolean,
    onScanQr: () -> Unit,
    onAddFeed: () -> Unit,
    onSyncAll: () -> Unit,
) {
    val containerColor = if (enabledCount > 0) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (enabledCount > 0) {
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
                    imageVector = if (enabledCount > 0) Icons.Default.CloudSync else Icons.Default.CloudOff,
                    contentDescription = null,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (enabledCount > 0) {
                            "Remote JSON Sync Active"
                        } else {
                            "Remote JSON Sync (Opt-In)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (feedCount > 0) {
                            "$enabledCount of $feedCount feed(s) enabled • $syncedPinCount active remote pin(s) on timeline."
                        } else {
                            "Pull Pebble Timeline pins from your own authenticated HTTPS JSON endpoint or companion server. No network traffic occurs until you add a feed."
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
                    text = "HTTPS by default • Credentials encrypted on-device & excluded from Auto Backup.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onScanQr,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Scan QR", maxLines = 1)
                }
                if (enabledCount > 0) {
                    OutlinedButton(
                        onClick = onSyncAll,
                        enabled = !isSyncing,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Syncing...", maxLines = 1)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Sync Now", maxLines = 1)
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onAddFeed,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Add Feed", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFeedsCard(
    onScanQr: () -> Unit,
    onAddFeed: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "No remote feeds configured",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Scan the QR code from your companion server's /register page for instant pairing, or manually add an authenticated HTTPS JSON URL (schemaVersion: 1).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onScanQr) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Scan QR")
                }
                OutlinedButton(onClick = onAddFeed) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Add Manually")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteFeedCard(
    feed: RemotePinFeedConfig,
    syncedCount: Int,
    isSyncing: Boolean,
    isDiagnosticsExpanded: Boolean,
    zoneId: ZoneId,
    onToggleEnabled: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onEdit: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val isHttps = feed.url.startsWith("https://", ignoreCase = true)
    val statusBadge = when (feed.lastSyncStatus) {
        RemoteSyncStatus.NEVER_SYNCED -> Triple(
            "Never synced",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RemoteSyncStatus.SUCCESS -> Triple(
            "Synced",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        RemoteSyncStatus.NOT_MODIFIED -> Triple(
            "Up to date (304)",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        RemoteSyncStatus.PARTIAL_SUCCESS -> Triple(
            "Partial",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        RemoteSyncStatus.ERROR -> Triple(
            "Error",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = feed.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = feed.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = feed.isEnabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeedChipTag(
                    label = statusBadge.first,
                    containerColor = statusBadge.second,
                    contentColor = statusBadge.third,
                )
                FeedChipTag(
                    label = if (isHttps) "HTTPS" else "HTTP (LAN)",
                    containerColor = if (isHttps) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (isHttps) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                FeedChipTag(
                    label = feed.authType.label,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FeedChipTag(
                    label = RemoteFeedPollingOption.formatInterval(feed.pollingIntervalMinutes),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FeedChipTag(
                    label = "$syncedCount pin${if (syncedCount == 1) "" else "s"}",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feed.lastSyncAtMillis?.let { syncMillis ->
                val formatted = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                    epochMillis = syncMillis,
                    zoneId = zoneId,
                )
                Text(
                    text = "Last sync: $formatted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feed.lastSyncSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feed.lastError?.let { errorMsg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (feed.lastDiagnostics.isNotEmpty()) {
                TextButton(onClick = onToggleDiagnostics) {
                    Icon(
                        imageVector = if (isDiagnosticsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isDiagnosticsExpanded) {
                            "Hide diagnostics (${feed.lastDiagnostics.size})"
                        } else {
                            "Show diagnostics (${feed.lastDiagnostics.size})"
                        },
                    )
                }
                if (isDiagnosticsExpanded) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            feed.lastDiagnostics.forEach { diag ->
                                Text(
                                    text = "• $diag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncing,
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Syncing...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Sync Now")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = onEdit,
                        enabled = !isSyncing,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Edit")
                    }
                    IconButton(
                        onClick = onRequestDelete,
                        enabled = !isSyncing,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove feed",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedRemotePinsSection(
    uiState: RemoteFeedsUiState,
    isBusy: Boolean,
    onEditRemotePin: (SentPinRecord) -> Unit,
    onRequestDeletePin: (SentPinRecord) -> Unit,
) {
    val pins = uiState.remotePins
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Synced Remote Pins (${pins.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (pins.isEmpty()) {
                Text(
                    text = "Pins synced from your configured remote JSON feeds will appear here, kept cleanly separated from your manual Recent Sent Pins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            pins.forEachIndexed { index, record ->
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
                                text = "Feed: ${uiState.feedDisplayNameForPin(record)} • ID: ${record.id}",
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
                            onClick = { onEditRemotePin(record) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteFeedEditorDialog(
    editor: RemoteFeedEditorState,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onAuthTypeChange: (RemoteFeedAuthType) -> Unit,
    onApiKeyHeaderNameChange: (String) -> Unit,
    onBasicUsernameChange: (String) -> Unit,
    onSecretInputChange: (String) -> Unit,
    onAllowCleartextHttpChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPollingIntervalChange: (Int) -> Unit,
    onScanQr: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showSecret by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!editor.isTesting) onDismiss() },
        title = {
            Text(if (editor.isEditing) "Edit Remote Pin Feed" else "Add Remote Pin Feed")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onScanQr,
                    enabled = !editor.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Fill from Server QR Code")
                }

                OutlinedTextField(
                    value = editor.name,
                    onValueChange = onNameChange,
                    label = { Text("Feed Name (optional)") },
                    placeholder = { Text("e.g. Companion Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = editor.url,
                    onValueChange = onUrlChange,
                    label = { Text("Remote JSON URL *") },
                    placeholder = { Text("https://example.com/pins.json") },
                    singleLine = true,
                    isError = editor.urlError != null,
                    supportingText = {
                        Text(
                            editor.urlError
                                ?: "HTTPS required by default. Must return schemaVersion: 1 JSON.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Authentication",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RemoteFeedAuthType.entries.forEach { authType ->
                        FilterChip(
                            selected = editor.authType == authType,
                            onClick = { onAuthTypeChange(authType) },
                            label = { Text(authType.label) },
                        )
                    }
                }

                if (editor.authType == RemoteFeedAuthType.API_KEY_HEADER) {
                    OutlinedTextField(
                        value = editor.apiKeyHeaderName,
                        onValueChange = onApiKeyHeaderNameChange,
                        label = { Text("API Key Header Name *") },
                        placeholder = { Text("X-API-Key") },
                        singleLine = true,
                        isError = editor.headerNameError != null,
                        supportingText = editor.headerNameError?.let { err -> { Text(err) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (editor.authType == RemoteFeedAuthType.BASIC) {
                    OutlinedTextField(
                        value = editor.basicUsername,
                        onValueChange = onBasicUsernameChange,
                        label = { Text("Username *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (editor.authType != RemoteFeedAuthType.NONE) {
                    val secretLabel = when (editor.authType) {
                        RemoteFeedAuthType.BEARER_TOKEN -> "Bearer Token *"
                        RemoteFeedAuthType.API_KEY_HEADER -> "API Key *"
                        RemoteFeedAuthType.BASIC -> "Password *"
                        RemoteFeedAuthType.NONE -> ""
                    }
                    OutlinedTextField(
                        value = editor.secretInput,
                        onValueChange = onSecretInputChange,
                        label = { Text(secretLabel) },
                        placeholder = {
                            if (editor.isEditing && editor.hasStoredSecret) {
                                Text("Leave blank to keep stored secret")
                            } else {
                                Text("Enter secret")
                            }
                        },
                        singleLine = true,
                        isError = editor.authError != null,
                        visualTransformation = if (showSecret) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showSecret) "Hide secret" else "Show secret",
                                )
                            }
                        },
                        supportingText = {
                            Text(
                                editor.authError
                                    ?: if (editor.isEditing && editor.hasStoredSecret) {
                                        "Encrypted secret stored on-device (excluded from Auto Backup). Enter a new value to replace it."
                                    } else {
                                        "Encrypted on-device with Android Keystore and excluded from Auto Backup."
                                    },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Sync",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Include this feed in Sync All and periodic background polling",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = editor.isEnabled,
                        onCheckedChange = onEnabledChange,
                    )
                }

                Text(
                    text = "Background Polling Interval (WorkManager)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RemoteFeedPollingOption.entries.forEach { option ->
                        FilterChip(
                            selected = editor.pollingIntervalMinutes == option.minutes,
                            onClick = { onPollingIntervalChange(option.minutes) },
                            label = { Text(option.label) },
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (editor.allowCleartextHttp) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (editor.allowCleartextHttp) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = "Allow HTTP (LAN testing)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Unencrypted HTTP sends credentials in cleartext. Use only on trusted local networks.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = editor.allowCleartextHttp,
                        onCheckedChange = onAllowCleartextHttpChange,
                    )
                }

                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !editor.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (editor.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Testing connection...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Test Connection")
                    }
                }

                editor.testResult?.let { result ->
                    TestResultBanner(result = result)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !editor.isTesting,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !editor.isTesting,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun TestResultBanner(result: RemoteFeedTestResult) {
    when (result) {
        is RemoteFeedTestResult.Success -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (result.skippedCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Connected (HTTP ${result.httpStatus}, schema v${result.schemaVersion})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = buildString {
                            append("${result.validPinCount} valid pin(s)")
                            if (result.skippedCount > 0) append(" • ${result.skippedCount} invalid skipped")
                            if (result.etagPresent) append(" • ETag supported")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    result.diagnostics.forEach { diag ->
                        Text(
                            text = "• $diag",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        is RemoteFeedTestResult.Failure -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = result.headline,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = result.details,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrPairingConfirmationDialog(
    pending: PendingQrPairing,
    onConfirm: () -> Unit,
    onCustomize: () -> Unit,
    onDismiss: () -> Unit,
) {
    val payload = pending.payload
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (pending.isUpdatingExisting) {
                    "Update Paired Pin Server?"
                } else {
                    "Pair Remote Pin Server?"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (pending.isUpdatingExisting) {
                        "This pairing payload matches your existing feed \"${pending.existingFeedName ?: payload.displayName}\" and will update its credentials."
                    } else {
                        "Review the companion server details before saving credentials to this device:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = payload.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = payload.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Auth: ${payload.authType.label} • Poll: ${RemoteFeedPollingOption.formatInterval(payload.pollingIntervalMinutes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (payload.isCleartextHttp) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Unencrypted HTTP (LAN testing): Credentials will be sent in cleartext. Confirm only on a trusted local network.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (payload.isCleartextHttp) "Allow HTTP & Pair" else "Pair & Sync")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCustomize) {
                    Text("Customize")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun FeedChipTag(
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
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
