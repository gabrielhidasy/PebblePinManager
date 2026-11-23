package xyz.hidasy.pebblepinmanager.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Utility contract to inspect whether Android Notification Access is granted to this app.
 * Wired to settings onboarding in Issue #7 without blocking manual pin creation.
 */
interface NotificationAccessStatusChecker {
    fun isNotificationAccessEnabled(): Boolean
}

/**
 * Pure-Kotlin parser for Android's `Settings.Secure.enabled_notification_listeners` setting value.
 */
object NotificationAccessParser {
    fun isPackageEnabled(enabledListeners: String?, packageName: String): Boolean {
        val pkg = packageName.trim()
        if (enabledListeners.isNullOrBlank() || pkg.isEmpty()) return false
        val expectedPrefix = "$pkg/"
        return enabledListeners
            .split(':')
            .map { it.trim() }
            .any { component -> component.startsWith(expectedPrefix) }
    }
}

class AndroidNotificationAccessStatusChecker(
    private val context: Context,
) : NotificationAccessStatusChecker {
    override fun isNotificationAccessEnabled(): Boolean {
        val enabledListeners = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS_KEY,
            )
        }.getOrNull()
        return NotificationAccessParser.isPackageEnabled(enabledListeners, context.packageName)
    }

    fun createSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createFallbackSettingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        private const val ENABLED_NOTIFICATION_LISTENERS_KEY: String = "enabled_notification_listeners"
    }
}
