package xyz.hidasy.pebblepinmanager.notifications

import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.ZoneId

enum class RuleMatchScope {
    NONE,
    APP_RULE,
    APP_DEFAULT,
    GLOBAL_RULE,
    GLOBAL_DEFAULT,
}

/**
 * Detailed outcome of evaluating a [NotificationPinCandidate] against [NotificationRuleSettings],
 * useful both in the notification listener pipeline and in the in-app live rule tester.
 */
data class NotificationPinEvaluation(
    val event: TimelineEvent?,
    val matchedScope: RuleMatchScope,
    val matchedRuleName: String? = null,
    val reason: String,
)

/**
 * Concrete [NotificationPinMapper] enforcing opt-in package allowlists, optional per-app rule
 * configs with N ordered regex rules, global fallback keyword/regex rules, Pebble length
 * truncation, and clean pin-ID reuse for deduplicated notification updates.
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
    ): TimelineEvent? = evaluate(candidate, existingPinId).event

    suspend fun evaluate(
        candidate: NotificationPinCandidate,
        existingPinId: String? = null,
    ): NotificationPinEvaluation {
        val settings = settingsProvider()
        if (!settings.isAutoPinEnabled) {
            return ignored("Auto-pinning is disabled.")
        }
        if (!settings.isPackageAllowed(candidate.sourcePackage)) {
            return ignored("App ${candidate.sourcePackage} is not in the allowlist.")
        }
        if (settings.ignoreOngoing && candidate.isOngoing) {
            return ignored("Ignored ongoing notification.")
        }
        if (settings.ignoreGroupSummary && candidate.isGroupSummary) {
            return ignored("Ignored group summary notification.")
        }

        val rawTitle = candidate.title.trim().ifEmpty { candidate.appLabel?.trim().orEmpty() }
        val rawBody = buildBodyText(candidate)
        if (rawTitle.isEmpty() && rawBody.isNullOrBlank()) {
            return ignored("Notification has no title or body text.")
        }

        val searchableText = listOfNotNull(
            rawTitle.takeIf { it.isNotEmpty() },
            rawBody?.takeIf { it.isNotBlank() },
            candidate.subText?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(separator = "\n")

        val activeAppConfig = settings.appRuleFor(candidate.sourcePackage)?.takeIf { it.enabled }

        if (activeAppConfig != null) {
            val appExcluded = activeAppConfig.parsedExcludeKeywords().firstOrNull { keyword ->
                searchableText.contains(keyword, ignoreCase = true)
            }
            if (appExcluded != null) {
                return ignored("Excluded by app keyword \"$appExcluded\".")
            }

            val appIncludeKeywords = activeAppConfig.parsedKeywords()
            if (appIncludeKeywords.isNotEmpty()) {
                val matched = appIncludeKeywords.any { keyword ->
                    searchableText.contains(keyword, ignoreCase = true)
                }
                if (!matched) {
                    return ignored("Did not match any app include keyword.")
                }
            }

            val enabledAppRules = activeAppConfig.regexRules.filter { it.enabled }
            enabledAppRules.forEachIndexed { index, rule ->
                if (ruleMatches(rule, rawTitle, rawBody, searchableText)) {
                    val ruleLabel = rule.displayName(index)
                    if (rule.action == NotificationRuleAction.IGNORE) {
                        return NotificationPinEvaluation(
                            event = null,
                            matchedScope = RuleMatchScope.APP_RULE,
                            matchedRuleName = ruleLabel,
                            reason = "Ignored by app rule \"$ruleLabel\".",
                        )
                    }
                    val event = buildEvent(
                        candidate = candidate,
                        existingPinId = existingPinId,
                        settings = settings,
                        appConfig = activeAppConfig,
                        rule = rule,
                        rawTitle = rawTitle,
                        rawBody = rawBody,
                        searchableText = searchableText,
                    ) ?: return ignored("App rule \"$ruleLabel\" produced an empty title.")

                    return NotificationPinEvaluation(
                        event = event,
                        matchedScope = RuleMatchScope.APP_RULE,
                        matchedRuleName = ruleLabel,
                        reason = "Matched app rule \"$ruleLabel\".",
                    )
                }
            }

            if (activeAppConfig.requireRegexRuleMatch) {
                val msg = if (enabledAppRules.isEmpty()) {
                    "Requires an app regex rule match, but no app regex rules are enabled."
                } else {
                    "Did not match any of ${enabledAppRules.size} enabled app regex rule(s)."
                }
                return ignored(msg)
            }

            if (activeAppConfig.fallbackToGlobalRules) {
                return evaluateGlobal(
                    candidate = candidate,
                    existingPinId = existingPinId,
                    settings = settings,
                    appConfig = activeAppConfig,
                    skipGlobalIncludeKeywords = appIncludeKeywords.isNotEmpty(),
                    rawTitle = rawTitle,
                    rawBody = rawBody,
                    searchableText = searchableText,
                )
            }

            val event = buildEvent(
                candidate = candidate,
                existingPinId = existingPinId,
                settings = settings,
                appConfig = activeAppConfig,
                rule = null,
                rawTitle = rawTitle,
                rawBody = rawBody,
                searchableText = searchableText,
            ) ?: return ignored("Notification produced an empty title.")

            return NotificationPinEvaluation(
                event = event,
                matchedScope = RuleMatchScope.APP_DEFAULT,
                matchedRuleName = null,
                reason = "Matched app defaults.",
            )
        }

        return evaluateGlobal(
            candidate = candidate,
            existingPinId = existingPinId,
            settings = settings,
            appConfig = null,
            skipGlobalIncludeKeywords = false,
            rawTitle = rawTitle,
            rawBody = rawBody,
            searchableText = searchableText,
        )
    }

    private fun evaluateGlobal(
        candidate: NotificationPinCandidate,
        existingPinId: String?,
        settings: NotificationRuleSettings,
        appConfig: AppNotificationRuleConfig?,
        skipGlobalIncludeKeywords: Boolean,
        rawTitle: String,
        rawBody: String?,
        searchableText: String,
    ): NotificationPinEvaluation {
        val globalExcluded = settings.parsedExcludeKeywords().firstOrNull { keyword ->
            searchableText.contains(keyword, ignoreCase = true)
        }
        if (globalExcluded != null) {
            return ignored("Excluded by global keyword \"$globalExcluded\".")
        }

        if (!skipGlobalIncludeKeywords) {
            val globalKeywords = settings.parsedKeywords()
            if (globalKeywords.isNotEmpty()) {
                val matched = globalKeywords.any { keyword ->
                    searchableText.contains(keyword, ignoreCase = true)
                }
                if (!matched) {
                    return ignored("Did not match any global include keyword.")
                }
            }
        }

        val enabledGlobalRules = settings.globalRegexRules.filter { it.enabled }
        enabledGlobalRules.forEachIndexed { index, rule ->
            if (ruleMatches(rule, rawTitle, rawBody, searchableText)) {
                val ruleLabel = rule.displayName(index)
                if (rule.action == NotificationRuleAction.IGNORE) {
                    return NotificationPinEvaluation(
                        event = null,
                        matchedScope = RuleMatchScope.GLOBAL_RULE,
                        matchedRuleName = ruleLabel,
                        reason = "Ignored by global rule \"$ruleLabel\".",
                    )
                }
                val event = buildEvent(
                    candidate = candidate,
                    existingPinId = existingPinId,
                    settings = settings,
                    appConfig = appConfig,
                    rule = rule,
                    rawTitle = rawTitle,
                    rawBody = rawBody,
                    searchableText = searchableText,
                ) ?: return ignored("Global rule \"$ruleLabel\" produced an empty title.")

                return NotificationPinEvaluation(
                    event = event,
                    matchedScope = RuleMatchScope.GLOBAL_RULE,
                    matchedRuleName = ruleLabel,
                    reason = "Matched global rule \"$ruleLabel\".",
                )
            }
        }

        if (settings.requireGlobalRegexRuleMatch) {
            val msg = if (enabledGlobalRules.isEmpty()) {
                "Requires a global regex rule match, but no global regex rules are enabled."
            } else {
                "Did not match any of ${enabledGlobalRules.size} enabled global regex rule(s)."
            }
            return ignored(msg)
        }

        val legacyRule = buildLegacyGlobalRule(settings)
        val event = buildEvent(
            candidate = candidate,
            existingPinId = existingPinId,
            settings = settings,
            appConfig = appConfig,
            rule = legacyRule,
            rawTitle = rawTitle,
            rawBody = rawBody,
            searchableText = searchableText,
        ) ?: return ignored("Notification produced an empty title.")

        val scope = if (appConfig != null && legacyRule == null) {
            RuleMatchScope.APP_DEFAULT
        } else {
            RuleMatchScope.GLOBAL_DEFAULT
        }
        val reason = if (scope == RuleMatchScope.APP_DEFAULT) {
            "Matched app defaults (global fallback)."
        } else {
            "Matched global defaults."
        }
        return NotificationPinEvaluation(
            event = event,
            matchedScope = scope,
            matchedRuleName = null,
            reason = reason,
        )
    }

    private fun buildLegacyGlobalRule(settings: NotificationRuleSettings): NotificationRegexRule? {
        if (
            settings.titleRegexPattern.isBlank() &&
            settings.descriptionRegexPattern.isBlank() &&
            settings.timeRegexPattern.isBlank()
        ) {
            return null
        }
        return NotificationRegexRule(
            id = "legacy-global",
            name = "Global Extraction",
            titleRegexPattern = settings.titleRegexPattern,
            titleSource = NotificationTextField.ANY,
            descriptionRegexPattern = settings.descriptionRegexPattern,
            descriptionSource = NotificationTextField.ANY,
            timeRegexPattern = settings.timeRegexPattern,
        )
    }

    private fun ruleMatches(
        rule: NotificationRegexRule,
        rawTitle: String,
        rawBody: String?,
        searchableText: String,
    ): Boolean {
        val matchPattern = rule.matchPattern.trim()
        if (matchPattern.isNotEmpty()) {
            val regex = compileRegexOrNull(matchPattern) ?: return false
            val target = textForField(rule.matchTarget, rawTitle, rawBody, searchableText)
            if (target.isEmpty() || !regex.containsMatchIn(target)) return false
        } else if (rule.action == NotificationRuleAction.IGNORE) {
            // Guardrail: never silently drop everything on a blank IGNORE match pattern.
            return false
        }

        val excludePattern = rule.excludePattern.trim()
        if (excludePattern.isNotEmpty()) {
            val excludeRegex = compileRegexOrNull(excludePattern)
            if (excludeRegex != null) {
                val target = textForField(rule.excludeTarget, rawTitle, rawBody, searchableText)
                if (target.isNotEmpty() && excludeRegex.containsMatchIn(target)) {
                    return false
                }
            }
        }
        return true
    }

    private fun buildEvent(
        candidate: NotificationPinCandidate,
        existingPinId: String?,
        settings: NotificationRuleSettings,
        appConfig: AppNotificationRuleConfig?,
        rule: NotificationRegexRule?,
        rawTitle: String,
        rawBody: String?,
        searchableText: String,
    ): TimelineEvent? {
        val titleSource = rule?.titleSource ?: NotificationTextField.ANY
        val titlePattern = rule?.titleRegexPattern.orEmpty()
        val descriptionSource = rule?.descriptionSource ?: NotificationTextField.ANY
        val descriptionPattern = rule?.descriptionRegexPattern.orEmpty()
        val timePattern = rule?.timeRegexPattern.orEmpty()

        val extractedTitle = extractTitle(
            source = titleSource,
            pattern = titlePattern,
            rawTitle = rawTitle,
            rawBody = rawBody,
            searchableText = searchableText,
        )

        val extractedDescription = extractDescription(
            source = descriptionSource,
            pattern = descriptionPattern,
            titleSource = titleSource,
            extractedTitle = extractedTitle,
            rawTitle = rawTitle,
            rawBody = rawBody,
            searchableText = searchableText,
        )

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
            timePattern.isNotBlank() -> NotificationTimeExtractor.extractEventTimeMillis(
                text = searchableText,
                referenceMillis = referenceMillis,
                zoneId = zoneIdProvider(),
                customTimeRegex = timePattern,
            )
            settings.extractTimeFromText -> NotificationTimeExtractor.extractEventTimeMillis(
                text = searchableText,
                referenceMillis = referenceMillis,
                zoneId = zoneIdProvider(),
            )
            else -> null
        }

        val resolvedIconUri = listOf(rule?.iconUri, appConfig?.defaultIconUri, settings.defaultIconUri)
            .firstNotNullOfOrNull { uri -> uri?.trim()?.takeIf { PinIcon.isValidSystemIconUri(it) } }
            ?: PinIcon.NOTIFICATION_FLAG.uri

        val resolvedBackgroundColor = listOf(
            rule?.backgroundColor,
            appConfig?.defaultBackgroundColor,
            settings.defaultBackgroundColor,
        ).firstNotNullOfOrNull { color ->
            color?.trim()?.takeIf { PinAccentColor.isValidHexColor(it) }
        }

        val resolvedDuration = listOf(
            rule?.durationMinutes,
            appConfig?.defaultDurationMinutes,
            settings.defaultDurationMinutes,
        ).firstNotNullOfOrNull { minutes ->
            minutes?.takeIf { it in TimelineEvent.MIN_DURATION_MINUTES..TimelineEvent.MAX_DURATION_MINUTES }
        }

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

    private fun extractTitle(
        source: NotificationTextField,
        pattern: String,
        rawTitle: String,
        rawBody: String?,
        searchableText: String,
    ): String {
        val bodyText = rawBody.orEmpty()
        val firstBodyLine = bodyText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val fallbackTitle = when (source) {
            NotificationTextField.BODY -> firstBodyLine.ifEmpty { rawTitle }
            NotificationTextField.TITLE, NotificationTextField.ANY -> rawTitle
        }

        if (pattern.isBlank()) return fallbackTitle

        val matched = when (source) {
            NotificationTextField.TITLE -> extractByRegex(pattern, primary = rawTitle, fallback = null)
            NotificationTextField.BODY -> extractByRegex(pattern, primary = bodyText, fallback = null)
            NotificationTextField.ANY -> extractByRegex(pattern, primary = rawTitle, fallback = searchableText)
        }
        return matched ?: fallbackTitle
    }

    private fun extractDescription(
        source: NotificationTextField,
        pattern: String,
        titleSource: NotificationTextField,
        extractedTitle: String,
        rawTitle: String,
        rawBody: String?,
        searchableText: String,
    ): String? {
        val bodyText = rawBody.orEmpty()
        if (pattern.isNotBlank()) {
            val matched = when (source) {
                NotificationTextField.TITLE -> extractByRegex(pattern, primary = rawTitle, fallback = null)
                NotificationTextField.BODY -> extractByRegex(pattern, primary = bodyText, fallback = null)
                NotificationTextField.ANY -> extractByRegex(pattern, primary = bodyText, fallback = searchableText)
            }
            if (matched != null) return matched
        }

        return when (source) {
            NotificationTextField.TITLE -> rawTitle.takeIf { it.isNotBlank() }
            NotificationTextField.BODY, NotificationTextField.ANY -> {
                if (titleSource == NotificationTextField.BODY && rawBody != null) {
                    stripLeadingTitleLine(rawBody, extractedTitle)
                } else {
                    rawBody
                }
            }
        }
    }

    private fun stripLeadingTitleLine(body: String, title: String): String? {
        val normalizedTitle = title.trim()
        val lines = body.lines()
        val firstNonBlankIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonBlankIdx == -1) return null
        if (lines[firstNonBlankIdx].trim() != normalizedTitle) return body
        val remaining = lines.drop(firstNonBlankIdx + 1)
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
        return remaining.takeIf { it.isNotEmpty() }
    }

    private fun textForField(
        field: NotificationTextField,
        rawTitle: String,
        rawBody: String?,
        searchableText: String,
    ): String = when (field) {
        NotificationTextField.TITLE -> rawTitle
        NotificationTextField.BODY -> rawBody.orEmpty()
        NotificationTextField.ANY -> searchableText
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
        fallback: String?,
    ): String? {
        val regex = compileRegexOrNull(pattern) ?: return null
        val match = (primary.takeIf { it.isNotEmpty() }?.let { regex.find(it) })
            ?: (fallback?.takeIf { it.isNotEmpty() }?.let { regex.find(it) })
            ?: return null
        val group1 = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return group1 ?: match.value.trim().takeIf { it.isNotEmpty() }
    }

    private fun compileRegexOrNull(pattern: String): Regex? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    private fun ignored(reason: String): NotificationPinEvaluation = NotificationPinEvaluation(
        event = null,
        matchedScope = RuleMatchScope.NONE,
        matchedRuleName = null,
        reason = reason,
    )

    companion object {
        fun truncateWithEllipsis(value: String, maxLength: Int): String {
            if (maxLength <= 0) return ""
            if (value.length <= maxLength) return value
            if (maxLength == 1) return "…"
            return value.take(maxLength - 1).trimEnd() + "…"
        }
    }
}
