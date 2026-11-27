package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.model.ConnectedWatch
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pure mapper between PebblePinManager domain types and PebbleKit Android 2 SDK models.
 */
object TimelinePinMapper {
    fun toPebbleKitPin(event: TimelineEvent): TimelinePin {
        val normalized = event.normalized()
        return TimelinePin(
            id = normalized.id,
            startTime = Instant.fromEpochMilliseconds(normalized.startTimeMillis),
            duration = normalized.durationMinutes?.minutes,
            layout = TimelineLayout(
                type = normalized.layoutType.toPebbleKitLayoutType(),
                title = normalized.title,
                body = normalized.description,
                tinyIcon = normalized.iconUri,
                primaryColor = normalized.primaryColor,
                secondaryColor = normalized.secondaryColor,
                backgroundColor = normalized.backgroundColor,
            ),
        )
    }

    fun toPinSendResult(
        pinId: String,
        watchappUuid: UUID,
        result: TimelineResult,
    ): PinSendResult = when (result) {
        TimelineResult.Success -> PinSendResult.Success(pinId)
        TimelineResult.FailedNoPebbleApp -> PinSendResult.NoPebbleApp
        TimelineResult.FailedNoPermissions -> PinSendResult.NoPermissions(watchappUuid.toString())
        TimelineResult.FailedUnsupportedAction -> PinSendResult.UnsupportedAction
        TimelineResult.FailedUnknownPin -> PinSendResult.UnexpectedError(
            "Pebble app reported an unknown pin while inserting '$pinId'.",
        )
        is TimelineResult.Unknown -> PinSendResult.UnexpectedError(
            result.message ?: "Unknown timeline error returned by Pebble companion app.",
        )
    }

    fun toPinDeleteResult(
        pinId: String,
        watchappUuid: UUID,
        result: TimelineResult,
    ): PinDeleteResult = when (result) {
        TimelineResult.Success -> PinDeleteResult.Success(pinId)
        TimelineResult.FailedUnknownPin -> PinDeleteResult.UnknownPin(pinId)
        TimelineResult.FailedNoPebbleApp -> PinDeleteResult.NoPebbleApp
        TimelineResult.FailedNoPermissions -> PinDeleteResult.NoPermissions(watchappUuid.toString())
        TimelineResult.FailedUnsupportedAction -> PinDeleteResult.UnsupportedAction
        is TimelineResult.Unknown -> PinDeleteResult.UnexpectedError(
            result.message ?: "Unknown timeline error returned while deleting pin '$pinId'.",
        )
    }

    fun toConnectedPebbleWatch(watch: ConnectedWatch): ConnectedPebbleWatch {
        val baseVersion = "${watch.firmwareVersionMajor}.${watch.firmwareVersionMinor}.${watch.firmwareVersionPatch}"
        val fullVersion = watch.firmwareVersionTag
            ?.takeIf { it.isNotBlank() }
            ?.let { "$baseVersion-$it" }
            ?: baseVersion
        return ConnectedPebbleWatch(
            id = watch.id.value,
            name = watch.name.ifBlank { "Pebble Watch" },
            platformCode = watch.platform,
            platform = PebbleHardwarePlatform.fromPlatformCode(watch.platform),
            revision = watch.revision,
            firmwareVersion = fullVersion,
        )
    }

    private fun PinLayoutType.toPebbleKitLayoutType(): TimelineLayoutType = when (this) {
        PinLayoutType.GENERIC_PIN -> TimelineLayoutType.GENERIC_PIN
        PinLayoutType.CALENDAR_PIN -> TimelineLayoutType.CALENDAR_PIN
    }
}
