package com.yaser8541.autocallapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil

class DirectCallModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AutoCall/DirectCall"
    }

    override fun getName() = "DirectCall"

    @ReactMethod
    fun call(rawNumber: String, promise: Promise) {
        val phoneNumber = rawNumber.trim()
        Log.i(
            TAG,
            "call_entered rawNumber=$rawNumber trimmed=$phoneNumber hasCurrentActivity=${reactApplicationContext.currentActivity != null}"
        )

        if (phoneNumber.isEmpty()) {
            Log.e(TAG, "call_rejected invalid number")
            promise.reject("E_INVALID_NUMBER", "Phone number is empty")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            reactApplicationContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        Log.i(TAG, "call_permission_status granted=$hasPermission")

        if (!hasPermission) {
            promise.reject("E_PERMISSION_DENIED", "CALL_PHONE permission not granted")
            return
        }

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }

        val canResolve = callIntent.resolveActivity(reactApplicationContext.packageManager) != null
        Log.i(TAG, "call_resolve_activity canResolve=$canResolve")

        if (!canResolve) {
            promise.reject("E_NO_CALL_ACTIVITY", "No Android activity can handle ACTION_CALL")
            return
        }

        UiThreadUtil.runOnUiThread {
            try {
                val activity = reactApplicationContext.currentActivity
                val usedCurrentActivity = activity != null

                if (activity != null) {
                    activity.startActivity(callIntent)
                } else {
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    reactApplicationContext.startActivity(callIntent)
                }

                val result = Arguments.createMap().apply {
                    putString("action", Intent.ACTION_CALL)
                    putString("phoneNumber", phoneNumber)
                    putBoolean("usedCurrentActivity", usedCurrentActivity)
                    putString("timestamp", System.currentTimeMillis().toString())
                }

                Log.i(TAG, "call_start_activity_success usedCurrentActivity=$usedCurrentActivity")
                promise.resolve(result)
            } catch (error: SecurityException) {
                Log.e(TAG, "call_start_activity_security_exception", error)
                promise.reject("E_SECURITY_EXCEPTION", error)
            } catch (error: ActivityNotFoundException) {
                Log.e(TAG, "call_start_activity_not_found", error)
                promise.reject("E_ACTIVITY_NOT_FOUND", error)
            } catch (error: Throwable) {
                Log.e(TAG, "call_start_activity_unexpected", error)
                promise.reject("E_CALL_FAILED", error)
            }
        }
    }
}
