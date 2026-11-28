package com.lpecom.gemglasses.bobai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class StickyOp { UPSERT, DELETE }

@Serializable
private data class OutboxSnapshot(val schemaVersion: Int = 1, val ops: Map<String, StickyOp> = emptyMap())

/** A pending change; [seq] changes every time the op for that id is (re)queued. */
data class PendingSticky(val id: String, val op: StickyOp, val seq: Long)

/**
 * Sticky changes not yet uploaded to the office: sticky id to the latest op (latest wins).
 * Persisted atomically (temp-file rename) in one JSON file so nothing is lost offline or on
 * process death. Holds ids only, never sticky text.
 */
class StickyOutbox(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var counter = 0L
    private val ops = LinkedHashMap<String, PendingSticky>()

    /** False when no outbox file existed at start: the first run after the upgrade. */
    val existedAtStart: Boolean = file.exists()

    init {
        if (existedAtStart) {
            try {
                json.decodeFromString(OutboxSnapshot.serializer(), file.readText()).ops
                    .forEach { (id, op) -> ops[id] = PendingSticky(id, op, ++counter) }
            } catch (e: Exception) {
                file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }
        }
    }

    fun enqueue(id: String, op: StickyOp) = enqueueAll(listOf(id to op))

    fun enqueueAll(items: List<Pair<String, StickyOp>>) = synchronized(lock) {
        items.forEach { (id, op) ->
            ops.remove(id) // keep arrival order; a re-queued id moves to the end
            ops[id] = PendingSticky(id, op, ++counter)
        }
        persist()
    }

    fun pending(): List<PendingSticky> = synchronized(lock) { ops.values.toList() }

    /** Removes [id]'s op only if it was not re-queued since [seq] was read. */
    fun remove(id: String, seq: Long) = synchronized(lock) {
        if (ops[id]?.seq == seq) {
            ops.remove(id)
            persist()
        }
    }

    /** Drops every pending op (phone removed). The file stays, empty, so no backfill runs again. */
    fun clear() = synchronized(lock) {
        ops.clear()
        persist()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(OutboxSnapshot.serializer(), OutboxSnapshot(ops = ops.mapValues { it.value.op })))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }
}
