package com.yaser8541.autocallapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.IllegalStateException
import java.net.URISyntaxException
import kotlin.math.max

data class ScreenMirrorServiceStartResult(
    val success: Boolean,
    val reason: String,
    val message: String
)

class ScreenMirrorService : Service() {
    companion object {
        private const val TAG = "AutoCall/ScreenMirrorService"
        private const val SERVER = "https://serverautocall-production.up.railway.app"
        private const val CHANNEL_ID = "autocall_screen_mirror"
        private const val CHANNEL_NAME = "AutoCall Screen Mirror"
        private const val NOTIFICATION_ID = 14002
        private const val ACTION_START = "com.yaser8541.autocallapp.screen_mirror.START"
        private const val ACTION_STOP = "com.yaser8541.autocallapp.screen_mirror.STOP"
        private const val EXTRA_STOP_REASON = "stop_reason"
        private const val JPEG_QUALITY = 50
        private const val TARGET_FRAME_INTERVAL_MS = 120L
        private const val MAX_FRAME_BYTES = 1572864 // 1.5MB

        fun startSharing(context: Context): ScreenMirrorServiceStartResult {
            if (!ScreenMirrorPermissionStore.hasPermission()) {
                return ScreenMirrorServiceStartResult(
                    success = false,
                    reason = "screen_mirror_permission_not_granted",
                    message = "Screen mirror permission is not granted"
                )
            }

            return try {
                val appContext = context.applicationContext
                val intent = Intent(appContext, ScreenMirrorService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                ScreenMirrorServiceStartResult(
                    success = true,
                    reason = "started",
                    message = "Screen mirror start requested"
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to request screen mirror service start", error)
                ScreenMirrorServiceStartResult(
                    success = false,
                    reason = "screen_mirror_service_start_failed",
                    message = error.message ?: "Failed to start screen mirror service"
                )
            }
        }

        fun stopSharing(context: Context, reason: String = "stopped_by_user") {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ScreenMirrorService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STOP_REASON, reason)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to request screen mirror stop", error)
            }
        }
    }

    private val stateLock = Any()
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var socket: Socket? = null
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0
    private var lastFrameSentAtMs: Long = 0L
    private var isStreaming: Boolean = false
    private var deviceUid: String = ""

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.i(TAG, "[SCREEN_MIRROR] MediaProjection stopped by system")
            emitScreenError("media_projection_stopped")
            stopStreamingInternal(
                stopReason = "media_projection_stopped",
                emitStoppedEvent = false,
                emitErrorState = true
            )
            stopSelf()
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        handleImageAvailable(reader)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deviceUid = DeviceIdentityStore.getOrCreateDeviceUid(applicationContext)
        createNotificationChannel()
        startServiceForeground()

        workerThread = HandlerThread("AutoCallScreenMirrorWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        ScreenMirrorRuntimeState.markIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                val reason = intent?.getStringExtra(EXTRA_STOP_REASON) ?: "stopped"
                stopStreamingInternal(
                    stopReason = reason,
                    emitStoppedEvent = true,
                    emitErrorState = false
                )
                stopSelf()
            }

