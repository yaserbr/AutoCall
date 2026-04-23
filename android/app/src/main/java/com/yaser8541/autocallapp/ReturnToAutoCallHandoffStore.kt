package com.yaser8541.autocallapp

import android.os.SystemClock

object ReturnToAutoCallHandoffStore {
    data class TrampolineReport(
        val requestId: String,
        val moveToFrontCalled: Boolean,
        val startActivityCalled: Boolean,
        val intentTarget: String?,
        val intentFlags: Int,
        val topPackageBefore: String?,
        val topPackageAfter: String?,
        val failureReason: String?
    )

    private val lock = Object()
    private val pendingRequests = mutableSetOf<String>()
    private val reports = mutableMapOf<String, TrampolineReport>()

    fun registerRequest(requestId: String) {
        synchronized(lock) {
            pendingRequests.add(requestId)
            reports.remove(requestId)
        }
    }

    fun publishReport(report: TrampolineReport) {
        synchronized(lock) {
            if (!pendingRequests.contains(report.requestId)) {
                pendingRequests.add(report.requestId)
            }
            reports[report.requestId] = report
            lock.notifyAll()
        }
    }

    fun awaitReport(requestId: String, timeoutMs: Long): TrampolineReport? {
        if (timeoutMs <= 0L) {
            synchronized(lock) { return reports[requestId] }
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (SystemClock.elapsedRealtime() <= deadline) {
                reports[requestId]?.let { return it }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                lock.wait(remaining)
            }
            return reports[requestId]
        }
    }

    fun clear(requestId: String) {
        synchronized(lock) {
            pendingRequests.remove(requestId)
            reports.remove(requestId)
        }
    }
}
