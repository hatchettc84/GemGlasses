package com.lpecom.gemglasses.bobai.office

import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.bobai.domain.Sticky
import com.lpecom.gemglasses.bobai.domain.StickyKind
import com.lpecom.gemglasses.gemini.BackendAuth
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The approved office manuals Bob reads from, as cached on the phone and handed to
 * [com.lpecom.gemglasses.bobai.data.BobAiController]. [versions] maps each document id to the
 * "version:sha256" it was downloaded at, so a later sync only fetches what changed.
 */
@Serializable
data class OfficeKnowledge(
    val tenantId: String,
    val generatedAt: Long,
    val expiresAt: Long,
    val documents: List<SourceDocument>,
    val versions: Map<String, String> = emptyMap(),
)

/** One row of GET /api/device/knowledge: metadata only, no page text (camelCase, as field-operations.ts emits it). */
@Serializable
data class OfficeDocMeta(
    val id: String,
    val tenantId: String,
    val title: String,
    val revision: String,
    val applicableModels: Set<String> = emptySet(),
    val version: Int,
    val sha256: String,
    val kind: String = "manual",
    val author: String? = null,
) {
    val cacheKey: String get() = "$version:$sha256"
}

/** GET /api/device/knowledge: the manifest of every document currently approved for this tenant. */
@Serializable
data class OfficeKnowledgeIndex(
    val tenantId: String,
    val generatedAt: Long,
    val expiresAt: Long,
    val documents: List<OfficeDocMeta>,
)

/** GET /api/device/knowledge/:id: one approved document with its pages. */
@Serializable
data class OfficeDocumentBody(val version: Int, val sha256: String, val document: SourceDocument) {
    val cacheKey: String get() = "$version:$sha256"
}

/** A row of `SELECT j.*, u.name AS assigned_name FROM jobs j ...` (snake_case columns). */
@Serializable
data class OfficeJobRow(
    val id: String,
    val title: String,
    val site: String,
    val description: String = "",
    val status: String,
    @SerialName("due_at") val dueAt: String? = null,
    val revision: Int = 1,
)

/** A row of `SELECT t.* FROM tasks t ...` (snake_case columns). */
@Serializable
data class OfficeTaskRow(
    val id: String,
    @SerialName("job_id") val jobId: String,
    val title: String,
    val completed: Int = 0,
    val revision: Int = 1,
)

/** GET /api/device/jobs, already filtered to this phone's user by the backend. */
@Serializable
data class OfficeWork(val jobs: List<OfficeJobRow>, val tasks: List<OfficeTaskRow>)

/** GET /api/device/me: only what the phone needs. [senior] is false when the field is absent (old backend). */
@Serializable
data class OfficeMe(val senior: Boolean = false)

/** Raised before any request is sent when this phone has no device key; device routes never use the app secret. */
class NotPairedException : IOException("This phone is not paired with the office")

/** The office answered 404: e.g. a document that was revoked after the manifest was read. */
class OfficeNotFoundException(path: String) : IOException("Not found on the office server: $path")

/** The office refused a write for good (HTTP 400 invalid or 409 too many); retrying the same request cannot help. */
class OfficeRejectedException(val code: Int) : IOException("The office rejected the request (HTTP $code)")

interface OfficeApi {
    /** The manifest of approved documents (no page text). */
    suspend fun knowledge(): OfficeKnowledgeIndex

    /** One approved document with its pages; [OfficeNotFoundException] when it is no longer approved. */
    suspend fun knowledgeDocument(id: String): OfficeDocumentBody

    suspend fun work(): OfficeWork

    /** Creates or replaces this technician's sticky in the office. [OfficeRejectedException] on 400/409. */
    suspend fun putSticky(sticky: Sticky)

    /** Deletes a sticky from the office; idempotent. */
    suspend fun deleteSticky(id: String)

    /** This phone's user, e.g. whether they are a senior technician. */
    suspend fun me(): OfficeMe

    /**
     * Sends an expert tip to the office as a pending draft. [OfficeRejectedException] with 400
     * (title or text invalid) or 403 (not a senior technician). Title and text are never logged.
     */
    suspend fun postTip(title: String, text: String)
}

/**
 * Reads the office backend with this phone's device key (via [BackendAuth]). A removed phone
 * throws [com.lpecom.gemglasses.gemini.DeviceRemovedException]; the shared app secret is only
 * ever sent by [BackendAuth] for a phone that was never paired, and [OfficeSync] does not call
 * this class in that case.
 */
