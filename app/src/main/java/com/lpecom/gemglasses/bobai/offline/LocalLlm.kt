package com.lpecom.gemglasses.bobai.offline

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A local text model. Kept behind an interface so tests never load the native runtime. */
interface LocalLlm {
    /** Where the model file is expected (pushed with adb for the spike). */
    val modelFile: File

    /** Loads the model; returns a human-readable backend label, or throws. */
    suspend fun load(): String

    /** One stateless exchange: a fresh conversation per call, so nothing carries over. */
    suspend fun generate(systemInstruction: String, userMessage: String): String

    fun unload()
}

/**
 * LiteRT-LM implementation (com.google.ai.edge.litertlm). Tries the GPU
 * backend first and falls back to CPU. Greedy decoding (topK = 1) so the same
 * excerpt and question give the same wording.
 */
@Singleton
class LiteRtLocalLlm @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalLlm {

    override val modelFile: File
        get() = File(context.getExternalFilesDir("models"), MODEL_FILE_NAME)

    @Volatile private var engine: Engine? = null

    override suspend fun load(): String = withContext(Dispatchers.IO) {
        engine?.let { return@withContext "already loaded" }
        check(modelFile.isFile) { "No model at ${modelFile.path}" }
        val attempts = listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
        var last: Throwable? = null
        for ((label, backend) in attempts) {
            val e = Engine(EngineConfig(modelPath = modelFile.path, backend = backend(), cacheDir = context.cacheDir.path))
            try {
                val started = System.currentTimeMillis()
                e.initialize()
                engine = e
                Log.i(TAG, "model loaded on $label in ${System.currentTimeMillis() - started} ms")
                return@withContext label
            } catch (t: Throwable) {
                Log.w(TAG, "load on $label failed: ${t.message}")
                runCatching { e.close() }
                last = t
            }
        }
        throw IllegalStateException("Could not load the model: ${last?.message}", last)
    }

    override suspend fun generate(systemInstruction: String, userMessage: String): String =
        withContext(Dispatchers.IO) {
            val e = checkNotNull(engine) { "Model not loaded" }
            withTimeout(GENERATION_TIMEOUT_MS) {
                e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemInstruction),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
                    ),
                ).use { conversation ->
                    val out = StringBuilder()
                    conversation.sendMessageAsync(userMessage).collect { out.append(it.toString()) }
                    out.toString()
                }
            }
        }

    override fun unload() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        private const val TAG = "LocalLlm"
        const val MODEL_FILE_NAME = "bobai.litertlm"
        private const val GENERATION_TIMEOUT_MS = 45_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds abstract fun localLlm(impl: LiteRtLocalLlm): LocalLlm
}
