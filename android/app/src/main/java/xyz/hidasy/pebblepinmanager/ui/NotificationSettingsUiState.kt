package xyz.hidasy.pebblepinmanager.ui

import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.notifications.AppNotificationRuleConfig
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinEvaluation
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleValidator
import java.time.ZoneId

data class NotificationSettingsUiState(
    val isNotificationAccessEnabled: Boolean = false,
    val isListenerConnected: Boolean = false,
    val settings: NotificationRuleSettings = NotificationRuleSettings(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val appSearchQuery: String = "",
    val showSelectedAppsOnly: Boolean = false,
    val showCustomRulesOnly: Boolean = false,
    val manualPackageInput: String = "",
    val manualPackageError: String? = null,
    val keywordFilterInput: String = "",
    val excludeKeywordFilterInput: String = "",
    val titleRegexInput: String = "",
    val titleRegexError: String? = null,
    val descriptionRegexInput: String = "",
    val descriptionRegexError: String? = null,
    val timeRegexInput: String = "",
    val timeRegexError: String? = null,
    val rulesSavedMessage: String? = null,
    val ruleTesterPackage: String? = null,
    val ruleTesterTitle: String = "",
    val ruleTesterBody: String = "",
    val ruleTesterEvaluation: NotificationPinEvaluation? = null,
    val autoPins: List<SentPinRecord> = emptyList(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val deletingPinId: String? = null,
    val pinActionFeedback: SendFeedback? = null,
) {
    val autoPinCount: Int
        get() = autoPins.size

    val allowedCount: Int
        get() = settings.allowedPackages.size

    val configuredAppRulesCount: Int
        get() = settings.configuredAppRuleCount()

    val selectedDefaultIcon: PinIcon
        get() = PinIcon.fromUri(settings.defaultIconUri)

    val selectedDefaultBackgroundColor: PinAccentColor?
        get() = PinAccentColor.fromHex(settings.defaultBackgroundColor)

    val hasLegacyGlobalRegexes: Boolean
        get() = settings.titleRegexPattern.isNotBlank() ||
            settings.descriptionRegexPattern.isNotBlank() ||
            settings.timeRegexPattern.isNotBlank()

    val filteredInstalledApps: List<InstalledAppInfo>
        get() {
            val query = appSearchQuery.trim()
            return installedApps
                .asSequence()
                .filter { app ->
                    !showSelectedAppsOnly || app.packageName in settings.allowedPackages
                }
                .filter { app ->
                    !showCustomRulesOnly || hasCustomRules(app.packageName)
                }
                .filter { app ->
                    query.isEmpty() ||
                        app.appName.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    compareByDescending<InstalledAppInfo> { it.packageName in settings.allowedPackages }
                        .thenByDescending { hasCustomRules(it.packageName) }
                        .thenBy { it.appName.lowercase() }
                        .thenBy { it.packageName.lowercase() },
                )
                .toList()
        }

    /**
     * Allowlisted package names that are not present in the launcher-visible [installedApps] list
     * (e.g., manually entered packages or system services without launcher icons).
     */
    val unlistedAllowedPackages: List<String>
        get() {
            val installedSet = installedApps.mapTo(HashSet()) { it.packageName }
            return settings.allowedPackages
                .filterNot { it in installedSet }
                .sorted()
        }

    val hasUnsavedRuleEdits: Boolean
        get() = keywordFilterInput.trim() != settings.keywordFilter.trim() ||
            excludeKeywordFilterInput.trim() != settings.excludeKeywordFilter.trim() ||
            titleRegexInput.trim() != settings.titleRegexPattern.trim() ||
            descriptionRegexInput.trim() != settings.descriptionRegexPattern.trim() ||
            timeRegexInput.trim() != settings.timeRegexPattern.trim()

    fun hasCustomRules(packageName: String): Boolean {
        val config = settings.appRuleFor(packageName) ?: return false
        return config.hasCustomizations()
    }

    fun appRuleConfigOrDefault(packageName: String): AppNotificationRuleConfig {
        return settings.appRuleFor(packageName) ?: AppNotificationRuleConfig(packageName = packageName.trim())
    }

    fun appRuleBadgeLabel(packageName: String): String? {
        val config = settings.appRuleFor(packageName) ?: return null
        if (!config.hasCustomizations()) return null
        if (!config.enabled) return "Paused"
        val count = config.regexRules.size
        return when {
            count == 1 -> "1 regex rule"
            count > 1 -> "$count regex rules"
            else -> "Custom"
        }
    }

    fun displayNameForPackage(packageName: String): String {
        val trimmed = packageName.trim()
        return installedApps.firstOrNull { it.packageName == trimmed }?.appName ?: trimmed
    }

    fun formatAutoPinSource(record: SentPinRecord): String {
        val rawKey = record.sourceKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Source: Notification"
        val packageName = extractSourcePackage(rawKey)
        val appLabel = packageName?.let { pkg ->
            installedApps.firstOrNull { it.packageName == pkg }?.appName
        }
        return if (appLabel != null) {
            "Source: $appLabel • $rawKey"
        } else {
            "Source: $rawKey"
        }
    }

    companion object {
        internal fun extractSourcePackage(sourceKey: String): String? {
            val trimmed = sourceKey.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split('|')
            if (parts.size >= 2 && parts[0].toIntOrNull() != null &&
                NotificationRuleValidator.validatePackageName(parts[1]) == null
            ) {
                return parts[1]
            }
            if (parts.size >= 2 && NotificationRuleValidator.validatePackageName(parts[0]) == null) {
                return parts[0]
            }
            return trimmed.takeIf { NotificationRuleValidator.validatePackageName(it) == null }
        }
    }
}
