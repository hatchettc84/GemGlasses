package com.lpecom.gemglasses.bobai.sync

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

class InMemoryDeviceKeyStore(var stored: DevicePairing? = null) : DeviceKeyStore {
    override fun get() = stored
    var removed = false
    override fun save(pairing: DevicePairing) { stored = pairing; removed = false }
    override fun clear() { stored = null; removed = false }
    override fun markRemoved() { stored = null; removed = true }
    override fun wasRemoved() = removed
}

class PairingClientTest {
    private val server = MockWebServer()
    private val store = InMemoryDeviceKeyStore()
    private lateinit var client: PairingClient

    @Before fun setUp() {
        server.start()
        client = PairingClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, store, server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `pairs and stores the device key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"deviceKey":"secret-key","deviceId":"D1","user":{"name":"Tia","email":"tia@example.com"}}""",
        ))
        val result = client.pair(" 482913 ", "SM-F966U")
        assertEquals(PairResult.Paired("Tia"), result)
        assertEquals(DevicePairing("D1", "secret-key", "Tia"), store.stored)
        val request = server.takeRequest()
        assertEquals("/api/device/pair", request.path)
        assertEquals("""{"code":"482913","label":"SM-F966U"}""", request.body.readUtf8())
    }

    @Test fun `shows the server's message for a wrong code and stores nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":"invalid_code","message":"That code is wrong or expired. Ask your admin for a new one."}""",
        ))
        val result = client.pair("000000", "SM-F966U")
        assertEquals(PairResult.Rejected("invalid_code", "That code is wrong or expired. Ask your admin for a new one."), result)
        assertNull(store.stored)
    }

    @Test fun `server errors are a generic failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val result = client.pair("482913", "SM-F966U")
        assertTrue(result is PairResult.Failed)
        assertNull(store.stored)
    }

    @Test fun `no connection is a failure, not a crash`() = runBlocking {
        server.shutdown()
        val result = client.pair("482913", "SM-F966U")
        assertEquals(PairResult.Failed("Couldn't reach the office console. Check the connection and try again."), result)
    }

    @Test fun `an unexpected 200 body is a failure and stores nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Sign in to Wi-Fi</html>"))
        val result = client.pair("482913", "SM-F966U")
        assertEquals(PairResult.Failed("The office console sent an unexpected reply. Try again."), result)
        assertNull(store.stored)
    }
}
