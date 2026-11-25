package xyz.hidasy.pebblepinmanager.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal object PinComposerFeedbackTags {
    const val SNACKBAR = "pin_composer_feedback_snackbar"
    const val SNACKBAR_HEADLINE = "pin_composer_feedback_snackbar_headline"
    const val SNACKBAR_DETAILS = "pin_composer_feedback_snackbar_details"
    const val SNACKBAR_ACTION = "pin_composer_feedback_snackbar_action"
    const val SNACKBAR_DISMISS = "pin_composer_feedback_snackbar_dismiss"
    const val SUBMIT_BUTTON = "pin_composer_submit_button"
    const val INLINE_FEEDBACK_BANNER = "pin_composer_inline_feedback_banner"
}

internal enum class PinFeedbackSnackbarTone {
    WATCH_SUCCESS,
    QUEUED_SUCCESS,
    SIMULATED_SUCCESS,
    ERROR,
}

internal data class PinFeedbackSnackbarVisuals(
    val headline: String,
    val details: String,
    val tone: PinFeedbackSnackbarTone,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration = SnackbarDuration.Long,
) : SnackbarVisuals {
    override val message: String
        get() = if (details.isBlank()) headline else "$headline — $details"
}

internal object PinComposerFeedbackMapper {
    const val ACTION_VIEW = "View"

    fun toSnackbarVisuals(
        uiState: PinComposerUiState,
        canOpenSimulatedTimeline: Boolean,
    ): PinFeedbackSnackbarVisuals? {
        val confirmation = uiState.sendConfirmation?.takeIf {
            it.eventId == uiState.feedbackEventId
        }
        if (confirmation != null) {
            val tone = when (confirmation.deliveryStatus) {
                PinDeliveryStatus.SENT_TO_WATCH -> PinFeedbackSnackbarTone.WATCH_SUCCESS
                PinDeliveryStatus.QUEUED_FOR_WATCH -> PinFeedbackSnackbarTone.QUEUED_SUCCESS
                PinDeliveryStatus.SIMULATED -> PinFeedbackSnackbarTone.SIMULATED_SUCCESS
            }
            val actionLabel = when {
                confirmation.deliveryStatus == PinDeliveryStatus.SIMULATED && canOpenSimulatedTimeline ->
                    ACTION_VIEW
                confirmation.deliveryStatus != PinDeliveryStatus.SIMULATED &&
                    !confirmation.isAutoPin &&
                    uiState.sentPins.isNotEmpty() ->
                    ACTION_VIEW
                else -> null
            }
            return PinFeedbackSnackbarVisuals(
                headline = confirmation.snackbarTitle,
                details = confirmation.snackbarDetails,
                tone = tone,
                actionLabel = actionLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        }

        return when (val feedback = uiState.feedback) {
            is SendFeedback.Success -> PinFeedbackSnackbarVisuals(
                headline = feedback.headline,
                details = feedback.details,
                tone = if (uiState.isRunWithoutWatch) {
                    PinFeedbackSnackbarTone.SIMULATED_SUCCESS
                } else {
                    PinFeedbackSnackbarTone.WATCH_SUCCESS
                },
                actionLabel = null,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )

            is SendFeedback.Error -> PinFeedbackSnackbarVisuals(
                headline = feedback.headline,
                details = feedback.details,
                tone = PinFeedbackSnackbarTone.ERROR,
                actionLabel = null,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )

            null -> null
        }
    }
}

/**
 * Triggers a subtle confirmation haptic pulse on successful pin delivery.
 *
 * Uses Android's semantic [HapticFeedbackConstants.CONFIRM] on API 30+ (Android 11+) and falls
 * back to Compose's subtle [HapticFeedbackType.TextHandleMove] on API 26–29.
 */
internal fun performPinConfirmationHaptic(
    view: View,
    composeHapticFeedback: HapticFeedback,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        composeHapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

@Composable
internal fun PinFeedbackSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val visuals = snackbarData.visuals as? PinFeedbackSnackbarVisuals
    val tone = visuals?.tone ?: PinFeedbackSnackbarTone.WATCH_SUCCESS
    val headline = visuals?.headline ?: snackbarData.visuals.message
    val details = visuals?.details.orEmpty()

    val containerColor = when (tone) {
        PinFeedbackSnackbarTone.WATCH_SUCCESS -> MaterialTheme.colorScheme.secondary
        PinFeedbackSnackbarTone.QUEUED_SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
        PinFeedbackSnackbarTone.SIMULATED_SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
        PinFeedbackSnackbarTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        PinFeedbackSnackbarTone.WATCH_SUCCESS -> MaterialTheme.colorScheme.onSecondary
        PinFeedbackSnackbarTone.QUEUED_SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
        PinFeedbackSnackbarTone.SIMULATED_SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
        PinFeedbackSnackbarTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    val borderColor = when (tone) {
        PinFeedbackSnackbarTone.WATCH_SUCCESS -> null
        PinFeedbackSnackbarTone.QUEUED_SUCCESS -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        PinFeedbackSnackbarTone.SIMULATED_SUCCESS -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
        PinFeedbackSnackbarTone.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    }
    val leadingIcon = when (tone) {
        PinFeedbackSnackbarTone.WATCH_SUCCESS -> Icons.Default.CheckCircle
        PinFeedbackSnackbarTone.QUEUED_SUCCESS -> Icons.Default.Schedule
        PinFeedbackSnackbarTone.SIMULATED_SUCCESS -> Icons.Default.ViewTimeline
        PinFeedbackSnackbarTone.ERROR -> Icons.Default.ErrorOutline
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(PinComposerFeedbackTags.SNACKBAR)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(PinComposerFeedbackTags.SNACKBAR_HEADLINE),
                )
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(PinComposerFeedbackTags.SNACKBAR_DETAILS),
                    )
                }
            }
            snackbarData.visuals.actionLabel?.let { actionLabel ->
                TextButton(
                    onClick = { snackbarData.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                    modifier = Modifier.testTag(PinComposerFeedbackTags.SNACKBAR_ACTION),
                ) {
                    Text(
                        text = actionLabel,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (snackbarData.visuals.withDismissAction) {
                IconButton(
                    onClick = { snackbarData.dismiss() },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag(PinComposerFeedbackTags.SNACKBAR_DISMISS),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss confirmation",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
