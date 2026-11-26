package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.office.HttpOfficeApi
import com.lpecom.gemglasses.bobai.office.NotPairedException
import com.lpecom.gemglasses.bobai.sync.DevicePairing
import com.lpecom.gemglasses.bobai.sync.InMemoryDeviceKeyStore
import com.lpecom.gemglasses.gemini.BackendAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpOfficeApiTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun api(store: InMemoryDeviceKeyStore): HttpOfficeApi {
        val j = Json { ignoreUnknownKeys = true }
        return HttpOfficeApi(OkHttpClient(), j, BackendAuth(store, j, "app-secret"), server.url("/").toString())
    }

    @Test fun `an unpaired phone never sends a device request or the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jobs":[],"tasks":[]}"""))
        val e = runCatching { api(InMemoryDeviceKeyStore()).work() }.exceptionOrNull()
        assertTrue(e is NotPairedException)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a paired phone sends its device key only`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jobs":[],"tasks":[]}"""))
        api(InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))).work()
        val req = server.takeRequest()
        assertEquals("Bearer dev-key", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-App-Secret"))
    }
}
