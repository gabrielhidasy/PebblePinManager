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
import xyz.hidasy.pebblepinmanager.BuildConfig
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
        activeInstance = this
        _isListenerConnected.value = true
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        _isListenerConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val candidate = extractCandidate(sbn)
        val rawSnapshot = if (BuildConfig.DEV_MODE_AVAILABLE) {
            buildRawRecord(sbn, candidate, capturedAtMillis = System.currentTimeMillis())
        } else {
            null
        }
        val app = application as? PebblePinManagerApp ?: return
        val pipeline = app.container.notificationPinPipeline
        val settingsRepo = app.container.notificationSettingsRepository

        serviceScope.launch {
            var outcomeLabel: String? = null
            try {
                when (val result = pipeline.process(candidate)) {
                    is NotificationPipelineResult.Delivered -> {
                        outcomeLabel = "Delivered (${result.pinId})"
                        Log.d(TAG, "Delivered notification pin ${result.pinId} from ${candidate.sourcePackage}")
                    }
                    is NotificationPipelineResult.Unchanged -> {
                        outcomeLabel = "Unchanged (${result.pinId})"
                        Log.d(TAG, "Skipped unchanged notification pin ${result.pinId} from ${candidate.sourcePackage}")
                    }
                    is NotificationPipelineResult.ValidationFailed -> {
                        outcomeLabel = "Validation failed"
                        Log.w(TAG, "Skipped invalid notification pin from ${candidate.sourcePackage}: ${result.reasons}")
                    }
                    is NotificationPipelineResult.DeliveryFailed -> {
                        outcomeLabel = "Delivery failed"
                        Log.w(TAG, "Failed delivering notification pin from ${candidate.sourcePackage}: ${result.result}")
                    }
                    NotificationPipelineResult.Ignored -> {
                        outcomeLabel = "Ignored"
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                outcomeLabel = "Error"
                Log.e(TAG, "Unexpected error processing notification from ${candidate.sourcePackage}", err)
            }

            if (BuildConfig.DEV_MODE_AVAILABLE && rawSnapshot != null) {
                try {
                    if (settingsRepo.getSettings().isDevModeEnabled) {
                        app.container.rawNotificationRepository.recordNotification(
                            rawSnapshot.copy(pipelineOutcome = outcomeLabel),
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (err: Throwable) {
                    Log.w(TAG, "Failed to record dev raw notification from ${candidate.sourcePackage}", err)
                }
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
        if (activeInstance === this) {
            activeInstance = null
        }
        _isListenerConnected.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildRawRecord(
        sbn: StatusBarNotification,
        candidate: NotificationPinCandidate,
        capturedAtMillis: Long,
        pipelineOutcome: String? = null,
    ): RawNotificationRecord {
        val notification = sbn.notification
        val postTime = sbn.postTime.takeIf { it > 0L } ?: candidate.eventTimeMillis
        return RawNotificationRecord.fromCandidate(
            candidate = candidate,
            capturedAtMillis = capturedAtMillis,
            postTimeMillis = postTime,
            channelId = notification?.channelId,
            category = notification?.category,
            pipelineOutcome = pipelineOutcome,
        )
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

        @Volatile
        private var activeInstance: PebbleNotificationListenerService? = null

        /**
         * Captures currently active status-bar notifications (excluding this app) when the
         * listener service is connected, so Dev Mode can immediately inspect existing shade items.
         */
        fun snapshotActiveNotifications(
            capturedAtMillis: Long = System.currentTimeMillis(),
        ): List<RawNotificationRecord> {
            if (!BuildConfig.DEV_MODE_AVAILABLE) return emptyList()
            val service = activeInstance ?: return emptyList()
            val active = runCatching { service.activeNotifications }.getOrNull() ?: return emptyList()
            return active
                .filterNotNull()
                .filter { it.packageName != service.packageName }
                .map { sbn ->
                    val candidate = service.extractCandidate(sbn)
                    service.buildRawRecord(
                        sbn = sbn,
                        candidate = candidate,
                        capturedAtMillis = capturedAtMillis,
                        pipelineOutcome = "Active snapshot",
                    )
                }
                .sortedByDescending { it.postTimeMillis }
        }
    }
}
