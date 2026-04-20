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
    private const val MAX_AUTO_HANGUP_SECONDS = 600
    private const val MAX_SERVER_CALL_DURATION_SECONDS = 3600

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var pendingHangupRunnable: Runnable? = null

    @Volatile
    private var answeredCurrentRinging = false

    @Volatile
    private var isPlacingOutgoingCall = false

    @Volatile
    private var pendingOutgoingServerHangupSeconds: Int? = null

    @Volatile
    private var outgoingServerHangupScheduled = false

    fun applyAutoAnswerSettings(
        context: Context,
        enabled: Boolean,
        requestedAutoHangupSeconds: Int?
    ): AutoAnswerSnapshot {
        val appContext = context.applicationContext

        if (enabled) {
            val currentSeconds = AutoAnswerStore.getAutoHangupSeconds(appContext)
            val targetSeconds = normalizeAutoHangupSeconds(requestedAutoHangupSeconds ?: currentSeconds)
            AutoAnswerStore.setAutoHangupSeconds(appContext, targetSeconds)
            AutoAnswerStore.setEnabled(appContext, true)
            AutoAnswerStore.setLastEvent(appContext, "Auto answer enabled")
            Log.i(TAG, "Auto answer enabled autoHangupSeconds=$targetSeconds")
        } else {
            AutoAnswerStore.setEnabled(appContext, false)
            onAutoAnswerDisabled(appContext)
            AutoAnswerStore.setLastEvent(appContext, "Auto answer disabled")
            Log.i(TAG, "Auto answer disabled")
        }

        return AutoAnswerStore.snapshot(appContext)
    }

    fun onOutgoingCallStarted(context: Context) {
        onOutgoingCallStarted(context, null)
    }

    fun onServerOutgoingCallStarted(context: Context, durationSeconds: Int?) {
        onOutgoingCallStarted(context, normalizeServerDurationSeconds(durationSeconds))
    }

    private fun onOutgoingCallStarted(context: Context, serverDurationSeconds: Int?) {
        val appContext = context.applicationContext
        isPlacingOutgoingCall = true
        answeredCurrentRinging = false
        pendingOutgoingServerHangupSeconds = serverDurationSeconds
        outgoingServerHangupScheduled = false
        cancelScheduledHangup(appContext, "Auto-answer timer skipped due to outgoing call")
        if (serverDurationSeconds != null) {
            Log.i(
                TAG,
                "Server call duration will start after answered seconds=$serverDurationSeconds"
            )
        }
        Log.i(TAG, "Outgoing call detected -> auto-answer ignored")
    }

    fun onPhoneStateChanged(context: Context, state: String?) {
        val appContext = context.applicationContext
        val phoneState = state ?: return

        when (phoneState) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (isPlacingOutgoingCall) {
                    Log.i(TAG, "Auto-answer timer skipped due to outgoing call")
                    return
                }

                Log.i(TAG, "Incoming call detected -> auto-answer enabled")
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
                if (isPlacingOutgoingCall) {
                    Log.i(TAG, "Outgoing call detected -> auto-answer ignored")
                    scheduleOutgoingServerHangupAfterAnsweredIfNeeded(appContext)
                } else {
                    Log.i(TAG, "Call is off-hook")
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Phone state idle")
                answeredCurrentRinging = false
                isPlacingOutgoingCall = false
                pendingOutgoingServerHangupSeconds = null
                outgoingServerHangupScheduled = false
                cancelScheduledHangup(appContext, "Call idle - timer cleared")
            }
        }
    }

    fun onAutoAnswerDisabled(context: Context) {
        val appContext = context.applicationContext
        answeredCurrentRinging = false
        isPlacingOutgoingCall = false
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
                isPlacingOutgoingCall = false
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
            if (isPlacingOutgoingCall) {
                Log.i(TAG, "Auto-answer timer skipped due to outgoing call")
                return
            }
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
            Log.i(TAG, "Auto-answer timer started")
            AutoAnswerStore.setLastEvent(context, "Scheduling auto hangup in ${seconds}s")
            mainHandler.postDelayed(runnable, seconds * 1000L)
        }
    }

    private fun scheduleOutgoingServerHangupAfterAnsweredIfNeeded(context: Context) {
        val seconds = pendingOutgoingServerHangupSeconds ?: return
        synchronized(lock) {
            if (outgoingServerHangupScheduled) {
                return
            }
            if (pendingHangupRunnable != null) {
                return
            }

            val runnable = Runnable {
                synchronized(lock) {
                    pendingHangupRunnable = null
                    outgoingServerHangupScheduled = false
                }
                endCurrentCall(context, "Call ended after ${seconds}s")
            }

            pendingHangupRunnable = runnable
            outgoingServerHangupScheduled = true
            pendingOutgoingServerHangupSeconds = null
            Log.i(TAG, "Server outgoing duration timer started after answered in ${seconds}s")
            AutoAnswerStore.setLastEvent(context, "Scheduling outgoing call end in ${seconds}s")
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

    private fun normalizeServerDurationSeconds(durationSeconds: Int?): Int? {
        if (durationSeconds == null || durationSeconds <= 0) {
            return null
        }
        return durationSeconds.coerceAtMost(MAX_SERVER_CALL_DURATION_SECONDS)
    }

    private fun normalizeAutoHangupSeconds(seconds: Int): Int {
        return seconds.coerceIn(1, MAX_AUTO_HANGUP_SECONDS)
    }
}

