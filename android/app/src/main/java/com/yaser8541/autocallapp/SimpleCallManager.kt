package com.yaser8541.autocallapp

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

data class SimpleCallStartResult(
    val success: Boolean,
    val reason: String,
    val message: String,
    val phoneNumber: String? = null,
    val autoEndMs: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object SimpleCallManager {
    private const val TAG = "AutoCall/SimpleCall"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var pendingAutoEndRunnable: Runnable? = null

    fun startSimpleCall(
        context: Context,
        rawPhoneNumber: String,
        autoEndMs: Double?,
        activity: Activity?
    ): SimpleCallStartResult {
        val appContext = context.applicationContext
        val normalizedPhone = normalizePhoneNumber(rawPhoneNumber) ?: run {
            return SimpleCallStartResult(
                success = false,
                reason = "invalid_number",
                message = "Phone number is empty or invalid"
            )
        }

        val hasCallPhonePermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCallPhonePermission) {
            return SimpleCallStartResult(
                success = false,
                reason = "permission_denied",
                message = "CALL_PHONE permission is required",
                phoneNumber = normalizedPhone
            )
        }

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$normalizedPhone")
        }
        val canResolve = callIntent.resolveActivity(appContext.packageManager) != null
        if (!canResolve) {
            return SimpleCallStartResult(
                success = false,
                reason = "no_call_activity",
                message = "No Android activity can resolve ACTION_CALL",
                phoneNumber = normalizedPhone
            )
        }

        cancelAutoEndTimer()

        return try {
            if (activity != null) {
                activity.startActivity(callIntent)
            } else {
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(callIntent)
            }

            val normalizedAutoEndMs = normalizeAutoEndMs(autoEndMs)
            Log.i(TAG, "startSimpleCall autoEndMs=$normalizedAutoEndMs")
            scheduleAutoEndIfNeeded(appContext, normalizedAutoEndMs)

            SimpleCallStartResult(
                success = true,
                reason = "started",
                message = "ACTION_CALL launched successfully",
                phoneNumber = normalizedPhone,
                autoEndMs = normalizedAutoEndMs
            )
        } catch (error: SecurityException) {
            cancelAutoEndTimer()
            Log.e(TAG, "startSimpleCall exception", error)
            SimpleCallStartResult(
                success = false,
                reason = "security_exception",
                message = error.message ?: "SecurityException while launching ACTION_CALL",
                phoneNumber = normalizedPhone
            )
        } catch (error: ActivityNotFoundException) {
            cancelAutoEndTimer()
            Log.e(TAG, "startSimpleCall exception", error)
            SimpleCallStartResult(
                success = false,
                reason = "activity_not_found",
                message = error.message ?: "No activity found for ACTION_CALL",
                phoneNumber = normalizedPhone
            )
        } catch (error: Throwable) {
            cancelAutoEndTimer()
            Log.e(TAG, "startSimpleCall exception", error)
            SimpleCallStartResult(
                success = false,
                reason = "action_call_failed",
                message = error.message ?: "Unexpected error while launching ACTION_CALL",
                phoneNumber = normalizedPhone
            )
        }
    }

    fun endCurrentCall(context: Context): Boolean {
        cancelAutoEndTimer()
        return attemptEndCall(context.applicationContext)
    }

    private fun scheduleAutoEndIfNeeded(context: Context, delayMs: Long?) {
        Log.i(TAG, "scheduleAutoEndIfNeeded delayMs=$delayMs")
        cancelAutoEndTimer()

        if (delayMs == null || delayMs <= 0L) {
            return
        }

        Log.i(TAG, "creating new auto-end timer ms=$delayMs")
        val appContext = context.applicationContext
        val runnable = Runnable {
            synchronized(lock) {
                pendingAutoEndRunnable = null
            }
            Log.i(TAG, "auto-end runnable fired")
            attemptEndCall(appContext)
        }

        synchronized(lock) {
            pendingAutoEndRunnable = runnable
        }
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoEndTimer() {
        synchronized(lock) {
            val runnable = pendingAutoEndRunnable ?: return
            Log.i(TAG, "cancelling previous auto-end timer")
            mainHandler.removeCallbacks(runnable)
            pendingAutoEndRunnable = null
        }
    }

    private fun attemptEndCall(context: Context): Boolean {
        Log.i(TAG, "attemptEndCall invoked")
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager?
        if (telecomManager == null) {
            Log.i(TAG, "endCall result=false")
            return false
        }

        return try {
            @Suppress("DEPRECATION")
            val ended = telecomManager.endCall()
            Log.i(TAG, "endCall result=$ended")
            ended
        } catch (error: Throwable) {
            Log.e(TAG, "endCall exception", error)
            false
        }
    }

    private fun normalizeAutoEndMs(autoEndMs: Double?): Long? {
        if (autoEndMs == null || autoEndMs <= 0.0) {
            return null
        }
        val normalized = autoEndMs.toLong()
        if (normalized <= 0L) {
            return null
        }
        return normalized.coerceAtMost(Int.MAX_VALUE.toLong())
    }

    private fun normalizePhoneNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val normalized = trimmed.replace(Regex("[^\\d+]"), "")
        val plusCount = normalized.count { it == '+' }
        if (plusCount > 1 || (plusCount == 1 && !normalized.startsWith("+"))) {
            return null
        }

        val digits = normalized.replace("+", "")
        if (digits.isEmpty()) {
            return null
        }

        return normalized
    }
}
