package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import xyz.hidasy.pebblepinmanager.BuildConfig

data class NotificationAccessDisclosureSection(
    val heading: String,
    val body: String,
)

data class NotificationAccessDisclosureCopy(
    val title: String,
    val intro: String,
    val sections: List<NotificationAccessDisclosureSection>,
    val footer: String,
    val agreeButtonLabel: String,
    val cancelButtonLabel: String,
) {
    companion object {
        const val TITLE = "Notification Access Disclosure"
        const val AGREE_BUTTON_LABEL = "Agree & Open Settings"
        const val CANCEL_BUTTON_LABEL = "Cancel"

        fun create(includeDevModeDisclosure: Boolean = BuildConfig.DEV_MODE_AVAILABLE): NotificationAccessDisclosureCopy {
            val whatDataBody = if (includeDevModeDisclosure) {
                "Incoming notification text from apps you select (and raw notifications while optional Developer Mode is enabled), plus launchable app names for the allowlist picker — including while the app is closed or in the background."
            } else {
                "Incoming notification text from apps you select, plus launchable app names for the allowlist picker — including while the app is closed or in the background."
            }

            return NotificationAccessDisclosureCopy(
                title = TITLE,
                intro = "Before opening Android Notification Access Settings, please review how ${AppBranding.APP_DISPLAY_NAME} accesses and uses your data:",
                sections = listOf(
                    NotificationAccessDisclosureSection(
                        heading = "What data is accessed",
                        body = whatDataBody,
                    ),
                    NotificationAccessDisclosureSection(
                        heading = "How it is used",
                        body = "Processed 100% on-device to generate Pebble Timeline pins and passed locally via PebbleKit 2 IPC only to your installed Pebble companion app.",
                    ),
                    NotificationAccessDisclosureSection(
                        heading = "Zero off-device transmission",
                        body = "No internet permission, no external servers, and no third-party data sharing, analytics, or ads.",
                    ),
                ),
                footer = "Notification Access is optional. Manual pin creation always works without it, and you can disable or revoke access at any time in Android Settings.",
                agreeButtonLabel = AGREE_BUTTON_LABEL,
                cancelButtonLabel = CANCEL_BUTTON_LABEL,
            )
        }
    }
}

object NotificationAccessDisclosureTags {
    const val DIALOG = "notification_access_disclosure_dialog"
    const val AGREE_BUTTON = "notification_access_disclosure_agree"
    const val CANCEL_BUTTON = "notification_access_disclosure_cancel"
}

/**
 * Dialog behavior for the Prominent Disclosure modal:
 * - Tapping outside the modal never dismisses or navigates to Settings.
 * - Pressing Back triggers [onDismissRequest], which cancels without navigating to Settings.
 */
val NotificationAccessDisclosureDialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
)

/**
 * State gate that ensures Android Notification Access Settings cannot be opened without
 * presenting the Prominent Disclosure modal and receiving affirmative user consent.
 */
@Stable
class NotificationAccessDisclosureState(
    initialVisible: Boolean = false,
) {
    var isVisible: Boolean by mutableStateOf(initialVisible)
        private set

    fun requestSettingsAccess() {
        isVisible = true
    }

    fun cancel() {
        isVisible = false
    }

    fun dismissWithoutConsent() {
        isVisible = false
    }

    /**
     * Dismisses the disclosure modal and invokes [onOpenSettings] only if the disclosure
     * modal is currently visible. Returns `true` if affirmative consent proceeded to Settings.
     */
    fun confirmAndOpenSettings(onOpenSettings: () -> Unit): Boolean {
        if (!isVisible) return false
        isVisible = false
        onOpenSettings()
        return true
    }

    companion object {
        val Saver: Saver<NotificationAccessDisclosureState, Boolean> = Saver(
            save = { it.isVisible },
            restore = { NotificationAccessDisclosureState(initialVisible = it) },
        )
    }
}

@Composable
fun rememberNotificationAccessDisclosureState(
    initialVisible: Boolean = false,
): NotificationAccessDisclosureState = rememberSaveable(
    saver = NotificationAccessDisclosureState.Saver,
) {
    NotificationAccessDisclosureState(initialVisible = initialVisible)
}

@Composable
fun NotificationAccessDisclosureDialog(
    includeDevModeDisclosure: Boolean = BuildConfig.DEV_MODE_AVAILABLE,
    onAgreeAndOpenSettings: () -> Unit,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit = onCancel,
) {
    val copy = NotificationAccessDisclosureCopy.create(
        includeDevModeDisclosure = includeDevModeDisclosure,
    )

    AlertDialog(
        modifier = Modifier.testTag(NotificationAccessDisclosureTags.DIALOG),
        onDismissRequest = onDismissRequest,
        properties = NotificationAccessDisclosureDialogProperties,
        icon = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = copy.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = copy.intro,
                    style = MaterialTheme.typography.bodyMedium,
                )

                copy.sections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = section.heading,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = section.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = copy.footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAgreeAndOpenSettings,
                modifier = Modifier.testTag(NotificationAccessDisclosureTags.AGREE_BUTTON),
            ) {
                Text(copy.agreeButtonLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(NotificationAccessDisclosureTags.CANCEL_BUTTON),
            ) {
                Text(copy.cancelButtonLabel)
            }
        },
    )
}
