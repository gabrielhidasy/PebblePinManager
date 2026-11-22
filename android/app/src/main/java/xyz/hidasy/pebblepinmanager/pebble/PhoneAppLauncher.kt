package xyz.hidasy.pebblepinmanager.pebble

/**
 * Reason why a direct [android.content.Context.startActivity] launch was permitted without being
 * silently blocked by Android 10+ Background Activity Launch (BAL) restrictions.
 */
enum class DirectLaunchReason {
    /** An Activity in this app is currently visible in the foreground. */
    APP_IN_FOREGROUND,

    /** Device is running Android 8–9 (API 26–28), before BAL restrictions were introduced. */
    PRE_ANDROID_10,

    /** User granted `SYSTEM_ALERT_WINDOW` ("Display over other apps"), which exempts BAL on Android 10+. */
    OVERLAY_EXEMPTION,
}

/**
 * Outcome of handling a watch-initiated `MSG_WATCH_REQ_OPEN_PHONE` request.
 */
sealed interface PhoneUiOpenResult {
    /** `MainActivity` was launched directly. */
    data class OpenedDirect(val reason: DirectLaunchReason) : PhoneUiOpenResult

    /**
     * Direct background launch would be blocked by Android 10+ BAL restrictions, so a high-priority
     * tap-to-open notification was posted instead.
     */
    data object NotificationPosted : PhoneUiOpenResult

    /**
     * Neither a direct launch nor a handoff notification was possible (for example, Android 10+
     * background state with both overlay permission and notifications disabled).
     */
    data class Blocked(val reason: String) : PhoneUiOpenResult

    val watchStatusOk: Boolean
        get() = this is OpenedDirect || this is NotificationPosted

    val watchStatusText: String
        get() = when (this) {
            is OpenedDirect -> STATUS_OPENED_DIRECT
            NotificationPosted -> STATUS_TAP_NOTIFICATION
            is Blocked -> STATUS_OPEN_MANUALLY
        }

    companion object {
        const val STATUS_OPENED_DIRECT: String = "Opened phone app"
        const val STATUS_TAP_NOTIFICATION: String = "Tap phone notification"
        const val STATUS_OPEN_MANUALLY: String = "Open app on phone"
    }
}

/**
 * Snapshot of this device's watch-to-phone handoff capabilities.
 */
data class PhoneHandoffCapabilities(
    val isAppInForeground: Boolean,
    val sdkInt: Int,
    val canDrawOverlays: Boolean,
    val canPostNotifications: Boolean,
) {
    /** Android 10 (API 29) introduced Background Activity Launch (BAL) restrictions. */
    val requiresBackgroundHandoff: Boolean
        get() = sdkInt >= PolicyPhoneAppLauncher.ANDROID_10_API_LEVEL

    /** True if the app can open directly from the background without tapping a notification. */
    val canLaunchDirectlyFromBackground: Boolean
        get() = !requiresBackgroundHandoff || canDrawOverlays

    /** True if the watch can reach the phone from the background either directly or via notification. */
    val canHandoffFromBackground: Boolean
        get() = canLaunchDirectlyFromBackground || canPostNotifications

    /** True when the inline permission banner should be displayed in the composer UI. */
    fun shouldShowPermissionBanner(isDismissed: Boolean): Boolean =
        requiresBackgroundHandoff && !canHandoffFromBackground && !isDismissed

    /**
     * True when the top-bar watch handoff bell icon should render filled (ready);
     * false when it should render hollow/outlined (permissions not granted).
     */
    val isHandoffBellFilled: Boolean
        get() = canHandoffFromBackground
}

/**
 * Opens the phone UI (or performs an Android 10+ background handoff) in response to watch requests.
 */
interface PhoneAppLauncher {
    fun openPhoneUi(): PhoneUiOpenResult

    fun cancelHandoffNotification()

    fun inspectCapabilities(): PhoneHandoffCapabilities
}

/**
 * Platform operations required by [PolicyPhoneAppLauncher], abstracted for pure-JVM unit testing.
 */
interface PhoneLaunchEnvironment {
    val sdkInt: Int

