package com.yaser8541.autocallapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone

class AutoCallCommandPollingService : Service() {
    companion object {
        private const val TAG = "AutoCall/CommandService"
        private const val SERVER = "https://serverautocall-production.up.railway.app"
        private const val DEVICE_UID = "device_123"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val MAX_SERVER_CALL_DURATION_SECONDS = 3600
        private const val RIYADH_TIMEZONE_ID = "Asia/Riyadh"

        private const val CHANNEL_ID = "autocall_background_command_polling"
        private const val CHANNEL_NAME = "AutoCall Background Runtime"
        private const val NOTIFICATION_ID = 14001

        fun start(context: Context) {
            val appContext = context.applicationContext
            val serviceIntent = Intent(appContext, AutoCallCommandPollingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent)
                } else {
                    appContext.startService(serviceIntent)
                }
                Log.i(TAG, "background runtime service start requested")
            } catch (error: Throwable) {
                Log.e(TAG, "failed to start background runtime service", error)
            }
        }
    }

    private data class ServerCallCommand(
        val id: String,
        val action: String?,
        val type: String,
        val phoneNumber: String?,
        val durationSeconds: Int?,
        val scheduledAt: String?
    )

    private data class HttpResult(
        val code: Int,
        val body: String
    ) {
        fun isSuccessCode(): Boolean = code in 200..299
    }

    private val inFlightCommandIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val executedCommandIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var destroyed = false

    @Volatile
    private var hasRegisteredDevice = false

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollCommandsInBackground()
            if (!destroyed) {
                workerHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Listening for server commands")
        )

        workerThread = HandlerThread("AutoCallCommandPollingService").apply { start() }
        workerHandler = Handler(workerThread.looper)
        workerHandler.post(pollRunnable)
        Log.i(TAG, "background runtime service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::workerHandler.isInitialized) {
            workerHandler.removeCallbacks(pollRunnable)
            workerHandler.post(pollRunnable)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (::workerHandler.isInitialized) {
            workerHandler.removeCallbacksAndMessages(null)
        }
        if (::workerThread.isInitialized) {
            workerThread.quitSafely()
        }
        Log.i(TAG, "background runtime service stopped")
        super.onDestroy()
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
            description = "Keeps AutoCall server command polling active in background"
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

    private fun pollCommandsInBackground() {
        try {
            ensureDeviceRegistered()
            sendHeartbeat()

            val commands = fetchPendingCommands()
            Log.i(
                TAG,
                "server command poll received in background/runtime layer count=${commands.size}"
            )
            processCommands(commands)
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime poll failed", error)
        }
    }

    private fun ensureDeviceRegistered() {
        if (hasRegisteredDevice) {
            return
        }

        val payload = JSONObject().apply {
            put("deviceUid", DEVICE_UID)
        }
        val result = postJson("$SERVER/devices/register", payload)
        if (result.isSuccessCode()) {
            hasRegisteredDevice = true
            Log.i(TAG, "background/runtime register device success")
        } else {
            Log.w(TAG, "background/runtime register device failed code=${result.code}")
        }
    }

    private fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("deviceUid", DEVICE_UID)
        }
        val result = postJson("$SERVER/devices/heartbeat", payload)
        if (!result.isSuccessCode()) {
            Log.w(TAG, "background/runtime heartbeat failed code=${result.code}")
        }
    }

    private fun fetchPendingCommands(): List<ServerCallCommand> {
        val encodedDeviceUid = URLEncoder.encode(
            DEVICE_UID,
            StandardCharsets.UTF_8.toString()
        )
        val result = get("$SERVER/commands?deviceUid=$encodedDeviceUid&status=pending")
        if (!result.isSuccessCode()) {
            Log.w(TAG, "background/runtime command poll failed code=${result.code}")
            return emptyList()
        }
        return parseCommands(result.body)
    }

    private fun parseCommands(rawJson: String): List<ServerCallCommand> {
        if (rawJson.isBlank()) {
            return emptyList()
        }

        return try {
            val array = JSONArray(rawJson)
            val parsed = mutableListOf<ServerCallCommand>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                val type = item.optString("type", "")
                if (id.isBlank() || type.isBlank()) {
                    continue
                }

                parsed.add(
                    ServerCallCommand(
                        id = id,
                        action = if (item.isNull("action")) null else item.optString("action"),
                        type = type,
                        phoneNumber = if (item.isNull("phoneNumber")) {
                            null
                        } else {
                            item.optString("phoneNumber").takeIf { value -> value.isNotBlank() }
                        },
                        durationSeconds = parseDurationSeconds(item),
                        scheduledAt = if (item.isNull("scheduledAt")) null else item.optString("scheduledAt")
                    )
                )
            }
            parsed
        } catch (error: Throwable) {
            Log.e(TAG, "failed to parse server commands in runtime layer", error)
            emptyList()
        }
    }

    private fun processCommands(commands: List<ServerCallCommand>) {
        val nowMs = System.currentTimeMillis()
        val sortedCommands = commands.sortedBy { commandSortKey(it) }

        for (command in sortedCommands) {
            if (executedCommandIds.contains(command.id)) {
                Log.i(TAG, "Already executed, skipping commandId=${command.id}")
                continue
            }
            if (inFlightCommandIds.contains(command.id)) {
                continue
            }

            if (!isCommandDue(command, nowMs)) {
                Log.i(
                    TAG,
                    "Skipping scheduled command (not yet time) commandId=${command.id} " +
                        "scheduledAt=${command.scheduledAt ?: "null"}"
                )
                continue
            }

            val action = resolveAction(command)
            Log.i(
                TAG,
                "Executing scheduled command commandId=${command.id} action=$action"
            )
            val success = when (action) {
                "end" -> dispatchEndCommand(command)
                else -> dispatchCallCommand(command)
            }
            if (success) {
                executedCommandIds.add(command.id)
            }
        }
    }

    private fun resolveAction(command: ServerCallCommand): String {
        val explicitAction = command.action?.lowercase(Locale.US)
        if (!explicitAction.isNullOrBlank()) {
            return explicitAction
        }
        return if (command.type.equals("END", ignoreCase = true)) "end" else "call"
    }

    private fun dispatchEndCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            updateCommandStatus(command.id, "executing")
            Log.i(
                TAG,
                "end command dispatched from background/runtime layer commandId=${command.id}"
            )

            val result = SimpleCallManager.endCurrentCall(applicationContext)
            if (result.ended) {
                updateCommandStatus(command.id, "executed")
                Log.i(TAG, "background/runtime end command executed commandId=${command.id}")
                return true
            } else {
                updateCommandStatus(command.id, "failed")
                Log.w(
                    TAG,
                    "background/runtime end command failed commandId=${command.id} " +
                        "reason=${result.reason} " +
                        "hasAnswerPhoneCallsPermission=${result.hasAnswerPhoneCallsPermission}"
                )
                return false
            }
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime end command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed")
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchCallCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            val phoneNumber = command.phoneNumber
            if (phoneNumber.isNullOrBlank()) {
                Log.w(TAG, "background/runtime call command missing phone commandId=${command.id}")
                updateCommandStatus(command.id, "failed")
                return false
            }

            updateCommandStatus(command.id, "executing")
            val callResult = SimpleCallManager.startSimpleCall(
                context = applicationContext,
                rawPhoneNumber = phoneNumber,
                autoEndMs = null,
                activity = null
            )
            if (!callResult.success) {
                updateCommandStatus(command.id, "failed")
                Log.w(
                    TAG,
                    "background/runtime call command failed commandId=${command.id} " +
                        "reason=${callResult.reason} message=${callResult.message}"
                )
                return false
            }

            AutoAnswerController.onServerOutgoingCallStarted(
                applicationContext,
                command.durationSeconds
            )
            updateCommandStatus(command.id, "executed")
            Log.i(TAG, "background/runtime call command executed commandId=${command.id}")
            return true
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime call command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed")
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String) {
        val payload = JSONObject().apply {
            put("status", status)
        }
        val result = postJson("$SERVER/commands/$commandId/status", payload)
        if (!result.isSuccessCode()) {
            Log.w(
                TAG,
                "background/runtime update command status failed commandId=$commandId " +
                    "status=$status code=${result.code}"
            )
        }
    }

    private fun parseDurationSeconds(item: JSONObject): Int? {
        if (item.isNull("durationSeconds")) {
            return null
        }
        val raw = item.optDouble("durationSeconds", Double.NaN)
        if (raw.isNaN() || raw <= 0.0) {
            return null
        }
        val normalized = raw.toInt()
        if (normalized <= 0) {
            return null
        }
        return normalized.coerceAtMost(MAX_SERVER_CALL_DURATION_SECONDS)
    }

    private fun commandSortKey(command: ServerCallCommand): Long {
        if (command.scheduledAt.isNullOrBlank()) {
            return 0L
        }
        return parseScheduledAtMs(command.scheduledAt) ?: 0L
    }

    private fun isCommandDue(command: ServerCallCommand, nowMs: Long): Boolean {
        if (command.scheduledAt.isNullOrBlank()) {
            return true
        }
        val scheduledAtMs = parseScheduledAtMs(command.scheduledAt)
        if (scheduledAtMs == null) {
            Log.w(
                TAG,
                "Unparseable scheduledAt; executing immediately to avoid stuck command " +
                    "commandId=${command.id} scheduledAt=${command.scheduledAt}"
            )
            return true
        }
        return nowMs >= scheduledAtMs
    }

    private fun parseScheduledAtMs(scheduledAt: String?): Long? {
        if (scheduledAt.isNullOrBlank()) {
            return null
        }

        val raw = scheduledAt.trim()
        if (raw.isEmpty()) {
            return null
        }

        raw.toLongOrNull()?.let { numeric ->
            return if (raw.length >= 13) {
                numeric
            } else {
                numeric * 1000L
            }
        }

        val normalized = raw.replace('\u00A0', ' ')
        val timezoneAwarePatterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        for (pattern in timezoneAwarePatterns) {
            parseWithPattern(normalized, pattern, null)?.let { parsedMs ->
                return parsedMs
            }
        }

        val riyadhTimeZone = TimeZone.getTimeZone(RIYADH_TIMEZONE_ID)
        val riyadhLocalPatterns = arrayOf(
            "dd/MM/yyyy, HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy, HH:mm",
            "dd/MM/yyyy HH:mm"
        )
        for (pattern in riyadhLocalPatterns) {
            parseWithPattern(normalized, pattern, riyadhTimeZone)?.let { parsedMs ->
                return parsedMs
            }
        }

        return null
    }

    private fun parseWithPattern(
        value: String,
        pattern: String,
        timeZone: TimeZone?
    ): Long? {
        return try {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                if (timeZone != null) {
                    this.timeZone = timeZone
                }
            }
            parser.parse(value)?.time
        } catch (_: Throwable) {
            null
        }
    }

    private fun get(url: String): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false

            val code = connection.responseCode
            val body = readResponseBody(connection, code)
            HttpResult(code, body)
        } catch (error: Throwable) {
            Log.e(TAG, "runtime GET failed url=$url", error)
            HttpResult(-1, "")
        } finally {
            connection?.disconnect()
        }
    }

    private fun postJson(url: String, payload: JSONObject): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { output ->
                output.write(bytes)
                output.flush()
            }

            val code = connection.responseCode
            val body = readResponseBody(connection, code)
            HttpResult(code, body)
        } catch (error: Throwable) {
            Log.e(TAG, "runtime POST failed url=$url payload=$payload", error)
            HttpResult(-1, "")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readResponseBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readText()
        }
    }
}
