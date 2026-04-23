package com.yaser8541.autocallapp

import android.os.SystemClock
import android.util.Log

object AppForegroundTracker {
    private const val TAG = "AutoCall/AppForeground"

    data class Snapshot(
        val isMainActivityResumed: Boolean,
        val isMainActivityWindowFocused: Boolean,
        val mainActivityCreateCount: Int,
        val mainActivityResumeCount: Int,
        val mainActivityPauseCount: Int,
        val lastMainActivityResumeAtMs: Long,
        val lastMainActivityPauseAtMs: Long,
        val lastMainActivityWindowFocusChangedAtMs: Long
    )

    @Volatile
    private var isMainActivityResumed: Boolean = false

    @Volatile
    private var isMainActivityWindowFocused: Boolean = false

    @Volatile
    private var mainActivityCreateCount: Int = 0

    @Volatile
    private var mainActivityResumeCount: Int = 0

    @Volatile
    private var mainActivityPauseCount: Int = 0

    @Volatile
    private var lastMainActivityResumeAtMs: Long = 0L

    @Volatile
    private var lastMainActivityPauseAtMs: Long = 0L

    @Volatile
    private var lastMainActivityWindowFocusChangedAtMs: Long = 0L

    fun onMainActivityCreated() {
        val now = SystemClock.elapsedRealtime()
        mainActivityCreateCount += 1
        Log.i(TAG, "MainActivity created at=$now count=$mainActivityCreateCount")
    }

    fun onMainActivityResumed() {
        val now = SystemClock.elapsedRealtime()
        isMainActivityResumed = true
        mainActivityResumeCount += 1
        lastMainActivityResumeAtMs = now
        Log.i(TAG, "MainActivity resumed at=$now count=$mainActivityResumeCount")
    }

    fun onMainActivityPaused() {
        val now = SystemClock.elapsedRealtime()
        isMainActivityResumed = false
        isMainActivityWindowFocused = false
        mainActivityPauseCount += 1
        lastMainActivityPauseAtMs = now
        Log.i(TAG, "MainActivity paused at=$now count=$mainActivityPauseCount")
    }

    fun onMainActivityWindowFocusChanged(hasFocus: Boolean) {
        val now = SystemClock.elapsedRealtime()
        isMainActivityWindowFocused = hasFocus
        lastMainActivityWindowFocusChangedAtMs = now
        Log.i(TAG, "MainActivity window focus changed hasFocus=$hasFocus at=$now")
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            isMainActivityResumed = isMainActivityResumed,
            isMainActivityWindowFocused = isMainActivityWindowFocused,
            mainActivityCreateCount = mainActivityCreateCount,
            mainActivityResumeCount = mainActivityResumeCount,
            mainActivityPauseCount = mainActivityPauseCount,
            lastMainActivityResumeAtMs = lastMainActivityResumeAtMs,
            lastMainActivityPauseAtMs = lastMainActivityPauseAtMs,
            lastMainActivityWindowFocusChangedAtMs = lastMainActivityWindowFocusChangedAtMs
        )
    }
}
