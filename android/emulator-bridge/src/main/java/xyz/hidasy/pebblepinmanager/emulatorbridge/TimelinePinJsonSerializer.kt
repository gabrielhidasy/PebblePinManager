package xyz.hidasy.pebblepinmanager.emulatorbridge

import io.rebble.pebblekit2.common.model.TimelinePin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

@Serializable
data class BridgePinPayload(
    val id: String,
    val watchappUuid: String,
    val callingPackage: String? = null,
    val time: String,
    val duration: Int? = null,
    val layout: BridgePinLayoutPayload,
)

@Serializable
data class BridgePinLayoutPayload(
    val type: String,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val tinyIcon: String? = null,
    val smallIcon: String? = null,
    val largeIcon: String? = null,
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val backgroundColor: String? = null,
)

/**
 * Pure Kotlin authorization & serialization helper for the PebbleKit2 emulator bridge.
 */
object TimelinePinJsonSerializer {
    val AUTHORIZED_WATCHAPP_UUID: UUID = UUID.fromString("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042")

    val AUTHORIZED_PACKAGES: Set<String> = setOf(
        "xyz.hidasy.pebblepinmanager",
        "xyz.hidasy.pebblepinmanager.debug",
    )

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun isAuthorized(callingPackage: String?, watchappUuid: UUID): Boolean {
        if (watchappUuid != AUTHORIZED_WATCHAPP_UUID) {
            return false
        }
        if (callingPackage != null && callingPackage !in AUTHORIZED_PACKAGES) {
            return false
        }
        return true
    }

    fun toPayload(
        callingPackage: String?,
        watchappUuid: UUID,
        pin: TimelinePin,
    ): BridgePinPayload {
        val isoUtc = Instant.ofEpochMilli(pin.startTime.toEpochMilliseconds()).toString()
        val durationMinutes = pin.duration?.inWholeMinutes?.toInt()?.takeIf { it > 0 }
        return BridgePinPayload(
            id = pin.id,
            watchappUuid = watchappUuid.toString(),
            callingPackage = callingPackage,
            time = isoUtc,
            duration = durationMinutes,
            layout = BridgePinLayoutPayload(
                type = pin.layout.type.code,
                title = pin.layout.title,
                subtitle = pin.layout.subtitle,
                body = pin.layout.body,
                tinyIcon = pin.layout.tinyIcon,
                smallIcon = pin.layout.smallIcon,
                largeIcon = pin.layout.largeIcon,
                primaryColor = pin.layout.primaryColor,
                secondaryColor = pin.layout.secondaryColor,
                backgroundColor = pin.layout.backgroundColor,
            ),
        )
    }

    fun serialize(
        callingPackage: String?,
        watchappUuid: UUID,
        pin: TimelinePin,
    ): String {
        val payload = toPayload(callingPackage, watchappUuid, pin)
        return json.encodeToString(BridgePinPayload.serializer(), payload)
    }
}
