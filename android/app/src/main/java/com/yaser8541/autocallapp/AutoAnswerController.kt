package com.yaser8541.autocallapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

object AutoAnswerController {
    private const val TAG = "AutoCall/AutoAnswer"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var pendingHangupRunnable: Runnable? = null

    @Volatile
    private var answeredCurrentRinging = false

    fun onPhoneStateChanged(context: Context, state: String?) {
        val appContext = context.applicationContext
        val phoneState = state ?: return

        when (phoneState) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.i(TAG, "Incoming call ringing")
                AutoAnswerStore.setLastEvent(appContext, "Incoming call detected")

                if (!AutoAnswerStore.isEnabled(appContext)) {
                    Log.i(TAG, "Auto answer disabled; skipping")
                    return
                }

                if (answeredCurrentRinging) {
                    Log.i(TAG, "Incoming ringing already handled; skipping")
                    return
                }

                val accepted = acceptRingingCall(appContext)
                if (accepted) {
                    answeredCurrentRinging = true
                    scheduleAutoHangup(appContext)
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.i(TAG, "Call is off-hook")
                if (AutoAnswerStore.isEnabled(appContext)) {
                    scheduleAutoHangup(appContext)
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Phone state idle")
                answeredCurrentRinging = false
                cancelScheduledHangup(appContext, "Call idle - timer cleared")
            }
        }
    }

    fun onAutoAnswerDisabled(context: Context) {
        val appContext = context.applicationContext
        answeredCurrentRinging = false
        cancelScheduledHangup(appContext, "Auto answer disabled")
    }

    fun endCurrentCall(context: Context, reason: String = "Ending current call"): Boolean {
        val appContext = context.applicationContext
        cancelScheduledHangup(appContext, "Clearing timer before end call")

        if (!hasAnswerPhoneCallsPermission(appContext)) {
            Log.e(TAG, "Cannot end call: ANSWER_PHONE_CALLS permission denied")
            AutoAnswerStore.setLastEvent(appContext, "End call failed: permission denied")
            return false
        }

        val telecomManager = appContext.getSystemService(TelecomManager::class.java)
        if (telecomManager == null) {
            Log.e(TAG, "Cannot end call: TelecomManager unavailable")
            AutoAnswerStore.setLastEvent(appContext, "End call failed: telecom unavailable")
            return false
        }

        return try {
            @Suppress("DEPRECATION")
            val ended = telecomManager.endCall()
            if (ended) {
                Log.i(TAG, "Ending current call")
                AutoAnswerStore.setLastEvent(appContext, reason)
            } else {
                Log.w(TAG, "TelecomManager.endCall returned false")
                AutoAnswerStore.setLastEvent(appContext, "End call request returned false")
            }
            ended
        } catch (error: SecurityException) {
            Log.e(TAG, "End call security error", error)
            AutoAnswerStore.setLastEvent(appContext, "End call failed: security exception")
            false
        } catch (error: Throwable) {
            Log.e(TAG, "End call failed", error)
            AutoAnswerStore.setLastEvent(appContext, "End call failed: ${error.message ?: "unknown"}")
            false
        }
    }

    fun isHangupScheduled(): Boolean = pendingHangupRunnable != null

    private fun acceptRingingCall(context: Context): Boolean {
        if (!hasAnswerPhoneCallsPermission(context)) {
            Log.e(TAG, "Cannot answer: ANSWER_PHONE_CALLS permission denied")
            AutoAnswerStore.setLastEvent(context, "Auto answer failed: permission denied")
            return false
        }

        val telecomManager = context.getSystemService(TelecomManager::class.java)
        if (telecomManager == null) {
            Log.e(TAG, "Cannot answer: TelecomManager unavailable")
            AutoAnswerStore.setLastEvent(context, "Auto answer failed: telecom unavailable")
            return false
        }

        return try {
            Log.i(TAG, "Accepting incoming call")
            telecomManager.acceptRingingCall()
            AutoAnswerStore.setLastEvent(context, "Answered automatically")
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Accept call security error", error)
            AutoAnswerStore.setLastEvent(context, "Auto answer failed: security exception")
            false
        } catch (error: Throwable) {
            Log.e(TAG, "Accept call failed", error)
            AutoAnswerStore.setLastEvent(context, "Auto answer failed: ${error.message ?: "unknown"}")
            false
        }
    }

    private fun scheduleAutoHangup(context: Context) {
        val seconds = AutoAnswerStore.getAutoHangupSeconds(context)
        synchronized(lock) {
            if (pendingHangupRunnable != null) {
                return
            }

            val runnable = Runnable {
                synchronized(lock) {
                    pendingHangupRunnable = null
                }
                endCurrentCall(context, "Call ended after ${seconds}s")
            }

            pendingHangupRunnable = runnable
            Log.i(TAG, "Scheduling auto hangup in ${seconds}s")
            AutoAnswerStore.setLastEvent(context, "Scheduling auto hangup in ${seconds}s")
            mainHandler.postDelayed(runnable, seconds * 1000L)
        }
    }

    private fun cancelScheduledHangup(context: Context, reason: String) {
        synchronized(lock) {
            val runnable = pendingHangupRunnable ?: return
            mainHandler.removeCallbacks(runnable)
            pendingHangupRunnable = null
            Log.i(TAG, reason)
        }
    }

    private fun hasAnswerPhoneCallsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

