package com.lpecom.gemglasses.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes microphone capture and speaker playback through the glasses' Bluetooth
 * audio device. The glasses mic requires a communication (SCO / LE Audio)
 * profile, which lowers playback fidelity — acceptable for voice.
 *
 * API 31+ uses [AudioManager.setCommunicationDevice]; older devices fall back
 * to the deprecated SCO path.
 */
@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var active = false

    /** True while communication audio is pointed at a Bluetooth device. */
    val routedToBluetooth: Boolean get() = active

    /** Points communication audio at the glasses (or best Bluetooth device). */
    fun routeToGlasses() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = audioManager.availableCommunicationDevices.firstOrNull { it.isBluetooth() }
            if (target == null) {
                // No headset to route to: leave the phone out of call mode.
                // Call mode with no SCO device made the glasses' Bluetooth link
                // drop whenever the camera stream started (seen on the Fold7),
                // so live view got no frames while voice was on. The mic and
                // speaker still request voice processing via their own
                // VOICE_COMMUNICATION source/usage.
                Log.w(TAG, "no Bluetooth communication device found; staying in normal audio mode")
                return
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (audioManager.setCommunicationDevice(target)) {
                Log.i(TAG, "communication device set to ${target.productName}")
            } else {
                Log.w(TAG, "could not set communication device ${target.productName}")
            }
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
        active = true
    }

    /** Restores normal audio routing. */
    fun restore() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        active = false
    }

    private fun AudioDeviceInfo.isBluetooth() =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private companion object {
        const val TAG = "BluetoothAudioRouter"
    }
}
