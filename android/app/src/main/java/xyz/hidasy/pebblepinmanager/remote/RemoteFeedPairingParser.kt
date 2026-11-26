package xyz.hidasy.pebblepinmanager.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Validated configuration decoded from a companion server `/register` QR code or `pebblepin://pair` URI.
 *
 * Raw secrets ([secret]) must never be logged or included in UI error messages; they are stored
 * exclusively in [RemoteFeedSecretStore].
 */
data class RemoteFeedPairingPayload(
    val url: String,
    val name: String,
    val authType: RemoteFeedAuthType,
    val apiKeyHeaderName: String = RemotePinFeedConfig.DEFAULT_API_KEY_HEADER,
    val basicUsername: String = "",
    val secret: RemoteFeedSecret? = null,
    val isCleartextHttp: Boolean = false,
    val pollingIntervalMinutes: Int = RemotePinFeedConfig.DEFAULT_POLLING_INTERVAL_MINUTES,
) {
    val displayName: String
        get() = name.trim().ifEmpty { RemoteFeedUrlValidator.deriveDefaultFeedName(url) }
}

sealed interface RemoteFeedPairingParseResult {
    data class Valid(val payload: RemoteFeedPairingPayload) : RemoteFeedPairingParseResult

    data class Invalid(val message: String) : RemoteFeedPairingParseResult
}

/**
 * Parses and validates QR pairing payloads produced by the PebblePinManager companion server's
 * `/register` page (`pebblepin://pair?v=1&...`) as well as JSON pairing objects.
 */
