package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared AppMessage binary/dictionary contract matching `watchapp/src/c/protocol.h`.
 */
object WatchAppProtocol {
    /* AppMessage keys */
    val KEY_MSG_TYPE: UInt = 1u
    val KEY_PIN_COUNT: UInt = 2u
    val KEY_PIN_INDEX: UInt = 3u
    val KEY_PIN_ID: UInt = 4u
    val KEY_PIN_TITLE: UInt = 5u
    val KEY_PIN_TIME: UInt = 6u
    val KEY_DELTA_MINUTES: UInt = 7u
    val KEY_DELTA_DAYS: UInt = 8u
    val KEY_STATUS_OK: UInt = 9u
    val KEY_STATUS_TEXT: UInt = 10u
    val KEY_SYNC_GENERATION: UInt = 11u

    /* Watch -> Phone message types */
    const val MSG_WATCH_REQ_SYNC: Int = 1
    const val MSG_WATCH_REQ_OPEN_PHONE: Int = 2
    const val MSG_WATCH_REQ_RESCHEDULE: Int = 3
    const val MSG_WATCH_REQ_DELETE: Int = 4

    /* Phone -> Watch message types */
    const val MSG_PHONE_SYNC_START: Int = 10
    const val MSG_PHONE_PIN_ITEM: Int = 11
    const val MSG_PHONE_ACTION_RESULT: Int = 12

    /* Buffer limits matching watchapp/src/c/protocol.h (excluding NUL terminator) */
    const val MAX_PINS: Int = 12
    const val MAX_PIN_ID_BYTES: Int = 23
    const val MAX_PIN_TITLE_BYTES: Int = 39
    const val MAX_PIN_TIME_BYTES: Int = 27
    const val MAX_STATUS_TEXT_BYTES: Int = 47

    fun encodeSyncStart(count: Int, generation: Int): PebbleDictionary {
        val clampedCount = count.coerceIn(0, MAX_PINS)
        val clampedGen = generation.and(0xFFFF)
        return mapOf(
            KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(MSG_PHONE_SYNC_START),
            KEY_PIN_COUNT to PebbleDictionaryItem.UInt8(clampedCount),
            KEY_SYNC_GENERATION to PebbleDictionaryItem.UInt16(clampedGen),
        )
    }

    fun encodePinItem(
        index: Int,
        totalCount: Int,
        generation: Int,
        summary: WatchPinSummary,
    ): PebbleDictionary {
        val clampedCount = totalCount.coerceIn(0, MAX_PINS)
        val clampedIndex = index.coerceIn(0, MAX_PINS - 1)
        val clampedGen = generation.and(0xFFFF)
        return mapOf(
            KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(MSG_PHONE_PIN_ITEM),
            KEY_SYNC_GENERATION to PebbleDictionaryItem.UInt16(clampedGen),
            KEY_PIN_INDEX to PebbleDictionaryItem.UInt8(clampedIndex),
            KEY_PIN_COUNT to PebbleDictionaryItem.UInt8(clampedCount),
            KEY_PIN_ID to PebbleDictionaryItem.Text(truncateUtf8Bytes(summary.id, MAX_PIN_ID_BYTES)),
            KEY_PIN_TITLE to PebbleDictionaryItem.Text(truncateUtf8Bytes(summary.title, MAX_PIN_TITLE_BYTES)),
            KEY_PIN_TIME to PebbleDictionaryItem.Text(truncateUtf8Bytes(summary.timeLabel, MAX_PIN_TIME_BYTES)),
        )
    }

    fun encodeActionResult(ok: Boolean, statusText: String): PebbleDictionary {
        return mapOf(
            KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(MSG_PHONE_ACTION_RESULT),
            KEY_STATUS_OK to PebbleDictionaryItem.UInt8(if (ok) 1 else 0),
            KEY_STATUS_TEXT to PebbleDictionaryItem.Text(
                truncateUtf8Bytes(statusText.trim().ifEmpty { if (ok) "Done" else "Error" }, MAX_STATUS_TEXT_BYTES),
            ),
        )
    }

    fun decodeWatchRequest(data: PebbleDictionary): WatchRequest? {
        val msgType = data[KEY_MSG_TYPE]?.asInt() ?: return null
        return when (msgType) {
            MSG_WATCH_REQ_SYNC -> WatchRequest.SyncRequest
            MSG_WATCH_REQ_OPEN_PHONE -> WatchRequest.OpenPhoneUi
            MSG_WATCH_REQ_RESCHEDULE -> {
                val pinId = data[KEY_PIN_ID]?.asText()?.trim().orEmpty()
                if (pinId.isEmpty()) return null
                val deltaMinutes = data[KEY_DELTA_MINUTES]?.asInt() ?: 0
                val deltaDays = data[KEY_DELTA_DAYS]?.asInt() ?: 0
                if (deltaMinutes == 0 && deltaDays == 0) return null
                WatchRequest.ReschedulePin(
                    pinId = pinId,
                    deltaMinutes = deltaMinutes,
                    deltaDays = deltaDays,
                )
            }
            MSG_WATCH_REQ_DELETE -> {
                val pinId = data[KEY_PIN_ID]?.asText()?.trim().orEmpty()
                if (pinId.isEmpty()) return null
                WatchRequest.DeletePin(pinId = pinId)
            }
            else -> null
        }
    }

