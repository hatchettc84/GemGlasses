package com.lpecom.gemglasses.bobai.data

import com.lpecom.gemglasses.bobai.domain.Job
import com.lpecom.gemglasses.bobai.domain.KnowledgePack
import com.lpecom.gemglasses.bobai.domain.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Everything BobAI persists on the device. Nothing here is synced off-device yet. */
@Serializable
data class StoreSnapshot(
    val schemaVersion: Int = 1,
    val jobs: List<Job> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val packs: List<KnowledgePack> = emptyList(),
    val activeSessionId: String? = null,
)

sealed interface LoadResult {
    data class Loaded(val snapshot: StoreSnapshot) : LoadResult
    data object Missing : LoadResult

    /** The file could not be parsed; it was moved aside so nothing is silently lost. */
    data class Corrupted(val backup: File) : LoadResult
}

/**
 * Local-first job/session persistence (brief §7 JobSessionStore). A single
 * JSON file in app-private storage, written atomically via temp-file rename
 * so a process death mid-write cannot corrupt the last good copy.
 */
class JobSessionStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): LoadResult {
        if (!file.exists()) return LoadResult.Missing
        return try {
            LoadResult.Loaded(json.decodeFromString(StoreSnapshot.serializer(), file.readText()))
        } catch (e: Exception) {
            val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            file.renameTo(backup)
            LoadResult.Corrupted(backup)
        }
    }

    @Synchronized
    fun save(snapshot: StoreSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(StoreSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }
}
