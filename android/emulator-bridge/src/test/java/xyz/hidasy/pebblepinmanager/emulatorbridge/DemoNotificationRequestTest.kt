package xyz.hidasy.pebblepinmanager.emulatorbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoNotificationRequestTest {
    @Test
    fun `fromExtras trims fields and applies sensible defaults`() {
        val req = DemoNotificationRequest.fromExtras(
            id = null,
            tag = "   ",
            title = "",
            text = "  Gate B12 departs in 15 min  ",
            bigText = null,
            subText = "  ",
            ongoing = null,
        )

        assertEquals(DemoNotificationRequest.DEFAULT_NOTIFICATION_ID, req.id)
        assertNull(req.tag)
        assertEquals(DemoNotificationRequest.DEFAULT_TITLE, req.title)
        assertEquals("Gate B12 departs in 15 min", req.text)
        assertNull(req.bigText)
        assertNull(req.subText)
        assertFalse(req.ongoing)
    }

    @Test
    fun `fromExtras preserves explicit values`() {
        val req = DemoNotificationRequest.fromExtras(
            id = 2002,
            tag = "demo-flight",
            title = "Flight DL123 Boarding",
            text = "Gate B12 departs in 15 min",
            bigText = "Gate B12 departs in 15 min - Terminal 2",
            subText = "Delta Air Lines",
            ongoing = true,
        )

        assertEquals(2002, req.id)
        assertEquals("demo-flight", req.tag)
        assertEquals("Flight DL123 Boarding", req.title)
        assertEquals("Gate B12 departs in 15 min", req.text)
        assertEquals("Gate B12 departs in 15 min - Terminal 2", req.bigText)
        assertEquals("Delta Air Lines", req.subText)
        assertTrue(req.ongoing)
    }

    @Test
    fun `cancel request normalizes tag and cancelAll`() {
        val single = DemoNotificationCancelRequest.fromExtras(
            id = 2001,
            tag = " demo-promo ",
            cancelAll = false,
        )
        assertEquals(2001, single.id)
        assertEquals("demo-promo", single.tag)
        assertFalse(single.cancelAll)

        val all = DemoNotificationCancelRequest.fromExtras(
            id = null,
            tag = "  ",
            cancelAll = true,
        )
        assertEquals(DemoNotificationRequest.DEFAULT_NOTIFICATION_ID, all.id)
        assertNull(all.tag)
        assertTrue(all.cancelAll)
    }
}
