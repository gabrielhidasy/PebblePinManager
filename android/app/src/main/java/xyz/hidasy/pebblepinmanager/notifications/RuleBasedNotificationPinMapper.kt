package xyz.hidasy.pebblepinmanager.notifications

import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.ZoneId

/**
 * Concrete [NotificationPinMapper] enforcing opt-in package allowlists, optional keyword filtering,
 * optional regex extraction for title/description/time, Pebble length truncation, and clean pin-ID
 * reuse for deduplicated notification updates.
 */
class RuleBasedNotificationPinMapper(
    private val settingsProvider: suspend () -> NotificationRuleSettings,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val pinIdGenerator: () -> String = { TimelineEvent.generatePinId(prefix = "ppm-notif") },
) : NotificationPinMapper {

    constructor(
        settings: NotificationRuleSettings,
        clockMillis: () -> Long = System::currentTimeMillis,
        zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
        pinIdGenerator: () -> String = { TimelineEvent.generatePinId(prefix = "ppm-notif") },
    ) : this(
        settingsProvider = { settings },
        clockMillis = clockMillis,
        zoneIdProvider = zoneIdProvider,
        pinIdGenerator = pinIdGenerator,
    )

    override suspend fun mapOrNull(
        candidate: NotificationPinCandidate,
        existingPinId: String?,
    ): TimelineEvent? {
        val settings = settingsProvider()
        if (!settings.isAutoPinEnabled) return null
        if (!settings.isPackageAllowed(candidate.sourcePackage)) return null
        if (settings.ignoreOngoing && candidate.isOngoing) return null
        if (settings.ignoreGroupSummary && candidate.isGroupSummary) return null

        val rawTitle = candidate.title.trim().ifEmpty { candidate.appLabel?.trim().orEmpty() }
        val rawBody = buildBodyText(candidate)
        if (rawTitle.isEmpty() && rawBody.isNullOrBlank()) return null

        val searchableText = listOfNotNull(
            rawTitle.takeIf { it.isNotEmpty() },
            rawBody?.takeIf { it.isNotBlank() },
            candidate.subText?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(separator = "\n")

        val keywords = settings.parsedKeywords()
        if (keywords.isNotEmpty()) {
            val matchedKeyword = keywords.any { keyword ->
                searchableText.contains(keyword, ignoreCase = true)
            }
            if (!matchedKeyword) return null
        }

        val extractedTitle = extractByRegex(
            pattern = settings.titleRegexPattern,
            primary = rawTitle,
            fallback = searchableText,
        ) ?: rawTitle

        val extractedDescription = extractByRegex(
            pattern = settings.descriptionRegexPattern,
            primary = rawBody.orEmpty(),
            fallback = searchableText,
        ) ?: rawBody

        val finalTitleRaw = extractedTitle.trim().ifEmpty {
            extractedDescription?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }
        if (finalTitleRaw.isEmpty()) return null

        val finalTitle = truncateWithEllipsis(finalTitleRaw, TimelineEvent.MAX_TITLE_LENGTH)
        val finalDescription = extractedDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != finalTitleRaw }
            ?.let { truncateWithEllipsis(it, TimelineEvent.MAX_DESCRIPTION_LENGTH) }

        val referenceMillis = candidate.eventTimeMillis.takeIf { it > 0L } ?: clockMillis()
        val extractedTimeMillis = when {
            settings.timeRegexPattern.isNotBlank() -> NotificationTimeExtractor.extractEventTimeMillis(
                text = searchableText,
                referenceMillis = referenceMillis,
                zoneId = zoneIdProvider(),
                customTimeRegex = settings.timeRegexPattern,
            )
            settings.extractTimeFromText -> NotificationTimeExtractor.extractEventTimeMillis(
                text = searchableText,
                referenceMillis = referenceMillis,
                zoneId = zoneIdProvider(),
            )
            else -> null
        }

        val resolvedIconUri = settings.defaultIconUri
            .takeIf { PinIcon.isValidSystemIconUri(it) }
            ?: PinIcon.NOTIFICATION_FLAG.uri
        val resolvedBackgroundColor = settings.defaultBackgroundColor
            ?.trim()
            ?.takeIf { PinAccentColor.isValidHexColor(it) }
        val resolvedDuration = settings.defaultDurationMinutes
            ?.takeIf { it in TimelineEvent.MIN_DURATION_MINUTES..TimelineEvent.MAX_DURATION_MINUTES }

        return TimelineEvent(
            id = existingPinId?.takeIf { it.isNotBlank() } ?: pinIdGenerator(),
            title = finalTitle,
            description = finalDescription,
            startTimeMillis = extractedTimeMillis ?: referenceMillis,
            durationMinutes = resolvedDuration,
            iconUri = resolvedIconUri,
            backgroundColor = resolvedBackgroundColor,
            layoutType = PinLayoutType.GENERIC_PIN,
            source = EventSource.NOTIFICATION,
            sourceKey = candidate.deduplicationKey(),
        )
    }

    private fun buildBodyText(candidate: NotificationPinCandidate): String? {
        val bigText = candidate.bigText?.trim()?.takeIf { it.isNotEmpty() }
        val text = candidate.text?.trim()?.takeIf { it.isNotEmpty() }
        val subText = candidate.subText?.trim()?.takeIf { it.isNotEmpty() }

        val mainBody = bigText ?: text
        return when {
            mainBody == null -> subText
            subText == null || mainBody.contains(subText, ignoreCase = true) -> mainBody
            else -> "$mainBody\n$subText"
        }
    }

    private fun extractByRegex(
        pattern: String,
        primary: String,
        fallback: String,
    ): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        val regex = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val match = (primary.takeIf { it.isNotEmpty() }?.let { regex.find(it) })
            ?: regex.find(fallback)
            ?: return null
        val group1 = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return group1 ?: match.value.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        fun truncateWithEllipsis(value: String, maxLength: Int): String {
            if (maxLength <= 0) return ""
            if (value.length <= maxLength) return value
            if (maxLength == 1) return "…"
            return value.take(maxLength - 1).trimEnd() + "…"
        }
    }
}
