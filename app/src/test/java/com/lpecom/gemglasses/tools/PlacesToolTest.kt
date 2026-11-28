package com.lpecom.gemglasses.tools

import com.lpecom.gemglasses.bobai.sync.DevicePairing
import com.lpecom.gemglasses.bobai.sync.InMemoryDeviceKeyStore
import com.lpecom.gemglasses.gemini.BackendAuth
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import com.lpecom.gemglasses.state.ConversationStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlacesToolTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun tool(store: InMemoryDeviceKeyStore) =
        PlacesTool(OkHttpClient(), json, { null }, ConversationStore(), BackendAuth(store, json, "app-secret"), server.url("/").toString())

    private val args = buildJsonObject { put("consulta", "cafe") }
    private val ok = """{"summary":"Tem um cafe.","places":[{"title":"Cafe","uri":"https://maps/x"}]}"""

    @Test fun `a paired phone sends its device key, not the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody(ok))
        val out = tool(InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))).execute(args)
        assertEquals("ok", out["status"]?.jsonPrimitive?.content)
        val req = server.takeRequest()
        assertEquals("Bearer dev-key", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-App-Secret"))
    }

    @Test fun `an unpaired phone sends the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody(ok))
        tool(InMemoryDeviceKeyStore()).execute(args)
        val req = server.takeRequest()
        assertEquals("app-secret", req.getHeader("X-App-Secret"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun `a removed phone makes no request and never sends the secret`() = runBlocking {
        val store = InMemoryDeviceKeyStore().also { it.removed = true }
        val e = runCatching { tool(store).execute(args) }.exceptionOrNull()
        assertTrue(e is DeviceRemovedException)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a revoked key response marks the phone removed`() = runBlocking {
        val store = InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"device_revoked"}"""))
        val e = runCatching { tool(store).execute(args) }.exceptionOrNull()
        assertTrue(e is DeviceRemovedException)
        assertTrue(store.removed)
    }
}
