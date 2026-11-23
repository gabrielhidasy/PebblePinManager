package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.serialization.Serializable
import xyz.hidasy.pebblepinmanager.domain.PinIcon

/**
 * User-configurable rules controlling which Android notifications are automatically converted
 * into Pebble Timeline pins.
 *
 * Strictly opt-in: [allowedPackages] is empty by default so notifications never flood the watch
 * timeline until the user explicitly selects source apps.
 */
@Serializable
data class NotificationRuleSettings(
    val isAutoPinEnabled: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
    val keywordFilter: String = "",
    val titleRegexPattern: String = "",
    val descriptionRegexPattern: String = "",
    val timeRegexPattern: String = "",
    val extractTimeFromText: Boolean = true,
    val ignoreOngoing: Boolean = true,
    val ignoreGroupSummary: Boolean = true,
    val deletePinOnDismiss: Boolean = false,
    val defaultIconUri: String = PinIcon.NOTIFICATION_FLAG.uri,
    val defaultBackgroundColor: String? = null,
    val defaultDurationMinutes: Int? = null,
) {
    fun parsedKeywords(): List<String> = keywordFilter
        .split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    fun isPackageAllowed(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return trimmed.isNotEmpty() && trimmed in allowedPackages
    }
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
