package xyz.hidasy.pebblepinmanager.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.domain.TimelineEventValidator
import xyz.hidasy.pebblepinmanager.domain.ValidationResult
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Parses and validates a versioned remote JSON pin feed into canonical [TimelineEvent] models.
 *
 * Schema v1 root contract:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "updatedAt": "2026-11-25T22:00:00Z",
 *   "pins": [ ... ]
 * }
 * ```
 *
 * Per-entry validation failures are collected as [RemotePinDiagnostic] records and skipped without
 * aborting the rest of the feed.
 */
object RemotePinFeedParser {
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(
        rawJson: String,
        feedSourceKey: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): RemotePinFeedParseResult {
        if (rawJson.isBlank()) {
            return RemotePinFeedParseResult.InvalidFeed("Response body is empty.")
        }

        val rootElement = runCatching { json.parseToJsonElement(rawJson) }.getOrElse { err ->
            return RemotePinFeedParseResult.InvalidFeed(
                "Invalid JSON syntax: ${err.message?.lineSequence()?.firstOrNull() ?: "unable to parse"}",
            )
        }

        val root = rootElement as? JsonObject
            ?: return RemotePinFeedParseResult.InvalidFeed("Feed root must be a JSON object.")

        val versionElement = root["schemaVersion"]
            ?: return RemotePinFeedParseResult.InvalidFeed("Missing required field 'schemaVersion'.")
        val schemaVersion = (versionElement as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.intOrNull
            ?: return RemotePinFeedParseResult.InvalidFeed("'schemaVersion' must be an integer (expected $SUPPORTED_SCHEMA_VERSION).")

        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return RemotePinFeedParseResult.InvalidFeed(
                "Unsupported feed schemaVersion $schemaVersion (supported: $SUPPORTED_SCHEMA_VERSION).",
            )
        }

        val updatedAtElement = root["updatedAt"]
        val updatedAt: String? = when (updatedAtElement) {
            null, is JsonNull -> null
            is JsonPrimitive -> if (updatedAtElement.isString) {
                updatedAtElement.content.trim().takeIf { it.isNotEmpty() }
            } else {
                return RemotePinFeedParseResult.InvalidFeed("'updatedAt' must be an ISO-8601 timestamp string.")
            }
            else -> return RemotePinFeedParseResult.InvalidFeed("'updatedAt' must be an ISO-8601 timestamp string.")
        }

        val pinsElement = root["pins"]
            ?: return RemotePinFeedParseResult.InvalidFeed("Missing required array field 'pins'.")
        val pinsArray = pinsElement as? JsonArray
            ?: return RemotePinFeedParseResult.InvalidFeed("'pins' must be a JSON array.")

        val validEvents = mutableListOf<TimelineEvent>()
        val seenIds = linkedSetOf<String>()
        val diagnostics = mutableListOf<RemotePinDiagnostic>()

        pinsArray.forEachIndexed { index, element ->
            val obj = element as? JsonObject
            if (obj == null) {
                diagnostics.add(
                    RemotePinDiagnostic(
                        index = index,
                        pinId = null,
                        reason = "Pin entry at index $index must be a JSON object.",
                    ),
                )
                return@forEachIndexed
            }

            val rawId = extractOptionalString(obj["id"])
            val trimmedId = rawId?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmedId == null) {
                diagnostics.add(
                    RemotePinDiagnostic(
                        index = index,
                        pinId = null,
                        reason = "Pin entry at index $index is missing a non-blank string 'id'.",
                    ),
                )
                return@forEachIndexed
            }

            if (!seenIds.add(trimmedId)) {
                diagnostics.add(
                    RemotePinDiagnostic(
                        index = index,
                        pinId = trimmedId,
                        reason = "Duplicate pin id '$trimmedId' at index $index skipped.",
                    ),
                )
                return@forEachIndexed
            }

            val entryErrors = mutableListOf<String>()

            val title = when (val titleEl = obj["title"]) {
                null, is JsonNull -> {
                    entryErrors.add("Missing required field 'title'.")
                    ""
                }
                is JsonPrimitive -> if (titleEl.isString) {
                    titleEl.content
                } else {
                    entryErrors.add("'title' must be a string.")
                    ""
                }
                else -> {
                    entryErrors.add("'title' must be a string.")
                    ""
                }
            }

            val description = parseOptionalStringField(obj, "description", entryErrors)
            val startTimeMillis = parseStartTimeMillis(obj, entryErrors)
            val durationMinutes = parseOptionalIntField(obj, "durationMinutes", entryErrors)
            val iconUri = parseOptionalStringField(obj, "iconUri", entryErrors)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: TimelineEvent.DEFAULT_ICON_URI
            val backgroundColor = parseOptionalColorField(obj, "backgroundColor", entryErrors)
            val primaryColor = parseOptionalColorField(obj, "primaryColor", entryErrors)
            val secondaryColor = parseOptionalColorField(obj, "secondaryColor", entryErrors)
            val layoutType = parseLayoutType(obj, entryErrors)

            if (entryErrors.isNotEmpty() || startTimeMillis == null) {
                diagnostics.add(
                    RemotePinDiagnostic(
                        index = index,
                        pinId = trimmedId,
                        reason = "Pin '$trimmedId': ${entryErrors.joinToString(" ")}",
                    ),
                )
                return@forEachIndexed
            }

            val candidate = TimelineEvent(
                id = trimmedId,
                title = title,
                description = description,
                startTimeMillis = startTimeMillis,
                durationMinutes = durationMinutes,
                iconUri = iconUri,
                backgroundColor = backgroundColor,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                layoutType = layoutType,
                source = EventSource.REMOTE_URL,
                sourceKey = feedSourceKey,
            )

            when (val validation = TimelineEventValidator.validate(candidate, nowMillis)) {
                is ValidationResult.Valid -> validEvents.add(validation.event)
                is ValidationResult.Invalid -> {
                    val reason = validation.errors.joinToString(" ") { it.message }
                    diagnostics.add(
                        RemotePinDiagnostic(
                            index = index,
                            pinId = trimmedId,
                            reason = "Pin '$trimmedId': $reason",
                        ),
                    )
                }
            }
        }

        return RemotePinFeedParseResult.Success(
            schemaVersion = schemaVersion,
            updatedAt = updatedAt,
            totalEntries = pinsArray.size,
            validEvents = validEvents,
            seenIds = seenIds,
            diagnostics = diagnostics,
        )
    }

    private fun extractOptionalString(element: JsonElement?): String? {
        val prim = element as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content else null
    }

    private fun parseOptionalStringField(
        obj: JsonObject,
        fieldName: String,
        errors: MutableList<String>,
    ): String? {
        val el = obj[fieldName] ?: return null
        if (el is JsonNull) return null
        if (el is JsonPrimitive && el.isString) {
            return el.content
        }
        errors.add("'$fieldName' must be a string.")
        return null
    }

    private fun parseOptionalIntField(
        obj: JsonObject,
        fieldName: String,
        errors: MutableList<String>,
    ): Int? {
        val el = obj[fieldName] ?: return null
        if (el is JsonNull) return null
        if (el is JsonPrimitive && !el.isString) {
            val value = el.intOrNull
            if (value != null) return value
        }
        errors.add("'$fieldName' must be an integer.")
        return null
    }

    private fun parseOptionalColorField(
        obj: JsonObject,
        fieldName: String,
        errors: MutableList<String>,
    ): String? {
        val raw = parseOptionalStringField(obj, fieldName, errors)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (!PinAccentColor.isValidHexColor(raw)) {
            errors.add("'$fieldName' must be a 6-digit hex color like #00AAFF (got '$raw').")
            return null
        }
        val clean = raw.removePrefix("#").uppercase()
        return "#$clean"
    }

    private fun parseLayoutType(
        obj: JsonObject,
        errors: MutableList<String>,
    ): PinLayoutType {
        val raw = parseOptionalStringField(obj, "layoutType", errors)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return PinLayoutType.GENERIC_PIN
        return PinLayoutType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: run {
                val allowed = PinLayoutType.entries.joinToString(", ") { it.name }
                errors.add("Unsupported layoutType '$raw' (allowed: $allowed).")
                PinLayoutType.GENERIC_PIN
            }
    }

    private fun parseStartTimeMillis(
        obj: JsonObject,
        errors: MutableList<String>,
    ): Long? {
        val startTimeEl = obj["startTime"]
        val startTimeMillisEl = obj["startTimeMillis"]

        val chosen = when {
            startTimeEl != null && startTimeEl !is JsonNull -> startTimeEl
            startTimeMillisEl != null && startTimeMillisEl !is JsonNull -> startTimeMillisEl
            else -> {
                errors.add("Missing required timestamp ('startTime' or 'startTimeMillis').")
                return null
            }
        }

        if (chosen !is JsonPrimitive) {
            errors.add("'startTime' must be an ISO-8601 timestamp string or epoch milliseconds.")
            return null
        }

        if (!chosen.isString) {
            val millis = chosen.longOrNull
            if (millis != null) return millis
            errors.add("Numeric 'startTime' must be a valid epoch millisecond integer.")
            return null
        }

        val text = chosen.content.trim()
        if (text.isEmpty()) {
            errors.add("'startTime' must not be blank.")
            return null
        }

        // Accept ISO-8601 UTC (`2026-11-26T14:00:00Z`) or explicit offset (`2026-11-26T11:00:00-03:00`).
        val parsedInstant = runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        if (parsedInstant != null) {
            return parsedInstant.toEpochMilli()
        }

        errors.add("Invalid 'startTime' value '$text' (expected ISO-8601 UTC/offset timestamp or epoch millis).")
        return null
    }
}

data class RemotePinDiagnostic(
    val index: Int,
    val pinId: String?,
    val reason: String,
)

sealed interface RemotePinFeedParseResult {
    data class Success(
        val schemaVersion: Int,
        val updatedAt: String?,
        val totalEntries: Int,
        val validEvents: List<TimelineEvent>,
        val seenIds: Set<String>,
        val diagnostics: List<RemotePinDiagnostic>,
    ) : RemotePinFeedParseResult

    data class InvalidFeed(val reason: String) : RemotePinFeedParseResult
}
