package com.yaser8541.autocallapp

import android.os.Build

object ScreenMirrorAutomationState {
    private val lock = Any()

    @Volatile
    var isWaitingForPermission: Boolean = false
        private set

    fun beginPermissionFlow() {
        synchronized(lock) {
            isWaitingForPermission = true
        }
    }

    fun endPermissionFlow() {
        synchronized(lock) {
            isWaitingForPermission = false
        }
    }

    fun isSamsungOneUiTarget(): Boolean {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val sdkInt = Build.VERSION.SDK_INT
        return manufacturer.equals("samsung", ignoreCase = true) && sdkInt in 34..36
    }
}
