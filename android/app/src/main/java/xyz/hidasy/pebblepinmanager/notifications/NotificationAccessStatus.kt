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

class AndroidNotificationAccessStatusChecker(
    private val context: Context,
) : NotificationAccessStatusChecker {
    override fun isNotificationAccessEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS_KEY,
        ) ?: return false
        return enabledListeners
            .split(':')
            .any { component -> component.startsWith("${context.packageName}/") }
    }

    fun createSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        private const val ENABLED_NOTIFICATION_LISTENERS_KEY: String = "enabled_notification_listeners"
    }
}
