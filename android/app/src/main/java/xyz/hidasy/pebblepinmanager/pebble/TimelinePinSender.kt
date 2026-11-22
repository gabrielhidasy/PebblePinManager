package xyz.hidasy.pebblepinmanager.pebble

import kotlinx.coroutines.flow.Flow
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent

/**
 * Transport abstraction for delivering and managing Pebble Timeline pins.
 *
 * Implemented primarily by [PebbleKit2PinSender] (Core Devices Pebble app / microPebble via
 * `io.rebble.pebblekit2:client`), while remaining cleanly swappable for tests or alternative
 * bridges (such as a classic Pebble / PebbleKit JS bridge).
 */
interface TimelinePinSender : AutoCloseable {
    suspend fun sendPin(event: TimelineEvent): PinSendResult

    suspend fun deletePin(pinId: String): PinDeleteResult

    suspend fun getCompanionStatus(): PebbleCompanionStatus

    fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>>

    override fun close() = Unit
}

sealed interface PinSendResult {
    data class Success(val pinId: String) : PinSendResult

    /** No PebbleKit 2-compatible Pebble companion app is installed or selected. */
    data object NoPebbleApp : PinSendResult

    /** Companion app rejected the request because the Android package is not authorized for the watchapp UUID. */
    data class NoPermissions(val watchappUuid: String) : PinSendResult

    /** Installed Pebble companion app does not support PebbleKit 2 timeline pin actions. */
    data object UnsupportedAction : PinSendResult

    data class UnexpectedError(
        val message: String,
        val cause: Throwable? = null,
    ) : PinSendResult
}

sealed interface PinDeleteResult {
    data class Success(val pinId: String) : PinDeleteResult
    data class UnknownPin(val pinId: String) : PinDeleteResult
    data object NoPebbleApp : PinDeleteResult
    data class NoPermissions(val watchappUuid: String) : PinDeleteResult
    data object UnsupportedAction : PinDeleteResult
    data class UnexpectedError(
        val message: String,
        val cause: Throwable? = null,
    ) : PinDeleteResult
}

sealed interface PebbleCompanionStatus {
    data class Available(
        val selectedPackage: String,
        val eligiblePackages: List<String>,
    ) : PebbleCompanionStatus

    data class Unavailable(val reason: String) : PebbleCompanionStatus
}

data class ConnectedPebbleWatch(
    val id: String,
    val name: String,
    val platformCode: String,
    val platform: PebbleHardwarePlatform,
    val revision: String,
    val firmwareVersion: String,
) {
    val displayLabel: String
        get() = "$name — ${platform.displayName}"
}

enum class PebbleHardwarePlatform(val code: String, val displayName: String) {
    APLITE("aplite", "Pebble Classic / Steel (aplite)"),
    BASALT("basalt", "Pebble Time (basalt)"),
    CHALK("chalk", "Pebble Time Round (chalk)"),
    DIORITE("diorite", "Pebble 2 (diorite)"),
    EMERY("emery", "Pebble Time 2 (emery)"),
    FLINT("flint", "Pebble 2 Duo (flint)"),
    GABBRO("gabbro", "Pebble Time 2 Round (gabbro)"),
    UNKNOWN("unknown", "Pebble Watch"),
    ;

    val isTargetColorTimelineHardware: Boolean
        get() = this == BASALT || this == EMERY

    /**
     * True for color-screen Pebble watches that render pin `backgroundColor` accents
     * (`basalt`, `chalk`, `emery`, `gabbro`).
     */
    val supportsColorTimeline: Boolean
        get() = this == BASALT || this == CHALK || this == EMERY || this == GABBRO

    companion object {
        fun fromPlatformCode(code: String?): PebbleHardwarePlatform {
            val normalized = code?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == normalized } ?: UNKNOWN
        }
    }
}
