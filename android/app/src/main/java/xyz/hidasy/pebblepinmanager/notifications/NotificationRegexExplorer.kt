package xyz.hidasy.pebblepinmanager.notifications

import java.time.ZoneId

/**
 * Single regex match occurrence inside a target string, including all capturing groups (1..N).
 */
data class RegexMatchSpan(
    val range: IntRange,
    val value: String,
    val capturingGroups: List<String?>,
) {
    val firstCapturingGroup: String?
        get() = capturingGroups.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Result of running a regex pattern against a specific [NotificationTextField] of a notification.
 */
data class RegexExplorationResult(
    val pattern: String,
    val targetField: NotificationTextField,
    val targetText: String,
    val ignoreCase: Boolean = true,
    val errorMessage: String? = null,
    val matches: List<RegexMatchSpan> = emptyList(),
    val extractedValue: String? = null,
) {
    val isBlankPattern: Boolean
        get() = pattern.trim().isEmpty()

    val isValid: Boolean
        get() = errorMessage == null

    val isMatched: Boolean
        get() = matches.isNotEmpty()

    val matchCount: Int
        get() = matches.size

    val firstMatch: RegexMatchSpan?
        get() = matches.firstOrNull()
}

/**
 * Complete evaluation of a draft [NotificationRegexRule] against a captured [RawNotificationRecord],
 * exposing per-field regex match details, parsed timestamp, draft pin preview, and current saved
 * rule evaluation side by side.
 */
data class DraftRuleExplorationResult(
    val textFields: NotificationTextFields,
    val matchResult: RegexExplorationResult,
    val excludeResult: RegexExplorationResult,
    val titleResult: RegexExplorationResult,
    val descriptionResult: RegexExplorationResult,
    val timeResult: RegexExplorationResult,
    val parsedEventTimeMillis: Long?,
    val draftEvaluation: NotificationPinEvaluation,
    val currentSettingsEvaluation: NotificationPinEvaluation,
    val pipelineWarnings: List<String>,
)

/**
 * Pure-Kotlin regex inspection engine backing Dev Mode's interactive regex explorer.
 *
 * Uses the exact same text normalization ([NotificationTextFields]), regex options
 * (`RegexOption.IGNORE_CASE`), capture-group-1 extraction semantics, time parsing
 * ([NotificationTimeExtractor]), and mapping ([RuleBasedNotificationPinMapper]) as the live
 * notification listener pipeline.
 */
object NotificationRegexExplorer {
    fun evaluatePattern(
        pattern: String,
        targetField: NotificationTextField,
        textFields: NotificationTextFields,
        ignoreCase: Boolean = true,
    ): RegexExplorationResult {
        val targetText = textFields.textForField(targetField)
        return evaluateOnText(
            pattern = pattern,
            targetField = targetField,
            targetText = targetText,
            ignoreCase = ignoreCase,
        )
    }

    /**
     * Evaluates a title/description extraction regex matching [RuleBasedNotificationPinMapper]'s
     * fallback order when [source] is [NotificationTextField.ANY] (primary = title or body,
     * fallback = combined searchable text).
     */
    fun evaluateExtractionPattern(
        pattern: String,
        source: NotificationTextField,
        textFields: NotificationTextFields,
        forTitle: Boolean,
        ignoreCase: Boolean = true,
    ): RegexExplorationResult {
        val targetText = when (source) {
            NotificationTextField.TITLE -> textFields.rawTitle
            NotificationTextField.BODY -> textFields.rawBody.orEmpty()
            NotificationTextField.ANY -> {
                val primary = if (forTitle) textFields.rawTitle else textFields.rawBody.orEmpty()
                val primaryResult = evaluateOnText(
                    pattern = pattern,
                    targetField = source,
                    targetText = primary,
                    ignoreCase = ignoreCase,
                )
                if (primaryResult.isMatched || !primaryResult.isValid || primaryResult.isBlankPattern) {
                    return primaryResult
                }
                textFields.searchableText
            }
        }
        return evaluateOnText(
            pattern = pattern,
            targetField = source,
            targetText = targetText,
            ignoreCase = ignoreCase,
        )
    }

    fun evaluateOnText(
        pattern: String,
        targetField: NotificationTextField,
        targetText: String,
        ignoreCase: Boolean = true,
    ): RegexExplorationResult {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return RegexExplorationResult(
                pattern = pattern,
                targetField = targetField,
                targetText = targetText,
                ignoreCase = ignoreCase,
            )
        }

        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = runCatching { Regex(trimmed, options) }.getOrElse { err ->
            return RegexExplorationResult(
                pattern = pattern,
                targetField = targetField,
                targetText = targetText,
                ignoreCase = ignoreCase,
                errorMessage = "Invalid regex: ${err.message?.lineSequence()?.firstOrNull() ?: "syntax error"}",
            )
        }

        if (targetText.isEmpty()) {
            return RegexExplorationResult(
                pattern = pattern,
                targetField = targetField,
                targetText = targetText,
                ignoreCase = ignoreCase,
            )
        }

        val spans = regex.findAll(targetText)
            .take(MAX_MATCHES_TO_COLLECT)
            .map { result ->
                val groups = if (result.groups.size > 1) {
                    (1 until result.groups.size).map { idx -> result.groups[idx]?.value }
                } else {
                    emptyList()
                }
                RegexMatchSpan(
                    range = result.range,
                    value = result.value,
                    capturingGroups = groups,
                )
            }
            .toList()

        val first = spans.firstOrNull()
        val extracted = first?.let { match ->
            match.firstCapturingGroup ?: match.value.trim().takeIf { it.isNotEmpty() }
        }

        return RegexExplorationResult(
            pattern = pattern,
            targetField = targetField,
            targetText = targetText,
            ignoreCase = ignoreCase,
            matches = spans,
            extractedValue = extracted,
        )
    }

    suspend fun evaluateDraftRule(
        record: RawNotificationRecord,
        draftRule: NotificationRegexRule,
        currentSettings: NotificationRuleSettings,
        zoneId: ZoneId = ZoneId.systemDefault(),
        clockMillis: Long = System.currentTimeMillis(),
    ): DraftRuleExplorationResult {
        val candidate = record.toCandidate()
        val textFields = NotificationTextFields.fromCandidate(candidate)
        val normalizedRule = draftRule.normalized().copy(enabled = true)

        val matchResult = evaluatePattern(
            pattern = normalizedRule.matchPattern,
            targetField = normalizedRule.matchTarget,
            textFields = textFields,
            ignoreCase = true,
        )
        val excludeResult = evaluatePattern(
            pattern = normalizedRule.excludePattern,
            targetField = normalizedRule.excludeTarget,
            textFields = textFields,
            ignoreCase = true,
        )
        val titleResult = evaluateExtractionPattern(
            pattern = normalizedRule.titleRegexPattern,
            source = normalizedRule.titleSource,
            textFields = textFields,
            forTitle = true,
            ignoreCase = true,
        )
        val descriptionResult = evaluateExtractionPattern(
            pattern = normalizedRule.descriptionRegexPattern,
            source = normalizedRule.descriptionSource,
            textFields = textFields,
            forTitle = false,
            ignoreCase = true,
        )
        val timeResult = evaluatePattern(
            pattern = normalizedRule.timeRegexPattern,
            targetField = NotificationTextField.ANY,
            textFields = textFields,
            ignoreCase = true,
        )

        val referenceMillis = candidate.eventTimeMillis.takeIf { it > 0L } ?: clockMillis
        val parsedTimeMillis = when {
            normalizedRule.timeRegexPattern.isNotBlank() -> NotificationTimeExtractor.extractEventTimeMillis(
                text = textFields.searchableText,
                referenceMillis = referenceMillis,
                zoneId = zoneId,
                customTimeRegex = normalizedRule.timeRegexPattern,
            )
            currentSettings.extractTimeFromText -> NotificationTimeExtractor.extractEventTimeMillis(
                text = textFields.searchableText,
                referenceMillis = referenceMillis,
                zoneId = zoneId,
            )
            else -> null
        }

        // Evaluate the draft rule in isolation so users can test regexes on any raw notification
        // (even before allowlisting the app or when inspecting ongoing/summary notifications).
        val existingAppConfig = currentSettings.appRuleFor(candidate.sourcePackage)
        val draftSettings = currentSettings.copy(
            isAutoPinEnabled = true,
            allowedPackages = currentSettings.allowedPackages + candidate.sourcePackage,
            ignoreOngoing = false,
            ignoreGroupSummary = false,
            appRules = currentSettings.appRules + (
                candidate.sourcePackage to AppNotificationRuleConfig(
                    packageName = candidate.sourcePackage,
                    enabled = true,
                    regexRules = listOf(normalizedRule),
                    requireRegexRuleMatch = true,
                    fallbackToGlobalRules = false,
                    defaultIconUri = existingAppConfig?.defaultIconUri,
                    defaultBackgroundColor = existingAppConfig?.defaultBackgroundColor,
                    defaultDurationMinutes = existingAppConfig?.defaultDurationMinutes,
                )
            ),
        )
        val draftMapper = RuleBasedNotificationPinMapper(
            settings = draftSettings,
            clockMillis = { clockMillis },
            zoneIdProvider = { zoneId },
            pinIdGenerator = { "ppm-dev-preview" },
        )
        val draftEvaluation = draftMapper.evaluate(candidate)

        val currentMapper = RuleBasedNotificationPinMapper(
            settings = currentSettings,
            clockMillis = { clockMillis },
            zoneIdProvider = { zoneId },
            pinIdGenerator = { "ppm-current-preview" },
        )
        val currentEvaluation = currentMapper.evaluate(candidate)

        val warnings = buildList {
            if (!currentSettings.isAutoPinEnabled) {
                add("Auto-pinning is currently turned off in Auto-Pins settings.")
            }
            if (!currentSettings.isPackageAllowed(candidate.sourcePackage)) {
                add("${candidate.sourcePackage} is not allowlisted yet (saving as an App Rule will allowlist it automatically).")
            }
            if (currentSettings.ignoreOngoing && candidate.isOngoing) {
                add("This notification is ongoing and 'Ignore ongoing notifications' is enabled.")
            }
            if (currentSettings.ignoreGroupSummary && candidate.isGroupSummary) {
                add("This notification is a group summary and 'Ignore group summary notifications' is enabled.")
            }
        }

        return DraftRuleExplorationResult(
            textFields = textFields,
            matchResult = matchResult,
            excludeResult = excludeResult,
            titleResult = titleResult,
            descriptionResult = descriptionResult,
            timeResult = timeResult,
            parsedEventTimeMillis = parsedTimeMillis,
            draftEvaluation = draftEvaluation,
            currentSettingsEvaluation = currentEvaluation,
            pipelineWarnings = warnings,
        )
    }

    private const val MAX_MATCHES_TO_COLLECT = 50
}
