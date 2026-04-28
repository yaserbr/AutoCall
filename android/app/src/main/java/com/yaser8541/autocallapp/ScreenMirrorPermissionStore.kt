package com.yaser8541.autocallapp

import android.content.Intent
import android.os.Build

data class ScreenMirrorPermissionSnapshot(
    val resultCode: Int,
    val resultData: Intent
)

object ScreenMirrorPermissionStore {
    private val lock = Any()
    private var resultCode: Int? = null
    private var resultData: Intent? = null
    private var consumedOnAndroid14Plus: Boolean = false

    fun savePermission(resultCode: Int, data: Intent) {
        synchronized(lock) {
            this.resultCode = resultCode
            this.resultData = Intent(data)
            this.consumedOnAndroid14Plus = false
        }
    }

    fun hasPermission(): Boolean {
        synchronized(lock) {
            val hasRawPermission = resultCode != null && resultData != null
            if (!hasRawPermission) {
                return false
            }

            if (requiresSingleUseToken() && consumedOnAndroid14Plus) {
                return false
            }
            return true
        }
    }

    fun takeForNewSession(): ScreenMirrorPermissionSnapshot? {
        synchronized(lock) {
            val rawCode = resultCode ?: return null
            val rawData = resultData ?: return null

            if (requiresSingleUseToken()) {
                if (consumedOnAndroid14Plus) {
                    return null
                }
                consumedOnAndroid14Plus = true
            }

            return ScreenMirrorPermissionSnapshot(
                resultCode = rawCode,
                resultData = Intent(rawData)
            )
        }
    }

    fun clearPermission() {
        synchronized(lock) {
            resultCode = null
            resultData = null
            consumedOnAndroid14Plus = false
        }
    }

    private fun requiresSingleUseToken(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
