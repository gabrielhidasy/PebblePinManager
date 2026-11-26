package xyz.hidasy.pebblepinmanager.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Clock

/**
 * Reconciles pins fetched from authenticated remote JSON feeds against locally tracked
 * [SentPinRecord]s and delivers inserts, in-place updates, and deletes via [TimelinePinSender].
 *
 * Works identically against both [xyz.hidasy.pebblepinmanager.pebble.PebbleKit2PinSender] and
 * [xyz.hidasy.pebblepinmanager.pebble.SimulatedTimelinePinSender] ("Run without a watch" mode).
 */
class RemoteFeedSyncEngine(
    private val settingsRepository: RemoteFeedSettingsRepository,
    private val secretStore: RemoteFeedSecretStore,
    private val httpClient: RemoteFeedHttpClient,
    private val pinSender: TimelinePinSender,
    private val sentPinRepository: SentPinRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val onPinsChanged: (suspend () -> Unit)? = null,
) {
    private val syncMutex = Mutex()

    /**
     * Fetches and reconciles a single configured remote feed.
     *
     * @param forceFullFetch when true, omits `If-None-Match` / `If-Modified-Since` conditional
     * headers to force a fresh full reconciliation.
     */
    suspend fun syncFeed(
        feedId: String,
        forceFullFetch: Boolean = false,
    ): RemoteFeedSyncOutcome = syncMutex.withLock {
        syncFeedLocked(feedId = feedId, forceFullFetch = forceFullFetch)
    }

    /**
     * Syncs all enabled feeds (optionally restricted to feeds whose polling interval has elapsed).
     */
    suspend fun syncAllEnabledFeeds(
        onlyDue: Boolean = false,
        forceFullFetch: Boolean = false,
    ): List<RemoteFeedSyncOutcome> = syncMutex.withLock {
        val nowMillis = clock.millis()
        val enabledFeeds = settingsRepository.getSettings().feeds.filter { it.isEnabled }
        val targetFeeds = if (onlyDue) {
            enabledFeeds.filter { isFeedDue(it, nowMillis) }
        } else {
            enabledFeeds
        }
        targetFeeds.map { feed ->
            syncFeedLocked(feedId = feed.id, forceFullFetch = forceFullFetch)
        }
    }

    /**
     * Tests connectivity, authentication, and JSON schema/pin validation for a draft feed
     * configuration without mutating any watch pins or stored feed state.
     */
    suspend fun testConnection(
        config: RemotePinFeedConfig,
        secret: RemoteFeedSecret?,
    ): RemoteFeedTestResult {
        val normalized = config.normalized()
        val fetchResult = httpClient.fetch(
            RemoteFeedHttpRequest(
                url = normalized.url,
                allowCleartextHttp = normalized.allowCleartextHttp,
                authType = normalized.authType,
                apiKeyHeaderName = normalized.effectiveApiKeyHeaderName,
                basicUsername = normalized.basicUsername,
                secret = secret,
                ifNoneMatch = null,
                ifModifiedSince = null,
            ),
        )
        return when (fetchResult) {
            is RemoteFeedFetchResult.Success -> {
                when (
                    val parsed = RemotePinFeedParser.parse(
                        rawJson = fetchResult.body,
                        feedSourceKey = normalized.sourceKey,
                        nowMillis = clock.millis(),
                    )
                ) {
                    is RemotePinFeedParseResult.InvalidFeed -> RemoteFeedTestResult.Failure(
                        headline = "Invalid feed JSON",
                        details = parsed.reason,
                    )

                    is RemotePinFeedParseResult.Success -> RemoteFeedTestResult.Success(
                        httpStatus = fetchResult.httpStatus,
                        schemaVersion = parsed.schemaVersion,
                        validPinCount = parsed.validEvents.size,
                        skippedCount = parsed.diagnostics.size,
                        updatedAt = parsed.updatedAt,
                        etagPresent = !fetchResult.etag.isNullOrBlank(),
                        diagnostics = parsed.diagnostics.map { it.reason }.take(MAX_TEST_DIAGNOSTICS),
                    )
                }
            }

            is RemoteFeedFetchResult.NotModified -> RemoteFeedTestResult.Success(
                httpStatus = 304,
                schemaVersion = RemotePinFeedParser.SUPPORTED_SCHEMA_VERSION,
                validPinCount = 0,
                skippedCount = 0,
                updatedAt = null,
                etagPresent = !fetchResult.etag.isNullOrBlank(),
                diagnostics = emptyList(),
            )

            is RemoteFeedFetchResult.Unauthorized -> RemoteFeedTestResult.Failure(
                headline = "Authentication failed (HTTP ${fetchResult.httpStatus})",
                details = fetchResult.message,
            )

            is RemoteFeedFetchResult.HttpError -> RemoteFeedTestResult.Failure(
                headline = "HTTP ${fetchResult.httpStatus}",
                details = fetchResult.message,
            )

            is RemoteFeedFetchResult.InvalidRequest -> RemoteFeedTestResult.Failure(
                headline = "Invalid feed configuration",
                details = fetchResult.message,
            )

            is RemoteFeedFetchResult.NetworkError -> RemoteFeedTestResult.Failure(
                headline = "Connection failed",
                details = fetchResult.message,
            )
        }
    }

    /**
     * Deletes a configured feed and its encrypted credentials, optionally deleting all synced pins
     * previously created by that feed from the Pebble Timeline and local history.
     */
    suspend fun deleteFeed(
        feedId: String,
        removeSyncedPins: Boolean = true,
    ): RemoteFeedDeleteResult = syncMutex.withLock {
        val sourceKey = RemotePinSourceKey.forFeed(feedId)
        var deletedPinCount = 0
        val errors = mutableListOf<String>()

        if (removeSyncedPins) {
            val syncedPins = sentPinRepository.observeSentPins().first()
                .filter { it.isRemotePin && it.sourceKey == sourceKey }
            for (record in syncedPins) {
                when (val result = pinSender.deletePin(record.id)) {
                    is PinDeleteResult.Success,
                    is PinDeleteResult.UnknownPin,
                    -> {
                        sentPinRepository.deletePinRecord(record.id)
                        deletedPinCount++
                    }
                    else -> {
                        errors.add("Could not delete pin '${record.id}': ${describeDeleteError(result)}")
                    }
                }
            }
            if (deletedPinCount > 0) {
                runCatching { onPinsChanged?.invoke() }
            }
        }

        secretStore.deleteSecret(feedId)
        settingsRepository.deleteFeed(feedId)

        RemoteFeedDeleteResult(
            feedId = feedId,
            deletedPinCount = deletedPinCount,
            errors = errors,
        )
    }

    internal fun isFeedDue(feed: RemotePinFeedConfig, nowMillis: Long): Boolean {
        if (!feed.hasPeriodicPolling) return false
        val lastSync = feed.lastSyncAtMillis ?: return true
        val intervalMillis = feed.pollingIntervalMinutes.toLong() * 60_000L
        return (nowMillis - lastSync) >= (intervalMillis - DUE_TOLERANCE_MS)
    }

    private suspend fun syncFeedLocked(
        feedId: String,
        forceFullFetch: Boolean,
    ): RemoteFeedSyncOutcome {
        val feed = settingsRepository.getFeed(feedId)
            ?: return RemoteFeedSyncOutcome.Error(
                feedId = feedId,
                feedName = feedId,
                message = "Remote feed not found.",
            )

        val nowMillis = clock.millis()
        val secret = secretStore.getSecret(feed.id)
        val fetchResult = httpClient.fetch(
            RemoteFeedHttpRequest(
                url = feed.url,
                allowCleartextHttp = feed.allowCleartextHttp,
                authType = feed.authType,
                apiKeyHeaderName = feed.effectiveApiKeyHeaderName,
                basicUsername = feed.basicUsername,
                secret = secret,
                ifNoneMatch = if (forceFullFetch) null else feed.etag,
                ifModifiedSince = if (forceFullFetch) null else feed.lastModified,
            ),
        )

        val allPinsBefore = sentPinRepository.observeSentPins().first()
        val previouslySyncedForFeed = allPinsBefore.filter {
            it.isRemotePin && it.sourceKey == feed.sourceKey
        }

        when (fetchResult) {
            is RemoteFeedFetchResult.NotModified -> {
                val summary = "Up to date (304 Not Modified) • ${previouslySyncedForFeed.size} synced pin(s)"
                settingsRepository.updateFeed(feed.id) { current ->
                    current.copy(
                        etag = fetchResult.etag ?: current.etag,
                        lastModified = fetchResult.lastModified ?: current.lastModified,
                        lastSyncAtMillis = nowMillis,
                        lastSyncStatus = RemoteSyncStatus.NOT_MODIFIED,
                        lastSyncSummary = summary,
                        lastError = null,
                        lastSyncedPinCount = previouslySyncedForFeed.size,
                    )
                }
                return RemoteFeedSyncOutcome.NotModified(
                    feedId = feed.id,
                    feedName = feed.displayName,
                    activePinCount = previouslySyncedForFeed.size,
                )
            }

            is RemoteFeedFetchResult.Unauthorized -> {
                recordFeedError(feed.id, nowMillis, fetchResult.message, previouslySyncedForFeed.size)
                return RemoteFeedSyncOutcome.Error(
                    feedId = feed.id,
                    feedName = feed.displayName,
                    message = fetchResult.message,
                )
            }

            is RemoteFeedFetchResult.HttpError -> {
                recordFeedError(feed.id, nowMillis, fetchResult.message, previouslySyncedForFeed.size)
                return RemoteFeedSyncOutcome.Error(
                    feedId = feed.id,
                    feedName = feed.displayName,
                    message = fetchResult.message,
                )
            }

            is RemoteFeedFetchResult.InvalidRequest -> {
                recordFeedError(feed.id, nowMillis, fetchResult.message, previouslySyncedForFeed.size)
                return RemoteFeedSyncOutcome.Error(
                    feedId = feed.id,
                    feedName = feed.displayName,
                    message = fetchResult.message,
                )
            }

            is RemoteFeedFetchResult.NetworkError -> {
                recordFeedError(feed.id, nowMillis, fetchResult.message, previouslySyncedForFeed.size)
                return RemoteFeedSyncOutcome.Error(
                    feedId = feed.id,
                    feedName = feed.displayName,
                    message = fetchResult.message,
                )
            }

            is RemoteFeedFetchResult.Success -> {
                val parsed = RemotePinFeedParser.parse(
                    rawJson = fetchResult.body,
                    feedSourceKey = feed.sourceKey,
                    nowMillis = nowMillis,
                )
                if (parsed is RemotePinFeedParseResult.InvalidFeed) {
                    recordFeedError(feed.id, nowMillis, parsed.reason, previouslySyncedForFeed.size)
                    return RemoteFeedSyncOutcome.Error(
                        feedId = feed.id,
                        feedName = feed.displayName,
                        message = parsed.reason,
                    )
                }

                val parseSuccess = parsed as RemotePinFeedParseResult.Success
                val existingById = allPinsBefore.associateBy { it.id }

                var insertedCount = 0
                var updatedCount = 0
                var unchangedCount = 0
                var deletedCount = 0
                var deliveryFailures = 0
                val diagnostics = mutableListOf<String>()
                diagnostics.addAll(parseSuccess.diagnostics.map { it.reason })

                for (event in parseSuccess.validEvents) {
                    val existing = existingById[event.id]
                    if (existing != null && (!existing.isRemotePin || existing.sourceKey != feed.sourceKey)) {
                        diagnostics.add(
                            "Skipped pin '${event.id}': ID already belongs to a ${describeSource(existing.eventSource)} pin.",
                        )
                        continue
                    }

                    if (existing != null && existing.matchesEvent(event)) {
                        unchangedCount++
                        continue
                    }

                    when (val sendResult = pinSender.sendPin(event)) {
                        is PinSendResult.Success -> {
                            sentPinRepository.saveSentPin(
                                SentPinRecord.fromTimelineEvent(
                                    event = event,
                                    sentAtMillis = nowMillis,
                                ),
                            )
                            if (existing == null) insertedCount++ else updatedCount++
                        }
                        else -> {
                            deliveryFailures++
                            diagnostics.add(
                                "Failed to deliver pin '${event.id}': ${describeSendError(sendResult)}",
                            )
                        }
                    }
                }

                // Delete previously synced pins for this feed that were removed from the feed JSON.
                for (oldRecord in previouslySyncedForFeed) {
                    if (oldRecord.id in parseSuccess.seenIds) continue
                    when (val deleteResult = pinSender.deletePin(oldRecord.id)) {
                        is PinDeleteResult.Success,
                        is PinDeleteResult.UnknownPin,
                        -> {
                            sentPinRepository.deletePinRecord(oldRecord.id)
                            deletedCount++
                        }
                        else -> {
                            deliveryFailures++
                            diagnostics.add(
                                "Failed to delete removed pin '${oldRecord.id}': ${describeDeleteError(deleteResult)}",
                            )
                        }
                    }
                }

                if (insertedCount > 0 || updatedCount > 0 || deletedCount > 0) {
                    runCatching { onPinsChanged?.invoke() }
                }

                val activeCountAfter = sentPinRepository.observeSentPins().first()
                    .count { it.isRemotePin && it.sourceKey == feed.sourceKey }
                val skippedCount = parseSuccess.diagnostics.size +
                    (diagnostics.size - parseSuccess.diagnostics.size - deliveryFailures).coerceAtLeast(0)

                val status = when {
                    deliveryFailures > 0 && insertedCount == 0 && updatedCount == 0 && deletedCount == 0 && unchangedCount == 0 ->
                        RemoteSyncStatus.ERROR
                    deliveryFailures > 0 || diagnostics.isNotEmpty() ->
                        RemoteSyncStatus.PARTIAL_SUCCESS
                    else ->
                        RemoteSyncStatus.SUCCESS
                }

                val summaryParts = buildList {
                    if (insertedCount > 0) add("+$insertedCount added")
                    if (updatedCount > 0) add("~$updatedCount updated")
                    if (deletedCount > 0) add("-$deletedCount deleted")
                    if (insertedCount == 0 && updatedCount == 0 && deletedCount == 0) {
                        add("$unchangedCount unchanged")
                    }
                    if (skippedCount > 0) add("$skippedCount skipped")
                    if (deliveryFailures > 0) add("$deliveryFailures failed")
                }
                val summary = "${summaryParts.joinToString(", ")} • $activeCountAfter active"
                val topError = if (deliveryFailures > 0) {
                    diagnostics.lastOrNull { it.startsWith("Failed to ") }
                } else {
                    null
                }

                settingsRepository.updateFeed(feed.id) { current ->
                    current.copy(
                        // Only advance ETag / Last-Modified when watch delivery succeeded cleanly so
                        // transient companion-app errors retry on the next sync.
                        etag = if (deliveryFailures == 0) fetchResult.etag else current.etag,
                        lastModified = if (deliveryFailures == 0) fetchResult.lastModified else current.lastModified,
                        lastSyncAtMillis = nowMillis,
                        lastSyncStatus = status,
                        lastSyncSummary = summary,
                        lastError = topError,
                        lastSyncedPinCount = activeCountAfter,
                        lastSkippedCount = skippedCount,
                        lastDiagnostics = diagnostics.take(RemotePinFeedConfig.MAX_STORED_DIAGNOSTICS),
                        lastFeedUpdatedAt = parseSuccess.updatedAt ?: current.lastFeedUpdatedAt,
                    )
                }

                return RemoteFeedSyncOutcome.Synced(
                    feedId = feed.id,
                    feedName = feed.displayName,
                    insertedCount = insertedCount,
                    updatedCount = updatedCount,
                    unchangedCount = unchangedCount,
                    deletedCount = deletedCount,
                    skippedCount = skippedCount,
                    deliveryFailureCount = deliveryFailures,
                    activePinCount = activeCountAfter,
                    diagnostics = diagnostics,
                )
            }
        }
    }

    private suspend fun recordFeedError(
        feedId: String,
        nowMillis: Long,
        message: String,
        currentActivePinCount: Int,
    ) {
        settingsRepository.updateFeed(feedId) { current ->
            current.copy(
                lastSyncAtMillis = nowMillis,
                lastSyncStatus = RemoteSyncStatus.ERROR,
                lastSyncSummary = null,
                lastError = message,
                lastSyncedPinCount = currentActivePinCount,
            )
        }
    }

    private fun SentPinRecord.matchesEvent(event: TimelineEvent): Boolean =
        title == event.title &&
            description == event.description &&
            startTimeMillis == event.startTimeMillis &&
            durationMinutes == event.durationMinutes &&
            iconUri == event.iconUri &&
            backgroundColor == event.backgroundColor &&
            primaryColor == event.primaryColor &&
            secondaryColor == event.secondaryColor &&
            layoutType == event.layoutType.name

    private fun describeSource(source: EventSource): String = when (source) {
        EventSource.MANUAL -> "manual"
        EventSource.NOTIFICATION -> "notification auto-pin"
        EventSource.REMOTE_URL -> "different remote feed"
    }

    private fun describeSendError(result: PinSendResult): String = when (result) {
        is PinSendResult.Success -> "OK"
        PinSendResult.NoPebbleApp -> "Pebble companion app not available"
        is PinSendResult.NoPermissions -> "Companion app denied permission for watchapp ${result.watchappUuid}"
        PinSendResult.UnsupportedAction -> "Timeline pin actions unsupported by Pebble companion app"
        is PinSendResult.UnexpectedError -> result.message
    }

    private fun describeDeleteError(result: PinDeleteResult): String = when (result) {
        is PinDeleteResult.Success,
        is PinDeleteResult.UnknownPin,
        -> "OK"
        PinDeleteResult.NoPebbleApp -> "Pebble companion app not available"
        is PinDeleteResult.NoPermissions -> "Companion app denied permission for watchapp ${result.watchappUuid}"
        PinDeleteResult.UnsupportedAction -> "Timeline delete unsupported by Pebble companion app"
        is PinDeleteResult.UnexpectedError -> result.message
    }

    companion object {
        private const val DUE_TOLERANCE_MS: Long = 60_000L
        private const val MAX_TEST_DIAGNOSTICS: Int = 5
    }
}

