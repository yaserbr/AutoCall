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
    fun enableAutoAnswer(config: ReadableMap?, promise: Promise) {
        try {
            val requestedSeconds = if (
                config != null &&
                config.hasKey("autoHangupSeconds") &&
                !config.isNull("autoHangupSeconds")
            ) {
                config.getInt("autoHangupSeconds")
            } else {
                AutoAnswerStore.getAutoHangupSeconds(reactContext)
            }

            val normalizedSeconds = requestedSeconds.coerceIn(1, 600)
            AutoAnswerStore.setAutoHangupSeconds(reactContext, normalizedSeconds)
            AutoAnswerStore.setEnabled(reactContext, true)
            AutoAnswerStore.setLastEvent(reactContext, "Auto answer enabled")
            Log.i(TAG, "Auto answer enabled")

            promise.resolve(buildStatusMap())
        } catch (error: Throwable) {
            Log.e(TAG, "enableAutoAnswer failed", error)
            promise.reject("E_ENABLE_AUTO_ANSWER_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun disableAutoAnswer(promise: Promise) {
        try {
            AutoAnswerStore.setEnabled(reactContext, false)
            AutoAnswerController.onAutoAnswerDisabled(reactContext)
            AutoAnswerStore.setLastEvent(reactContext, "Auto answer disabled")
            Log.i(TAG, "Auto answer disabled")
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
}
