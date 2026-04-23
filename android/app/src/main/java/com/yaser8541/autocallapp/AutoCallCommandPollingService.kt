package com.yaser8541.autocallapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale

class AutoCallCommandPollingService : Service() {
    companion object {
        private const val TAG = "AutoCall/CommandService"
        private const val SERVER = "https://serverautocall-production.up.railway.app"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val MAX_AUTO_HANGUP_SECONDS = 600
        private const val MAX_SERVER_CALL_DURATION_SECONDS = 3600
        private const val MAX_TRACKED_PROCESSED_COMMAND_IDS = 500

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
        val message: String?,
        val url: String?,
        val appName: String?,
        val resolvedPackageName: String?,
        val durationSeconds: Int?,
        val enabled: Boolean?,
        val autoHangupSeconds: Int?,
        val scheduledAt: String?
    )

    private data class HttpResult(
        val code: Int,
        val body: String
    ) {
        fun isSuccessCode(): Boolean = code in 200..299
    }

    private val inFlightCommandIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val processedCommandIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

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

            val claimedCommand = claimNextCommand()
            Log.i(
                TAG,
                "background/runtime claim result commandId=${claimedCommand?.id ?: "null"}"
            )
            processClaimedCommand(claimedCommand)
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime poll failed", error)
        }
    }

    private fun ensureDeviceRegistered() {
        if (hasRegisteredDevice) {
            return
        }

        val deviceUid = currentDeviceUid()
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
        }
        val result = postJson("$SERVER/devices/register", payload)
        if (result.isSuccessCode()) {
            hasRegisteredDevice = true
            syncDeviceIdentityFromServerResponse(result.body)
            Log.i(TAG, "background/runtime register device success uid=$deviceUid")
        } else {
            Log.w(
                TAG,
                "background/runtime register device failed uid=$deviceUid code=${result.code}"
            )
        }
    }

    private fun sendHeartbeat() {
        val deviceUid = currentDeviceUid()
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
        }
        val result = postJson("$SERVER/devices/heartbeat", payload)
        if (result.isSuccessCode()) {
            syncDeviceIdentityFromServerResponse(result.body)
            return
        }
        Log.w(TAG, "background/runtime heartbeat failed uid=$deviceUid code=${result.code}")
    }

    private fun claimNextCommand(): ServerCallCommand? {
        val deviceUid = currentDeviceUid()
        val payload = JSONObject().apply {
            put("deviceUid", deviceUid)
        }
        Log.i(TAG, "background/runtime command claim request uid=$deviceUid")
        val result = postJson("$SERVER/commands/claim", payload)
        if (!result.isSuccessCode()) {
            Log.w(
                TAG,
                "background/runtime command claim failed uid=$deviceUid code=${result.code}"
            )
            return null
        }
        return parseClaimResponse(result.body, deviceUid)
    }

    private fun parseClaimResponse(rawJson: String, deviceUid: String): ServerCallCommand? {
        if (rawJson.isBlank()) {
            Log.i(TAG, "background/runtime command claim empty body uid=$deviceUid")
            return null
        }

        return try {
            val root = JSONObject(rawJson)
            val commandObject = root.optJSONObject("command")
            if (commandObject == null) {
                Log.i(TAG, "background/runtime command claim returned no command uid=$deviceUid")
                return null
            }

            val parsedCommand = parseCommand(commandObject)
            if (parsedCommand == null) {
                Log.w(TAG, "background/runtime command claim returned invalid command uid=$deviceUid")
                return null
            }
            Log.i(
                TAG,
                "background/runtime command claimed uid=$deviceUid commandId=${parsedCommand.id} status=executing"
            )
            parsedCommand
        } catch (error: Throwable) {
            Log.e(TAG, "failed to parse command claim response in runtime layer", error)
            null
        }
    }

    private fun parseCommand(item: JSONObject): ServerCallCommand? {
        val id = item.optString("id", "")
        val type = item.optString("type", "")
        if (id.isBlank() || type.isBlank()) {
            return null
        }

        return ServerCallCommand(
            id = id,
            action = if (item.isNull("action")) null else item.optString("action"),
            type = type,
            phoneNumber = if (item.isNull("phoneNumber")) {
                null
            } else {
                item.optString("phoneNumber").takeIf { value -> value.isNotBlank() }
            },
            message = if (item.isNull("message")) {
                null
            } else {
                item.optString("message").takeIf { value -> value.isNotBlank() }
            },
            url = parseUrl(item),
            appName = parseAppName(item),
            resolvedPackageName = parseResolvedPackageName(item),
            durationSeconds = parseDurationSeconds(item),
            enabled = parseAutoAnswerEnabled(item),
            autoHangupSeconds = parseAutoHangupSeconds(item),
            scheduledAt = if (item.isNull("scheduledAt")) null else item.optString("scheduledAt")
        )
    }

    private fun rememberProcessedCommandId(commandId: String) {
        synchronized(processedCommandIds) {
            if (processedCommandIds.contains(commandId)) {
                return
            }
            processedCommandIds.add(commandId)
            while (processedCommandIds.size > MAX_TRACKED_PROCESSED_COMMAND_IDS) {
                val iterator = processedCommandIds.iterator()
                if (!iterator.hasNext()) {
                    break
                }
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun hasLocalDuplicate(commandId: String): Boolean {
        if (processedCommandIds.contains(commandId)) {
            Log.i(
                TAG,
                "background/runtime duplicate command ignored (processed) commandId=$commandId"
            )
            return true
        }
        if (inFlightCommandIds.contains(commandId)) {
            Log.i(
                TAG,
                "background/runtime duplicate command ignored (in_flight) commandId=$commandId"
            )
            return true
        }
        return false
    }

    private fun processClaimedCommand(command: ServerCallCommand?) {
        if (command == null) {
            return
        }

        val commandId = command.id.trim()
        if (commandId.isBlank()) {
            Log.w(TAG, "background/runtime claimed command missing id")
            return
        }

        if (hasLocalDuplicate(commandId)) {
            return
        }

        rememberProcessedCommandId(commandId)
        val action = resolveAction(command)
        Log.i(
            TAG,
            "background/runtime command selected commandId=$commandId action=$action type=${command.type}"
        )
        when (action) {
            "end" -> dispatchEndCommand(command)
            "sms" -> dispatchSmsCommand(command)
            "auto_answer" -> dispatchAutoAnswerCommand(command)
            "open_app" -> dispatchOpenAppCommand(command)
            "open_url" -> dispatchOpenUrlCommand(command)
            "close_webview" -> dispatchCloseWebViewCommand(command)
            "return_to_autocall" -> dispatchReturnToAutoCallCommand(command)
            else -> dispatchCallCommand(command)
        }
    }

    private fun resolveAction(command: ServerCallCommand): String {
        val explicitAction = command.action?.lowercase(Locale.US)
        if (!explicitAction.isNullOrBlank()) {
            when (explicitAction) {
                "call",
                "end",
                "sms",
                "auto_answer",
                "open_app",
                "open_url",
                "close_webview",
                "return_to_autocall" -> return explicitAction
            }
        }
        return when {
            command.type.equals("END", ignoreCase = true) -> "end"
            command.type.equals("SMS", ignoreCase = true) -> "sms"
            command.type.equals("AUTO_ANSWER", ignoreCase = true) -> "auto_answer"
            command.type.equals("OPEN_APP", ignoreCase = true) -> "open_app"
            command.type.equals("OPEN_URL", ignoreCase = true) -> "open_url"
            command.type.equals("CLOSE_WEBVIEW", ignoreCase = true) -> "close_webview"
            command.type.equals("RETURN_TO_AUTOCALL", ignoreCase = true) -> "return_to_autocall"
            else -> "call"
        }
    }

    private fun dispatchAutoAnswerCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(TAG, "background/runtime command execution started commandId=${command.id} action=auto_answer")
            val enabled = command.enabled
            if (enabled == null) {
                Log.w(
                    TAG,
                    "background/runtime auto-answer command missing enabled commandId=${command.id}"
                )
                updateCommandStatus(command.id, "failed", "AUTO_ANSWER command missing enabled")
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=auto_answer result=failed")
                return false
            }

            if (enabled && !hasAutoAnswerPermissions()) {
                Log.w(
                    TAG,
                    "background/runtime auto-answer command missing permissions commandId=${command.id}"
                )
                updateCommandStatus(
                    command.id,
                    "failed",
                    "AUTO_ANSWER permissions missing: ANSWER_PHONE_CALLS/READ_PHONE_STATE"
                )
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=auto_answer result=failed")
                return false
            }

            AutoAnswerController.applyAutoAnswerSettings(
                context = applicationContext,
                enabled = enabled,
                requestedAutoHangupSeconds = if (enabled) {
                    command.autoHangupSeconds
                } else {
                    null
                }
            )
            updateCommandStatus(command.id, "executed")
            Log.i(
                TAG,
                "background/runtime auto-answer command executed commandId=${command.id} " +
                    "enabled=$enabled autoHangupSeconds=${command.autoHangupSeconds ?: "null"}"
            )
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=auto_answer result=executed")
            return true
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "background/runtime auto-answer command crash commandId=${command.id}",
                error
            )
            updateCommandStatus(
                command.id,
                "failed",
                error.message ?: "auto_answer_command_crash"
            )
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=auto_answer result=failed")
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchEndCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(TAG, "background/runtime command execution started commandId=${command.id} action=end")
            Log.i(
                TAG,
                "end command dispatched from background/runtime layer commandId=${command.id}"
            )

            val result = SimpleCallManager.endCurrentCall(applicationContext)
            if (result.ended) {
                updateCommandStatus(command.id, "executed")
                Log.i(TAG, "background/runtime end command executed commandId=${command.id}")
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=end result=executed")
                return true
            } else {
                updateCommandStatus(command.id, "failed", result.reason)
                Log.w(
                    TAG,
                    "background/runtime end command failed commandId=${command.id} " +
                        "reason=${result.reason} " +
                        "hasAnswerPhoneCallsPermission=${result.hasAnswerPhoneCallsPermission}"
                )
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=end result=failed")
                return false
            }
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime end command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed", error.message ?: "end_command_crash")
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=end result=failed")
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchCallCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(TAG, "background/runtime command execution started commandId=${command.id} action=call")
            val phoneNumber = command.phoneNumber
            if (phoneNumber.isNullOrBlank()) {
                Log.w(TAG, "background/runtime call command missing phone commandId=${command.id}")
                updateCommandStatus(command.id, "failed", "CALL command missing phoneNumber")
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=call result=failed")
                return false
            }

            val callResult = SimpleCallManager.startSimpleCall(
                context = applicationContext,
                rawPhoneNumber = phoneNumber,
                autoEndMs = null,
                activity = null
            )
            if (!callResult.success) {
                updateCommandStatus(
                    command.id,
                    "failed",
                    "${callResult.reason}: ${callResult.message}"
                )
                Log.w(
                    TAG,
                    "background/runtime call command failed commandId=${command.id} " +
                        "reason=${callResult.reason} message=${callResult.message}"
                )
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=call result=failed")
                return false
            }

            AutoAnswerController.onServerOutgoingCallStarted(
                applicationContext,
                command.durationSeconds
            )
            updateCommandStatus(command.id, "executed")
            Log.i(TAG, "background/runtime call command executed commandId=${command.id}")
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=call result=executed")
            return true
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime call command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed", error.message ?: "call_command_crash")
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=call result=failed")
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchSmsCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(TAG, "background/runtime command execution started commandId=${command.id} action=sms")
            val phoneNumber = command.phoneNumber
            if (phoneNumber.isNullOrBlank()) {
                Log.w(TAG, "background/runtime sms command missing phone commandId=${command.id}")
                updateCommandStatus(command.id, "failed", "SMS command missing phoneNumber")
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=sms result=failed")
                return false
            }

            val message = command.message
            if (message.isNullOrBlank()) {
                Log.w(TAG, "background/runtime sms command missing message commandId=${command.id}")
                updateCommandStatus(command.id, "failed", "SMS command missing message")
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=sms result=failed")
                return false
            }

            val smsResult = SimpleSmsManager.sendServerCommandSms(
                context = applicationContext,
                rawPhoneNumber = phoneNumber,
                rawMessage = message
            )
            if (!smsResult.success) {
                updateCommandStatus(command.id, "failed", smsResult.message)
                Log.w(
                    TAG,
                    "background/runtime sms command failed commandId=${command.id} " +
                        "reason=${smsResult.reason} message=${smsResult.message}"
                )
                Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=sms result=failed")
                return false
            }

            updateCommandStatus(command.id, "executed")
            Log.i(TAG, "background/runtime sms command executed commandId=${command.id}")
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=sms result=executed")
            return true
        } catch (error: AutoCallException) {
            val failureReason = error.message ?: "SEND_SMS permission denied"
            Log.w(
                TAG,
                "background/runtime sms command blocked commandId=${command.id} " +
                    "code=${error.code} message=$failureReason"
            )
            updateCommandStatus(command.id, "failed", failureReason)
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=sms result=failed")
            return false
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime sms command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed", error.message ?: "sms_command_crash")
            Log.i(TAG, "background/runtime command execution finished commandId=${command.id} action=sms result=failed")
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchOpenAppCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(
                TAG,
                "background/runtime command execution started commandId=${command.id} action=open_app"
            )
            val appName = command.appName
            val resolvedPackageName = command.resolvedPackageName
            if (appName.isNullOrBlank() && resolvedPackageName.isNullOrBlank()) {
                updateCommandStatus(
                    command.id,
                    "failed",
                    "OPEN_APP command missing appName/resolvedPackageName"
                )
                Log.w(
                    TAG,
                    "background/runtime OPEN_APP command missing appName/resolvedPackageName commandId=${command.id}"
                )
                Log.i(
                    TAG,
                    "background/runtime command execution finished commandId=${command.id} action=open_app result=failed"
                )
                return false
            }

            Log.i(
                TAG,
                "background/runtime OPEN_APP command received commandId=${command.id} " +
                    "appName=${appName ?: "null"} resolvedPackageName=${resolvedPackageName ?: "null"}"
            )

            val result = InstalledAppLauncher.openApp(
                context = applicationContext,
                appName = appName,
                resolvedPackageName = resolvedPackageName
            )

            if (!result.success) {
                val failureReason = result.message.ifBlank { "App not installed" }
                updateCommandStatus(command.id, "failed", failureReason)
                Log.w(
                    TAG,
                    "background/runtime OPEN_APP command failed commandId=${command.id} " +
                        "reason=${result.reason} message=${result.message} " +
                        "appName=${appName ?: "null"} packageName=${result.packageName ?: "null"} " +
                        "attemptedResolvedPackageName=${result.attemptedResolvedPackageName ?: "null"}"
                )
                Log.i(
                    TAG,
                    "background/runtime command execution finished commandId=${command.id} action=open_app result=failed"
                )
                return false
            }

            updateCommandStatus(command.id, "executed")
            Log.i(
                TAG,
                "background/runtime OPEN_APP command executed commandId=${command.id} " +
                    "appName=${appName ?: "null"} packageName=${result.packageName ?: "null"} " +
                    "matchedLabel=${result.matchedLabel ?: "null"}"
            )
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=open_app result=executed"
            )
            return true
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime OPEN_APP command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed", error.message ?: "App not installed")
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=open_app result=failed"
            )
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchOpenUrlCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(
                TAG,
                "background/runtime command execution started commandId=${command.id} action=open_url"
            )

            val rawUrl = command.url
            if (rawUrl.isNullOrBlank()) {
                updateCommandStatus(command.id, "failed", "OPEN_URL command missing url")
                Log.w(
                    TAG,
                    "background/runtime OPEN_URL command missing url commandId=${command.id}"
                )
                Log.i(
                    TAG,
                    "background/runtime command execution finished commandId=${command.id} action=open_url result=failed"
                )
                return false
            }

            Log.i(
                TAG,
                "background/runtime OPEN_URL command received commandId=${command.id} url=$rawUrl"
            )
            val result = InAppWebViewController.openUrl(
                context = applicationContext,
                rawUrl = rawUrl
            )
            if (!result.success) {
                updateCommandStatus(command.id, "failed", result.message)
                Log.w(
                    TAG,
                    "background/runtime OPEN_URL command failed commandId=${command.id} " +
                        "reason=${result.reason} message=${result.message}"
                )
                Log.i(
                    TAG,
                    "background/runtime command execution finished commandId=${command.id} action=open_url result=failed"
                )
                return false
            }

            updateCommandStatus(command.id, "executed")
            Log.i(
                TAG,
                "background/runtime OPEN_URL command executed commandId=${command.id} " +
                    "url=${result.url ?: "null"} replacedExisting=${result.replacedExisting}"
            )
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=open_url result=executed"
            )
            return true
        } catch (error: Throwable) {
            Log.e(TAG, "background/runtime OPEN_URL command crash commandId=${command.id}", error)
            updateCommandStatus(command.id, "failed", error.message ?: "open_url_command_crash")
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=open_url result=failed"
            )
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchCloseWebViewCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(
                TAG,
                "background/runtime command execution started commandId=${command.id} action=close_webview"
            )
            Log.i(
                TAG,
                "background/runtime CLOSE_WEBVIEW command received commandId=${command.id}"
            )

            val result = InAppWebViewController.closeWebView(applicationContext)
            if (!result.success) {
                updateCommandStatus(command.id, "failed", result.message)
                Log.w(
                    TAG,
                    "background/runtime CLOSE_WEBVIEW command failed commandId=${command.id} " +
                        "reason=${result.reason} message=${result.message}"
                )
                Log.i(
                    TAG,
                    "background/runtime command execution finished commandId=${command.id} action=close_webview result=failed"
                )
                return false
            }

            updateCommandStatus(command.id, "executed")
            if (result.noOp) {
                Log.i(
                    TAG,
                    "background/runtime CLOSE_WEBVIEW no-op commandId=${command.id} no active webview"
                )
            } else {
                Log.i(
                    TAG,
                    "background/runtime CLOSE_WEBVIEW command executed commandId=${command.id}"
                )
            }
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=close_webview result=executed"
            )
            return true
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "background/runtime CLOSE_WEBVIEW command crash commandId=${command.id}",
                error
            )
            updateCommandStatus(command.id, "failed", error.message ?: "close_webview_command_crash")
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=close_webview result=failed"
            )
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun dispatchReturnToAutoCallCommand(command: ServerCallCommand): Boolean {
        inFlightCommandIds.add(command.id)
        try {
            Log.i(
                TAG,
                "background/runtime command execution started commandId=${command.id} action=return_to_autocall"
            )
            Log.i(
                TAG,
                "background/runtime RETURN_TO_AUTOCALL command received commandId=${command.id}"
            )

            val result = AutoCallAppNavigator.returnToAutoCall(applicationContext)
            if (!result.success) {
                updateCommandStatus(command.id, "failed", result.message)
                Log.w(
                    TAG,
                    "background/runtime RETURN_TO_AUTOCALL command failed commandId=${command.id} " +
                        "reason=${result.reason} message=${result.message}"
                )
                Log.i(
                    TAG,
                    "background/runtime command execution finished commandId=${command.id} action=return_to_autocall result=failed"
                )
                return false
            }

            updateCommandStatus(command.id, "executed")
            if (result.noOp) {
                Log.i(
                    TAG,
                    "background/runtime RETURN_TO_AUTOCALL no-op commandId=${command.id} already in AutoCall"
                )
            } else {
                Log.i(
                    TAG,
                    "background/runtime RETURN_TO_AUTOCALL command executed commandId=${command.id} " +
                        "webViewWasOpen=${result.webViewWasOpen}"
                )
            }
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=return_to_autocall result=executed"
            )
            return true
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "background/runtime RETURN_TO_AUTOCALL command crash commandId=${command.id}",
                error
            )
            updateCommandStatus(
                command.id,
                "failed",
                error.message ?: "return_to_autocall_command_crash"
            )
            Log.i(
                TAG,
                "background/runtime command execution finished commandId=${command.id} action=return_to_autocall result=failed"
            )
            return false
        } finally {
            inFlightCommandIds.remove(command.id)
        }
    }

    private fun updateCommandStatus(
        commandId: String,
        status: String,
        failureReason: String? = null
    ) {
        val payload = JSONObject().apply {
            put("status", status)
            if (!failureReason.isNullOrBlank()) {
                put("failureReason", failureReason)
            }
        }
        Log.i(
            TAG,
            "background/runtime status update request commandId=$commandId status=$status failureReason=${failureReason ?: "null"}"
        )
        val result = postJson("$SERVER/commands/$commandId/status", payload)
        if (!result.isSuccessCode()) {
            Log.w(
                TAG,
                "background/runtime update command status failed commandId=$commandId " +
                    "status=$status failureReason=${failureReason ?: "null"} code=${result.code}"
            )
            return
        }
        Log.i(TAG, "background/runtime status update success commandId=$commandId status=$status")
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

    private fun parseAutoAnswerEnabled(item: JSONObject): Boolean? {
        if (!item.has("enabled") || item.isNull("enabled")) {
            return null
        }

        return try {
            when (val raw = item.get("enabled")) {
                is Boolean -> raw
                is String -> {
                    when (raw.trim().lowercase(Locale.US)) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseAutoHangupSeconds(item: JSONObject): Int? {
        if (item.isNull("autoHangupSeconds")) {
            return null
        }
        val raw = item.optDouble("autoHangupSeconds", Double.NaN)
        if (raw.isNaN() || raw <= 0.0) {
            return null
        }
        val normalized = raw.toInt()
        if (normalized <= 0) {
            return null
        }
        return normalized.coerceAtMost(MAX_AUTO_HANGUP_SECONDS)
    }

    private fun parseUrl(item: JSONObject): String? {
        if (item.isNull("url")) {
            return null
        }
        return item.optString("url").takeIf { value -> value.isNotBlank() }
    }

    private fun parseAppName(item: JSONObject): String? {
        if (item.isNull("appName")) {
            return null
        }
        return item.optString("appName").takeIf { value -> value.isNotBlank() }
    }

    private fun parseResolvedPackageName(item: JSONObject): String? {
        if (item.isNull("resolvedPackageName")) {
            return null
        }
        return item.optString("resolvedPackageName").takeIf { value -> value.isNotBlank() }
    }

    private fun hasAutoAnswerPermissions(): Boolean {
        val appContext = applicationContext
        val requiredPermissions = arrayOf(
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE
        )
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(
                appContext,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun currentDeviceUid(): String {
        return DeviceIdentityStore.getOrCreateDeviceUid(applicationContext)
    }

    private fun syncDeviceIdentityFromServerResponse(rawBody: String) {
        if (rawBody.isBlank()) {
            return
        }

        try {
            val root = JSONObject(rawBody)
            val device = root.optJSONObject("device") ?: return
            val serverDeviceUid = if (device.isNull("deviceUid")) null else device.optString("deviceUid")
            val serverDeviceName = if (device.isNull("deviceName")) null else device.optString("deviceName")
            DeviceIdentityStore.syncFromServer(
                context = applicationContext,
                deviceUid = serverDeviceUid,
                deviceName = serverDeviceName
            )
        } catch (error: Throwable) {
            Log.w(TAG, "background/runtime identity sync parse failed", error)
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
