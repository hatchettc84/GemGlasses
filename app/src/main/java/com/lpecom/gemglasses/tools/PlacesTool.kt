package com.lpecom.gemglasses.tools

import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.gemini.BackendAuth
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import com.lpecom.gemglasses.location.LatLng
import com.lpecom.gemglasses.location.LocationProvider
import com.lpecom.gemglasses.gemini.protocol.FunctionDeclaration
import com.lpecom.gemglasses.state.CitedPlace
import com.lpecom.gemglasses.state.ConversationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * `buscar_lugares` — grounded place search. The heavy lifting (Gemini
 * `generateContent` + Maps grounding, with the API key) happens on the backend
 * Worker; the client just forwards the query and current location, then splits
 * the result into a short spoken summary (returned to the model) and the list
 * of cited places (pushed to the transcript for the ToS-required source UI).
 */
class PlacesTool internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val locate: suspend () -> LatLng?,
    private val conversation: ConversationStore,
    private val auth: BackendAuth,
    private val baseUrl: String,
) : AgentTool {

    @Inject constructor(
        client: OkHttpClient,
        json: Json,
        location: LocationProvider,
        conversation: ConversationStore,
        auth: BackendAuth,
    ) : this(client, json, { location.current() }, conversation, auth, BuildConfig.BACKEND_URL)

    override val name = "buscar_lugares"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Busca lugares reais próximos (restaurantes, farmácias, etc.) " +
            "usando dados do Google Maps. Sempre use esta ferramenta em vez de " +
            "inventar nomes de estabelecimentos.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "consulta": {
                  "type": "string",
                  "description": "O que procurar, ex.: 'café aberto agora', 'farmácia mais próxima'."
                }
              },
              "required": ["consulta"]
            }
            """
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val consulta = args.requireString("consulta")
        val loc = locate()

        val payload = buildJsonObject {
            put("query", consulta)
            if (loc != null) {
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
            }
        }

        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/places")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
        // Throws DeviceRemovedException for a removed phone: never falls back to the app secret.
        val sent = auth.authorize(builder)
        val request = builder.build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                auth.checkResponse(sent, response.code, body)
                check(response.isSuccessful) { "places endpoint ${response.code}" }
                parseAndPublish(body)
            }
        }.getOrElse {
            if (it is DeviceRemovedException) throw it
            buildJsonObject {
                put("status", "error")
                put("resumo", "Não consegui buscar lugares agora.")
            }
        }
    }

    private fun parseAndPublish(body: String): JsonObject {
        val root = json.parseToJsonElement(body).jsonObject
        val summary = root["summary"]?.jsonPrimitive?.content ?: "Encontrei alguns lugares."
        val places = root["places"]?.jsonArray.orEmpty().mapNotNull { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val uri = obj["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
            CitedPlace(title, uri)
        }
        conversation.addPlaces(places)

        return buildJsonObject {
            put("status", "ok")
            put("resumo", summary)
            put("total", places.size)
        }
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty() = this ?: kotlinx.serialization.json.JsonArray(emptyList())

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
