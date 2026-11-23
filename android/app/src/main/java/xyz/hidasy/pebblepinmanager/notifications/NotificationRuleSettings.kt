package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.serialization.Serializable
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import java.util.UUID

/**
 * Selects which part of an Android notification a regex filter or field extractor targets.
 */
@Serializable
enum class NotificationTextField(val label: String) {
    /** Targets title & body combined (e.g., for matching or title-first extraction). */
    ANY("Title & Body"),

    /** Targets only the notification title (e.g., sender name in Gmail). */
    TITLE("Title"),

    /** Targets only the notification body (bigText / text / subText, e.g., subject & snippet in Gmail). */
    BODY("Body"),
}

/**
 * Action taken when a [NotificationRegexRule] matches an incoming notification.
 */
@Serializable
enum class NotificationRuleAction(val label: String) {
    /** Convert the matching notification into a Pebble Timeline pin using this rule's extraction settings. */
    CREATE_PIN("Create Pin"),

    /** Stop evaluating further rules and ignore the matching notification. */
    IGNORE("Ignore"),
}

/**
 * Ordered regex filter and field-extraction rule that can be attached either to a specific app
 * ([AppNotificationRuleConfig.regexRules]) or globally ([NotificationRuleSettings.globalRegexRules]).
 */
@Serializable
data class NotificationRegexRule(
    val id: String = generateRuleId(),
    val name: String = "",
    val enabled: Boolean = true,
    val action: NotificationRuleAction = NotificationRuleAction.CREATE_PIN,
    /**
     * Optional gating regex. When non-blank, this rule only matches if [matchPattern] matches the
     * text selected by [matchTarget]. Required when [action] is [NotificationRuleAction.IGNORE].
     */
    val matchPattern: String = "",
    val matchTarget: NotificationTextField = NotificationTextField.ANY,
    /**
     * Optional negative regex. When non-blank and it matches the text selected by [excludeTarget],
     * this rule does not match.
     */
    val excludePattern: String = "",
    val excludeTarget: NotificationTextField = NotificationTextField.ANY,
    /**
     * Optional extraction regex for the Pin title (uses capturing group 1 or full match).
     * Applied to [titleSource]. When [titleSource] is [NotificationTextField.BODY] and this
     * pattern is blank, the first non-blank line of the notification body is promoted to Pin title
     * (ideal for apps like Gmail where Android sets the sender as notification title).
     */
    val titleRegexPattern: String = "",
    val titleSource: NotificationTextField = NotificationTextField.ANY,
    /**
     * Optional extraction regex for the Pin description (uses capturing group 1 or full match).
     * Applied to [descriptionSource].
     */
    val descriptionRegexPattern: String = "",
    val descriptionSource: NotificationTextField = NotificationTextField.BODY,
    /**
     * Optional extraction regex to isolate a timestamp substring before parsing with
     * [NotificationTimeExtractor].
     */
    val timeRegexPattern: String = "",
    val iconUri: String? = null,
    val backgroundColor: String? = null,
    val durationMinutes: Int? = null,
) {
    fun displayName(fallbackIndex: Int? = null): String {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) return trimmed
        if (matchPattern.isNotBlank()) return matchPattern.trim()
        return if (fallbackIndex != null) "Rule ${fallbackIndex + 1}" else "Untitled Rule"
    }

    fun normalized(): NotificationRegexRule = copy(
        id = id.trim().ifEmpty { generateRuleId() },
        name = name.trim(),
        matchPattern = matchPattern.trim(),
        excludePattern = excludePattern.trim(),
        titleRegexPattern = titleRegexPattern.trim(),
        descriptionRegexPattern = descriptionRegexPattern.trim(),
        timeRegexPattern = timeRegexPattern.trim(),
        iconUri = iconUri?.trim()?.takeIf { it.isNotEmpty() },
        backgroundColor = backgroundColor?.trim()?.takeIf { it.isNotEmpty() },
    )

    companion object {
        fun generateRuleId(): String = "rule-${UUID.randomUUID().toString().take(8)}"
    }
}

/**
 * Optional per-app auto-pin filtering, N ordered regex rules, and pin appearance overrides.
 */
