package com.yaser8541.autocallapp

object ScreenMirrorStartCommandStore {
    private val lock = Any()
    private var pendingStartCommandId: String? = null

    fun enqueue(commandId: String): Boolean {
        val normalized = commandId.trim()
        if (normalized.isEmpty()) {
            return false
        }
        synchronized(lock) {
            val existing = pendingStartCommandId
            if (!existing.isNullOrBlank() && existing != normalized) {
                return false
            }
            pendingStartCommandId = normalized
            return true
        }
    }

    fun takePendingStartCommandId(): String? {
        synchronized(lock) {
            val commandId = pendingStartCommandId
            pendingStartCommandId = null
            return commandId
        }
    }

    fun clear(commandId: String? = null) {
        synchronized(lock) {
            val normalized = commandId?.trim().orEmpty()
            if (normalized.isBlank()) {
                pendingStartCommandId = null
                return
            }
            if (pendingStartCommandId == normalized) {
                pendingStartCommandId = null
            }
        }
    }
}
