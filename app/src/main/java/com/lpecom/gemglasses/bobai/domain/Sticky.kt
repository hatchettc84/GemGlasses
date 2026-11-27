package com.lpecom.gemglasses.bobai.domain

import kotlinx.serialization.Serializable

/** A note or draft Bob saved for the technician to review later. Drafts are never sent. */
@Serializable
enum class StickyKind { NOTE, DRAFT }

@Serializable
data class Sticky(
    val id: String,
    val kind: StickyKind,
    val title: String,
    val text: String,
    val jobTitle: String? = null,
    val createdAt: Long,
    val done: Boolean = false,
)

object StickyLimits {
    const val MAX_TITLE = 80
    const val MAX_TEXT = 2000
    const val MAX_STICKIES = 500
}
