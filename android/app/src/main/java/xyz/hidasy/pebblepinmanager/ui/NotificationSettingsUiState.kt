package xyz.hidasy.pebblepinmanager.ui

import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings

data class NotificationSettingsUiState(
    val isNotificationAccessEnabled: Boolean = false,
    val isListenerConnected: Boolean = false,
    val settings: NotificationRuleSettings = NotificationRuleSettings(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val appSearchQuery: String = "",
    val showSelectedAppsOnly: Boolean = false,
    val manualPackageInput: String = "",
    val manualPackageError: String? = null,
    val keywordFilterInput: String = "",
    val titleRegexInput: String = "",
    val titleRegexError: String? = null,
    val descriptionRegexInput: String = "",
    val descriptionRegexError: String? = null,
    val timeRegexInput: String = "",
    val timeRegexError: String? = null,
    val rulesSavedMessage: String? = null,
    val autoPinCount: Int = 0,
) {
    val allowedCount: Int
        get() = settings.allowedPackages.size

    val selectedDefaultIcon: PinIcon
        get() = PinIcon.fromUri(settings.defaultIconUri)

    val selectedDefaultBackgroundColor: PinAccentColor?
        get() = PinAccentColor.fromHex(settings.defaultBackgroundColor)

    val filteredInstalledApps: List<InstalledAppInfo>
        get() {
            val query = appSearchQuery.trim()
            return installedApps
                .asSequence()
                .filter { app ->
                    !showSelectedAppsOnly || app.packageName in settings.allowedPackages
                }
                .filter { app ->
                    query.isEmpty() ||
                        app.appName.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    compareByDescending<InstalledAppInfo> { it.packageName in settings.allowedPackages }
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
            titleRegexInput.trim() != settings.titleRegexPattern.trim() ||
            descriptionRegexInput.trim() != settings.descriptionRegexPattern.trim() ||
            timeRegexInput.trim() != settings.timeRegexPattern.trim()
}
