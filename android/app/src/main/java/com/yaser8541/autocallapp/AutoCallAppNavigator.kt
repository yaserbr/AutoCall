package com.yaser8541.autocallapp

import android.content.Context
import android.util.Log

object AutoCallAppNavigator {
    private const val TAG = "AutoCall/AppNavigator"
    private const val RETURN_TO_AUTOCALL_TIMEOUT_MS = 6000L
    private const val DEFAULT_AUTOCALL_APP_NAME = "AutoCall"

    data class ReturnToAutoCallResult(
        val success: Boolean,
        val reason: String,
        val message: String,
        val noOp: Boolean,
        val webViewWasOpen: Boolean
    )

    fun returnToAutoCall(context: Context): ReturnToAutoCallResult {
        val appContext = context.applicationContext
        val appPackageName = appContext.packageName
        val appName = resolveAppName(appContext)

        Log.i(
            TAG,
            "RETURN_TO_AUTOCALL started package=$appPackageName appName=$appName"
        )

        return try {
            val accessibilityStatus = AutoCallAccessibilityHelper.snapshot(appContext)
            if (!accessibilityStatus.enabled) {
                Log.w(TAG, "RETURN_TO_AUTOCALL failed accessibility not enabled")
                return ReturnToAutoCallResult(
                    success = false,
                    reason = "accessibility_service_not_enabled",
                    message = "accessibility_service_not_enabled",
                    noOp = false,
                    webViewWasOpen = false
                )
            }

            if (!accessibilityStatus.connected || !accessibilityStatus.serviceInstancePresent) {
                Log.w(
                    TAG,
                    "RETURN_TO_AUTOCALL failed accessibility not ready " +
                        "connected=${accessibilityStatus.connected} " +
                        "instancePresent=${accessibilityStatus.serviceInstancePresent}"
                )
                return ReturnToAutoCallResult(
                    success = false,
                    reason = "accessibility_service_not_connected",
                    message = "accessibility_service_not_connected",
                    noOp = false,
                    webViewWasOpen = false
                )
            }

            val execution = AutoCallAccessibilityService.performReturnToAutoCall(
                targetPackageName = appPackageName,
                targetAppLabel = appName,
                timeoutMs = RETURN_TO_AUTOCALL_TIMEOUT_MS
            )

            if (execution.success) {
                val reason = if (execution.noOp) "noop" else "returned"
                val message = if (execution.noOp) {
                    "autocall_already_in_foreground"
                } else {
                    "returned_to_autocall"
                }
                Log.i(
                    TAG,
                    "RETURN_TO_AUTOCALL executed successfully " +
                        "noOp=${execution.noOp} recentsOpened=${execution.recentsOpened} " +
                        "topPackageAfterClick=${execution.topPackageAfterClick ?: "unknown"}"
                )
                ReturnToAutoCallResult(
                    success = true,
                    reason = reason,
                    message = message,
                    noOp = execution.noOp,
                    webViewWasOpen = false
                )
            } else {
                val failureReason = execution.failureReason ?: "return_to_autocall_failed"
                Log.w(
                    TAG,
                    "RETURN_TO_AUTOCALL failed " +
                        "failureReason=$failureReason recentsOpened=${execution.recentsOpened} " +
                        "recentsVisibleAfterClick=${execution.recentsVisibleAfterClick} " +
                        "topPackageAfterClick=${execution.topPackageAfterClick ?: "unknown"} " +
                        "mainActivityWindowFocusedAfterClick=${execution.mainActivityWindowFocusedAfterClick}"
                )
                ReturnToAutoCallResult(
                    success = false,
                    reason = failureReason,
                    message = failureReason,
                    noOp = false,
                    webViewWasOpen = false
                )
            }
        } catch (error: Throwable) {
            Log.e(TAG, "RETURN_TO_AUTOCALL crashed", error)
            ReturnToAutoCallResult(
                success = false,
                reason = "return_to_autocall_failed",
                message = error.message ?: "return_to_autocall_failed",
                noOp = false,
                webViewWasOpen = false
            )
        }
    }

    private fun resolveAppName(context: Context): String {
        return runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTOCALL_APP_NAME
    }
}
