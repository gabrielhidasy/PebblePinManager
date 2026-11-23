package xyz.hidasy.pebblepinmanager.notifications

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.PebblePinManagerApp

/**
 * Android [NotificationListenerService] that listens to incoming system notifications locally
 * on-device and forwards allowlisted entries through [NotificationPinPipeline] -> [TimelinePinSender].
 *
 * Never sends data to any external server and ignores this app's own notifications.
 */
class PebbleNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isListenerConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isListenerConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val candidate = extractCandidate(sbn)
        val app = application as? PebblePinManagerApp ?: return
        val pipeline = app.container.notificationPinPipeline

        serviceScope.launch {
            try {
                when (val result = pipeline.process(candidate)) {
                    is NotificationPipelineResult.Delivered -> {
                        Log.d(TAG, "Delivered notification pin ${result.pinId} from ${candidate.sourcePackage}")
                    }
                    is NotificationPipelineResult.Unchanged -> {
                        Log.d(TAG, "Skipped unchanged notification pin ${result.pinId} from ${candidate.sourcePackage}")
                    }
                    is NotificationPipelineResult.ValidationFailed -> {
                        Log.w(TAG, "Skipped invalid notification pin from ${candidate.sourcePackage}: ${result.reasons}")
                    }
                    is NotificationPipelineResult.DeliveryFailed -> {
                        Log.w(TAG, "Failed delivering notification pin from ${candidate.sourcePackage}: ${result.result}")
                    }
                    NotificationPipelineResult.Ignored -> Unit
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                Log.e(TAG, "Unexpected error processing notification from ${candidate.sourcePackage}", err)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val app = application as? PebblePinManagerApp ?: return
        val settingsRepo = app.container.notificationSettingsRepository
        val pipeline = app.container.notificationPinPipeline
        val dedupKey = NotificationDeduplicationKey.compute(
            sourcePackage = sbn.packageName.orEmpty(),
            notificationKey = sbn.key.orEmpty(),
            tag = sbn.tag,
            notificationId = sbn.id,
        )

        serviceScope.launch {
            try {
                if (!settingsRepo.getSettings().deletePinOnDismiss) return@launch
                pipeline.processRemoval(dedupKey)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                Log.e(TAG, "Unexpected error handling notification removal for ${sbn.packageName}", err)
            }
        }
    }

    override fun onDestroy() {
        _isListenerConnected.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun extractCandidate(sbn: StatusBarNotification): NotificationPinCandidate {
        val notification = sbn.notification
        val extras = notification?.extras
        val flags = notification?.flags ?: 0

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawBigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val inboxTextLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { line -> line?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n")
        val bigText = rawBigText ?: inboxTextLines
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val rawWhen = notification?.`when` ?: 0L
        val eventTimeMillis = when {
            rawWhen > 0L -> rawWhen
            sbn.postTime > 0L -> sbn.postTime
            else -> System.currentTimeMillis()
        }

        val sourcePackage = sbn.packageName.orEmpty()
        val appLabel = resolveAppLabel(packageManager, sourcePackage)

        return NotificationPinCandidate(
            sourcePackage = sourcePackage,
            notificationKey = sbn.key.orEmpty(),
            title = title,
            text = text,
            eventTimeMillis = eventTimeMillis,
            subText = subText,
            bigText = bigText,
            tag = sbn.tag,
            notificationId = sbn.id,
            isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0,
            isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            appLabel = appLabel,
        )
    }

    private fun resolveAppLabel(pm: PackageManager, pkg: String): String? {
        if (pkg.isBlank()) return null
        return runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "PebbleNotifListener"
        private val _isListenerConnected = MutableStateFlow(false)
        val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()
    }
}
