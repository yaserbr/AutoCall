package com.yaser8541.autocallapp

import android.os.SystemClock
import android.util.Log

object AppForegroundTracker {
    private const val TAG = "AutoCall/AppForeground"

    data class Snapshot(
        val isMainActivityResumed: Boolean,
        val mainActivityCreateCount: Int,
        val mainActivityResumeCount: Int,
        val mainActivityPauseCount: Int,
        val mainActivityNewIntentCount: Int,
        val lastMainActivityResumeAtMs: Long,
        val lastMainActivityPauseAtMs: Long,
        val lastMainActivityIntentAtMs: Long,
        val lastMainActivityCreateAtMs: Long
    )

    @Volatile
    private var isMainActivityResumed: Boolean = false

    @Volatile
    private var mainActivityCreateCount: Int = 0

    @Volatile
    private var mainActivityResumeCount: Int = 0

    @Volatile
    private var mainActivityPauseCount: Int = 0

    @Volatile
    private var mainActivityNewIntentCount: Int = 0

    @Volatile
    private var lastMainActivityResumeAtMs: Long = 0L

    @Volatile
    private var lastMainActivityPauseAtMs: Long = 0L

    @Volatile
    private var lastMainActivityIntentAtMs: Long = 0L

    @Volatile
    private var lastMainActivityCreateAtMs: Long = 0L

    fun onMainActivityCreated() {
        val now = SystemClock.elapsedRealtime()
        mainActivityCreateCount += 1
        lastMainActivityCreateAtMs = now
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
        mainActivityPauseCount += 1
        lastMainActivityPauseAtMs = now
        Log.i(TAG, "MainActivity paused at=$now count=$mainActivityPauseCount")
    }

    fun onMainActivityNewIntent() {
        val now = SystemClock.elapsedRealtime()
        mainActivityNewIntentCount += 1
        lastMainActivityIntentAtMs = now
        Log.i(TAG, "MainActivity received new intent at=$now count=$mainActivityNewIntentCount")
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            isMainActivityResumed = isMainActivityResumed,
            mainActivityCreateCount = mainActivityCreateCount,
            mainActivityResumeCount = mainActivityResumeCount,
            mainActivityPauseCount = mainActivityPauseCount,
            mainActivityNewIntentCount = mainActivityNewIntentCount,
            lastMainActivityResumeAtMs = lastMainActivityResumeAtMs,
            lastMainActivityPauseAtMs = lastMainActivityPauseAtMs,
            lastMainActivityIntentAtMs = lastMainActivityIntentAtMs,
            lastMainActivityCreateAtMs = lastMainActivityCreateAtMs
        )
    }
}
