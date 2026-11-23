package xyz.hidasy.pebblepinmanager.ui

import xyz.hidasy.pebblepinmanager.notifications.DraftRuleExplorationResult
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRuleValidation
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationTextField
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRecord
import xyz.hidasy.pebblepinmanager.notifications.RegexExplorationResult
import java.time.ZoneId

data class DevModeUiState(
    val rawNotifications: List<RawNotificationRecord> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val settings: NotificationRuleSettings = NotificationRuleSettings(),
    val isNotificationAccessEnabled: Boolean = false,
    val isListenerConnected: Boolean = false,
    val searchQuery: String = "",
    val hideOngoing: Boolean = false,
    val hideGroupSummary: Boolean = false,
    val allowlistedOnly: Boolean = false,
    val selectedNotificationId: String? = null,
    val scratchpadPattern: String = "",
    val scratchpadField: NotificationTextField = NotificationTextField.ANY,
    val scratchpadIgnoreCase: Boolean = true,
    val scratchpadResult: RegexExplorationResult? = null,
    val draftRule: NotificationRegexRule = NotificationRegexRule(),
    val draftRuleValidation: NotificationRegexRuleValidation = NotificationRegexRuleValidation(),
    val draftExploration: DraftRuleExplorationResult? = null,
    val statusMessage: String? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val selectedNotification: RawNotificationRecord?
        get() = selectedNotificationId?.let { id ->
            rawNotifications.firstOrNull { it.id == id }
        }

    val filteredNotifications: List<RawNotificationRecord>
        get() {
            val query = searchQuery.trim()
            return rawNotifications
                .asSequence()
                .filter { !hideOngoing || !it.isOngoing }
                .filter { !hideGroupSummary || !it.isGroupSummary }
                .filter { !allowlistedOnly || settings.isPackageAllowed(it.sourcePackage) }
                .filter { record ->
                    if (query.isEmpty()) return@filter true
                    val appName = displayNameFor(record)
                    appName.contains(query, ignoreCase = true) ||
                        record.sourcePackage.contains(query, ignoreCase = true) ||
                        record.title.contains(query, ignoreCase = true) ||
                        record.text.orEmpty().contains(query, ignoreCase = true) ||
                        record.bigText.orEmpty().contains(query, ignoreCase = true) ||
                        record.subText.orEmpty().contains(query, ignoreCase = true) ||
                        record.channelId.orEmpty().contains(query, ignoreCase = true)
                }
                .toList()
        }

    val existingAppRulesForSelected: List<NotificationRegexRule>
        get() = selectedNotification
            ?.let { settings.appRuleFor(it.sourcePackage)?.regexRules }
            .orEmpty()

    val existingGlobalRules: List<NotificationRegexRule>
        get() = settings.globalRegexRules

    fun displayNameFor(record: RawNotificationRecord): String {
        val installedLabel = installedApps.firstOrNull { it.packageName == record.sourcePackage }?.appName
        return record.displayAppName(installedAppLabel = installedLabel)
    }

    fun isAllowlisted(packageName: String): Boolean = settings.isPackageAllowed(packageName)
}
