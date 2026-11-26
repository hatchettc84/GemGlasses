package com.lpecom.gemglasses.bobai.office

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File

sealed interface SyncOutcome {
    data object Synced : SyncOutcome
    data object NotPaired : SyncOutcome
    data object Removed : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Pulls approved manuals and assigned jobs from the office and hands them to Bob. The last good
 * copy is cached under [dir] so the phone works offline; a failed sync never discards it.
 * Manual text is reference data only.
 */
class OfficeSync(
    private val api: OfficeApi,
    private val dir: File,
    private val controller: BobAiController,
    private val isPaired: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val knowledgeFile get() = File(dir, "knowledge.json")
    private val workFile get() = File(dir, "work.json")

    /** Applies the cached copy (if any). Call on launch, before any network. */
    fun loadCached() {
        readCached(knowledgeFile, OfficeKnowledge.serializer())?.let(controller::applyOfficeKnowledge)
        readCached(workFile, OfficeWork.serializer())?.let(controller::applyOfficeWork)
    }

    suspend fun syncNow(): SyncOutcome {
        if (!isPaired()) return SyncOutcome.NotPaired
        return try {
            val knowledge = api.knowledge()
            val work = api.work()
            write(knowledgeFile, json.encodeToString(OfficeKnowledge.serializer(), knowledge))
            write(workFile, json.encodeToString(OfficeWork.serializer(), work))
            controller.applyOfficeKnowledge(knowledge)
            controller.applyOfficeWork(work)
            SyncOutcome.Synced
        } catch (e: DeviceRemovedException) {
            SyncOutcome.Removed
        } catch (e: NotPairedException) {
            SyncOutcome.NotPaired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun <T> readCached(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun write(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Could not save ${file.name}")
        }
    }
}
