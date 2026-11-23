package xyz.hidasy.pebblepinmanager.emulatorbridge

/**
 * Pure-Kotlin representation of an E2E demo notification broadcast payload.
 *
 * Kept free of Android framework dependencies so parsing and normalization can be unit-tested
 * directly on the JVM.
 */
data class DemoNotificationRequest(
    val id: Int = DEFAULT_NOTIFICATION_ID,
    val tag: String? = null,
    val title: String = DEFAULT_TITLE,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val ongoing: Boolean = false,
) {
    companion object {
        const val DEFAULT_NOTIFICATION_ID: Int = 1001
        const val DEFAULT_TITLE: String = "Demo Notification"

        fun fromExtras(
            id: Int?,
            tag: String?,
            title: String?,
            text: String?,
            bigText: String?,
            subText: String?,
            ongoing: Boolean?,
        ): DemoNotificationRequest {
            val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TITLE
            val normalizedText = text?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedBigText = bigText?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedSubText = subText?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedTag = tag?.trim()?.takeIf { it.isNotEmpty() }

            return DemoNotificationRequest(
                id = id ?: DEFAULT_NOTIFICATION_ID,
                tag = normalizedTag,
                title = normalizedTitle,
                text = normalizedText,
                bigText = normalizedBigText,
                subText = normalizedSubText,
                ongoing = ongoing ?: false,
            )
        }
    }
}

data class DemoNotificationCancelRequest(
    val id: Int = DemoNotificationRequest.DEFAULT_NOTIFICATION_ID,
    val tag: String? = null,
    val cancelAll: Boolean = false,
) {
    companion object {
        fun fromExtras(
            id: Int?,
            tag: String?,
            cancelAll: Boolean?,
        ): DemoNotificationCancelRequest = DemoNotificationCancelRequest(
            id = id ?: DemoNotificationRequest.DEFAULT_NOTIFICATION_ID,
            tag = tag?.trim()?.takeIf { it.isNotEmpty() },
            cancelAll = cancelAll ?: false,
        )
    }
}