    /**
     * Truncates [text] so its UTF-8 byte representation never exceeds [maxBytes] and never splits
     * a multi-byte UTF-8 codepoint or surrogate pair. Appends `"..."` when shortened if space allows.
     */
    fun truncateUtf8Bytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val sanitized = text.trim()
        if (sanitized.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return sanitized
        }

        val ellipsis = "..."
        val reserveEllipsis = maxBytes > ellipsis.length
        val targetBytes = if (reserveEllipsis) maxBytes - ellipsis.length else maxBytes

        val builder = StringBuilder()
        var usedBytes = 0
        var i = 0
        while (i < sanitized.length) {
            val codePoint = sanitized.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val chunk = sanitized.substring(i, i + charCount)
            val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
            if (usedBytes + chunkBytes > targetBytes) {
                break
            }
            builder.append(chunk)
            usedBytes += chunkBytes
            i += charCount
        }
        return if (reserveEllipsis) {
            builder.toString().trimEnd() + ellipsis
        } else {
            builder.toString()
        }
    }

    private fun PebbleDictionaryItem.asInt(): Int? = when (this) {
        is PebbleDictionaryItem.Int8 -> value.toInt()
        is PebbleDictionaryItem.Int16 -> value.toInt()
        is PebbleDictionaryItem.Int32 -> value
        is PebbleDictionaryItem.UInt8 -> value.toInt()
        is PebbleDictionaryItem.UInt16 -> value.toInt()
        is PebbleDictionaryItem.UInt32 -> value.toInt()
        else -> null
    }

    private fun PebbleDictionaryItem.asText(): String? = when (this) {
        is PebbleDictionaryItem.Text -> value
        else -> null
    }
}

data class WatchPinSummary(
    val id: String,
    val title: String,
    val timeLabel: String,
)

sealed interface WatchRequest {
    data object SyncRequest : WatchRequest
    data object OpenPhoneUi : WatchRequest
    data class ReschedulePin(
        val pinId: String,
        val deltaMinutes: Int,
        val deltaDays: Int,
    ) : WatchRequest
    data class DeletePin(val pinId: String) : WatchRequest
}

/**
 * Formats [SentPinRecord] timestamps into concise, readable subtitles tailored for Pebble's
 * compact rectangular and round `MenuLayer` rows.
 */
object WatchPinSummaryFormatter {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun toWatchSummary(
        record: SentPinRecord,
        nowMillis: Long,
        zoneId: ZoneId,
    ): WatchPinSummary {
        val label = formatCompactTime(
            startTimeMillis = record.startTimeMillis,
            durationMinutes = record.durationMinutes,
            nowMillis = nowMillis,
            zoneId = zoneId,
        )
        return WatchPinSummary(
            id = WatchAppProtocol.truncateUtf8Bytes(record.id, WatchAppProtocol.MAX_PIN_ID_BYTES),
            title = WatchAppProtocol.truncateUtf8Bytes(record.title, WatchAppProtocol.MAX_PIN_TITLE_BYTES),
            timeLabel = WatchAppProtocol.truncateUtf8Bytes(label, WatchAppProtocol.MAX_PIN_TIME_BYTES),
        )
    }

    fun formatCompactTime(
        startTimeMillis: Long,
        durationMinutes: Int? = null,
        nowMillis: Long,
        zoneId: ZoneId,
    ): String {
        val target = Instant.ofEpochMilli(startTimeMillis).atZone(zoneId)
        val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val targetDate = target.toLocalDate()
        val timeText = target.format(timeFormatter)

        val datePrefix = when (targetDate) {
            today -> "Today"
            today.plusDays(1) -> "Tmrw"
            today.minusDays(1) -> "Yest"
            else -> target.format(monthDayFormatter)
        }

        val base = "$datePrefix $timeText"
        val durationText = durationMinutes?.takeIf { it > 0 }?.let(::formatCompactDuration)
        if (durationText != null) {
            val withDuration = "$base ($durationText)"
            if (withDuration.toByteArray(Charsets.UTF_8).size <= WatchAppProtocol.MAX_PIN_TIME_BYTES) {
                return withDuration
            }
        }
        return base
    }

    private fun formatCompactDuration(durationMinutes: Int): String {
        val hours = durationMinutes / 60
        val mins = durationMinutes % 60
        return when {
            hours == 0 -> "${mins}m"
            mins == 0 -> "${hours}h"
            else -> "${hours}h${mins}m"
        }
    }
}
