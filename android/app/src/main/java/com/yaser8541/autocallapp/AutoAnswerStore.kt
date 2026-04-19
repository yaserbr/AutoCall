package com.yaser8541.autocallapp

import android.content.Context

data class AutoAnswerSnapshot(
    val enabled: Boolean,
    val autoHangupSeconds: Int,
    val lastEvent: String,
    val lastEventAt: Long
)

object AutoAnswerStore {
    private const val PREFS_NAME = "autocall_auto_answer_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_HANGUP_SECONDS = "auto_hangup_seconds"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_EVENT_AT = "last_event_at"

    private const val DEFAULT_AUTO_HANGUP_SECONDS = 20

    fun snapshot(context: Context): AutoAnswerSnapshot {
        val prefs = prefs(context)
        return AutoAnswerSnapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            autoHangupSeconds = prefs.getInt(KEY_AUTO_HANGUP_SECONDS, DEFAULT_AUTO_HANGUP_SECONDS),
            lastEvent = prefs.getString(KEY_LAST_EVENT, "Idle") ?: "Idle",
            lastEventAt = prefs.getLong(KEY_LAST_EVENT_AT, 0L)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setAutoHangupSeconds(context: Context, seconds: Int) {
        val normalized = seconds.coerceIn(1, 600)
        prefs(context)
            .edit()
            .putInt(KEY_AUTO_HANGUP_SECONDS, normalized)
            .apply()
    }

    fun getAutoHangupSeconds(context: Context): Int {
        return prefs(context).getInt(KEY_AUTO_HANGUP_SECONDS, DEFAULT_AUTO_HANGUP_SECONDS)
    }

    fun setLastEvent(context: Context, message: String) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_EVENT, message)
            .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