@Serializable
data class AppNotificationRuleConfig(
    val packageName: String,
    val enabled: Boolean = true,
    val keywordFilter: String = "",
    val excludeKeywordFilter: String = "",
    val regexRules: List<NotificationRegexRule> = emptyList(),
    /**
     * When `true`, notifications from this app are ignored unless one of its [regexRules] matches
     * (ideal for high-volume apps like Gmail where only specific emails should create timeline pins).
     */
    val requireRegexRuleMatch: Boolean = false,
    /**
     * When `true` and [requireRegexRuleMatch] is `false`, notifications that do not match an app
     * regex rule fall back to global keyword/regex rules.
     */
    val fallbackToGlobalRules: Boolean = true,
    val defaultIconUri: String? = null,
    val defaultBackgroundColor: String? = null,
    val defaultDurationMinutes: Int? = null,
) {
    fun parsedKeywords(): List<String> = NotificationRuleSettings.parseKeywordList(keywordFilter)

    fun parsedExcludeKeywords(): List<String> = NotificationRuleSettings.parseKeywordList(excludeKeywordFilter)

    fun hasCustomizations(): Boolean =
        keywordFilter.isNotBlank() ||
            excludeKeywordFilter.isNotBlank() ||
            regexRules.isNotEmpty() ||
            requireRegexRuleMatch ||
            !fallbackToGlobalRules ||
            !defaultIconUri.isNullOrBlank() ||
            !defaultBackgroundColor.isNullOrBlank() ||
            defaultDurationMinutes != null

    fun normalized(): AppNotificationRuleConfig = copy(
        packageName = packageName.trim(),
        keywordFilter = keywordFilter.trim(),
        excludeKeywordFilter = excludeKeywordFilter.trim(),
        regexRules = regexRules.map { it.normalized() },
        defaultIconUri = defaultIconUri?.trim()?.takeIf { it.isNotEmpty() },
        defaultBackgroundColor = defaultBackgroundColor?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * User-configurable rules controlling which Android notifications are automatically converted
 * into Pebble Timeline pins.
 *
 * Strictly opt-in: [allowedPackages] is empty by default so notifications never flood the watch
 * timeline until the user explicitly selects source apps.
 *
 * Supports both quick setup (allowlist apps + global defaults/rules) and optional per-app rule
 * configurations ([appRules]) with N ordered regex rules per app and globally ([globalRegexRules]).
 */
@Serializable
data class NotificationRuleSettings(
    val isAutoPinEnabled: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
    val keywordFilter: String = "",
    val excludeKeywordFilter: String = "",
    val titleRegexPattern: String = "",
    val descriptionRegexPattern: String = "",
    val timeRegexPattern: String = "",
    val globalRegexRules: List<NotificationRegexRule> = emptyList(),
    val requireGlobalRegexRuleMatch: Boolean = false,
    val appRules: Map<String, AppNotificationRuleConfig> = emptyMap(),
    val extractTimeFromText: Boolean = true,
    val ignoreOngoing: Boolean = true,
    val ignoreGroupSummary: Boolean = true,
    val deletePinOnDismiss: Boolean = false,
    val isDevModeEnabled: Boolean = false,
    val defaultIconUri: String = PinIcon.NOTIFICATION_FLAG.uri,
    val defaultBackgroundColor: String? = null,
    val defaultDurationMinutes: Int? = null,
) {
    fun parsedKeywords(): List<String> = parseKeywordList(keywordFilter)

    fun parsedExcludeKeywords(): List<String> = parseKeywordList(excludeKeywordFilter)

    fun isPackageAllowed(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return trimmed.isNotEmpty() && trimmed in allowedPackages
    }

    fun appRuleFor(packageName: String): AppNotificationRuleConfig? {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return null
        return appRules[trimmed]
    }

    fun configuredAppRuleCount(): Int =
        appRules.values.count { it.enabled && it.hasCustomizations() }

    companion object {
        fun parseKeywordList(raw: String): List<String> = raw
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }
}

/**
 * Handy starter presets for common tricky notification formats (e.g., Gmail sender-as-title).
 */
object NotificationRulePresets {
    fun subjectAsTitleRule(
        name: String = "Subject as Pin Title",
        matchPattern: String = "",
        matchTarget: NotificationTextField = NotificationTextField.ANY,
    ): NotificationRegexRule = NotificationRegexRule(
        name = name,
        action = NotificationRuleAction.CREATE_PIN,
        matchPattern = matchPattern,
        matchTarget = matchTarget,
        titleSource = NotificationTextField.BODY,
        descriptionSource = NotificationTextField.BODY,
    )
}

data class NotificationRegexRuleValidation(
    val matchPatternError: String? = null,
    val excludePatternError: String? = null,
    val titleRegexError: String? = null,
    val descriptionRegexError: String? = null,
    val timeRegexError: String? = null,
) {
    val isValid: Boolean
        get() = matchPatternError == null &&
            excludePatternError == null &&
            titleRegexError == null &&
            descriptionRegexError == null &&
            timeRegexError == null
}

/**
 * Pure-Kotlin validators for notification filter rules and package identifiers.
 */
object NotificationRuleValidator {
    private val PACKAGE_NAME_REGEX: Regex =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun validateRegex(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            Regex(trimmed, RegexOption.IGNORE_CASE)
            null
        }.getOrElse { err ->
            "Invalid regex: ${err.message?.lineSequence()?.firstOrNull() ?: "syntax error"}"
        }
    }

    fun validateRule(rule: NotificationRegexRule): NotificationRegexRuleValidation {
        val matchError = when {
            rule.action == NotificationRuleAction.IGNORE && rule.matchPattern.trim().isEmpty() ->
                "Enter a match regex for Ignore rules so they don't drop every notification."
            else -> validateRegex(rule.matchPattern)
        }
        return NotificationRegexRuleValidation(
            matchPatternError = matchError,
            excludePatternError = validateRegex(rule.excludePattern),
            titleRegexError = validateRegex(rule.titleRegexPattern),
            descriptionRegexError = validateRegex(rule.descriptionRegexPattern),
            timeRegexError = validateRegex(rule.timeRegexPattern),
        )
    }

    fun validatePackageName(packageName: String): String? {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) {
            return "Enter an Android package name (e.g. com.example.app)."
        }
        if (!PACKAGE_NAME_REGEX.matches(trimmed)) {
            return "Invalid package name format (expected e.g. com.example.app)."
        }
        return null
    }
}
