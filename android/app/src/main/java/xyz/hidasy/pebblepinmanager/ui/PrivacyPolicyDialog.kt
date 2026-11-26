package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PrivacyPolicyHighlight(
    val title: String,
    val detail: String,
)

object PrivacyPolicyInfo {
    const val DEVELOPER_NAME: String = "Gabriel Hidasy"
    const val CONTACT_EMAIL: String = "gabrielhidasy+play@gmail.com"

    /**
     * Public HTTPS Privacy Policy URL opened when the user taps "Open online".
     */
    const val PRIVACY_POLICY_URL: String =
        "https://pebblepinmanager.hidasy.xyz/privacy"

    val summaryHighlights: List<PrivacyPolicyHighlight> = listOf(
        PrivacyPolicyHighlight(
            title = "Local-first — opt-in network access only",
            detail = "${AppBranding.APP_DISPLAY_NAME} makes zero network requests by default. The INTERNET permission is used strictly when you explicitly configure an authenticated Remote Pin Feed URL (HTTPS by default). There are no analytics, telemetry, crash reporting, or ads.",
        ),
        PrivacyPolicyHighlight(
            title = "Encrypted credentials & Auto Backup exclusion",
            detail = "Remote feed auth tokens and passwords are encrypted on-device using Android Keystore (AES-256-GCM) in a dedicated store excluded from Android Auto Backup and device-to-device transfer.",
        ),
        PrivacyPolicyHighlight(
            title = "Pin history & local Pebble sync",
            detail = "Pin history (up to 100 recent pins) and app preferences are stored locally in AndroidX DataStore and sent only over local PebbleKit 2 IPC / Bluetooth to your paired Pebble watch.",
        ),
        PrivacyPolicyHighlight(
            title = "Opt-in Auto-Pins & installed apps",
            detail = "Notification Access is off by default and processed strictly on-device. The Auto-Pins allowlist queries launchable installed apps (ACTION_MAIN / CATEGORY_LAUNCHER) strictly locally on-device and never transmits your app list off-device.",
        ),
        PrivacyPolicyHighlight(
            title = "Your data, your control",
            detail = "Delete individual pins or remote feeds in-app, revoke optional permissions in Android Settings at any time, or erase all local data via Clear storage or uninstalling.",
        ),
    )
}

@Composable
internal fun PrivacyPolicyDialog(
    openOnlineFailed: Boolean,
    onOpenOnline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Policy,
                contentDescription = null,
            )
        },
        title = { Text("Privacy Policy") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "This summary is available offline inside the app. Open the full Privacy Policy online at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PrivacyPolicyInfo.summaryHighlights.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Developer & Privacy Contact",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${PrivacyPolicyInfo.DEVELOPER_NAME} (${PrivacyPolicyInfo.CONTACT_EMAIL})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = PrivacyPolicyInfo.PRIVACY_POLICY_URL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (openOnlineFailed) {
                    Text(
                        text = "No web browser was found to open the link automatically. You can copy or visit the URL above.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenOnline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open online")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
