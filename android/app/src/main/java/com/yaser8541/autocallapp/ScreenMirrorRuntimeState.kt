package com.yaser8541.autocallapp

data class ScreenMirrorStateSnapshot(
    val status: String,
    val reason: String?,
    val permissionGranted: Boolean,
    val isSharing: Boolean,
    val updatedAt: Long
)

object ScreenMirrorRuntimeState {
    private val lock = Any()
    private var status: String = "idle"
    private var reason: String? = null
    private var updatedAt: Long = System.currentTimeMillis()

    fun markIdle(reason: String? = null) {
        update(status = "idle", reason = reason)
    }

    fun markLive() {
        update(status = "live", reason = null)
    }

    fun markStopped(reason: String? = null) {
        update(status = "idle", reason = reason)
    }

    fun markError(reason: String?) {
        update(status = "error", reason = reason)
    }

    fun snapshot(): ScreenMirrorStateSnapshot {
        synchronized(lock) {
            val normalizedStatus = status
            return ScreenMirrorStateSnapshot(
                status = normalizedStatus,
                reason = reason,
                permissionGranted = ScreenMirrorPermissionStore.hasPermission(),
                isSharing = normalizedStatus == "live",
                updatedAt = updatedAt
            )
        }
    }

    private fun update(status: String, reason: String?) {
        synchronized(lock) {
            this.status = status
            this.reason = reason?.trim()?.takeIf { it.isNotEmpty() }
            this.updatedAt = System.currentTimeMillis()
        }
    }
}
