package xyz.hidasy.pebblepinmanager.emulatorbridge

import android.util.Log
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleSenderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * PebbleKit Android 2 bound service that receives real IPC timeline pin insert/delete requests from
 * PebblePinManager and forwards them over HTTP (via `adb reverse`) to `scripts/pebble-emulator-bridge.py`,
 * which injects/removes pins on the live Pebble QEMU emulator (`basalt` or `emery`).
 */
class EmulatorBridgeSenderReceiver : BasePebbleSenderReceiver() {
    override suspend fun sendDataToPebble(
        callingPackage: String?,
        watchappUUID: UUID,
        data: PebbleDictionary,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult> {
        val cfg = BridgeConfigStore.configFlow.value
        val target = WatchIdentifier(cfg.watchId)
        val result = if (cfg.connected) TransmissionResult.Success else TransmissionResult.FailedWatchNotConnected
        return mapOf(target to result)
    }

    override suspend fun startAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult> {
        val cfg = BridgeConfigStore.configFlow.value
        val target = WatchIdentifier(cfg.watchId)
        val result = if (cfg.connected) TransmissionResult.Success else TransmissionResult.FailedWatchNotConnected
        return mapOf(target to result)
    }

    override suspend fun stopAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult> {
        val cfg = BridgeConfigStore.configFlow.value
        val target = WatchIdentifier(cfg.watchId)
        val result = if (cfg.connected) TransmissionResult.Success else TransmissionResult.FailedWatchNotConnected
        return mapOf(target to result)
    }

    override suspend fun insertTimelinePin(
        callingPackage: String?,
        watchappUUID: UUID,
        timelinePin: TimelinePin,
    ): TimelineResult = withContext(Dispatchers.IO) {
        BridgeConfigStore.init(applicationContext)
        if (!TimelinePinJsonSerializer.isAuthorized(callingPackage, watchappUUID)) {
            Log.w(TAG, "Rejected unauthorized insertTimelinePin from $callingPackage for $watchappUUID")
            return@withContext TimelineResult.FailedNoPermissions
        }

        val bridgeBaseUrl = BridgeConfigStore.configFlow.value.bridgeUrl.trimEnd('/')
        val bodyJson = TimelinePinJsonSerializer.serialize(callingPackage, watchappUUID, timelinePin)

        runCatching {
            val conn = (URL("$bridgeBaseUrl/pins").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { out ->
                    out.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
                }
                val code = conn.responseCode
                val responseBody = readResponseBody(conn)
                if (code in 200..299) {
                    Log.i(TAG, "Forwarded timeline pin '${timelinePin.id}' to emulator bridge (HTTP $code)")
                    TimelineResult.Success
                } else {
                    Log.e(TAG, "Emulator bridge rejected pin '${timelinePin.id}' (HTTP $code): $responseBody")
                    TimelineResult.Unknown("Emulator bridge error (HTTP $code): $responseBody")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            Log.e(TAG, "Failed reaching emulator bridge at $bridgeBaseUrl", err)
            TimelineResult.Unknown("Emulator bridge unreachable at $bridgeBaseUrl: ${err.message}")
        }
    }

    override suspend fun deleteTimelinePin(
        callingPackage: String?,
        watchappUUID: UUID,
        id: String,
    ): TimelineResult = withContext(Dispatchers.IO) {
        BridgeConfigStore.init(applicationContext)
        if (!TimelinePinJsonSerializer.isAuthorized(callingPackage, watchappUUID)) {
            Log.w(TAG, "Rejected unauthorized deleteTimelinePin from $callingPackage for $watchappUUID")
            return@withContext TimelineResult.FailedNoPermissions
        }

        val bridgeBaseUrl = BridgeConfigStore.configFlow.value.bridgeUrl.trimEnd('/')
        val encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val query = buildString {
            append("watchappUuid=").append(URLEncoder.encode(watchappUUID.toString(), StandardCharsets.UTF_8.name()))
            if (!callingPackage.isNullOrBlank()) {
                append("&callingPackage=").append(URLEncoder.encode(callingPackage, StandardCharsets.UTF_8.name()))
            }
        }

        runCatching {
            val conn = (URL("$bridgeBaseUrl/pins/$encodedId?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 5_000
                readTimeout = 20_000
            }
            try {
                val code = conn.responseCode
                val responseBody = readResponseBody(conn)
                when {
                    code in 200..299 -> {
                        Log.i(TAG, "Forwarded timeline pin deletion '$id' to emulator bridge (HTTP $code)")
                        TimelineResult.Success
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND -> {
                        TimelineResult.FailedUnknownPin
                    }
                    else -> {
                        Log.e(TAG, "Emulator bridge rejected delete '$id' (HTTP $code): $responseBody")
                        TimelineResult.Unknown("Emulator bridge delete error (HTTP $code): $responseBody")
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            Log.e(TAG, "Failed reaching emulator bridge at $bridgeBaseUrl", err)
            TimelineResult.Unknown("Emulator bridge unreachable at $bridgeBaseUrl: ${err.message}")
        }
    }

    private fun readResponseBody(conn: HttpURLConnection): String {
        val stream = runCatching {
            if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
        }.getOrNull() ?: return ""
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.take(300)
    }

    companion object {
        private const val TAG = "EmuBridgeReceiver"
    }
}
