package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.live.OfflineNotice
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class OfflineNoticeTest {
    private val dns = UnknownHostException("Unable to resolve host \"generativelanguage.googleapis.com\"")

    @Test fun `announces when the session drops and the phone has no internet`() =
        assertTrue(OfflineNotice.shouldAnnounce(dns, online = false))

    @Test fun `keeps retrying quietly when the phone is online`() =
        assertFalse(OfflineNotice.shouldAnnounce(dns, online = true))

    @Test fun `a clean close is not an offline notice`() =
        assertFalse(OfflineNotice.shouldAnnounce(null, online = false))

    @Test fun `removed glasses keep their own message`() =
        assertFalse(OfflineNotice.shouldAnnounce(DeviceRemovedException(), online = false))

    @Test fun `spoken text names the fix and never mentions keys or hosts`() {
        assertTrue("Wi-Fi" in OfflineNotice.SPOKEN && "mobile data" in OfflineNotice.SPOKEN)
        assertFalse("googleapis" in OfflineNotice.SPOKEN || "key" in OfflineNotice.SPOKEN.lowercase())
    }
}
