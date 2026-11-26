package xyz.hidasy.pebblepinmanager.remote

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Supported HTTP authentication schemes for a configured remote JSON pin feed.
 */
@Serializable
enum class RemoteFeedAuthType(val label: String) {
    BEARER_TOKEN("Bearer Token"),
    API_KEY_HEADER("API Key Header"),
    BASIC("HTTP Basic"),
    NONE("No Auth"),
}

/**
 * Outcome classification of the most recent sync attempt for a remote feed.
 */
@Serializable
enum class RemoteSyncStatus {
    NEVER_SYNCED,
    SUCCESS,
    NOT_MODIFIED,
    PARTIAL_SUCCESS,
    ERROR,
}

/**
 * Non-secret configuration and sync status metadata for a single remote JSON pin feed.
 *
 * Authentication secrets (bearer tokens, API keys, HTTP Basic passwords) are NEVER stored in this
 * model; they live exclusively in [RemoteFeedSecretStore] (`remote_feed_secrets`), which is
 * encrypted with Android Keystore and excluded from Android Auto Backup.
 */
@Serializable
data class RemotePinFeedConfig(
    val id: String = generateFeedId(),
    val name: String = "",
    val url: String = "",
    val authType: RemoteFeedAuthType = RemoteFeedAuthType.BEARER_TOKEN,
    val apiKeyHeaderName: String = DEFAULT_API_KEY_HEADER,
    val basicUsername: String = "",
    val allowCleartextHttp: Boolean = false,
    val isEnabled: Boolean = true,
    val pollingIntervalMinutes: Int = DEFAULT_POLLING_INTERVAL_MINUTES,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastSyncAtMillis: Long? = null,
    val lastSyncStatus: RemoteSyncStatus = RemoteSyncStatus.NEVER_SYNCED,
    val lastSyncSummary: String? = null,
    val lastError: String? = null,
    val lastSyncedPinCount: Int = 0,
    val lastSkippedCount: Int = 0,
    val lastDiagnostics: List<String> = emptyList(),
    val lastFeedUpdatedAt: String? = null,
) {
    val displayName: String
        get() = name.trim().ifEmpty { RemoteFeedUrlValidator.deriveDefaultFeedName(url) }

    val sourceKey: String
        get() = RemotePinSourceKey.forFeed(id)

    val effectiveApiKeyHeaderName: String
        get() = apiKeyHeaderName.trim().ifEmpty { DEFAULT_API_KEY_HEADER }

    val hasPeriodicPolling: Boolean
        get() = isEnabled && pollingIntervalMinutes >= MIN_POLLING_INTERVAL_MINUTES

    fun normalized(): RemotePinFeedConfig = copy(
        id = id.trim().ifEmpty { generateFeedId() },
        name = name.trim(),
        url = url.trim(),
        apiKeyHeaderName = apiKeyHeaderName.trim().ifEmpty { DEFAULT_API_KEY_HEADER },
        basicUsername = basicUsername.trim(),
        pollingIntervalMinutes = normalizePollingInterval(pollingIntervalMinutes),
        etag = etag?.trim()?.takeIf { it.isNotEmpty() },
        lastModified = lastModified?.trim()?.takeIf { it.isNotEmpty() },
        lastSyncSummary = lastSyncSummary?.trim()?.takeIf { it.isNotEmpty() },
        lastError = lastError?.trim()?.takeIf { it.isNotEmpty() },
        lastDiagnostics = lastDiagnostics.take(MAX_STORED_DIAGNOSTICS),
        lastFeedUpdatedAt = lastFeedUpdatedAt?.trim()?.takeIf { it.isNotEmpty() },
    )

    companion object {
        const val DEFAULT_API_KEY_HEADER: String = "X-API-Key"
        const val MANUAL_ONLY_INTERVAL_MINUTES: Int = 0
        const val MIN_POLLING_INTERVAL_MINUTES: Int = 15
        const val DEFAULT_POLLING_INTERVAL_MINUTES: Int = 60
        const val MAX_STORED_DIAGNOSTICS: Int = 20

        fun generateFeedId(): String {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
            return "feed-$suffix"
        }

        fun normalizePollingInterval(minutes: Int): Int {
            if (minutes <= 0) return MANUAL_ONLY_INTERVAL_MINUTES
            return minutes.coerceAtLeast(MIN_POLLING_INTERVAL_MINUTES)
        }
    }
}

/**
 * Container for all user-configured remote pin feeds stored in Preferences DataStore.
 */
@Serializable
data class RemotePinFeedsSettings(
    val feeds: List<RemotePinFeedConfig> = emptyList(),
)

/**
 * Sensitive credentials for a remote pin feed, persisted exclusively via [RemoteFeedSecretStore].
 */
@Serializable
data class RemoteFeedSecret(
    val token: String = "",
    val basicPassword: String = "",
) {
    fun hasCredentialFor(authType: RemoteFeedAuthType): Boolean = when (authType) {
        RemoteFeedAuthType.BEARER_TOKEN,
        RemoteFeedAuthType.API_KEY_HEADER,
        -> token.isNotBlank()
        RemoteFeedAuthType.BASIC -> basicPassword.isNotBlank()
        RemoteFeedAuthType.NONE -> true
    }

    fun isBlank(): Boolean = token.isBlank() && basicPassword.isBlank()
}

/**
 * Canonical `SentPinRecord.sourceKey` formatting for remote JSON pin feeds so synced pins stay
 * cleanly separated from `MANUAL` and `NOTIFICATION` pins and isolated per feed.
 */
object RemotePinSourceKey {
    const val PREFIX: String = "remote:"

    fun forFeed(feedId: String): String = "$PREFIX${feedId.trim()}"

    fun extractFeedId(sourceKey: String?): String? {
        val raw = sourceKey?.trim() ?: return null
        if (!raw.startsWith(PREFIX)) return null
        return raw.removePrefix(PREFIX).takeIf { it.isNotEmpty() }
    }
}

/**
 * Curated polling interval presets exposed in the Remote Feed settings UI.
 */
enum class RemoteFeedPollingOption(
    val minutes: Int,
    val label: String,
) {
    MANUAL_ONLY(0, "Manual only"),
    EVERY_15_MIN(15, "15 min"),
    EVERY_30_MIN(30, "30 min"),
    EVERY_1_HOUR(60, "1 hour"),
    EVERY_3_HOURS(180, "3 hours"),
    EVERY_6_HOURS(360, "6 hours"),
    EVERY_12_HOURS(720, "12 hours"),
    EVERY_24_HOURS(1440, "24 hours"),
    ;

    companion object {
        fun formatInterval(minutes: Int): String {
            if (minutes <= 0) return MANUAL_ONLY.label
            entries.firstOrNull { it.minutes == minutes }?.let { return "Every ${it.label}" }
            return if (minutes % 60 == 0) {
                val hours = minutes / 60
                "Every ${hours}h"
            } else {
                "Every ${minutes}m"
            }
        }
    }
}
