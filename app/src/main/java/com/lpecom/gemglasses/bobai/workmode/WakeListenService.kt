package com.lpecom.gemglasses.bobai.workmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lpecom.gemglasses.MainActivity
import com.lpecom.gemglasses.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Microphone foreground service that runs while Work Mode is on, so "Hey Bob"
 * keeps listening with the screen locked. Android only lets an app use the
 * microphone in the background from a microphone-type foreground service
 * started while the app was visible: this one is started by the Work Mode
 * button and stays up for the whole of Work Mode, including the Gemini Live
 * session a wake word starts (which may begin with the screen locked, when a
 * new foreground service can't be started).
 *
 * The ongoing notification says what is happening and has a "Turn off" action.
 */
@AndroidEntryPoint
class WakeListenService : Service() {

    @Inject lateinit var workMode: WorkModeController

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            // disable() stops this service.
            workMode.disable()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }
        if (started.isFailure) {
            Log.w(TAG, "could not start listening in the foreground: ${started.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // Not sticky: after the OS kills the process Work Mode state is gone,
        // so a restarted service would listen for nothing.
        return START_NOT_STICKY
    }

    /** Swiping BobAI away from recent apps turns Work Mode off rather than leaving it listening unseen. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        workMode.disable()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keeps the recognizer restarting with the screen off; capped like the voice session's. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BobAI::HeyBob").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val turnOff = PendingIntent.getService(
            this, 1, Intent(this, WakeListenService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Work Mode is on")
            .setContentText(TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(TEXT))
            .setSmallIcon(R.drawable.ic_glasses)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Turn off", turnOff)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Work Mode", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "WakeListenService"
        private const val CHANNEL_ID = "bobai_work_mode"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_TURN_OFF = "com.lpecom.gemglasses.bobai.WORK_MODE_OFF"
        private const val WAKELOCK_TIMEOUT_MS = 10L * 60L * 60L * 1000L // a long shift
        const val TEXT = "BobAI is listening for \"Hey Bob\". No audio is saved."

        /** Call from a user action while the app is visible. Returns false if Android refused. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeListenService::class.java))
        }.onFailure { Log.w(TAG, "could not start: ${it.javaClass.simpleName}") }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeListenService::class.java))
        }
    }
}
