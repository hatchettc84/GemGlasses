package com.lpecom.gemglasses.bobai.office

import com.lpecom.gemglasses.bobai.data.StickyOp
import com.lpecom.gemglasses.bobai.data.StickyOutbox
import com.lpecom.gemglasses.bobai.domain.Sticky
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends the pending sticky changes in [outbox] to the office, oldest first. A change leaves the
 * outbox only once the office accepted it (or permanently refused it, 400/409). A network or
 * server error, a removed phone or an unpaired phone stops the flush and keeps the rest.
 * Never logs sticky text or titles; only ids and HTTP status.
 */
class StickyUploader(
    private val outbox: StickyOutbox,
    private val api: OfficeApi,
    private val stickies: () -> List<Sticky>,
    private val isPaired: () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Starts a flush in the background when paired. Safe to call after every change. */
    fun requestFlush() {
        if (!isPaired()) return
        scope.launch { flush() }
    }

    /** Never throws except for cancellation. */
    suspend fun flush() {
        if (!isPaired()) return
        mutex.withLock {
            for (p in outbox.pending()) {
                try {
                    when (p.op) {
                        StickyOp.DELETE -> api.deleteSticky(p.id)
                        StickyOp.UPSERT -> {
                            val sticky = stickies().firstOrNull { it.id == p.id }
                            if (sticky != null) api.putSticky(sticky)
                        }
                    }
                    outbox.remove(p.id, p.seq)
                } catch (e: OfficeRejectedException) {
                    log("sticky ${p.id} rejected (HTTP ${e.code}); dropped")
                    outbox.remove(p.id, p.seq)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DeviceRemovedException) {
                    return
                } catch (e: NotPairedException) {
                    return
                } catch (e: Exception) {
                    log("sticky ${p.id} upload failed; will retry")
                    return
                }
            }
        }
    }
}
