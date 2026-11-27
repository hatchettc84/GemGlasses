package com.lpecom.gemglasses.bobai.live

import com.lpecom.gemglasses.bobai.domain.StickyKind

sealed interface StickyArgsResult {
    data class Ok(val kind: StickyKind, val title: String, val text: String, val job: String?) : StickyArgsResult
    data class Error(val reason: String) : StickyArgsResult
}

/** Validates the save_sticky arguments from the model. Pure, so it is unit tested. */
object StickyArgs {
    fun parse(kind: String?, title: String?, text: String?, job: String?): StickyArgsResult {
        val k = when (kind?.trim()?.lowercase()) {
            "note" -> StickyKind.NOTE
            "draft" -> StickyKind.DRAFT
            else -> return StickyArgsResult.Error("kind must be note or draft")
        }
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return StickyArgsResult.Error("title is required")
        val x = text?.trim().orEmpty()
        if (x.isEmpty()) return StickyArgsResult.Error("text is required")
        return StickyArgsResult.Ok(k, t, x, job?.trim()?.takeIf { it.isNotEmpty() })
    }
}
