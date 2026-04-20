package com.yaser8541.autocallapp

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil

class AutoCallNativeModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AutoCall/NativeModule"
        private const val MAX_SERVER_CALL_DURATION_SECONDS = 3600
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
}