sealed interface RemoteFeedSyncOutcome {
    val feedId: String
    val feedName: String

    data class Synced(
        override val feedId: String,
        override val feedName: String,
        val insertedCount: Int,
        val updatedCount: Int,
        val unchangedCount: Int,
        val deletedCount: Int,
        val skippedCount: Int,
        val deliveryFailureCount: Int,
        val activePinCount: Int,
        val diagnostics: List<String>,
    ) : RemoteFeedSyncOutcome

    data class NotModified(
        override val feedId: String,
        override val feedName: String,
        val activePinCount: Int,
    ) : RemoteFeedSyncOutcome

    data class Error(
        override val feedId: String,
        override val feedName: String,
        val message: String,
    ) : RemoteFeedSyncOutcome
}

sealed interface RemoteFeedTestResult {
    data class Success(
        val httpStatus: Int,
        val schemaVersion: Int,
        val validPinCount: Int,
        val skippedCount: Int,
        val updatedAt: String?,
        val etagPresent: Boolean,
        val diagnostics: List<String>,
    ) : RemoteFeedTestResult

    data class Failure(
        val headline: String,
        val details: String,
    ) : RemoteFeedTestResult
}

data class RemoteFeedDeleteResult(
    val feedId: String,
    val deletedPinCount: Int,
    val errors: List<String>,
)
