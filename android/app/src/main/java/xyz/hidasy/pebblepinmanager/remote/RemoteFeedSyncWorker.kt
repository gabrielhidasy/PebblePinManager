package xyz.hidasy.pebblepinmanager.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import xyz.hidasy.pebblepinmanager.PebblePinManagerApp
import java.util.concurrent.TimeUnit

/**
 * Abstraction over periodic background sync scheduling for remote JSON pin feeds.
 */
fun interface RemoteFeedSyncScheduler {
    fun updateSchedule(feeds: List<RemotePinFeedConfig>)
}

/**
 * Computes the effective WorkManager periodic cadence across all enabled remote feeds.
 *
 * Returns `null` when no feed has periodic background polling enabled (WorkManager work should
 * be cancelled so the app uses zero background network or battery).
 */
object RemoteFeedSchedulePolicy {
    fun computeMinIntervalMinutes(feeds: List<RemotePinFeedConfig>): Long? {
        return feeds
            .asSequence()
            .filter { it.hasPeriodicPolling }
            .map { it.pollingIntervalMinutes.toLong().coerceAtLeast(MIN_WORKMANAGER_INTERVAL_MINUTES) }
            .minOrNull()
    }

    const val MIN_WORKMANAGER_INTERVAL_MINUTES: Long = 15L
    const val UNIQUE_PERIODIC_WORK_NAME: String = "pebble_pin_manager_remote_feed_sync"
}

/**
 * Schedules or cancels [RemoteFeedSyncWorker] via AndroidX WorkManager with a [NetworkType.CONNECTED]
 * constraint so periodic sync runs only when network connectivity is available.
 */
class WorkManagerRemoteFeedSyncScheduler(
    private val context: Context,
) : RemoteFeedSyncScheduler {

    override fun updateSchedule(feeds: List<RemotePinFeedConfig>) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val intervalMinutes = RemoteFeedSchedulePolicy.computeMinIntervalMinutes(feeds)
        if (intervalMinutes == null) {
            workManager.cancelUniqueWork(RemoteFeedSchedulePolicy.UNIQUE_PERIODIC_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<RemoteFeedSyncWorker>(
            repeatInterval = intervalMinutes,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            RemoteFeedSchedulePolicy.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

/**
 * Background [CoroutineWorker] executed periodically by WorkManager to poll enabled remote JSON
 * pin feeds whose configured polling interval has elapsed.
 */
class RemoteFeedSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PebblePinManagerApp ?: return Result.success()
        return runCatching {
            app.container.remoteFeedSyncEngine.syncAllEnabledFeeds(onlyDue = true)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
