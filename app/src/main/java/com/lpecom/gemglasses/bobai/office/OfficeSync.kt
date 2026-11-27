package com.lpecom.gemglasses.bobai.office

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File

sealed interface SyncOutcome {
    data object Synced : SyncOutcome

    /**
     * The sync succeeded, but [notUpdated] listed manuals could not be downloaded (network error,
     * timeout or server error). Each one kept its cached copy if there was one, or was left out.
     * [jobsNotUpdated]: the manuals updated but the jobs could not be fetched (e.g. Plane was
     * slow), so the cached jobs were kept.
     */
    data class Partial(val notUpdated: Int, val jobsNotUpdated: Boolean = false) : SyncOutcome
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
 * is how a revocation reaches the phone. One document failing to download (anything but a 404)
 * does not fail the sync: its cached copy is kept, or it is skipped, and the outcome is
 * [SyncOutcome.Partial]. The manifest's drops always apply. Manuals are applied before jobs are
 * fetched, so a jobs failure keeps the cached jobs without holding back a manual update.
 *
 * A removed or unpaired phone keeps no office data: [forget] deletes the cache and clears Bob's
 * office manuals and jobs, and [loadCached] never applies a cache without a device key.
 */
class OfficeSync(
    private val api: OfficeApi,
    private val dir: File,
    private val controller: BobAiController,
    private val stickyUploader: StickyUploader? = null,
    private val stickyOutbox: com.lpecom.gemglasses.bobai.data.StickyOutbox? = null,
    private val isPaired: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val knowledgeFile get() = File(dir, "knowledge.json")
    private val workFile get() = File(dir, "work.json")

    /** Applies the cached copy (if any) when this phone is paired. Call on launch, before any network. */
    fun loadCached() {
        if (!isPaired()) {
            // Not paired (maybe never): drop office data, but keep queued stickies so they upload once paired.
            forget(clearStickyOutbox = false)
            return
        }
        readCached(knowledgeFile, OfficeKnowledge.serializer())?.let(controller::applyOfficeKnowledge)
        readCached(workFile, OfficeWork.serializer())?.let(controller::applyOfficeWork)
    }

    /** Deletes the cached office data and clears it from Bob (phone removed or unpaired). */
    fun forget(clearStickyOutbox: Boolean = true) {
        if (clearStickyOutbox) runCatching { stickyOutbox?.clear() }
        knowledgeFile.delete()
        workFile.delete()
        controller.clearOffice()
    }

    suspend fun syncNow(): SyncOutcome {
        val outcome = syncOffice()
        // Upload pending stickies last; a failure there never changes the outcome.
        try {
            stickyUploader?.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored: the outbox keeps the changes
        }
        return outcome
    }

    private suspend fun syncOffice(): SyncOutcome {
        if (!isPaired()) return SyncOutcome.NotPaired
        return try {
            val (knowledge, notUpdated) = fetchKnowledge()
            // Manuals first, on their own: a revocation must reach Bob even when the jobs
            // source (e.g. Plane behind the Worker) is slow or down.
            write(knowledgeFile, json.encodeToString(OfficeKnowledge.serializer(), knowledge))
            controller.applyOfficeKnowledge(knowledge)
            val work = try {
                api.work()
            } catch (e: DeviceRemovedException) {
                throw e
            } catch (e: NotPairedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // keep the cached jobs
            }
            if (work != null) {
                write(workFile, json.encodeToString(OfficeWork.serializer(), work))
                controller.applyOfficeWork(work)
            }
            when {
                work == null -> SyncOutcome.Partial(notUpdated, jobsNotUpdated = true)
                notUpdated == 0 -> SyncOutcome.Synced
                else -> SyncOutcome.Partial(notUpdated)
            }
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

    /**
     * Reads the manifest, reuses unchanged cached documents and downloads the rest. Returns the
     * knowledge and how many listed documents could not be downloaded.
     */
    private suspend fun fetchKnowledge(): Pair<OfficeKnowledge, Int> {
        val index = api.knowledge()
        val cached = readCached(knowledgeFile, OfficeKnowledge.serializer())?.takeIf { it.tenantId == index.tenantId }
        val cachedDocs = cached?.documents?.associateBy { it.id }.orEmpty()
        val versions = LinkedHashMap<String, String>()
        var notUpdated = 0
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
            } catch (e: DeviceRemovedException) {
                throw e
            } catch (e: NotPairedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network error, timeout or server error for this one document: keep the cached copy
                // (any version; its old cache key, if any, makes the next sync retry it) or skip it.
                notUpdated++
                val stale = cachedDocs[meta.id] ?: return@mapNotNull null
                cached?.versions?.get(meta.id)?.let { versions[meta.id] = it }
                return@mapNotNull stale
            }
            if (body.document.id != meta.id) return@mapNotNull null
            versions[meta.id] = body.cacheKey
            body.document
        }
        return OfficeKnowledge(index.tenantId, index.generatedAt, index.expiresAt, docs, versions) to notUpdated
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
