package com.lpecom.gemglasses.bobai.ui

import com.lpecom.gemglasses.bobai.ui.OfficeUrlPolicy.Decision.BLOCK
import com.lpecom.gemglasses.bobai.ui.OfficeUrlPolicy.Decision.EXTERNAL
import com.lpecom.gemglasses.bobai.ui.OfficeUrlPolicy.Decision.IN_WEBVIEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeUrlPolicyTest {
    private val policy = OfficeUrlPolicy("http://localhost:8095")

    @Test fun officeOriginStaysInWebView() {
        assertEquals(IN_WEBVIEW, policy.decide("http://localhost:8095"))
        assertEquals(IN_WEBVIEW, policy.decide("http://localhost:8095/"))
        assertEquals(IN_WEBVIEW, policy.decide("http://localhost:8095/acme/projects/1/issues?x=1#top"))
        assertEquals(IN_WEBVIEW, policy.decide("HTTP://LOCALHOST:8095/god-mode"))
    }

    @Test fun lookalikeHostsAreNotTheOffice() {
        assertNotEquals(IN_WEBVIEW, policy.decide("http://localhost:8095.evil.com/"))
        assertEquals(EXTERNAL, policy.decide("http://localhost.evil.com:8095/"))
        assertEquals(EXTERNAL, policy.decide("http://evil.com/localhost:8095"))
        // userinfo trick: the real host is evil.com
        assertEquals(EXTERNAL, policy.decide("http://localhost:8095@evil.com/"))
    }

    @Test fun otherPortsAndSchemesAreExternal() {
        assertEquals(EXTERNAL, policy.decide("http://localhost:8096/"))
        assertEquals(EXTERNAL, policy.decide("http://localhost/"))
        assertEquals(EXTERNAL, policy.decide("https://localhost:8095/"))
        assertEquals(EXTERNAL, policy.decide("http://127.0.0.1:8095/"))
        assertEquals(EXTERNAL, policy.decide("https://example.com/docs"))
    }

    @Test fun mailtoIsExternal() {
        assertEquals(EXTERNAL, policy.decide("mailto:office@example.com?subject=Hi"))
    }

    @Test fun dangerousOrUnknownSchemesAreBlocked() {
        assertEquals(BLOCK, policy.decide("javascript:alert(1)"))
        assertEquals(BLOCK, policy.decide("JavaScript:alert(1)"))
        assertEquals(BLOCK, policy.decide("file:///sdcard/secret.txt"))
        assertEquals(BLOCK, policy.decide("data:text/html,<b>x</b>"))
        assertEquals(BLOCK, policy.decide("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(BLOCK, policy.decide("content://com.example/x"))
        assertEquals(BLOCK, policy.decide("about:blank"))
        assertEquals(BLOCK, policy.decide(""))
        assertEquals(BLOCK, policy.decide(null))
        assertEquals(BLOCK, policy.decide("not a url"))
        assertEquals(BLOCK, policy.decide("/relative/path"))
    }

    @Test fun defaultPortsMatchImplicitPorts() {
        val https = OfficeUrlPolicy("https://office.example.com")
        assertEquals(IN_WEBVIEW, https.decide("https://office.example.com:443/x"))
        assertEquals(IN_WEBVIEW, https.decide("https://Office.Example.com/x"))
        assertEquals(EXTERNAL, https.decide("http://office.example.com/x"))
        assertEquals(EXTERNAL, https.decide("https://sub.office.example.com/x"))
        val http = OfficeUrlPolicy("http://office.example.com:80/app")
        assertEquals(IN_WEBVIEW, http.decide("http://office.example.com/other"))
    }

    @Test fun unusableOfficeUrlLoadsNothingInWebView() {
        val bad = OfficeUrlPolicy("javascript:alert(1)")
        assertFalse(bad.isConfigured)
        assertEquals(EXTERNAL, bad.decide("http://localhost:8095/"))
        assertTrue(policy.isConfigured)
    }
}
