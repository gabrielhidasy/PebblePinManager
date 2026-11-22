package xyz.hidasy.pebblepinmanager.pebble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import xyz.hidasy.pebblepinmanager.MainActivity
import xyz.hidasy.pebblepinmanager.R
import java.util.Collections

/**
 * Tracks whether any [Activity] in this app is currently in the started (visible) lifecycle state.
 *
 * Registered once via [Application.registerActivityLifecycleCallbacks] in [xyz.hidasy.pebblepinmanager.PebblePinManagerApp].
 */
class AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityIds: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    val hasStartedActivity: Boolean
        get() = startedActivityIds.isNotEmpty()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedActivityIds.add(System.identityHashCode(activity))
    }

    override fun onActivityResumed(activity: Activity) {
        startedActivityIds.add(System.identityHashCode(activity))
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        startedActivityIds.remove(System.identityHashCode(activity))
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        startedActivityIds.remove(System.identityHashCode(activity))
    }
}

/**
 * Android framework implementation of [PhoneLaunchEnvironment].
 */
class AndroidPhoneLaunchEnvironment(
    private val context: Context,
    private val foregroundTracker: AppForegroundTracker,
) : PhoneLaunchEnvironment {

    private val appContext: Context = context.applicationContext

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun isAppInForeground(): Boolean {
        if (!foregroundTracker.hasStartedActivity) {
            return false
        }
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        if (powerManager != null && !powerManager.isInteractive) {
            return false
        }
        val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            return false
        }
        return true
    }

    override fun canDrawOverlays(): Boolean {
        return runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
    }

    override fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }

        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return false
        }

        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
            ?: return false
        ensureHandoffChannel(notificationManager)
        val channel = notificationManager.getNotificationChannel(HANDOFF_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun startMainActivity(): Result<Unit> = runCatching {
        appContext.startActivity(createMainActivityIntent(appContext))
    }

    @SuppressLint("MissingPermission")
    override fun postHandoffNotification(): Result<Unit> = runCatching {
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager unavailable")
        ensureHandoffChannel(notificationManager)

        val contentIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN_MAIN,
            createMainActivityIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, HANDOFF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(appContext.getString(R.string.watch_handoff_notification_title))
            .setContentText(appContext.getString(R.string.watch_handoff_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(HANDOFF_NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                appContext.getString(R.string.watch_handoff_notification_action),
                contentIntent,
            )
            .build()

        NotificationManagerCompat.from(appContext).notify(HANDOFF_NOTIFICATION_ID, notification)
    }

    override fun cancelHandoffNotification() {
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(HANDOFF_NOTIFICATION_ID)
        }
    }

    private fun ensureHandoffChannel(notificationManager: NotificationManager) {
        val existing = notificationManager.getNotificationChannel(HANDOFF_CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            HANDOFF_CHANNEL_ID,
            appContext.getString(R.string.watch_handoff_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.watch_handoff_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG: String = "PhoneAppLauncher"
        const val HANDOFF_CHANNEL_ID: String = "watch_open_phone"
        const val HANDOFF_NOTIFICATION_ID: Int = 1001
        const val REQUEST_CODE_OPEN_MAIN: Int = 1001
        const val HANDOFF_NOTIFICATION_TIMEOUT_MS: Long = 5 * 60 * 1000L
        const val EXTRA_LAUNCHED_FROM_WATCH: String = "xyz.hidasy.pebblepinmanager.extra.LAUNCHED_FROM_WATCH"

        fun createLauncher(
            context: Context,
            foregroundTracker: AppForegroundTracker,
        ): PhoneAppLauncher {
            val environment = AndroidPhoneLaunchEnvironment(
                context = context,
                foregroundTracker = foregroundTracker,
            )
            return PolicyPhoneAppLauncher(
                environment = environment,
                logger = { message -> Log.i(TAG, message) },
            )
        }

        fun createMainActivityIntent(context: Context): Intent =
            Intent(context.applicationContext, MainActivity::class.java).apply {
                putExtra(EXTRA_LAUNCHED_FROM_WATCH, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }

        fun createNotificationSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }

        fun createOverlaySettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
    }
}

/**
 * Persists whether the user dismissed the inline Watch Handoff permission banner so it stays hidden
 * across app launches if they intentionally choose not to grant permissions.
 */
class WatchHandoffPromptPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun isPermissionBannerDismissed(): Boolean =
        prefs.getBoolean(KEY_BANNER_DISMISSED, false)

    fun setPermissionBannerDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_BANNER_DISMISSED, dismissed).apply()
    }

    companion object {
        private const val PREFS_NAME: String = "watch_handoff_prefs"
        private const val KEY_BANNER_DISMISSED: String = "permission_banner_dismissed"
    }
}
