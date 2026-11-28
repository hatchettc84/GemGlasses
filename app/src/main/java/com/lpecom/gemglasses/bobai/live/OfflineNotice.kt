package com.lpecom.gemglasses.bobai.live

import android.content.Context
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.util.Log
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the phone has no internet, Bob (Gemini Live) can't answer at all. Without this the
 * technician says "Hey Bob" and hears nothing, which looks like Bob isn't listening. Instead the
 * phone says so itself, on-device, and the wake word goes back to listening.
 */
object OfflineNotice {
    const val SPOKEN = "I can't reach the internet right now. Check the phone's Wi-Fi or mobile data, then try again."
    const val SCREEN = "No internet: Bob can't answer until the phone is back online. Check Wi-Fi or mobile data."

    /** A dropped session is announced as "offline" only when the phone itself has no internet. */
    fun shouldAnnounce(error: Throwable?, online: Boolean): Boolean =
        error != null && error !is DeviceRemovedException && !online
}

/** Whether the phone currently has a working (validated) internet connection. */
@Singleton
class NetworkCheck @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true) // can't tell: don't claim offline
}

/** Speaks a short app message with the phone's own text-to-speech (works offline). */
@Singleton
class NoticeSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null

    fun speak(text: String) {
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "text-to-speech unavailable ($status)")
                return@TextToSpeech
            }
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bob-notice")
        }
        tts = engine
    }

    private companion object {
        const val TAG = "BobVoice"
    }
}
