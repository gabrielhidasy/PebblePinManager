package xyz.hidasy.pebblepinmanager.emulatorbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Allows the Emulator E2E harness to post and cancel real Android heads-up notifications from a
 * distinct package (`xyz.hidasy.pebblepinmanager.emulatorbridge`) so `PebbleNotificationListenerService`
 * receives genuine system [android.service.notification.StatusBarNotification] callbacks during
 * Auto-Pin E2E runs and side-by-side demo videos.
 *
 * Example:
 * ```bash
 * adb shell am broadcast \
 *   -a xyz.hidasy.pebblepinmanager.emulatorbridge.POST_NOTIFICATION \
 *   -n xyz.hidasy.pebblepinmanager.emulatorbridge/.DemoNotificationReceiver \
 *   --ei id 2002 \
 *   --es tag demo-flight \
 *   --es title 'Flight DL123 Boarding' \
 *   --es text 'Gate B12 departs in 15 min'
 * ```
 */
class DemoNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            Log.e(TAG, "NotificationManager unavailable")
            return
        }

        when (intent.action) {
            ACTION_POST_NOTIFICATION -> handlePost(context, manager, intent)
            ACTION_CANCEL_NOTIFICATION -> handleCancel(manager, intent)
            else -> Unit
        }
    }

    private fun handlePost(context: Context, manager: NotificationManager, intent: Intent) {
        ensureChannel(manager)

        val request = DemoNotificationRequest.fromExtras(
            id = if (intent.hasExtra(EXTRA_ID)) intent.getIntExtra(EXTRA_ID, DemoNotificationRequest.DEFAULT_NOTIFICATION_ID) else null,
            tag = intent.getStringExtra(EXTRA_TAG),
            title = intent.getStringExtra(EXTRA_TITLE),
            text = intent.getStringExtra(EXTRA_TEXT),
            bigText = intent.getStringExtra(EXTRA_BIG_TEXT),
            subText = intent.getStringExtra(EXTRA_SUB_TEXT),
            ongoing = if (intent.hasExtra(EXTRA_ONGOING)) intent.getBooleanExtra(EXTRA_ONGOING, false) else null,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(request.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOngoing(request.ongoing)
            .setWhen(System.currentTimeMillis())

        if (!request.text.isNullOrBlank()) {
            builder.setContentText(request.text)
        }
        if (!request.bigText.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(request.bigText))
        }
        if (!request.subText.isNullOrBlank()) {
            builder.setSubText(request.subText)
        }

        val notification = builder.build()
        if (request.tag != null) {
            manager.notify(request.tag, request.id, notification)
        } else {
            manager.notify(request.id, notification)
        }
        Log.i(TAG, "Posted demo notification id=${request.id} tag=${request.tag} title='${request.title}'")
    }

    private fun handleCancel(manager: NotificationManager, intent: Intent) {
        val request = DemoNotificationCancelRequest.fromExtras(
            id = if (intent.hasExtra(EXTRA_ID)) intent.getIntExtra(EXTRA_ID, DemoNotificationRequest.DEFAULT_NOTIFICATION_ID) else null,
            tag = intent.getStringExtra(EXTRA_TAG),
            cancelAll = if (intent.hasExtra(EXTRA_CANCEL_ALL)) intent.getBooleanExtra(EXTRA_CANCEL_ALL, false) else null,
        )

        if (request.cancelAll) {
            manager.cancelAll()
            Log.i(TAG, "Cancelled all demo notifications")
        } else if (request.tag != null) {
            manager.cancel(request.tag, request.id)
            Log.i(TAG, "Cancelled demo notification id=${request.id} tag=${request.tag}")
        } else {
            manager.cancel(request.id)
            Log.i(TAG, "Cancelled demo notification id=${request.id}")
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "High-priority demo notifications for Emulator E2E Auto-Pin testing"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 120L)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_POST_NOTIFICATION = "xyz.hidasy.pebblepinmanager.emulatorbridge.POST_NOTIFICATION"
        const val ACTION_CANCEL_NOTIFICATION = "xyz.hidasy.pebblepinmanager.emulatorbridge.CANCEL_NOTIFICATION"
        const val EXTRA_ID = "id"
        const val EXTRA_TAG = "tag"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_BIG_TEXT = "big_text"
        const val EXTRA_SUB_TEXT = "sub_text"
        const val EXTRA_ONGOING = "ongoing"
        const val EXTRA_CANCEL_ALL = "cancel_all"
        const val CHANNEL_ID = "demo_notifications"
        private const val CHANNEL_NAME = "Demo Notifications"
        private const val TAG = "DemoNotificationRcvr"
    }
}
