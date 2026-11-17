package com.lpecom.gemglasses.glasses.real

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesBackend
import com.lpecom.gemglasses.glasses.GlassesDevice
import com.lpecom.gemglasses.glasses.RegistrationState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import com.meta.wearable.dat.core.types.RegistrationState as DatRegistrationState

/**
 * Real integration with the Meta Wearables Device Access Toolkit 1.0.x
 * (`com.meta.wearable:mwdat-core` / `mwdat-camera`, Maven Central).
 *
 * Compiled only when `gemglasses.useRealGlasses=true`; instantiated
 * reflectively by the DI layer so `src/main` never references the SDK.
 *
 * Registration and the camera-permission prompt both need a foreground
 * Activity, which this backend tracks itself via activity lifecycle callbacks
 * so the [GlassesBackend] interface stays Activity-free.
 */
@Suppress("unused")
class RealGlassesBackend(private val context: Context) : GlassesBackend {

    @Volatile private var currentActivity: WeakReference<Activity>? = null
    private val requestCounter = AtomicInteger()

    // The SDK throws WearablesException if any Wearables state is read before
    // initialize(), and the flows below read it during construction — so this
    // must run first (declaration order).
    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(ActivityTracker())
        Wearables.initialize(context.applicationContext)
            .onSuccess { Log.i(TAG, "DAT initialized (dev mode: ${Wearables.isDevMode})") }
            .onFailure { error, _ -> Log.e(TAG, "DAT failed to initialize: ${error.description}") }
    }

    override val registrationState: Flow<RegistrationState> =
        Wearables.registrationState.map { state ->
            when (state) {
                DatRegistrationState.REGISTERED -> RegistrationState.REGISTERED
                DatRegistrationState.REGISTERING -> RegistrationState.REGISTERING
                DatRegistrationState.AVAILABLE -> RegistrationState.NOT_REGISTERED
                DatRegistrationState.UNREGISTERING -> RegistrationState.REVOKED
                else -> RegistrationState.UNKNOWN // UNAVAILABLE: Meta AI app missing or unreachable
            }.also { Log.i(TAG, "registration: $state -> $it") }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val devices: Flow<List<GlassesDevice>> =
        Wearables.devices.flatMapLatest { ids ->
            val readings = ids.mapNotNull { id -> Wearables.devicesMetadata[id]?.map { id to it } }
            if (readings.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(readings) { pairs ->
                    pairs.map { (id, d) ->
                        GlassesDevice(
                            id = id.identifier,
                            name = d.name.ifBlank { "Meta glasses" },
                            connected = d.linkState == LinkState.CONNECTED,
                        )
                    }.also { list ->
                        Log.i(TAG, "devices: " + list.joinToString { "${it.name}=${if (it.connected) "connected" else "not connected"}" })
                    }
                }
            }
        }

    /** Initialisation happens in the constructor; see the init block. */
    override fun initialize() = Unit

    override fun startRegistration() {
        val activity = currentActivity?.get()
        if (activity == null) {
            Log.w(TAG, "startRegistration: no foreground activity")
            return
        }
        Wearables.startRegistration(activity)
    }

    override suspend fun cameraPermission(): CameraPermission =
        Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            { status -> if (status == PermissionStatus.Granted) CameraPermission.GRANTED else CameraPermission.DENIED },
            { error, _ ->
                Log.i(TAG, "camera permission unreadable: ${error.description}")
                CameraPermission.NOT_DETERMINED
            },
        )

    /**
     * Launches the Meta AI permission sheet. Registers directly on the
     * activity's result registry (no lifecycle owner) so it can be called at
     * any time, and unregisters as soon as the result arrives.
     */
    override suspend fun requestCameraPermission(): CameraPermission {
        val activity = currentActivity?.get() as? ComponentActivity
        if (activity == null) {
            Log.w(TAG, "requestCameraPermission: no foreground activity")
            return CameraPermission.NOT_DETERMINED
        }
        return suspendCancellableCoroutine { cont ->
            val key = "mwdat-camera-permission-${requestCounter.incrementAndGet()}"
            lateinit var launcher: ActivityResultLauncher<Permission>
            launcher = activity.activityResultRegistry.register(key, Wearables.RequestPermissionContract()) { result ->
                launcher.unregister()
                val mapped = result.fold(
                    { status -> if (status == PermissionStatus.Granted) CameraPermission.GRANTED else CameraPermission.DENIED },
                    { error, _ ->
                        Log.w(TAG, "camera permission request failed: ${error.description}")
                        CameraPermission.DENIED
                    },
                )
                if (cont.isActive) cont.resume(mapped)
            }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Permission.CAMERA)
        }
    }

    /**
     * Opens a device session, attaches the camera at the lowest quality and a
     * 2 fps frame rate, and emits each frame as a downscaled JPEG. Cancelling
     * the flow stops the stream and the session.
     */
    override fun cameraFrames(): Flow<ByteArray> = channelFlow {
        val session = Wearables.createSession(AutoDeviceSelector()).fold(
            { it },
            { error, _ ->
                Log.w(TAG, "camera: could not create a device session: ${error.description}")
                null
            },
        )
        if (session == null) {
            close()
            return@channelFlow
        }
        var camera: Camera? = null
        // Session and stream errors are terminal for this capture: end the flow
        // right away so callers can retry instead of waiting out their timeout.
        launch {
            session.errors.collect {
                Log.w(TAG, "session error: ${it.description}")
                close()
            }
        }
        launch {
            session.state.collect { state ->
                Log.i(TAG, "session: $state")
                if (state == DeviceSessionState.STARTED && camera == null) {
                    session.addCamera(StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = FRAME_RATE))
                        .onSuccess { cam ->
                            camera = cam
                            launch { cam.stream.state.collect { Log.i(TAG, "stream: $it") } }
                            launch {
                                cam.stream.errorStream.collect {
                                    Log.w(TAG, "stream error: ${it.description}")
                                    close()
                                }
                            }
                            launch {
                                var seen = 0
                                cam.stream.videoStream.collect { frame ->
                                    if (seen++ < 3) {
                                        Log.i(TAG, "frame ${frame.width}x${frame.height} bytes=${frame.buffer.remaining()} " +
                                            "compressed=${frame.isCompressed} codecConfig=${frame.isCodecConfig}")
                                    }
                                    frame.toDownscaledJpeg()?.let { trySend(it) }
                                }
                            }
                            cam.stream.start().onFailure { error, _ -> Log.w(TAG, "stream start failed: ${error.description}") }
                        }
                        .onFailure { error, _ -> Log.w(TAG, "addCamera failed: ${error.description}") }
                }
            }
        }
        session.start()
        awaitClose {
            runCatching { camera?.stop() }
            runCatching { session.stop() }
        }
    }

    /**
     * Uncompressed frames arrive as planar YUV 4:2:0. The plane order is not
     * documented, so this assumes I420 (Y, U, V) and repacks to NV21 for
     * [YuvImage]. If colours look swapped on device, the layout is YV12.
     */
    private fun VideoFrame.toDownscaledJpeg(): ByteArray? {
        if (isCompressed || isCodecConfig) return null
        val w = width
        val h = height
        val ySize = w * h
        val expected = ySize * 3 / 2
        val src = buffer.duplicate().apply { rewind() }
        if (src.remaining() < expected) {
            Log.w(TAG, "frame ${w}x$h has ${src.remaining()} bytes; expected $expected")
            return null
        }
        val i420 = ByteArray(expected).also { src.get(it) }
        val nv21 = ByteArray(expected)
        System.arraycopy(i420, 0, nv21, 0, ySize)
        val quarter = ySize / 4
        for (i in 0 until quarter) {
            nv21[ySize + i * 2] = i420[ySize + quarter + i] // V
            nv21[ySize + i * 2 + 1] = i420[ySize + i] // U
        }
        val full = ByteArrayOutputStream().use { out ->
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (maxOf(w, h) <= MAX_SIDE) return full
        val bmp = BitmapFactory.decodeByteArray(full, 0, full.size) ?: return full
        val ratio = MAX_SIDE.toFloat() / maxOf(w, h)
        val scaled = Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bmp.recycle()
            scaled.recycle()
            out.toByteArray()
        }
    }

    private inner class ActivityTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { currentActivity = WeakReference(activity) }
        override fun onActivityResumed(activity: Activity) { currentActivity = WeakReference(activity) }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity?.get() === activity) currentActivity = null
        }
    }

    private companion object {
        const val TAG = "RealGlassesBackend"
        const val MAX_SIDE = 768
        const val JPEG_QUALITY = 70
        const val FRAME_RATE = 2
    }
}
