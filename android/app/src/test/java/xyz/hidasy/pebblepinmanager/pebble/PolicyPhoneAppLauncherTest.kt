package xyz.hidasy.pebblepinmanager.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyPhoneAppLauncherTest {

    @Test
    fun `foreground app on Android 15 launches directly without posting notification`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 35,
            inForeground = true,
            canDrawOverlays = false,
            canPostNotifications = true,
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertEquals(PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND), result)
        assertTrue(result.watchStatusOk)
        assertEquals(PhoneUiOpenResult.STATUS_OPENED_DIRECT, result.watchStatusText)
        assertEquals(1, env.startActivityCalls)
        assertEquals(0, env.postNotificationCalls)
        assertEquals(1, env.cancelNotificationCalls)
    }

    @Test
    fun `background app on Android 10 to 16 without overlay uses notification handoff instead of silent BAL block`() {
        listOf(29, 31, 33, 34, 35, 36).forEach { api ->
            val env = FakePhoneLaunchEnvironment(
                sdkInt = api,
                inForeground = false,
                canDrawOverlays = false,
                canPostNotifications = true,
            )
            val launcher = PolicyPhoneAppLauncher(env)

            val result = launcher.openPhoneUi()

            assertEquals("API $api should use notification handoff", PhoneUiOpenResult.NotificationPosted, result)
            assertTrue(result.watchStatusOk)
            assertEquals(PhoneUiOpenResult.STATUS_TAP_NOTIFICATION, result.watchStatusText)
            assertEquals("API $api must not call startActivity when BAL will block", 0, env.startActivityCalls)
            assertEquals("API $api should post handoff notification", 1, env.postNotificationCalls)
        }
    }

    @Test
    fun `background app on Android 15 without overlay and with notifications denied reports Blocked`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 35,
            inForeground = false,
            canDrawOverlays = false,
            canPostNotifications = false,
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertTrue(result is PhoneUiOpenResult.Blocked)
        assertFalse(result.watchStatusOk)
        assertEquals(PhoneUiOpenResult.STATUS_OPEN_MANUALLY, result.watchStatusText)
        assertEquals(0, env.startActivityCalls)
        assertEquals(0, env.postNotificationCalls)
    }

    @Test
    fun `background app on Android 15 with overlay exemption launches directly`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 35,
            inForeground = false,
            canDrawOverlays = true,
            canPostNotifications = false,
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertEquals(PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.OVERLAY_EXEMPTION), result)
        assertTrue(result.watchStatusOk)
        assertEquals(PhoneUiOpenResult.STATUS_OPENED_DIRECT, result.watchStatusText)
        assertEquals(1, env.startActivityCalls)
        assertEquals(0, env.postNotificationCalls)
        assertEquals(1, env.cancelNotificationCalls)
    }

    @Test
    fun `background app on pre-Android 10 launches directly without overlay`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 28,
            inForeground = false,
            canDrawOverlays = false,
            canPostNotifications = false,
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertEquals(PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.PRE_ANDROID_10), result)
        assertTrue(result.watchStatusOk)
        assertEquals(PhoneUiOpenResult.STATUS_OPENED_DIRECT, result.watchStatusText)
        assertEquals(1, env.startActivityCalls)
        assertEquals(0, env.postNotificationCalls)
    }

    @Test
    fun `direct launch failure falls back to notification handoff when notifications are enabled`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 35,
            inForeground = false,
            canDrawOverlays = true,
            canPostNotifications = true,
            startActivityResult = Result.failure(SecurityException("BAL denied")),
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertEquals(PhoneUiOpenResult.NotificationPosted, result)
        assertEquals(1, env.startActivityCalls)
        assertEquals(1, env.postNotificationCalls)
    }

    @Test
    fun `direct launch failure with notifications disabled reports Blocked`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 35,
            inForeground = true,
            canDrawOverlays = false,
            canPostNotifications = false,
            startActivityResult = Result.failure(IllegalStateException("Activity unavailable")),
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertTrue(result is PhoneUiOpenResult.Blocked)
        assertFalse(result.watchStatusOk)
        assertEquals(1, env.startActivityCalls)
        assertEquals(0, env.postNotificationCalls)
    }

    @Test
    fun `notification post failure reports Blocked`() {
        val env = FakePhoneLaunchEnvironment(
            sdkInt = 35,
            inForeground = false,
            canDrawOverlays = false,
            canPostNotifications = true,
            postNotificationResult = Result.failure(SecurityException("POST_NOTIFICATIONS revoked")),
        )
        val launcher = PolicyPhoneAppLauncher(env)

        val result = launcher.openPhoneUi()

        assertTrue(result is PhoneUiOpenResult.Blocked)
        assertFalse(result.watchStatusOk)
        assertEquals(PhoneUiOpenResult.STATUS_OPEN_MANUALLY, result.watchStatusText)
        assertEquals(0, env.startActivityCalls)
        assertEquals(1, env.postNotificationCalls)
    }

    @Test
    fun `inspectCapabilities accurately summarizes background handoff readiness`() {
        val blockedApi35 = PolicyPhoneAppLauncher(
            FakePhoneLaunchEnvironment(
                sdkInt = 35,
                inForeground = false,
                canDrawOverlays = false,
                canPostNotifications = false,
            ),
        ).inspectCapabilities()
        assertTrue(blockedApi35.requiresBackgroundHandoff)
        assertFalse(blockedApi35.canLaunchDirectlyFromBackground)
        assertFalse(blockedApi35.canHandoffFromBackground)
        assertTrue(blockedApi35.shouldShowPermissionBanner(isDismissed = false))
        assertFalse(blockedApi35.shouldShowPermissionBanner(isDismissed = true))
        assertFalse(blockedApi35.isHandoffBellFilled)

        val notifApi35 = PolicyPhoneAppLauncher(
            FakePhoneLaunchEnvironment(
                sdkInt = 35,
                inForeground = false,
                canDrawOverlays = false,
                canPostNotifications = true,
            ),
        ).inspectCapabilities()
        assertTrue(notifApi35.requiresBackgroundHandoff)
        assertFalse(notifApi35.canLaunchDirectlyFromBackground)
        assertTrue(notifApi35.canHandoffFromBackground)
        assertFalse(notifApi35.shouldShowPermissionBanner(isDismissed = false))
        assertTrue(notifApi35.isHandoffBellFilled)

        val overlayApi35 = PolicyPhoneAppLauncher(
            FakePhoneLaunchEnvironment(
                sdkInt = 35,
                inForeground = false,
                canDrawOverlays = true,
                canPostNotifications = false,
            ),
        ).inspectCapabilities()
        assertTrue(overlayApi35.requiresBackgroundHandoff)
        assertTrue(overlayApi35.canLaunchDirectlyFromBackground)
        assertTrue(overlayApi35.canHandoffFromBackground)
        assertFalse(overlayApi35.shouldShowPermissionBanner(isDismissed = false))
        assertTrue(overlayApi35.isHandoffBellFilled)

        val api28 = PolicyPhoneAppLauncher(
            FakePhoneLaunchEnvironment(
                sdkInt = 28,
                inForeground = false,
                canDrawOverlays = false,
                canPostNotifications = false,
            ),
        ).inspectCapabilities()
        assertFalse(api28.requiresBackgroundHandoff)
        assertTrue(api28.canLaunchDirectlyFromBackground)
        assertTrue(api28.canHandoffFromBackground)
        assertFalse(api28.shouldShowPermissionBanner(isDismissed = false))
        assertTrue(api28.isHandoffBellFilled)
    }
}

private class FakePhoneLaunchEnvironment(
    override val sdkInt: Int,
    private val inForeground: Boolean,
    private val canDrawOverlays: Boolean,
    private val canPostNotifications: Boolean,
    private val startActivityResult: Result<Unit> = Result.success(Unit),
    private val postNotificationResult: Result<Unit> = Result.success(Unit),
) : PhoneLaunchEnvironment {
    var startActivityCalls: Int = 0
    var postNotificationCalls: Int = 0
    var cancelNotificationCalls: Int = 0

    override fun isAppInForeground(): Boolean = inForeground

    override fun canDrawOverlays(): Boolean = canDrawOverlays

    override fun canPostNotifications(): Boolean = canPostNotifications

    override fun startMainActivity(): Result<Unit> {
        startActivityCalls++
        return startActivityResult
    }

    override fun postHandoffNotification(): Result<Unit> {
        postNotificationCalls++
        return postNotificationResult
    }

    override fun cancelHandoffNotification() {
        cancelNotificationCalls++
    }
}
