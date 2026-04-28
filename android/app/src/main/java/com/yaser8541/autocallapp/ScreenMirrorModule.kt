package com.yaser8541.autocallapp

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class ScreenMirrorModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    companion object {
        private const val TAG = "AutoCall/ScreenMirrorModule"
        private const val REQUEST_CODE_SCREEN_CAPTURE = 40112
    }

    @Volatile
    private var permissionPromise: Promise? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName(): String = "ScreenMirrorModule"

    @ReactMethod
    fun requestScreenMirrorPermission(promise: Promise) {
        ScreenMirrorAutomationState.endPermissionFlow()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            promise.resolve(
                buildResultMap(
                    success = false,
                    reason = "media_projection_not_supported",
                    message = "Screen mirroring is not supported on this Android version"
                )
            )
            return
        }

        val hostActivity = reactContext.currentActivity
        if (hostActivity == null) {
            promise.resolve(
                buildResultMap(
                    success = false,
                    reason = "screen_mirror_activity_unavailable",
                    message = "Open AutoCall app screen to enable screen mirroring"
                )
            )
            return
        }

        val projectionManager =
            reactContext.getSystemService(MediaProjectionManager::class.java)
        if (projectionManager == null) {
            promise.resolve(
                buildResultMap(
                    success = false,
                    reason = "media_projection_manager_unavailable",
                    message = "MediaProjection manager is not available"
                )
            )
            return
        }

        permissionPromise?.resolve(
            buildResultMap(
                success = false,
                reason = "screen_mirror_request_interrupted",
                message = "A new screen mirror permission request started"
            )
        )

        permissionPromise = promise
        try {
            ScreenMirrorAutomationState.beginPermissionFlow()
            val permissionIntent = projectionManager.createScreenCaptureIntent()
            hostActivity.startActivityForResult(permissionIntent, REQUEST_CODE_SCREEN_CAPTURE)
        } catch (error: Throwable) {
            ScreenMirrorAutomationState.endPermissionFlow()
            permissionPromise = null
            promise.resolve(
                buildResultMap(
                    success = false,
                    reason = "screen_mirror_permission_request_failed",
                    message = error.message ?: "Failed to open screen share permission dialog"
                )
            )
        }
    }

    @ReactMethod
    fun getScreenMirrorState(promise: Promise) {
        try {
            promise.resolve(buildStateMap(ScreenMirrorRuntimeState.snapshot()))
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to read screen mirror state", error)
            promise.reject("E_SCREEN_MIRROR_STATE_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun startScreenMirrorFromCommand(promise: Promise) {
        try {
            val result = ScreenMirrorService.startSharing(reactContext)
            if (!result.success) {
                ScreenMirrorRuntimeState.markError(result.reason)
            }
            promise.resolve(
                buildResultMap(
                    success = result.success,
                    reason = result.reason,
                    message = result.message
                )
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start screen mirror from command", error)
            ScreenMirrorRuntimeState.markError("screen_mirror_start_failed")
            promise.resolve(
                buildResultMap(
                    success = false,
                    reason = "screen_mirror_start_failed",
                    message = error.message ?: "Failed to start screen mirror"
                )
            )
        }
    }

    @ReactMethod
    fun stopScreenMirroring(reason: String?, promise: Promise) {
        try {
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "stopped_by_user"
            ScreenMirrorService.stopSharing(reactContext, normalizedReason)
            ScreenMirrorRuntimeState.markStopped(normalizedReason)
            promise.resolve(
                buildResultMap(
                    success = true,
                    reason = "stopped",
                    message = "Screen mirror stop requested"
                )
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to stop screen mirror", error)
            promise.resolve(
                buildResultMap(
                    success = false,
                    reason = "screen_mirror_stop_failed",
                    message = error.message ?: "Failed to stop screen mirror"
                )
            )
        }
    }

    override fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (requestCode != REQUEST_CODE_SCREEN_CAPTURE) {
            return
        }

        ScreenMirrorAutomationState.endPermissionFlow()
        val promise = permissionPromise ?: return
        permissionPromise = null

        if (resultCode == Activity.RESULT_OK && data != null) {
            ScreenMirrorPermissionStore.savePermission(resultCode, data)
            ScreenMirrorRuntimeState.markIdle()
            promise.resolve(
                buildResultMap(
                    success = true,
                    reason = "screen_mirror_permission_granted",
                    message = "Screen mirror permission granted"
                )
            )
            return
        }

        ScreenMirrorPermissionStore.clearPermission()
        ScreenMirrorRuntimeState.markError("screen_mirror_permission_not_granted")
        promise.resolve(
            buildResultMap(
                success = false,
                reason = "screen_mirror_permission_not_granted",
                message = "Screen mirror permission was not granted"
            )
        )
    }

    override fun onNewIntent(intent: Intent) {
        // No-op
    }

    override fun invalidate() {
        ScreenMirrorAutomationState.endPermissionFlow()
        permissionPromise?.resolve(
            buildResultMap(
                success = false,
                reason = "screen_mirror_request_cancelled",
                message = "Screen mirror permission request cancelled"
            )
        )
        permissionPromise = null
        reactContext.removeActivityEventListener(this)
        super.invalidate()
    }

    private fun buildStateMap(snapshot: ScreenMirrorStateSnapshot) = Arguments.createMap().apply {
        putString("status", snapshot.status)
        if (snapshot.reason != null) {
            putString("reason", snapshot.reason)
        } else {
            putNull("reason")
        }
        putBoolean("permissionGranted", snapshot.permissionGranted)
        putBoolean("isSharing", snapshot.isSharing)
        putDouble("updatedAt", snapshot.updatedAt.toDouble())
    }

    private fun buildResultMap(
        success: Boolean,
        reason: String,
        message: String
    ) = Arguments.createMap().apply {
        putBoolean("success", success)
        putString("reason", reason)
        putString("message", message)
        putMap("state", buildStateMap(ScreenMirrorRuntimeState.snapshot()))
    }
}
