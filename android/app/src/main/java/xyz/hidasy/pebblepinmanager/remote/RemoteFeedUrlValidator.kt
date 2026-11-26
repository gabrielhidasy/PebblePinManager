package xyz.hidasy.pebblepinmanager.remote

import java.net.URI

/**
 * Validates remote JSON pin feed URLs and enforces HTTPS by default.
 */
object RemoteFeedUrlValidator {
    private val VALID_HEADER_NAME_REGEX = Regex("^[A-Za-z0-9-]+$")

    fun validate(
        rawUrl: String,
        allowCleartextHttp: Boolean = false,
    ): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid("Remote JSON URL is required.")
        }
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlValidationResult.Invalid("URL must not contain spaces or control characters.")
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlValidationResult.Invalid("Enter a valid URL (for example, https://example.com/pins.json).")

        val scheme = uri.scheme?.lowercase()
        when (scheme) {
            "https" -> Unit
            "http" -> {
                if (!allowCleartextHttp) {
                    return UrlValidationResult.Invalid(
                        "HTTPS is required by default. Enable \"Allow HTTP (LAN testing)\" only for trusted local networks.",
                    )
                }
            }
            null -> {
                return UrlValidationResult.Invalid(
                    "URL must start with https://${if (allowCleartextHttp) " or http://" else ""}.",
                )
            }
            else -> {
                return UrlValidationResult.Invalid(
                    "Unsupported URL scheme '$scheme'. Only https:// URLs${if (allowCleartextHttp) " (or opt-in http://)" else ""} are allowed.",
                )
            }
        }

        val host = uri.host?.trim()
        if (host.isNullOrEmpty()) {
            return UrlValidationResult.Invalid("URL must include a valid host name or IP address.")
        }

        if (!uri.userInfo.isNullOrEmpty()) {
            return UrlValidationResult.Invalid(
                "Do not embed credentials in the URL. Configure authentication separately so secrets are stored securely.",
            )
        }

        if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) {
            return UrlValidationResult.Invalid("URL port ${uri.port} is out of range (1–65535).")
        }

        return UrlValidationResult.Valid(normalizedUrl = trimmed, uri = uri)
    }

    fun validateHeaderName(headerName: String): String? {
        val trimmed = headerName.trim()
        if (trimmed.isEmpty()) {
            return "Header name is required (for example, X-API-Key)."
        }
        if (!VALID_HEADER_NAME_REGEX.matches(trimmed)) {
            return "Header name may only contain letters, digits, and hyphens."
        }
        return null
    }

    fun deriveDefaultFeedName(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: return "Remote Feed"
        val lastPathSegment = uri.path
            ?.split('/')
            ?.lastOrNull { it.isNotBlank() }
            ?.takeIf { it.length <= 24 }
        return if (lastPathSegment != null) "$host/$lastPathSegment" else host
    }
}

sealed interface UrlValidationResult {
    data class Valid(
        val normalizedUrl: String,
        val uri: URI,
    ) : UrlValidationResult

    data class Invalid(val message: String) : UrlValidationResult
}