class HttpOfficeApi internal constructor(
    client: OkHttpClient,
    private val json: Json,
    private val auth: BackendAuth,
    private val baseUrl: String,
) : OfficeApi {
    @Inject constructor(client: OkHttpClient, json: Json, auth: BackendAuth) :
        this(client, json, auth, BuildConfig.BACKEND_URL)

    // The shared client has no read timeout (live sockets); these are plain requests.
    private val client = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun knowledge(): OfficeKnowledgeIndex = get(listOf("knowledge"), OfficeKnowledgeIndex.serializer())

    override suspend fun knowledgeDocument(id: String): OfficeDocumentBody {
        // The id is one encoded path segment. "." and ".." would be resolved away by the URL
        // builder (changing the route), so they are never requested.
        if (id.isEmpty() || id == "." || id == "..") throw OfficeNotFoundException("knowledge/$id")
        return get(listOf("knowledge", id), OfficeDocumentBody.serializer())
    }

    override suspend fun work(): OfficeWork = get(listOf("jobs"), OfficeWork.serializer())

    override suspend fun putSticky(sticky: Sticky) {
        val body = buildJsonObject {
            put("kind", JsonPrimitive(if (sticky.kind == StickyKind.DRAFT) "draft" else "note"))
            put("title", JsonPrimitive(sticky.title))
            put("text", JsonPrimitive(sticky.text))
            put("jobTitle", sticky.jobTitle?.let { JsonPrimitive(it) } ?: JsonNull)
            put("jobId", sticky.jobId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("createdAt", JsonPrimitive(sticky.createdAt))
            put("done", JsonPrimitive(sticky.done))
        }.toString().toRequestBody("application/json".toMediaType())
        write("PUT", sticky.id, body)
    }

    override suspend fun deleteSticky(id: String) = write("DELETE", id, null)

    override suspend fun me(): OfficeMe = get(listOf("me"), OfficeMe.serializer())

    override suspend fun postTip(title: String, text: String) {
        val body = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("text", JsonPrimitive(text))
        }.toString().toRequestBody("application/json".toMediaType())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = (baseUrl.trimEnd('/') + "/api/device/").toHttpUrl().newBuilder()
                .addPathSegment("tips").build()
            val builder = Request.Builder().url(url).post(body)
            val sent = auth.authorize(builder)
            if (sent.deviceId == null) throw NotPairedException()
            client.newCall(builder.build()).execute().use { response ->
                val text2 = response.body?.string().orEmpty()
                auth.checkResponse(sent, response.code, text2)
                when {
                    response.isSuccessful -> Unit
                    response.code == 400 || response.code == 403 -> throw OfficeRejectedException(response.code)
                    else -> throw IOException("Office sync failed (HTTP ${response.code})")
                }
            }
        }
    }

    /** PUT/DELETE of /api/device/stickies/:id. Only the status code is ever reported, never the body sent. */
    private suspend fun write(method: String, id: String, body: okhttp3.RequestBody?) {
        if (id.isEmpty() || id == "." || id == "..") throw OfficeRejectedException(400)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = (baseUrl.trimEnd('/') + "/api/device/").toHttpUrl().newBuilder()
                .addPathSegment("stickies").addPathSegment(id).build()
            val builder = Request.Builder().url(url).method(method, body)
            val sent = auth.authorize(builder)
            if (sent.deviceId == null) throw NotPairedException()
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                auth.checkResponse(sent, response.code, text)
                when {
                    response.isSuccessful -> Unit
                    response.code == 400 || response.code == 409 -> throw OfficeRejectedException(response.code)
                    else -> throw IOException("Office sync failed (HTTP ${response.code})")
                }
            }
        }
    }

    private suspend fun <T> get(segments: List<String>, serializer: KSerializer<T>): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val path = segments.joinToString("/")
            val url = (baseUrl.trimEnd('/') + "/api/device/").toHttpUrl().newBuilder()
                .apply { segments.forEach { addPathSegment(it) } }
                .build()
            val builder = Request.Builder().url(url).get()
            val sent = auth.authorize(builder)
            if (sent.deviceId == null) throw NotPairedException() // never send a device route with the shared secret
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                auth.checkResponse(sent, response.code, body)
                if (response.code == 404) throw OfficeNotFoundException(path)
                if (!response.isSuccessful) throw IOException("Office sync failed (HTTP ${response.code})")
                try {
                    json.decodeFromString(serializer, body)
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw IOException("Office sync failed (unreadable response)", e)
                }
            }
        }
}
