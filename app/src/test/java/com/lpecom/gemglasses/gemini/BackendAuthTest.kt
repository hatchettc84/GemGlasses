package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.bobai.sync.DevicePairing
import com.lpecom.gemglasses.bobai.sync.InMemoryDeviceKeyStore
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackendAuthTest {
    private val revoked = """{"error":"device_revoked","message":"gone"}"""
    private fun auth(store: InMemoryDeviceKeyStore) = BackendAuth(store, Json { ignoreUnknownKeys = true }, "app-secret")
    private fun builder() = Request.Builder().url("http://localhost/x")

    @Test fun `paired sends the device key only`() {
        val b = builder()
        auth(InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))).authorize(b)
        val r = b.build()
        assertEquals("Bearer dev-key", r.header("Authorization"))
        assertNull(r.header("X-App-Secret"))
    }

    @Test fun `never paired sends the app secret`() {
        val b = builder()
        auth(InMemoryDeviceKeyStore()).authorize(b)
        val r = b.build()
        assertEquals("app-secret", r.header("X-App-Secret"))
        assertNull(r.header("Authorization"))
    }

    @Test fun `removed phone throws and sends no secret`() {
        val store = InMemoryDeviceKeyStore().also { it.removed = true }
        val b = builder()
        try { auth(store).authorize(b); fail("expected DeviceRemovedException") } catch (_: DeviceRemovedException) {}
        assertNull(b.build().header("X-App-Secret"))
    }

    @Test fun `401 revoked for a device key marks removed`() {
        val store = InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))
        val a = auth(store)
        val sent = a.authorize(builder())
        try { a.checkResponse(sent, 401, revoked); fail("expected DeviceRemovedException") } catch (_: DeviceRemovedException) {}
        assertTrue(store.removed)
        assertNull(store.stored)
    }

    @Test fun `401 for the app secret or other statuses do nothing`() {
        val store = InMemoryDeviceKeyStore()
        val a = auth(store)
        a.checkResponse(a.authorize(builder()), 401, revoked)
        val paired = InMemoryDeviceKeyStore(DevicePairing("D1", "k", "Tia"))
        val b = auth(paired)
        b.checkResponse(b.authorize(builder()), 500, revoked)
        b.checkResponse(b.authorize(builder()), 401, """{"error":"other"}""")
        assertTrue(!store.removed && !paired.removed)
        assertNotNull(paired.stored)
    }

    @Test fun `a late 401 for an old key does not delete a fresh pairing`() {
        val store = InMemoryDeviceKeyStore(DevicePairing("D1", "old-key", "Tia"))
        val a = auth(store)
        val sent = a.authorize(builder())
        store.save(DevicePairing("D2", "new-key", "Tia")) // re-paired while the request was in flight
        try { a.checkResponse(sent, 401, revoked); fail("expected DeviceRemovedException") } catch (_: DeviceRemovedException) {}
        assertEquals("D2", store.stored?.deviceId)
        assertTrue(!store.removed)
    }
}
