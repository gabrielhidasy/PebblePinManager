package xyz.hidasy.pebblepinmanager.ui

import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedAuthType
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedPairingPayload
import xyz.hidasy.pebblepinmanager.remote.RemoteFeedTestResult
import xyz.hidasy.pebblepinmanager.remote.RemotePinFeedConfig
import xyz.hidasy.pebblepinmanager.remote.RemotePinSourceKey
import java.time.ZoneId

/**
 * Pending QR / deep-link pairing payload awaiting explicit user confirmation (shown for cleartext
 * HTTP LAN URLs or external `pebblepin://pair` links).
 */
data class PendingQrPairing(
    val payload: RemoteFeedPairingPayload,
    val existingFeedId: String? = null,
    val existingFeedName: String? = null,
    val fromDeepLink: Boolean = false,
) {
    val isUpdatingExisting: Boolean
        get() = existingFeedId != null
}

/**
 * Draft form state for the Add / Edit Remote Feed modal dialog.
 */
data class RemoteFeedEditorState(
    val editingFeedId: String? = null,
    val name: String = "",
    val url: String = "",
    val authType: RemoteFeedAuthType = RemoteFeedAuthType.BEARER_TOKEN,
    val apiKeyHeaderName: String = RemotePinFeedConfig.DEFAULT_API_KEY_HEADER,
    val basicUsername: String = "",
    /** Newly typed secret value (token/API key or HTTP Basic password). Blank while editing keeps existing secret. */
    val secretInput: String = "",
    val hasStoredSecret: Boolean = false,
    val clearStoredSecret: Boolean = false,
    val allowCleartextHttp: Boolean = false,
    val isEnabled: Boolean = true,
    val pollingIntervalMinutes: Int = RemotePinFeedConfig.DEFAULT_POLLING_INTERVAL_MINUTES,
    val urlError: String? = null,
    val headerNameError: String? = null,
    val authError: String? = null,
    val isTesting: Boolean = false,
    val testResult: RemoteFeedTestResult? = null,
) {
    val isEditing: Boolean
        get() = editingFeedId != null
}

/**
 * Immutable UI state for the Remote Pin Feeds screen.
 */
data class RemoteFeedsUiState(
    val feeds: List<RemotePinFeedConfig> = emptyList(),
    val feedIdsWithSecrets: Set<String> = emptySet(),
    val remotePins: List<SentPinRecord> = emptyList(),
    val syncingFeedIds: Set<String> = emptySet(),
    val isSyncingAll: Boolean = false,
    val deletingPinId: String? = null,
    val expandedDiagnosticsFeedIds: Set<String> = emptySet(),
    val editorState: RemoteFeedEditorState? = null,
    val pendingPairing: PendingQrPairing? = null,
    val feedback: SendFeedback? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val enabledFeedCount: Int
        get() = feeds.count { it.isEnabled }

    val remotePinCount: Int
        get() = remotePins.size

    val isAnySyncInProgress: Boolean
        get() = isSyncingAll || syncingFeedIds.isNotEmpty()

    fun syncedPinsCountForFeed(feedId: String): Int {
        val sourceKey = RemotePinSourceKey.forFeed(feedId)
        return remotePins.count { it.sourceKey == sourceKey }
    }

    fun feedDisplayNameForPin(record: SentPinRecord): String {
        val feedId = RemotePinSourceKey.extractFeedId(record.sourceKey) ?: return "Remote Feed"
        return feeds.firstOrNull { it.id == feedId }?.displayName ?: "Remote Feed ($feedId)"
    }
}