    fun isAppInForeground(): Boolean

    fun canDrawOverlays(): Boolean

    fun canPostNotifications(): Boolean

    fun startMainActivity(): Result<Unit>

    fun postHandoffNotification(): Result<Unit>

    fun cancelHandoffNotification()
}

/**
 * Android 8–16+ Background Activity Launch (BAL) aware launcher policy.
 *
 * On Android 10+ (API 29+), `Context.startActivity()` from a bound background service like
 * [PebbleWatchListenerService] is silently blocked without throwing an exception unless the app
 * currently has a visible foreground window or holds a BAL exemption such as `SYSTEM_ALERT_WINDOW`.
 *
 * This policy detects whether a direct launch is actually permitted before calling
 * `startMainActivity()`, and otherwise posts a high-priority tap-to-open notification (or reports
 * [PhoneUiOpenResult.Blocked] if notifications are unavailable) so the Pebble watch never receives
 * a false-positive `"Opened phone app"` banner.
 */
class PolicyPhoneAppLauncher(
    private val environment: PhoneLaunchEnvironment,
    private val logger: (String) -> Unit = {},
) : PhoneAppLauncher {

    override fun openPhoneUi(): PhoneUiOpenResult {
        val capabilities = inspectCapabilities()
        val directReason = when {
            capabilities.isAppInForeground -> DirectLaunchReason.APP_IN_FOREGROUND
            !capabilities.requiresBackgroundHandoff -> DirectLaunchReason.PRE_ANDROID_10
            capabilities.canDrawOverlays -> DirectLaunchReason.OVERLAY_EXEMPTION
            else -> null
        }

        if (directReason != null) {
            val launchResult = environment.startMainActivity()
            if (launchResult.isSuccess) {
                runCatching { environment.cancelHandoffNotification() }
                logger("Opened phone UI directly (reason=$directReason, sdk=${capabilities.sdkInt})")
                return PhoneUiOpenResult.OpenedDirect(directReason)
            }
            val cause = launchResult.exceptionOrNull()?.message ?: "unknown error"
            logger("Direct phone UI launch failed ($directReason): $cause; trying notification fallback")
            return attemptNotificationFallback(
                capabilities = capabilities,
                fallbackPrefix = "Direct launch failed ($cause)",
            )
        }

        logger(
            "Direct launch skipped to avoid Android ${capabilities.sdkInt} BAL block " +
                "(foreground=false, canDrawOverlays=false); attempting notification handoff",
        )
        return attemptNotificationFallback(
            capabilities = capabilities,
            fallbackPrefix = "Background activity launch blocked on API ${capabilities.sdkInt}",
        )
    }

    override fun cancelHandoffNotification() {
        runCatching { environment.cancelHandoffNotification() }
    }

    override fun inspectCapabilities(): PhoneHandoffCapabilities = PhoneHandoffCapabilities(
        isAppInForeground = environment.isAppInForeground(),
        sdkInt = environment.sdkInt,
        canDrawOverlays = environment.canDrawOverlays(),
        canPostNotifications = environment.canPostNotifications(),
    )

    private fun attemptNotificationFallback(
        capabilities: PhoneHandoffCapabilities,
        fallbackPrefix: String,
    ): PhoneUiOpenResult {
        if (!capabilities.canPostNotifications) {
            val reason = "$fallbackPrefix and notifications are disabled or denied"
            logger(reason)
            return PhoneUiOpenResult.Blocked(reason)
        }

        val notifyResult = environment.postHandoffNotification()
        return if (notifyResult.isSuccess) {
            logger("Posted high-priority tap-to-open handoff notification (sdk=${capabilities.sdkInt})")
            PhoneUiOpenResult.NotificationPosted
        } else {
            val cause = notifyResult.exceptionOrNull()?.message ?: "unknown error"
            val reason = "$fallbackPrefix and notification handoff failed: $cause"
            logger(reason)
            PhoneUiOpenResult.Blocked(reason)
        }
    }

    companion object {
        const val ANDROID_10_API_LEVEL: Int = 29
    }
}
