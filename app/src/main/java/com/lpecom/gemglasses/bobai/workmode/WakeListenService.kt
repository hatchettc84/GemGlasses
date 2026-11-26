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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private var text = listenNotificationText(WorkPhase.ARMED)
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        pending = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start, including a stop request, goes through startForeground
        // first: the service was started with startForegroundService, and
        // Android crashes the app if it stops without ever calling it.
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TURN_OFF) {
            // disable() asks to stop this service; stopSelf too in case it was
            // only started for this action.
            workMode.disable()
            stopSelf()
            return START_NOT_STICKY
        }
        if (started.isFailure) {
            Log.w(TAG, "could not start listening in the foreground: ${started.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { sc ->
                sc.launch {
                    workMode.state.map { listenNotificationText(it.phase) }.distinctUntilChanged().collect { t ->
                        text = t
                        runCatching {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                        }
                    }
                }
            }
        }
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
        pending = false
        scope?.cancel()
        scope = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Needed: the recognizer is restarted from a main-thread Handler between
     * listening windows, and Handler delays run on uptime, which stops while
     * the CPU sleeps — without this, "Hey Bob" can go deaf after the first
     * window with the screen off. Held only while this service is up (it is
     * stopped whenever Work Mode is off or unavailable) and released in
     * [onDestroy], which every stop path reaches; the timeout is a backstop.
     */
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
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
        private const val ACTION_STOP = "com.lpecom.gemglasses.bobai.WORK_MODE_STOP"

        /** True from a successful [start] until the service is destroyed, so [stop] never starts one just to stop it. */
        @Volatile private var pending = false

        /** Call from a user action while the app is visible. Returns false if Android refused. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeListenService::class.java))
        }.onFailure { Log.w(TAG, "could not start: ${it.javaClass.simpleName}") }
            .isSuccess.also { if (it) pending = true }

        /**
         * Asks the service to stop with a start command rather than stopService,
         * so a service whose startForeground hasn't run yet still calls it
         * (see [onStartCommand]) before it stops. If Android won't start it
         * from here, falls back to stopService.
         */
        fun stop(context: Context) {
            if (!pending) return
            val intent = Intent(context, WakeListenService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }.onFailure {
                Log.w(TAG, "stop request refused, stopping directly: ${it.javaClass.simpleName}")
                context.stopService(Intent(context, WakeListenService::class.java))
            }
        }
    }
}
