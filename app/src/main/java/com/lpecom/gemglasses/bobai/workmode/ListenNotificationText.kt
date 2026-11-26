package com.lpecom.gemglasses.bobai.workmode

/** What the ongoing Work Mode notification says in each phase. */
fun listenNotificationText(phase: WorkPhase): String = when (phase) {
    WorkPhase.WAKE_DETECTED, WorkPhase.ACTIVE -> "Bob is talking with you."
    else -> "BobAI is listening for \"Hey Bob\". No audio is saved."
}