            ACTION_START -> startStreamingInternal()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopStreamingInternal(
            stopReason = "service_destroyed",
            emitStoppedEvent = false,
            emitErrorState = false
        )
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        super.onDestroy()
    }

    private fun startServiceForeground() {
        val notification = buildNotification("AutoCall is sharing the screen")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStreamingInternal() {
        synchronized(stateLock) {
            if (isStreaming) {
                emitScreenStarted()
                return
            }
        }

        val permissionSnapshot = ScreenMirrorPermissionStore.takeForNewSession()
        if (permissionSnapshot == null) {
            failStart("screen_mirror_permission_not_granted")
            return
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            failStart("media_projection_manager_unavailable")
            return
        }

        val projection = try {
            projectionManager.getMediaProjection(
                permissionSnapshot.resultCode,
                permissionSnapshot.resultData
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission token rejected while starting mirror", error)
            failStart("screen_mirror_permission_not_granted", clearPermission = true)
            return
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to obtain MediaProjection", error)
            failStart("media_projection_create_failed")
            return
        }

        if (projection == null) {
            failStart("media_projection_create_failed")
            return
        }

        val metrics = resolveDisplayMetrics()
        val width = max(1, metrics.widthPixels)
        val height = max(1, metrics.heightPixels)
        val densityDpi = max(1, metrics.densityDpi)
        val localHandler = workerHandler
        if (localHandler == null) {
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
            failStart("screen_mirror_worker_not_ready")
            return
        }

        try {
            projection.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
            Log.i(TAG, "[SCREEN_MIRROR] MediaProjection callback registered")

            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )
            val display = projection.createVirtualDisplay(
                "AutoCallScreenMirror",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                localHandler
            ) ?: throw IllegalStateException("createVirtualDisplay returned null")

            reader.setOnImageAvailableListener(onImageAvailableListener, localHandler)

            synchronized(stateLock) {
                mediaProjection = projection
                virtualDisplay = display
                imageReader = reader
                streamWidth = width
                streamHeight = height
                lastFrameSentAtMs = 0L
                isStreaming = true
            }

            ensureSocketConnected()
            ScreenMirrorRuntimeState.markLive()
            emitScreenStarted()
            Log.i(TAG, "Screen mirror started width=$width height=$height densityDpi=$densityDpi")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start screen capture pipeline", error)
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
            failStart("screen_mirror_start_failed")
        }
    }

    private fun failStart(reason: String, clearPermission: Boolean = false) {
        if (clearPermission) {
            ScreenMirrorPermissionStore.clearPermission()
        }
        emitScreenError(reason)
        stopStreamingInternal(
            stopReason = reason,
            emitStoppedEvent = false,
            emitErrorState = true
        )
        stopSelf()
    }

    private fun stopStreamingInternal(
        stopReason: String,
        emitStoppedEvent: Boolean,
        emitErrorState: Boolean
    ) {
        val wasStreaming: Boolean
        synchronized(stateLock) {
            wasStreaming = isStreaming
            isStreaming = false
        }

        if (emitStoppedEvent && wasStreaming) {
            emitScreenStopped(stopReason)
        }

        cleanupCaptureResources()
        teardownSocketConnection()

        if (emitErrorState) {
            ScreenMirrorRuntimeState.markError(stopReason)
        } else {
            ScreenMirrorRuntimeState.markStopped(stopReason)
        }
    }

    private fun cleanupCaptureResources() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null

        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
        } catch (_: Throwable) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null
    }

    private fun ensureSocketConnected() {
        if (socket != null) {
            try {
                if (socket?.connected() == true) {
                    emitDeviceJoin()
                } else {
                    socket?.connect()
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to reconnect existing screen mirror socket", error)
            }
            return
        }

        val options = IO.Options().apply {
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
            reconnectionDelayMax = 5000
            timeout = 10000
            forceNew = true
        }

        val createdSocket = try {
            IO.socket(SERVER, options)
        } catch (error: URISyntaxException) {
            Log.e(TAG, "Invalid socket URL for screen mirror", error)
            return
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to create socket for screen mirror", error)
            return
        }

        createdSocket.on(Socket.EVENT_CONNECT) {
            emitDeviceJoin()
        }
        createdSocket.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString() ?: "unknown"
            Log.w(TAG, "Screen mirror socket disconnected reason=$reason")
        }
        createdSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val reason = args.firstOrNull()?.toString() ?: "unknown"
            Log.w(TAG, "Screen mirror socket connect error reason=$reason")
        }

        socket = createdSocket
        createdSocket.connect()
    }

    private fun teardownSocketConnection() {
        val activeSocket = socket ?: return
        try {
            activeSocket.disconnect()
            activeSocket.close()
        } catch (_: Throwable) {
        } finally {
            socket = null
        }
    }

    private fun emitDeviceJoin() {
        val activeSocket = socket ?: return
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
        }
        activeSocket.emit("device:join", payload)
    }

    private fun emitScreenStarted() {
        val activeSocket = socket ?: return
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
        }
        activeSocket.emit("screen:started", payload)
    }

    private fun emitScreenStopped(reason: String) {
        val activeSocket = socket ?: return
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
            put("reason", reason)
        }
        activeSocket.emit("screen:stopped", payload)
    }

    private fun emitScreenError(reason: String) {
        val activeSocket = socket
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
            put("reason", reason)
        }
        activeSocket?.emit("screen:error", payload)
    }

    private fun handleImageAvailable(reader: ImageReader) {
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            if (!isStreaming) {
                return
            }
            if (now - lastFrameSentAtMs < TARGET_FRAME_INTERVAL_MS) {
                val skippedImage = reader.acquireLatestImage()
                skippedImage?.close()
                return
            }
            lastFrameSentAtMs = now
        }

        val image = reader.acquireLatestImage() ?: return
        try {
            processImageFrame(image)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to process screen mirror frame", error)
        } finally {
            image.close()
        }
    }

    private fun processImageFrame(image: Image) {
        val localWidth = streamWidth
        val localHeight = streamHeight
        if (localWidth <= 0 || localHeight <= 0) {
            return
        }

        val planes = image.planes
        if (planes.isEmpty()) {
            return
        }

        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) {
            return
        }

        val rowPadding = rowStride - pixelStride * localWidth
        val bitmapWidth = localWidth + max(0, rowPadding) / pixelStride

        var paddedBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null
        val outputStream = ByteArrayOutputStream()
        try {
            paddedBitmap = Bitmap.createBitmap(bitmapWidth, localHeight, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            croppedBitmap = Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                localWidth,
                localHeight
            )

            val compressed = croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            if (!compressed) {
                return
            }

            val frameBytes = outputStream.toByteArray()
            if (frameBytes.size > MAX_FRAME_BYTES) {
                return
            }

            val frameBase64 = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
            emitFrame(frameBase64, localWidth, localHeight)
        } finally {
            try {
                outputStream.close()
            } catch (_: Throwable) {
            }
            croppedBitmap?.recycle()
            paddedBitmap?.recycle()
        }
    }

    private fun emitFrame(frameBase64: String, width: Int, height: Int) {
        val activeSocket = socket ?: return
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
            put("frameBase64", frameBase64)
            put("mimeType", "image/jpeg")
            put("width", width)
            put("height", height)
            put("timestamp", System.currentTimeMillis())
        }
        activeSocket.emit("screen:frame", payload)
    }

    private fun resolveDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            metrics.setTo(resources.displayMetrics)
            return metrics
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when AutoCall is sharing the device screen"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, pendingIntentFlags)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle("AutoCall")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }
}
