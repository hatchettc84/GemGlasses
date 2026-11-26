package com.lpecom.gemglasses.bobai.office

import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.gemini.BackendAuth
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** GET /api/device/knowledge: camelCase, exactly as backend field-operations.ts emits it. */
@Serializable
data class OfficeKnowledge(
    val tenantId: String,
    val generatedAt: Long,
    val expiresAt: Long,
    val documents: List<SourceDocument>,
)

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

interface OfficeApi {
    suspend fun knowledge(): OfficeKnowledge
    suspend fun work(): OfficeWork
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

    override suspend fun knowledge(): OfficeKnowledge = get("knowledge", OfficeKnowledge.serializer())

    override suspend fun work(): OfficeWork = get("jobs", OfficeWork.serializer())

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/api/device/" + path).get()
            val sent = auth.authorize(builder)
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                auth.checkResponse(sent, response.code, body)
                if (!response.isSuccessful) throw IOException("Office sync failed (HTTP ${response.code})")
                try {
                    json.decodeFromString(serializer, body)
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw IOException("Office sync failed (unreadable response)", e)
                }
            }
        }
}
