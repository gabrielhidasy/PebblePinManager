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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.notifications.AppNotificationRuleConfig
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinEvaluation
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleAction
import xyz.hidasy.pebblepinmanager.notifications.NotificationRulePresets
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleValidator
import xyz.hidasy.pebblepinmanager.notifications.NotificationTextField
import xyz.hidasy.pebblepinmanager.notifications.RuleBasedNotificationPinMapper
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegexRuleSummaryCard(
    rule: NotificationRegexRule,
    index: Int,
    totalCount: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accentColor = parseComposeHexColor(rule.backgroundColor)
    val customIcon = rule.iconUri?.let { PinIcon.fromUri(it) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (customIcon != null || accentColor != null) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(accentColor ?: MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = (customIcon ?: PinIcon.NOTIFICATION_FLAG).toImageVector(),
                            contentDescription = null,
                            tint = accentColor?.contrastingCheckColor()
                                ?: MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${rule.displayName(index)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildRuleSubtitle(rule),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move rule up",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move rule down",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit rule",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete rule",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun buildRuleSubtitle(rule: NotificationRegexRule): String {
    val parts = mutableListOf<String>()
    if (rule.action == NotificationRuleAction.IGNORE) {
        parts += "Ignore when [${rule.matchTarget.label}] matches /${rule.matchPattern}/"
        return parts.joinToString(" • ")
    }
    if (rule.matchPattern.isNotBlank()) {
        parts += "Match [${rule.matchTarget.label}]: /${rule.matchPattern}/"
    } else {
        parts += "Matches all"
    }
    if (rule.excludePattern.isNotBlank()) {
        parts += "Exclude: /${rule.excludePattern}/"
    }
    if (rule.titleSource != NotificationTextField.ANY || rule.titleRegexPattern.isNotBlank()) {
        val src = rule.titleSource.label
        parts += if (rule.titleRegexPattern.isNotBlank()) {
            "Title [$src]: /${rule.titleRegexPattern}/"
        } else {
            "Title from $src"
        }
    }
    if (rule.descriptionRegexPattern.isNotBlank()) {
        parts += "Desc: /${rule.descriptionRegexPattern}/"
    } else if (rule.descriptionSource == NotificationTextField.TITLE) {
        parts += "Desc from Title"
    }
    if (rule.timeRegexPattern.isNotBlank()) {
        parts += "Time: /${rule.timeRegexPattern}/"
    }
    return parts.joinToString(" • ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppRulesEditorDialog(
    packageName: String,
    appName: String,
    initialConfig: AppNotificationRuleConfig,
    globalSettings: NotificationRuleSettings,
    zoneId: ZoneId,
    onSave: (AppNotificationRuleConfig) -> Unit,
    onResetToGlobal: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(packageName, initialConfig) { mutableStateOf(initialConfig) }
    var editingRule by remember { mutableStateOf<NotificationRegexRule?>(null) }
    var isAddingNewRule by remember { mutableStateOf(false) }

    var testerExpanded by rememberSaveable(packageName) { mutableStateOf(false) }
    var sampleTitle by rememberSaveable(packageName) { mutableStateOf("") }
    var sampleBody by rememberSaveable(packageName) { mutableStateOf("") }
    var previewEval by remember { mutableStateOf<NotificationPinEvaluation?>(null) }

    LaunchedEffect(draft, sampleTitle, sampleBody, globalSettings) {
        if (sampleTitle.isBlank() && sampleBody.isBlank()) {
            previewEval = null
        } else {
            val previewSettings = globalSettings.copy(
                allowedPackages = globalSettings.allowedPackages + packageName,
                appRules = globalSettings.appRules + (packageName to draft),
            )
            val mapper = RuleBasedNotificationPinMapper(
                settings = previewSettings,
                zoneIdProvider = { zoneId },
                pinIdGenerator = { "ppm-preview" },
            )
            previewEval = mapper.evaluate(
                xyz.hidasy.pebblepinmanager.notifications.NotificationPinCandidate(
                    sourcePackage = packageName,
                    notificationKey = "preview|$packageName|0",
                    title = sampleTitle,
                    text = sampleBody.takeIf { it.isNotBlank() },
                    bigText = sampleBody.takeIf { it.isNotBlank() },
                    eventTimeMillis = System.currentTimeMillis(),
                    appLabel = appName,
                ),
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$appName Rules",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close app rules")
                    }
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable custom rules for this app",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Turn off to temporarily use global defaults without deleting rules.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.enabled,
                            onCheckedChange = { draft = draft.copy(enabled = it) },
                        )
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = draft.keywordFilter,
                        onValueChange = { draft = draft.copy(keywordFilter = it) },
                        label = { Text("App include keywords (optional)") },
                        placeholder = { Text("flight, calendar, invoice") },
                        supportingText = {
                            Text("Comma-separated. Only notifications containing a keyword match this app.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = draft.excludeKeywordFilter,
                        onValueChange = { draft = draft.copy(excludeKeywordFilter = it) },
                        label = { Text("App exclude keywords (optional)") },
                        placeholder = { Text("newsletter, promo, unsubscribe") },
                        supportingText = {
                            Text("Comma-separated. Notifications containing any excluded keyword are ignored.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Regex Rules (${draft.regexRules.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Evaluated top-to-bottom. First matching enabled rule wins.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (draft.regexRules.isEmpty()) {
                        Text(
                            text = "No per-app regex rules yet. Add one or more rules to filter by sender/body, extract subject lines, or ignore noisy alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        draft.regexRules.forEachIndexed { index, rule ->
                            RegexRuleSummaryCard(
                                rule = rule,
                                index = index,
                                totalCount = draft.regexRules.size,
                                onToggleEnabled = { enabled ->
                                    draft = draft.copy(
                                        regexRules = draft.regexRules.map {
                                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                                        },
                                    )
                                },
                                onMoveUp = {
                                    if (index > 0) {
                                        val mutable = draft.regexRules.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index - 1, item)
                                        draft = draft.copy(regexRules = mutable)
                                    }
                                },
                                onMoveDown = {
                                    if (index < draft.regexRules.lastIndex) {
                                        val mutable = draft.regexRules.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index + 1, item)
                                        draft = draft.copy(regexRules = mutable)
                                    }
                                },
                                onEdit = {
                                    isAddingNewRule = false
                                    editingRule = rule
                                },
                                onDelete = {
                                    draft = draft.copy(
                                        regexRules = draft.regexRules.filterNot { it.id == rule.id },
                                    )
                                },
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                isAddingNewRule = true
                                editingRule = NotificationRegexRule()
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Regex Rule")
                        }
                        OutlinedButton(
                            onClick = {
                                draft = draft.copy(
                                    regexRules = draft.regexRules + NotificationRulePresets.subjectAsTitleRule(),
                                )
                            },
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Preset: Body as Title")
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Only pin when an app rule matches",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Great for Gmail/chat: skip notifications unless one of this app's regex rules matches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.requireRegexRuleMatch,
                            onCheckedChange = { draft = draft.copy(requireRegexRuleMatch = it) },
                        )
                    }

                    if (!draft.requireRegexRuleMatch) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Fall back to global rules if unmatched",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Apply global filters & regex rules when no app-specific regex rule matches.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = draft.fallbackToGlobalRules,
                                onCheckedChange = { draft = draft.copy(fallbackToGlobalRules = it) },
                            )
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "App Default Pin Icon (optional)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val selectedAppIcon = draft.defaultIconUri?.let { PinIcon.fromUri(it) }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(key = "app-icon-inherit") {
                            FilterChip(
                                selected = selectedAppIcon == null,
                                onClick = { draft = draft.copy(defaultIconUri = null) },
                                label = { Text("Use Global") },
                            )
                        }
                        items(PinIcon.entries, key = { it.systemName }) { icon ->
                            FilterChip(
                                selected = icon == selectedAppIcon,
                                onClick = { draft = draft.copy(defaultIconUri = icon.uri) },
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
                        text = "App Default Accent Color (optional)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val selectedAppColor = PinAccentColor.fromHex(draft.defaultBackgroundColor)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(key = "app-color-inherit") {
                            FilterChip(
                                selected = selectedAppColor == null,
                                onClick = { draft = draft.copy(defaultBackgroundColor = null) },
                                label = { Text("Use Global") },
                            )
                        }
                        items(PinAccentColor.entries, key = { it.hexCode }) { accent ->
                            AccentColorSwatch(
                                accent = accent,
                                isSelected = accent == selectedAppColor,
                                contentDescPrefix = "App default",
                                onClick = {
                                    draft = draft.copy(
                                        defaultBackgroundColor = if (accent == selectedAppColor) null else accent.hexCode,
                                    )
                                },
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { testerExpanded = !testerExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Test these rules with a sample notification",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Icon(
                            imageVector = if (testerExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (testerExpanded) "Hide tester" else "Show tester",
                        )
                    }

                    if (testerExpanded) {
                        OutlinedTextField(
                            value = sampleTitle,
                            onValueChange = { sampleTitle = it },
                            label = { Text("Sample notification title") },
                            placeholder = { Text("e.g. Delta Air Lines (Gmail sender)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = sampleBody,
                            onValueChange = { sampleBody = it },
                            label = { Text("Sample notification body") },
                            placeholder = { Text("e.g. Flight DL123 departs 16:30\nGate B12") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        previewEval?.let { evaluation ->
                            RuleEvaluationPreviewBox(evaluation = evaluation, zoneId = zoneId)
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (initialConfig.hasCustomizations()) {
                        TextButton(
                            onClick = onResetToGlobal,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Reset to Global")
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Button(onClick = { onSave(draft) }) {
                            Text("Save Rules")
                        }
                    }
                }
            }
        }
    }

    editingRule?.let { activeRule ->
        RegexRuleEditorDialog(
            initialRule = activeRule,
            isNew = isAddingNewRule,
            scopeLabel = appName,
            onSave = { savedRule ->
                val updatedList = if (isAddingNewRule) {
                    draft.regexRules + savedRule
                } else {
                    draft.regexRules.map { if (it.id == savedRule.id) savedRule else it }
                }
                draft = draft.copy(regexRules = updatedList)
                editingRule = null
            },
            onDismiss = { editingRule = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegexRuleEditorDialog(
    initialRule: NotificationRegexRule,
    isNew: Boolean,
    scopeLabel: String,
    onSave: (NotificationRegexRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initialRule.id) { mutableStateOf(initialRule) }
    val validation = remember(draft) { NotificationRuleValidator.validateRule(draft) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isNew) "New Regex Rule" else "Edit Regex Rule",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Scope: $scopeLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close rule editor")
                    }
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("Rule name") },
                        placeholder = { Text("e.g. Flight Alerts, Calendar Invites, Ignore Promos") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = "Action when matched",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotificationRuleAction.entries.forEach { action ->
                            FilterChip(
                                selected = draft.action == action,
                                onClick = { draft = draft.copy(action = action) },
                                label = { Text(action.label) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = draft.matchPattern,
                        onValueChange = { draft = draft.copy(matchPattern = it) },
                        label = {
                            Text(
                                if (draft.action == NotificationRuleAction.IGNORE) {
                                    "Match regex (required)"
                                } else {
                                    "Match regex (optional)"
                                },
                            )
                        },
                        placeholder = { Text("e.g. Delta|United or Invitation:") },
                        isError = validation.matchPatternError != null,
                        supportingText = {
                            Text(
                                validation.matchPatternError
                                    ?: "Rule only triggers when this regex matches the selected target below (blank = match all).",
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FieldSelectorRow(
                        label = "Match against",
                        selectedField = draft.matchTarget,
                        onFieldSelected = { draft = draft.copy(matchTarget = it) },
                    )

                    if (draft.action == NotificationRuleAction.CREATE_PIN) {
                        OutlinedTextField(
                            value = draft.excludePattern,
                            onValueChange = { draft = draft.copy(excludePattern = it) },
                            label = { Text("Exclude regex (optional)") },
                            placeholder = { Text("e.g. survey|unsubscribe|all-day") },
                            isError = validation.excludePatternError != null,
                            supportingText = {
                                Text(
                                    validation.excludePatternError
                                        ?: "Skip this rule if this regex matches.",
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        HorizontalDivider()

                        FieldSelectorRow(
                            label = "Pin title source",
                            selectedField = draft.titleSource,
                            onFieldSelected = { draft = draft.copy(titleSource = it) },
                        )

                        OutlinedTextField(
                            value = draft.titleRegexPattern,
                            onValueChange = { draft = draft.copy(titleRegexPattern = it) },
                            label = { Text("Title extraction regex (optional)") },
                            placeholder = { Text("e.g. Flight\\s+([A-Z0-9]+) or Subject:\\s*(.+)") },
                            isError = validation.titleRegexError != null,
                            supportingText = {
                                Text(
                                    validation.titleRegexError
                                        ?: if (draft.titleSource == NotificationTextField.BODY) {
                                            "Uses group 1 (or full match) from Body; if blank, uses 1st line of Body (great for Gmail)."
                                        } else {
                                            "Uses capturing group 1 (or full match) when matched."
                                        },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        FieldSelectorRow(
                            label = "Pin description source",
                            selectedField = draft.descriptionSource,
                            onFieldSelected = { draft = draft.copy(descriptionSource = it) },
                        )

                        OutlinedTextField(
                            value = draft.descriptionRegexPattern,
                            onValueChange = { draft = draft.copy(descriptionRegexPattern = it) },
                            label = { Text("Description extraction regex (optional)") },
                            placeholder = { Text("e.g. Gate\\s+([A-Z0-9]+)") },
                            isError = validation.descriptionRegexError != null,
                            supportingText = {
                                Text(
                                    validation.descriptionRegexError
                                        ?: "Uses group 1 (or full match) from the chosen source; set source to Title to keep Gmail sender as description.",
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = draft.timeRegexPattern,
                            onValueChange = { draft = draft.copy(timeRegexPattern = it) },
                            label = { Text("Time extraction regex (optional)") },
                            placeholder = { Text("e.g. Departs\\s+(\\d{1,2}:\\d{2})") },
                            isError = validation.timeRegexError != null,
                            supportingText = {
                                Text(
                                    validation.timeRegexError
                                        ?: "Isolates a timestamp substring before parsing; falls back to auto time detection / post time.",
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        HorizontalDivider()

                        Text(
                            text = "Rule Pin Icon Override (optional)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val selectedIcon = draft.iconUri?.let { PinIcon.fromUri(it) }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item(key = "rule-icon-inherit") {
                                FilterChip(
                                    selected = selectedIcon == null,
                                    onClick = { draft = draft.copy(iconUri = null) },
                                    label = { Text("Inherit") },
                                )
                            }
                            items(PinIcon.entries, key = { it.systemName }) { icon ->
                                FilterChip(
                                    selected = icon == selectedIcon,
                                    onClick = { draft = draft.copy(iconUri = icon.uri) },
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
                            text = "Rule Accent Color Override (optional)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val selectedColor = PinAccentColor.fromHex(draft.backgroundColor)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item(key = "rule-color-inherit") {
                                FilterChip(
                                    selected = selectedColor == null,
                                    onClick = { draft = draft.copy(backgroundColor = null) },
                                    label = { Text("Inherit") },
                                )
                            }
                            items(PinAccentColor.entries, key = { it.hexCode }) { accent ->
                                AccentColorSwatch(
                                    accent = accent,
                                    isSelected = accent == selectedColor,
                                    contentDescPrefix = "Rule",
                                    onClick = {
                                        draft = draft.copy(
                                            backgroundColor = if (accent == selectedColor) null else accent.hexCode,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onSave(draft.normalized()) },
                        enabled = validation.isValid,
                    ) {
                        Text(if (isNew) "Add Rule" else "Save Rule")
                    }
                }
            }
        }
    }
}

@Composable
internal fun FieldSelectorRow(
    label: String,
    selectedField: NotificationTextField,
    onFieldSelected: (NotificationTextField) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(NotificationTextField.entries, key = { it.name }) { field ->
                FilterChip(
                    selected = field == selectedField,
                    onClick = { onFieldSelected(field) },
                    label = { Text(field.label) },
                )
            }
        }
    }
}

@Composable
internal fun AccentColorSwatch(
    accent: PinAccentColor,
    isSelected: Boolean,
    contentDescPrefix: String,
    onClick: () -> Unit,
) {
    val swatchColor = accent.toComposeColor()
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
                contentDescription = "${accent.label} $contentDescPrefix accent color"
                selected = isSelected
            }
            .clickable(role = Role.RadioButton, onClick = onClick),
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

@Composable
internal fun RuleTesterCard(
    uiState: NotificationSettingsUiState,
    onPackageSelected: (String?) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rule Tester & Preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Try sample notification text to verify per-app and global regex rules.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse rule tester" else "Expand rule tester",
                )
            }

            if (expanded) {
                val allowedPackages = uiState.settings.allowedPackages.sorted()
                Text(
                    text = "Test app scope",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item(key = "tester-global") {
                        FilterChip(
                            selected = uiState.ruleTesterPackage == null,
                            onClick = { onPackageSelected(null) },
                            label = { Text("Global Defaults") },
                        )
                    }
                    items(allowedPackages, key = { it }) { pkg ->
                        FilterChip(
                            selected = uiState.ruleTesterPackage == pkg,
                            onClick = { onPackageSelected(pkg) },
                            label = { Text(uiState.displayNameForPackage(pkg)) },
                        )
                    }
                }

                OutlinedTextField(
                    value = uiState.ruleTesterTitle,
                    onValueChange = onTitleChange,
                    label = { Text("Sample notification title") },
                    placeholder = { Text("e.g. Delta Air Lines") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.ruleTesterBody,
                    onValueChange = onBodyChange,
                    label = { Text("Sample notification body") },
                    placeholder = { Text("e.g. Flight DL123 departs 16:30\nGate B12") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                uiState.ruleTesterEvaluation?.let { evaluation ->
                    RuleEvaluationPreviewBox(
                        evaluation = evaluation,
                        zoneId = uiState.zoneId,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RuleEvaluationPreviewBox(
    evaluation: NotificationPinEvaluation,
    zoneId: ZoneId,
) {
    val event = evaluation.event
    val containerColor = if (event != null) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (event != null) {
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (event != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (event != null) "Would Create Pin • ${evaluation.reason}" else "Ignored • ${evaluation.reason}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (event != null) {
                val pinIcon = PinIcon.fromUri(event.iconUri)
                val accentColor = parseComposeHexColor(event.backgroundColor)
                val formattedTime = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
                    epochMillis = event.startTimeMillis,
                    zoneId = zoneId,
                )
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accentColor ?: MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = pinIcon.toImageVector(),
                            contentDescription = pinIcon.label,
                            tint = accentColor?.contrastingCheckColor()
                                ?: MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!event.description.isNullOrBlank()) {
                            Text(
                                text = event.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "$formattedTime • ${pinIcon.label}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