object RemoteFeedPairingParser {
    const val SUPPORTED_VERSION: Int = 1
    const val PAIRING_SCHEME: String = "pebblepin"
    const val PAIRING_HOST: String = "pair"
    const val JSON_PAIRING_TYPE: String = "pebble-pin-server-pairing"
    private const val MAX_NAME_LENGTH: Int = 80

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parse(rawInput: String): RemoteFeedPairingParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return RemoteFeedPairingParseResult.Invalid("QR code is empty.")
        }

        return when {
            trimmed.startsWith("{") -> parseJsonPayload(trimmed)
            trimmed.startsWith("$PAIRING_SCHEME://", ignoreCase = true) -> parseUriPayload(trimmed)
            else -> RemoteFeedPairingParseResult.Invalid(
                "Not a Pin Manager for Pebble pairing QR code (expected a pebblepin://pair URI).",
            )
        }
    }

    private fun parseUriPayload(rawUri: String): RemoteFeedPairingParseResult {
        val uri = runCatching { URI(rawUri) }.getOrNull()
            ?: return RemoteFeedPairingParseResult.Invalid("Malformed pairing URI.")

        if (!PAIRING_SCHEME.equals(uri.scheme, ignoreCase = true)) {
            return RemoteFeedPairingParseResult.Invalid("Unsupported pairing URI scheme.")
        }
        if (!PAIRING_HOST.equals(uri.host, ignoreCase = true)) {
            return RemoteFeedPairingParseResult.Invalid("Unsupported pairing URI action.")
        }

        val params = parseQueryParams(uri.rawQuery)
        val versionRaw = params["v"]?.trim()
        val version = versionRaw?.toIntOrNull()
        if (version != SUPPORTED_VERSION) {
            return RemoteFeedPairingParseResult.Invalid(
                if (versionRaw.isNullOrEmpty()) {
                    "Pairing QR code is missing version parameter (v=1)."
                } else {
                    "Unsupported pairing QR version '$versionRaw'. Update Pin Manager for Pebble and try again."
                },
            )
        }

        return validateAndBuild(
            rawUrl = params["url"].orEmpty(),
            rawName = params["name"].orEmpty(),
            rawAuth = params["auth"],
            rawToken = params["token"].orEmpty(),
            rawHeader = params["header"].orEmpty(),
            rawUsername = params["user"].orEmpty(),
            rawPassword = params["pass"].orEmpty(),
            rawInterval = params["interval"]?.trim()?.toIntOrNull(),
        )
    }

    private fun parseJsonPayload(rawJson: String): RemoteFeedPairingParseResult {
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return RemoteFeedPairingParseResult.Invalid("Malformed JSON pairing QR code.")

        val type = root.stringField("type")
        if (type != null && !type.equals(JSON_PAIRING_TYPE, ignoreCase = true)) {
            return RemoteFeedPairingParseResult.Invalid("Unrecognized QR code type '$type'.")
        }

        val version = root.intField("schemaVersion") ?: root.intField("v")
        if (version != SUPPORTED_VERSION) {
            return RemoteFeedPairingParseResult.Invalid(
                "Unsupported pairing payload version (expected version $SUPPORTED_VERSION).",
            )
        }

        val rawInterval = root.intField("pollingIntervalMinutes") ?: root.intField("interval")

        return validateAndBuild(
            rawUrl = root.stringField("url").orEmpty(),
            rawName = root.stringField("name").orEmpty(),
            rawAuth = root.stringField("authType") ?: root.stringField("auth"),
            rawToken = root.stringField("token").orEmpty(),
            rawHeader = root.stringField("apiKeyHeaderName") ?: root.stringField("header").orEmpty(),
            rawUsername = root.stringField("basicUsername") ?: root.stringField("user").orEmpty(),
            rawPassword = root.stringField("basicPassword") ?: root.stringField("pass").orEmpty(),
            rawInterval = rawInterval,
        )
    }

    private fun validateAndBuild(
        rawUrl: String,
        rawName: String,
        rawAuth: String?,
        rawToken: String,
        rawHeader: String,
        rawUsername: String,
        rawPassword: String,
        rawInterval: Int?,
    ): RemoteFeedPairingParseResult {
        val urlValidation = RemoteFeedUrlValidator.validate(
            rawUrl = rawUrl,
            allowCleartextHttp = true,
        )
        val validUrl = when (urlValidation) {
            is UrlValidationResult.Valid -> urlValidation
            is UrlValidationResult.Invalid -> {
                return RemoteFeedPairingParseResult.Invalid("Invalid server URL in QR code: ${urlValidation.message}")
            }
        }
        val isHttp = validUrl.uri.scheme.equals("http", ignoreCase = true)

        val token = rawToken.trim()
        val authType = resolveAuthType(rawAuth, hasToken = token.isNotEmpty())
            ?: return RemoteFeedPairingParseResult.Invalid(
                "Unsupported authentication type '${rawAuth?.trim()}' in pairing QR code.",
            )

        val headerName = rawHeader.trim().ifEmpty { RemotePinFeedConfig.DEFAULT_API_KEY_HEADER }
        if (authType == RemoteFeedAuthType.API_KEY_HEADER) {
            val headerErr = RemoteFeedUrlValidator.validateHeaderName(headerName)
            if (headerErr != null) {
                return RemoteFeedPairingParseResult.Invalid("Invalid API key header in QR code: $headerErr")
            }
        }

        val username = rawUsername.trim()
        val secret: RemoteFeedSecret? = when (authType) {
            RemoteFeedAuthType.NONE -> null
            RemoteFeedAuthType.BEARER_TOKEN -> {
                if (token.isEmpty()) {
                    return RemoteFeedPairingParseResult.Invalid("Pairing QR code is missing the Bearer token.")
                }
                RemoteFeedSecret(token = token)
            }
            RemoteFeedAuthType.API_KEY_HEADER -> {
                if (token.isEmpty()) {
                    return RemoteFeedPairingParseResult.Invalid("Pairing QR code is missing the API key.")
                }
                RemoteFeedSecret(token = token)
            }
            RemoteFeedAuthType.BASIC -> {
                if (username.isEmpty() || username.contains(':')) {
                    return RemoteFeedPairingParseResult.Invalid("Pairing QR code has an invalid HTTP Basic username.")
                }
                if (rawPassword.isEmpty()) {
                    return RemoteFeedPairingParseResult.Invalid("Pairing QR code is missing the HTTP Basic password.")
                }
                RemoteFeedSecret(basicPassword = rawPassword)
            }
        }

        val normalizedName = rawName.trim().take(MAX_NAME_LENGTH)
        val interval = if (rawInterval != null) {
            RemotePinFeedConfig.normalizePollingInterval(rawInterval)
        } else {
            RemotePinFeedConfig.DEFAULT_POLLING_INTERVAL_MINUTES
        }

        return RemoteFeedPairingParseResult.Valid(
            RemoteFeedPairingPayload(
                url = validUrl.normalizedUrl,
                name = normalizedName,
                authType = authType,
                apiKeyHeaderName = headerName,
                basicUsername = username,
                secret = secret,
                isCleartextHttp = isHttp,
                pollingIntervalMinutes = interval,
            ),
        )
    }

    private fun resolveAuthType(rawAuth: String?, hasToken: Boolean): RemoteFeedAuthType? {
        val normalized = rawAuth?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) {
            return if (hasToken) RemoteFeedAuthType.BEARER_TOKEN else RemoteFeedAuthType.NONE
        }
        return when (normalized) {
            "bearer", "bearer_token" -> RemoteFeedAuthType.BEARER_TOKEN
            "apikey", "api_key", "api_key_header" -> RemoteFeedAuthType.API_KEY_HEADER
            "basic", "http_basic" -> RemoteFeedAuthType.BASIC
            "none", "no_auth" -> RemoteFeedAuthType.NONE
            else -> null
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
            val key = decodeQueryComponent(rawKey)
            val value = decodeQueryComponent(rawVal)
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun decodeQueryComponent(raw: String): String =
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)

    private fun JsonObject.stringField(name: String): String? {
        val element: JsonElement = this[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun JsonObject.intField(name: String): Int? {
        val element: JsonElement = this[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.content.trim().toIntOrNull()
    }

    @Suppress("unused")
    private fun JsonObject.boolField(name: String): Boolean? {
        val element: JsonElement = this[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.booleanOrNull
    }
}
