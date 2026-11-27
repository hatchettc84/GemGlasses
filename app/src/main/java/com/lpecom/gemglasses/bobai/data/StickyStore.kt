package com.lpecom.gemglasses.bobai.data

import com.lpecom.gemglasses.bobai.domain.Sticky
import com.lpecom.gemglasses.bobai.domain.StickyKind
import com.lpecom.gemglasses.bobai.domain.StickyLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StickySnapshot(
    val schemaVersion: Int = 1,
    val stickies: List<Sticky> = emptyList(),
)

/**
 * One JSON file of stickies (files/bobai/stickies.json), written atomically via
 * temp-file rename like [JobSessionStore]. A file that cannot be parsed is moved
 * aside, not lost. Stickies never leave the phone.
 */
class StickyStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Newest first. Missing or corrupt files give an empty list (corrupt ones are kept aside). */
    fun load(): List<Sticky> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(StickySnapshot.serializer(), file.readText()).stickies
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            emptyList()
        }
    }

    @Synchronized
    fun save(stickies: List<Sticky>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(StickySnapshot.serializer(), StickySnapshot(stickies = stickies)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }
}

/** Holds the stickies in memory (newest first) and persists every change. */
class StickyRepository(
    private val store: StickyStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val persist: (List<Sticky>) -> Unit = { store.save(it) },
    private val outbox: StickyOutbox? = null,
    /** Called after every change has been queued in [outbox]; used to start an upload. */
    private val onChanged: () -> Unit = {},
) {
    private val lock = Any()
    private val _stickies = MutableStateFlow(store.load())
    val stickies: StateFlow<List<Sticky>> = _stickies.asStateFlow()

    init {
        // First run after the upgrade: queue every sticky that was saved before uploads existed.
        if (outbox != null && !outbox.existedAtStart) {
            outbox.enqueueAll(_stickies.value.map { it.id to StickyOp.UPSERT })
        }
    }

    fun add(kind: StickyKind, title: String, text: String, jobTitle: String?, jobId: String? = null): Sticky = synchronized(lock) {
        val sticky = Sticky(
            id = newId(),
            kind = kind,
            title = title.trim().take(StickyLimits.MAX_TITLE),
            text = text.trim().take(StickyLimits.MAX_TEXT),
            jobTitle = jobTitle?.trim()?.takeIf { it.isNotEmpty() },
            jobId = jobId?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = clock(),
        )
        val list = (listOf(sticky) + _stickies.value).toMutableList()
        val ops = mutableListOf<Pair<String, StickyOp>>()
        while (list.size > StickyLimits.MAX_STICKIES) {
            val i = list.indexOfLast { it.done }.takeIf { it >= 0 } ?: list.lastIndex
            ops += list.removeAt(i).id to StickyOp.DELETE
        }
        ops += sticky.id to StickyOp.UPSERT
        commit(list, ops)
        sticky
    }

    fun setDone(id: String, done: Boolean) = synchronized(lock) {
        val changed = _stickies.value.any { it.id == id && it.done != done }
        commit(_stickies.value.map { if (it.id == id) it.copy(done = done) else it }, if (changed) listOf(id to StickyOp.UPSERT) else emptyList())
    }

    fun delete(id: String) = synchronized(lock) {
        commit(_stickies.value.filterNot { it.id == id }, listOf(id to StickyOp.DELETE))
    }

    private fun commit(list: List<Sticky>, ops: List<Pair<String, StickyOp>>) {
        _stickies.value = list
        persist(list)
        if (ops.isNotEmpty()) {
            runCatching { outbox?.enqueueAll(ops) }
            onChanged()
        }
    }
}
