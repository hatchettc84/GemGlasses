package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.bobai.sync.DevicePairing
import com.lpecom.gemglasses.bobai.sync.InMemoryDeviceKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TokenProviderTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun provider(store: InMemoryDeviceKeyStore) =
        TokenProvider(OkHttpClient(), Json { ignoreUnknownKeys = true }, store, server.url("/").toString(), "app-secret")

    @Test fun `a paired phone sends its device key, not the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"t1"}"""))
        val token = provider(InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))).fetchEphemeralToken()
        assertEquals("t1", token)
        val req = server.takeRequest()
        assertEquals("Bearer dev-key", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-App-Secret"))
    }

    @Test fun `an unpaired phone falls back to the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"t2"}"""))
        provider(InMemoryDeviceKeyStore()).fetchEphemeralToken()
        val req = server.takeRequest()
        assertEquals("app-secret", req.getHeader("X-App-Secret"))
        assertNull(req.getHeader("Authorization"))
    }
}
