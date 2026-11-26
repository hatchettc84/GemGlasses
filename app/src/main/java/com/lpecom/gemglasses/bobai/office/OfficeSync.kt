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
 *
 * Manuals sync from a manifest: only new or changed documents are downloaded, unchanged ones are
 * kept from the cache, and a cached document that is no longer in the manifest is dropped. That
 * is how a revocation reaches the phone.
 *
 * A removed or unpaired phone keeps no office data: [forget] deletes the cache and clears Bob's
 * office manuals and jobs, and [loadCached] never applies a cache without a device key.
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

    /** Applies the cached copy (if any) when this phone is paired. Call on launch, before any network. */
    fun loadCached() {
        if (!isPaired()) {
            forget()
            return
        }
        readCached(knowledgeFile, OfficeKnowledge.serializer())?.let(controller::applyOfficeKnowledge)
        readCached(workFile, OfficeWork.serializer())?.let(controller::applyOfficeWork)
    }

    /** Deletes the cached office data and clears it from Bob (phone removed or unpaired). */
    fun forget() {
        knowledgeFile.delete()
        workFile.delete()
        controller.clearOffice()
    }

    suspend fun syncNow(): SyncOutcome {
        if (!isPaired()) return SyncOutcome.NotPaired
        return try {
            val knowledge = fetchKnowledge()
            val work = api.work()
            write(knowledgeFile, json.encodeToString(OfficeKnowledge.serializer(), knowledge))
            write(workFile, json.encodeToString(OfficeWork.serializer(), work))
            controller.applyOfficeKnowledge(knowledge)
            controller.applyOfficeWork(work)
            SyncOutcome.Synced
        } catch (e: DeviceRemovedException) {
            forget()
            SyncOutcome.Removed
        } catch (e: NotPairedException) {
            SyncOutcome.NotPaired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Reads the manifest, reuses unchanged cached documents and downloads the rest. */
    private suspend fun fetchKnowledge(): OfficeKnowledge {
        val index = api.knowledge()
        val cached = readCached(knowledgeFile, OfficeKnowledge.serializer())?.takeIf { it.tenantId == index.tenantId }
        val cachedDocs = cached?.documents?.associateBy { it.id }.orEmpty()
        val versions = LinkedHashMap<String, String>()
        val docs = index.documents.mapNotNull { meta ->
            val reuse = cachedDocs[meta.id]?.takeIf { cached?.versions?.get(meta.id) == meta.cacheKey }
            if (reuse != null) {
                versions[meta.id] = meta.cacheKey
                return@mapNotNull reuse
            }
            val body = try {
                api.knowledgeDocument(meta.id)
            } catch (e: OfficeNotFoundException) {
                return@mapNotNull null // revoked since the manifest was read
            }
            if (body.document.id != meta.id) return@mapNotNull null
            versions[meta.id] = body.cacheKey
            body.document
        }
        return OfficeKnowledge(index.tenantId, index.generatedAt, index.expiresAt, docs, versions)
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
