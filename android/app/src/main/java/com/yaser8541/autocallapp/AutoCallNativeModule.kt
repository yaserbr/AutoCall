package com.yaser8541.autocallapp

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import java.net.HttpURLConnection
import java.net.URL

class AutoCallNativeModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AutoCall/NativeModule"
        private const val SERVER = "https://serverautocall-production.up.railway.app"
        private const val MAX_SERVER_CALL_DURATION_SECONDS = 3600
        private const val MIN_DOWNLOAD_SIZE_MB = 10
        private const val MAX_DOWNLOAD_SIZE_MB = 1000
        private const val DOWNLOAD_STREAM_CHUNK_BYTES = 64 * 1024
        private const val SEND_SMS_PERMISSION_DENIED_CODE = "E_SEND_SMS_PERMISSION_DENIED"
        private const val SEND_SMS_PERMISSION_DENIED_MESSAGE = "SEND_SMS permission denied"
    }

    override fun getName() = "AutoCallNative"

    @ReactMethod
    fun startOutgoingCall(phoneNumber: String, promise: Promise) {
        Log.i(TAG, "Outgoing call command received")
        UiThreadUtil.runOnUiThread {
            try {
                val result = CallExecutor.placeCall(
                    context = reactContext,
                    rawNumber = phoneNumber,
                    activity = reactContext.currentActivity
                )
                AutoAnswerController.onOutgoingCallStarted(reactContext)

                AutoAnswerStore.setLastEvent(reactContext, "Outgoing call started")
                Log.i(TAG, "Outgoing call started")

                val map = Arguments.createMap().apply {
                    putString("action", result.action)
                    putString("phoneNumber", result.phoneNumber)
                    putBoolean("usedCurrentActivity", result.usedCurrentActivity)
                    putDouble("timestamp", result.timestamp.toDouble())
                }
                promise.resolve(map)
            } catch (error: AutoCallException) {
                Log.e(TAG, "startOutgoingCall failed code=${error.code} message=${error.message}")
                AutoAnswerStore.setLastEvent(reactContext, "Outgoing call failed: ${error.message}")
                promise.reject(error.code, error.message, error)
            } catch (error: Throwable) {
                Log.e(TAG, "startOutgoingCall failed", error)
                AutoAnswerStore.setLastEvent(reactContext, "Outgoing call failed")
                promise.reject("E_OUTGOING_CALL_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun startSimpleCall(phoneNumber: String, autoEndMs: Double?, promise: Promise) {
        Log.i(TAG, "Simple call command received phone=$phoneNumber autoEndMs=$autoEndMs")
        UiThreadUtil.runOnUiThread {
            try {
                val result = SimpleCallManager.startSimpleCall(
                    context = reactContext,
                    rawPhoneNumber = phoneNumber,
                    autoEndMs = autoEndMs,
                    activity = reactContext.currentActivity
                )
                if (result.success) {
                    AutoAnswerController.onOutgoingCallStarted(reactContext)
                }

                val map = Arguments.createMap().apply {
                    putBoolean("success", result.success)
                    putString("reason", result.reason)
                    putString("message", result.message)
                    if (result.phoneNumber != null) {
                        putString("phoneNumber", result.phoneNumber)
                    } else {
                        putNull("phoneNumber")
                    }
                    if (result.autoEndMs != null) {
                        putDouble("autoEndMs", result.autoEndMs.toDouble())
                    } else {
                        putNull("autoEndMs")
                    }
                    putDouble("timestamp", result.timestamp.toDouble())
                }
                promise.resolve(map)
            } catch (error: Throwable) {
                Log.e(TAG, "startSimpleCall failed", error)
                val map = Arguments.createMap().apply {
                    putBoolean("success", false)
                    putString("reason", "start_simple_call_failed")
                    putString("message", error.message ?: "Unexpected error while starting simple call")
                    putNull("phoneNumber")
                    putNull("autoEndMs")
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                }
                promise.resolve(map)
            }
        }
    }

    @ReactMethod
    fun startServerCommandCall(phoneNumber: String, durationSeconds: Double?, promise: Promise) {
        Log.i(
            TAG,
            "Server command call received phone=$phoneNumber durationSeconds=$durationSeconds"
        )
        UiThreadUtil.runOnUiThread {
            try {
                val normalizedDurationSeconds = normalizeServerDurationSeconds(durationSeconds)
                val result = SimpleCallManager.startSimpleCall(
                    context = reactContext,
                    rawPhoneNumber = phoneNumber,
                    autoEndMs = null,
                    activity = reactContext.currentActivity
                )
                if (result.success) {
                    AutoAnswerController.onServerOutgoingCallStarted(
                        reactContext,
                        normalizedDurationSeconds
                    )
                }

                val map = Arguments.createMap().apply {
                    putBoolean("success", result.success)
                    putString("reason", result.reason)
                    putString("message", result.message)
                    if (result.phoneNumber != null) {
                        putString("phoneNumber", result.phoneNumber)
                    } else {
                        putNull("phoneNumber")
                    }
                    putNull("autoEndMs")
                    if (normalizedDurationSeconds != null) {
                        putDouble("durationSeconds", normalizedDurationSeconds.toDouble())
                    } else {
                        putNull("durationSeconds")
                    }
                    putDouble("timestamp", result.timestamp.toDouble())
                }
                promise.resolve(map)
            } catch (error: Throwable) {
                Log.e(TAG, "startServerCommandCall failed", error)
                val map = Arguments.createMap().apply {
                    putBoolean("success", false)
                    putString("reason", "start_server_command_call_failed")
                    putString(
                        "message",
                        error.message ?: "Unexpected error while starting server command call"
                    )
                    putNull("phoneNumber")
                    putNull("autoEndMs")
                    putNull("durationSeconds")
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                }
                promise.resolve(map)
            }
        }
    }

    @ReactMethod
    fun startServerCommandSms(phoneNumber: String, message: String, promise: Promise) {
        Log.i(
            TAG,
            "Server command SMS received phone=$phoneNumber messageLength=${message.length}"
        )
        UiThreadUtil.runOnUiThread {
            try {
                val result = SimpleSmsManager.sendServerCommandSms(
                    context = reactContext,
                    rawPhoneNumber = phoneNumber,
                    rawMessage = message
                )

                val map = Arguments.createMap().apply {
                    putBoolean("success", result.success)
                    putString("reason", result.reason)
                    putString("message", result.message)
                    if (result.phoneNumber != null) {
                        putString("phoneNumber", result.phoneNumber)
                    } else {
                        putNull("phoneNumber")
                    }
                    if (result.textLength != null) {
                        putDouble("textLength", result.textLength.toDouble())
                    } else {
                        putNull("textLength")
                    }
                    putDouble("timestamp", result.timestamp.toDouble())
                }
                promise.resolve(map)
            } catch (error: AutoCallException) {
                Log.e(TAG, "startServerCommandSms failed code=${error.code} message=${error.message}")
                val map = Arguments.createMap().apply {
                    putBoolean("success", false)
                    putString(
                        "reason",
                        if (error.code == SEND_SMS_PERMISSION_DENIED_CODE) {
                            "permission_denied"
                        } else {
                            "start_server_command_sms_failed"
                        }
                    )
                    putString(
                        "message",
                        if (error.code == SEND_SMS_PERMISSION_DENIED_CODE) {
                            SEND_SMS_PERMISSION_DENIED_MESSAGE
                        } else {
                            error.message ?: "Unexpected error while sending server command SMS"
                        }
                    )
                    putNull("phoneNumber")
                    putNull("textLength")
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                }
                promise.resolve(map)
            } catch (error: Throwable) {
                Log.e(TAG, "startServerCommandSms failed", error)
                val map = Arguments.createMap().apply {
                    putBoolean("success", false)
                    putString("reason", "start_server_command_sms_failed")
                    putString(
                        "message",
                        error.message ?: "Unexpected error while sending server command SMS"
                    )
                    putNull("phoneNumber")
                    putNull("textLength")
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                }
                promise.resolve(map)
            }
        }
    }

    @ReactMethod
    fun downloadDataForCommand(downloadSizeMb: Double, promise: Promise) {
        Thread {
            try {
                val normalizedDownloadSizeMb = normalizeDownloadSizeMb(downloadSizeMb)
                if (normalizedDownloadSizeMb == null) {
                    promise.resolve(
                        buildDownloadDataResultMap(
                            success = false,
                            reason = "invalid_download_size_mb",
                            message = "downloadSizeMb must be an integer between $MIN_DOWNLOAD_SIZE_MB and $MAX_DOWNLOAD_SIZE_MB",
                            downloadSizeMb = null,
                            durationSeconds = null
                        )
                    )
                    return@Thread
                }

                val durationSeconds = performDummyDownload(normalizedDownloadSizeMb)
                promise.resolve(
                    buildDownloadDataResultMap(
                        success = true,
                        reason = "download_completed",
                        message = "DOWNLOAD_DATA completed",
                        downloadSizeMb = normalizedDownloadSizeMb,
                        durationSeconds = durationSeconds
                    )
                )
            } catch (error: Throwable) {
                Log.e(TAG, "downloadDataForCommand failed", error)
                promise.resolve(
                    buildDownloadDataResultMap(
                        success = false,
                        reason = "download_failed",
                        message = error.message ?: "Failed to download dummy data",
                        downloadSizeMb = null,
                        durationSeconds = null
                    )
                )
            }
        }.start()
    }

    @ReactMethod
    fun enableAutoAnswer(config: ReadableMap?, promise: Promise) {
        try {
            val requestedSeconds: Int? = if (
                config != null &&
                config.hasKey("autoHangupSeconds") &&
                !config.isNull("autoHangupSeconds")
            ) {
                config.getInt("autoHangupSeconds")
            } else {
                null
            }

            AutoAnswerController.applyAutoAnswerSettings(
                context = reactContext,
                enabled = true,
                requestedAutoHangupSeconds = requestedSeconds
            )
            Log.i(TAG, "Auto answer enabled from native module")

            promise.resolve(buildStatusMap())
        } catch (error: Throwable) {
            Log.e(TAG, "enableAutoAnswer failed", error)
            promise.reject("E_ENABLE_AUTO_ANSWER_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun disableAutoAnswer(promise: Promise) {
        try {
            AutoAnswerController.applyAutoAnswerSettings(
                context = reactContext,
                enabled = false,
                requestedAutoHangupSeconds = null
            )
            Log.i(TAG, "Auto answer disabled from native module")
            promise.resolve(buildStatusMap())
        } catch (error: Throwable) {
            Log.e(TAG, "disableAutoAnswer failed", error)
            promise.reject("E_DISABLE_AUTO_ANSWER_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getAutoAnswerStatus(promise: Promise) {
        try {
            promise.resolve(buildStatusMap())
        } catch (error: Throwable) {
            Log.e(TAG, "getAutoAnswerStatus failed", error)
            promise.reject("E_GET_AUTO_ANSWER_STATUS_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getDeviceIdentity(promise: Promise) {
        try {
            val snapshot = DeviceIdentityStore.snapshot(reactContext)
            val map = Arguments.createMap().apply {
                putString("deviceUid", snapshot.deviceUid)
                putString("deviceName", snapshot.deviceName)
            }
            promise.resolve(map)
        } catch (error: Throwable) {
            Log.e(TAG, "getDeviceIdentity failed", error)
            promise.reject("E_GET_DEVICE_IDENTITY_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun endCurrentCall(promise: Promise) {
        try {
            Log.i(TAG, "Ending current call")
            val result = SimpleCallManager.endCurrentCall(reactContext)
            val map = Arguments.createMap().apply {
                putBoolean("ended", result.ended)
                putString("reason", result.reason)
                putBoolean("hasAnswerPhoneCallsPermission", result.hasAnswerPhoneCallsPermission)
            }
            Log.i(
                TAG,
                "endCurrentCall result ended=${result.ended} reason=${result.reason} " +
                    "hasAnswerPhoneCallsPermission=${result.hasAnswerPhoneCallsPermission}"
            )
            promise.resolve(map)
        } catch (error: Throwable) {
            Log.e(TAG, "endCurrentCall failed", error)
            promise.reject("E_END_CURRENT_CALL_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun openInAppWebView(url: String, promise: Promise) {
        try {
            Log.i(TAG, "OPEN_URL received in native module url=$url")
            val result = InAppWebViewController.openUrl(
                context = reactContext,
                rawUrl = url
            )
            promise.resolve(buildWebViewCommandResultMap(result))
        } catch (error: Throwable) {
            Log.e(TAG, "openInAppWebView failed", error)
            promise.reject("E_OPEN_WEBVIEW_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun closeInAppWebView(promise: Promise) {
        try {
            Log.i(TAG, "CLOSE_WEBVIEW received in native module")
            val result = InAppWebViewController.closeWebView(reactContext)
            promise.resolve(buildWebViewCommandResultMap(result))
        } catch (error: Throwable) {
            Log.e(TAG, "closeInAppWebView failed", error)
            promise.reject("E_CLOSE_WEBVIEW_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getInAppWebViewState(promise: Promise) {
        try {
            val snapshot = InAppWebViewController.snapshot()
            promise.resolve(buildWebViewStateMap(snapshot))
        } catch (error: Throwable) {
            Log.e(TAG, "getInAppWebViewState failed", error)
            promise.reject("E_GET_WEBVIEW_STATE_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun takePendingScreenMirrorStartCommandId(promise: Promise) {
        try {
            promise.resolve(ScreenMirrorStartCommandStore.takePendingStartCommandId())
        } catch (error: Throwable) {
            Log.e(TAG, "takePendingScreenMirrorStartCommandId failed", error)
            promise.reject("E_PENDING_SCREEN_MIRROR_COMMAND_READ_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun openInstalledApp(appName: String, resolvedPackageName: String?, promise: Promise) {
        Log.i(
            TAG,
            "OPEN_APP received in native module appName=$appName resolvedPackageName=${resolvedPackageName ?: "null"}"
        )
        UiThreadUtil.runOnUiThread {
            try {
                val result = InstalledAppLauncher.openApp(
                    context = reactContext,
                    appName = appName,
                    resolvedPackageName = resolvedPackageName
                )
                promise.resolve(buildOpenAppResultMap(result))
            } catch (error: Throwable) {
                Log.e(TAG, "openInstalledApp failed", error)
                promise.reject("E_OPEN_APP_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun returnToAutoCall(promise: Promise) {
        Log.i(TAG, "RETURN_TO_AUTOCALL received in native module")
        try {
            val result = AutoCallAppNavigator.returnToAutoCall(
                context = reactContext
            )
            promise.resolve(buildReturnToAutoCallResultMap(result))
        } catch (error: Throwable) {
            Log.e(TAG, "returnToAutoCall failed", error)
            promise.reject("E_RETURN_TO_AUTOCALL_FAILED", error.message, error)
        }
    }

    private fun buildStatusMap() = Arguments.createMap().apply {
        val snapshot = AutoAnswerStore.snapshot(reactContext)
        putBoolean("enabled", snapshot.enabled)
        putInt("autoHangupSeconds", snapshot.autoHangupSeconds)
        putBoolean("hangupScheduled", AutoAnswerController.isHangupScheduled())
        putString("lastEvent", snapshot.lastEvent)
        putDouble("lastEventAt", snapshot.lastEventAt.toDouble())
    }

    private fun normalizeServerDurationSeconds(durationSeconds: Double?): Int? {
        if (durationSeconds == null || durationSeconds.isNaN() || durationSeconds <= 0.0) {
            return null
        }
        val normalized = durationSeconds.toInt()
        if (normalized <= 0) {
            return null
        }
        return normalized.coerceAtMost(MAX_SERVER_CALL_DURATION_SECONDS)
    }

    private fun normalizeDownloadSizeMb(downloadSizeMb: Double?): Int? {
        if (downloadSizeMb == null || downloadSizeMb.isNaN()) {
            return null
        }
        if (downloadSizeMb % 1.0 != 0.0) {
            return null
        }
        val normalized = downloadSizeMb.toInt()
        if (normalized < MIN_DOWNLOAD_SIZE_MB || normalized > MAX_DOWNLOAD_SIZE_MB) {
            return null
        }
        return normalized
    }

    private fun performDummyDownload(downloadSizeMb: Int): Int {
        var connection: HttpURLConnection? = null
        try {
            val startNanos = System.nanoTime()
            val targetUrl = "$SERVER/dummy-download?mb=$downloadSizeMb"
            connection = URL(targetUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/octet-stream")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("DOWNLOAD_DATA request failed with code=$responseCode")
            }

            val buffer = ByteArray(DOWNLOAD_STREAM_CHUNK_BYTES)
            connection.inputStream.use { input ->
                while (true) {
                    val readBytes = input.read(buffer)
                    if (readBytes == -1) {
                        break
                    }
                }
            }

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            return ((elapsedMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildWebViewStateMap(snapshot: InAppWebViewController.WebViewState) =
        Arguments.createMap().apply {
            putBoolean("isOpen", snapshot.isOpen)
            if (snapshot.currentUrl != null) {
                putString("currentUrl", snapshot.currentUrl)
            } else {
                putNull("currentUrl")
            }
        }

    private fun buildWebViewCommandResultMap(result: InAppWebViewController.WebViewCommandResult) =
        Arguments.createMap().apply {
            putBoolean("success", result.success)
            putString("reason", result.reason)
            putString("message", result.message)
            if (result.url != null) {
                putString("url", result.url)
            } else {
                putNull("url")
            }
            putBoolean("replacedExisting", result.replacedExisting)
            putBoolean("closed", result.closed)
            putBoolean("noOp", result.noOp)
            putBoolean("isOpen", result.state.isOpen)
            if (result.state.currentUrl != null) {
                putString("currentUrl", result.state.currentUrl)
            } else {
                putNull("currentUrl")
            }
        }

    private fun buildOpenAppResultMap(result: InstalledAppLauncher.OpenAppResult) =
        Arguments.createMap().apply {
            putBoolean("success", result.success)
            putString("reason", result.reason)
            putString("message", result.message)
            if (result.appName != null) {
                putString("appName", result.appName)
            } else {
                putNull("appName")
            }
            if (result.packageName != null) {
                putString("packageName", result.packageName)
            } else {
                putNull("packageName")
            }
            if (result.matchedLabel != null) {
                putString("matchedLabel", result.matchedLabel)
            } else {
                putNull("matchedLabel")
            }
            if (result.attemptedResolvedPackageName != null) {
                putString("attemptedResolvedPackageName", result.attemptedResolvedPackageName)
            } else {
                putNull("attemptedResolvedPackageName")
            }
        }

    private fun buildReturnToAutoCallResultMap(result: AutoCallAppNavigator.ReturnToAutoCallResult) =
        Arguments.createMap().apply {
            putBoolean("success", result.success)
            putString("reason", result.reason)
            putString("message", result.message)
            putBoolean("noOp", result.noOp)
            putBoolean("webViewWasOpen", result.webViewWasOpen)
        }

    private fun buildDownloadDataResultMap(
        success: Boolean,
        reason: String,
        message: String,
        downloadSizeMb: Int?,
        durationSeconds: Int?
    ) = Arguments.createMap().apply {
        putBoolean("success", success)
        putString("reason", reason)
        putString("message", message)
        if (downloadSizeMb != null) {
            putDouble("downloadSizeMb", downloadSizeMb.toDouble())
        } else {
            putNull("downloadSizeMb")
        }
        if (durationSeconds != null) {
            putDouble("downloadDurationSeconds", durationSeconds.toDouble())
        } else {
            putNull("downloadDurationSeconds")
        }
    }
}
