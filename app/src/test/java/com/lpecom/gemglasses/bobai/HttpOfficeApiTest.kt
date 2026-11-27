package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.office.HttpOfficeApi
import com.lpecom.gemglasses.bobai.office.NotPairedException
import com.lpecom.gemglasses.bobai.office.OfficeNotFoundException
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

    @Test fun `one document is read from knowledge by id and a 404 is reported as not found`() = runBlocking {
        val store = InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))
        server.enqueue(
            MockResponse().setBody(
                """{"version":2,"sha256":"abc","document":{"id":"D 1","tenantId":"t","title":"T","family":"T","revision":"1","applicableModels":["ZX-9"],"approvalStatus":"APPROVED","packId":"office-approved","isMock":false,"pages":[]}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found","message":"Approved document not found."}"""))
        assertEquals("2:abc", api(store).knowledgeDocument("D 1").cacheKey)
        assertEquals("/api/device/knowledge/D%201", server.takeRequest().path)
        val e = runCatching { api(store).knowledgeDocument("gone") }.exceptionOrNull()
        assertTrue(e is OfficeNotFoundException)
    }

    @Test fun `a document id is one path segment, so a slash or dots cannot change the route`() = runBlocking {
        val store = InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))
        server.enqueue(MockResponse().setResponseCode(404))
        runCatching { api(store).knowledgeDocument("a/../jobs") }
        assertEquals("/api/device/knowledge/a%2F..%2Fjobs", server.takeRequest().path)
        // A bare "." or ".." would be resolved away by the URL builder; it is never requested.
        for (id in listOf(".", "..", "")) {
            assertTrue(runCatching { api(store).knowledgeDocument(id) }.exceptionOrNull() is OfficeNotFoundException)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `putSticky sends the contract body and maps 400 and 409 to rejected`() = runBlocking {
        val store = InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"too many stickies"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val s = com.lpecom.gemglasses.bobai.domain.Sticky("id-1", com.lpecom.gemglasses.bobai.domain.StickyKind.DRAFT, "T", "Body", null, "plane:abc", 1795658320970L, true)
        api(store).putSticky(s)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/device/stickies/id-1", req.path)
        assertEquals("Bearer dev-key", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertEquals("draft", (body["kind"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("1795658320970", (body["createdAt"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("true", (body["done"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("plane:abc", (body["jobId"] as kotlinx.serialization.json.JsonPrimitive).content)
        val e = runCatching { api(store).putSticky(s) }.exceptionOrNull()
        assertEquals(409, (e as com.lpecom.gemglasses.bobai.office.OfficeRejectedException).code)
        assertTrue(runCatching { api(store).putSticky(s) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `deleteSticky sends DELETE and an unpaired phone sends nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api(InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))).deleteSticky("id-1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/device/stickies/id-1", req.path)
        assertTrue(runCatching { api(InMemoryDeviceKeyStore()).deleteSticky("x") }.exceptionOrNull() is NotPairedException)
        assertEquals(1, server.requestCount)
    }
}
