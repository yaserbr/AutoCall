package com.yaser8541.autocallapp

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID

object AutoCallAppNavigator {
    private const val TAG = "AutoCall/AppNavigator"
    private const val FOREGROUND_WAIT_INITIAL_DELAY_MS = 120L
    private const val FOREGROUND_WAIT_INTERVAL_MS = 100L
    private const val FOREGROUND_WAIT_TIMEOUT_MS = 2500L
    private const val TRAMPOLINE_REPORT_TIMEOUT_MS = 3000L

    data class ReturnToAutoCallResult(
        val success: Boolean,
        val reason: String,
        val message: String,
        val noOp: Boolean,
        val webViewWasOpen: Boolean
    )

    private data class ExistingTaskCandidate(
        val appTask: ActivityManager.AppTask,
        val taskId: Int,
        val baseActivity: String?,
        val topActivity: String?
    )

    private data class ForegroundVerificationResult(
        val becameForeground: Boolean,
        val attempts: Int,
        val observedTopPackage: String?
    )

    fun returnToAutoCall(
        context: Context,
        currentActivity: Activity? = null
    ): ReturnToAutoCallResult {
        val appContext = context.applicationContext
        val webViewSnapshotBefore = InAppWebViewController.snapshot()
        val webViewWasOpen = webViewSnapshotBefore.isOpen

        val trackerBefore = AppForegroundTracker.snapshot()
        val topPackageBefore = observeForegroundPackage(appContext)
        val alreadyInMainActivity =
            currentActivity is MainActivity && !currentActivity.isFinishing
        val wasAlreadyForeground = alreadyInMainActivity || trackerBefore.isMainActivityResumed

        var webViewClosed = false
        var existingTaskFound = false
        var moveToFrontCalled = false
        var trampolineUsed = false
        var startActivityCalled = false
        var intentTarget: String? = null
        var intentFlags = 0
        var foregroundVerificationAttempts = 0
        var topPackageAfter: String? = topPackageBefore
        var noOp = false
        var failureReason: String? = null
        var finalDecision = "started"
        var lifecycleCallbacksObserved = "none"
        var mainActivityInstanceReused = true

        Log.i(
            TAG,
            "RETURN_TO_AUTOCALL received " +
                "wasAlreadyForeground=$wasAlreadyForeground " +
                "topPackageBefore=${topPackageBefore ?: "unknown"} " +
                "webViewWasOpen=$webViewWasOpen"
        )

        return try {
            if (webViewWasOpen) {
                val closeResult = InAppWebViewController.closeWebView(appContext)
                webViewClosed = closeResult.success && !closeResult.noOp
                Log.i(
                    TAG,
                    "RETURN_TO_AUTOCALL webview close result " +
                        "success=${closeResult.success} noOp=${closeResult.noOp} " +
                        "reason=${closeResult.reason} message=${closeResult.message}"
                )
            }

            val resumedAfterWebViewHandling = AppForegroundTracker.snapshot().isMainActivityResumed
            if (wasAlreadyForeground && resumedAfterWebViewHandling) {
                noOp = !webViewClosed
                finalDecision = if (noOp) {
                    "success_already_foreground_noop"
                } else {
                    "success_after_webview_close"
                }
                topPackageAfter = observeForegroundPackage(appContext) ?: topPackageAfter
                val trackerAfterNoOp = AppForegroundTracker.snapshot()
                lifecycleCallbacksObserved = buildLifecycleCallbackSummary(
                    before = trackerBefore,
                    after = trackerAfterNoOp
                )
                mainActivityInstanceReused =
                    trackerAfterNoOp.mainActivityCreateCount == trackerBefore.mainActivityCreateCount

                return successResult(
                    webViewWasOpen = webViewWasOpen,
                    noOp = noOp,
                    reason = if (noOp) "noop" else "returned",
                    message = if (noOp) "autocall_already_in_foreground" else "returned_to_autocall",
                    wasAlreadyForeground = wasAlreadyForeground,
                    existingTaskFound = true,
                    moveToFrontCalled = false,
                    trampolineUsed = false,
                    startActivityCalled = false,
                    intentTarget = null,
                    intentFlags = 0,
                    mainActivityInstanceReused = mainActivityInstanceReused,
                    webViewClosed = webViewClosed,
                    foregroundVerificationAttempts = 0,
                    topPackageBefore = topPackageBefore,
                    topPackageAfter = topPackageAfter,
                    lifecycleCallbacksObserved = lifecycleCallbacksObserved,
                    finalDecision = finalDecision
                )
            }

            val existingTask = findExistingAutoCallTask(appContext)
            existingTaskFound = existingTask != null
            if (!existingTaskFound) {
                failureReason = "existing_autocall_task_not_found"
                finalDecision = "failed_existing_task_not_found"
                val verification = verifyMainActivityForeground(appContext)
                foregroundVerificationAttempts = verification.attempts
                topPackageAfter = verification.observedTopPackage ?: topPackageAfter
                val trackerAfterFailure = AppForegroundTracker.snapshot()
                lifecycleCallbacksObserved = buildLifecycleCallbackSummary(
                    before = trackerBefore,
                    after = trackerAfterFailure
                )
                mainActivityInstanceReused =
                    trackerAfterFailure.mainActivityCreateCount == trackerBefore.mainActivityCreateCount
                return failureResult(
                    webViewWasOpen = webViewWasOpen,
                    reason = failureReason,
                    message = failureReason,
                    wasAlreadyForeground = wasAlreadyForeground,
                    existingTaskFound = existingTaskFound,
                    moveToFrontCalled = moveToFrontCalled,
                    trampolineUsed = trampolineUsed,
                    startActivityCalled = startActivityCalled,
                    intentTarget = intentTarget,
                    intentFlags = intentFlags,
                    mainActivityInstanceReused = mainActivityInstanceReused,
                    webViewClosed = webViewClosed,
                    foregroundVerificationAttempts = foregroundVerificationAttempts,
                    topPackageBefore = topPackageBefore,
                    topPackageAfter = topPackageAfter,
                    lifecycleCallbacksObserved = lifecycleCallbacksObserved,
                    finalDecision = finalDecision
                )
            }

            val targetTask = existingTask!!
            Log.i(
                TAG,
                "RETURN_TO_AUTOCALL existing task found " +
                    "taskId=${targetTask.taskId} " +
                    "baseActivity=${targetTask.baseActivity ?: "null"} " +
                    "topActivity=${targetTask.topActivity ?: "null"}"
            )

            moveToFrontCalled = true
            val moveToFrontError = bringTaskToFront(targetTask)
            val verificationAfterMove = verifyMainActivityForeground(appContext)
            foregroundVerificationAttempts += verificationAfterMove.attempts
            topPackageAfter = verificationAfterMove.observedTopPackage ?: topPackageAfter
            if (moveToFrontError == null && verificationAfterMove.becameForeground) {
                finalDecision = "success_move_to_front"
                val trackerAfterSuccess = AppForegroundTracker.snapshot()
                lifecycleCallbacksObserved = buildLifecycleCallbackSummary(
                    before = trackerBefore,
                    after = trackerAfterSuccess
                )
                mainActivityInstanceReused =
                    trackerAfterSuccess.mainActivityCreateCount == trackerBefore.mainActivityCreateCount
                return successResult(
                    webViewWasOpen = webViewWasOpen,
                    noOp = false,
                    reason = "returned",
                    message = "returned_to_autocall",
                    wasAlreadyForeground = wasAlreadyForeground,
                    existingTaskFound = existingTaskFound,
                    moveToFrontCalled = moveToFrontCalled,
                    trampolineUsed = false,
                    startActivityCalled = false,
                    intentTarget = null,
                    intentFlags = 0,
                    mainActivityInstanceReused = mainActivityInstanceReused,
                    webViewClosed = webViewClosed,
                    foregroundVerificationAttempts = foregroundVerificationAttempts,
                    topPackageBefore = topPackageBefore,
                    topPackageAfter = topPackageAfter,
                    lifecycleCallbacksObserved = lifecycleCallbacksObserved,
                    finalDecision = finalDecision
                )
            }

            failureReason = if (moveToFrontError != null) {
                "existing_autocall_task_move_to_front_failed"
            } else {
                "existing_autocall_task_not_foreground_after_move_to_front"
            }
            Log.w(
                TAG,
                "RETURN_TO_AUTOCALL moveToFront path not enough " +
                    "failureReason=$failureReason moveToFrontError=${moveToFrontError ?: "none"}"
            )

            trampolineUsed = true
            val requestId = UUID.randomUUID().toString()
            ReturnToAutoCallHandoffStore.registerRequest(requestId)
            val trampolineIntent = ReturnToAutoCallTrampolineActivity.createIntent(
                context = appContext,
                requestId = requestId,
                expectedTaskId = targetTask.taskId
            )
            intentTarget = ReturnToAutoCallTrampolineActivity::class.java.name
            intentFlags = trampolineIntent.flags
            try {
                appContext.startActivity(trampolineIntent)
                startActivityCalled = true
            } catch (error: Throwable) {
                failureReason = "trampoline_start_activity_failed"
                finalDecision = "failed_trampoline_start_activity_exception"
                Log.e(TAG, "RETURN_TO_AUTOCALL failed to start trampoline", error)
            }

            val trampolineReport = ReturnToAutoCallHandoffStore.awaitReport(
                requestId = requestId,
                timeoutMs = TRAMPOLINE_REPORT_TIMEOUT_MS
            )
            ReturnToAutoCallHandoffStore.clear(requestId)

            if (trampolineReport == null) {
                failureReason = if (failureReason == "trampoline_start_activity_failed") {
                    failureReason
                } else {
                    "trampoline_report_timeout"
                }
            } else {
                moveToFrontCalled = moveToFrontCalled || trampolineReport.moveToFrontCalled
                startActivityCalled = startActivityCalled || trampolineReport.startActivityCalled
                intentTarget = trampolineReport.intentTarget ?: intentTarget
                if (trampolineReport.intentFlags != 0) {
                    intentFlags = trampolineReport.intentFlags
                }
                topPackageAfter = trampolineReport.topPackageAfter ?: topPackageAfter
                if (!trampolineReport.failureReason.isNullOrBlank()) {
                    failureReason = trampolineReport.failureReason
                }
            }

            val verificationAfterTrampoline = verifyMainActivityForeground(appContext)
            foregroundVerificationAttempts += verificationAfterTrampoline.attempts
            topPackageAfter = verificationAfterTrampoline.observedTopPackage ?: topPackageAfter
            val trackerAfterHandoff = AppForegroundTracker.snapshot()
            lifecycleCallbacksObserved = buildLifecycleCallbackSummary(
                before = trackerBefore,
                after = trackerAfterHandoff
            )
            mainActivityInstanceReused =
                trackerAfterHandoff.mainActivityCreateCount == trackerBefore.mainActivityCreateCount

            if (verificationAfterTrampoline.becameForeground) {
                finalDecision = "success_trampoline_handoff"
                return successResult(
                    webViewWasOpen = webViewWasOpen,
                    noOp = false,
                    reason = "returned",
                    message = "returned_to_autocall",
                    wasAlreadyForeground = wasAlreadyForeground,
                    existingTaskFound = existingTaskFound,
                    moveToFrontCalled = moveToFrontCalled,
                    trampolineUsed = trampolineUsed,
                    startActivityCalled = startActivityCalled,
                    intentTarget = intentTarget,
                    intentFlags = intentFlags,
                    mainActivityInstanceReused = mainActivityInstanceReused,
                    webViewClosed = webViewClosed,
                    foregroundVerificationAttempts = foregroundVerificationAttempts,
                    topPackageBefore = topPackageBefore,
                    topPackageAfter = topPackageAfter,
                    lifecycleCallbacksObserved = lifecycleCallbacksObserved,
                    finalDecision = finalDecision
                )
            }

            if (
                failureReason == "existing_autocall_task_move_to_front_failed" ||
                failureReason == "existing_autocall_task_not_foreground_after_move_to_front" ||
                failureReason == "trampoline_report_timeout"
            ) {
                failureReason = "existing_autocall_task_not_foreground_after_trampoline_handoff"
            }
            finalDecision = "failed_trampoline_handoff_not_foreground"
            return failureResult(
                webViewWasOpen = webViewWasOpen,
                reason = failureReason,
                message = failureReason,
                wasAlreadyForeground = wasAlreadyForeground,
                existingTaskFound = existingTaskFound,
                moveToFrontCalled = moveToFrontCalled,
                trampolineUsed = trampolineUsed,
                startActivityCalled = startActivityCalled,
                intentTarget = intentTarget,
                intentFlags = intentFlags,
                mainActivityInstanceReused = mainActivityInstanceReused,
                webViewClosed = webViewClosed,
                foregroundVerificationAttempts = foregroundVerificationAttempts,
                topPackageBefore = topPackageBefore,
                topPackageAfter = topPackageAfter,
                lifecycleCallbacksObserved = lifecycleCallbacksObserved,
                finalDecision = finalDecision
            )
        } catch (error: Throwable) {
            failureReason = "return_to_autocall_failed"
            finalDecision = "failed_exception"
            topPackageAfter = observeForegroundPackage(appContext) ?: topPackageAfter
            val trackerAfterCrash = AppForegroundTracker.snapshot()
            lifecycleCallbacksObserved = buildLifecycleCallbackSummary(
                before = trackerBefore,
                after = trackerAfterCrash
            )
            mainActivityInstanceReused =
                trackerAfterCrash.mainActivityCreateCount == trackerBefore.mainActivityCreateCount
            Log.e(TAG, "RETURN_TO_AUTOCALL failed", error)
            return failureResult(
                webViewWasOpen = webViewWasOpen,
                reason = failureReason,
                message = error.message ?: failureReason,
                wasAlreadyForeground = wasAlreadyForeground,
                existingTaskFound = existingTaskFound,
                moveToFrontCalled = moveToFrontCalled,
                trampolineUsed = trampolineUsed,
                startActivityCalled = startActivityCalled,
                intentTarget = intentTarget,
                intentFlags = intentFlags,
                mainActivityInstanceReused = mainActivityInstanceReused,
                webViewClosed = webViewClosed,
                foregroundVerificationAttempts = foregroundVerificationAttempts,
                topPackageBefore = topPackageBefore,
                topPackageAfter = topPackageAfter,
                lifecycleCallbacksObserved = lifecycleCallbacksObserved,
                finalDecision = finalDecision
            )
        }
    }

    private fun bringTaskToFront(candidate: ExistingTaskCandidate): String? {
        return try {
            candidate.appTask.moveToFront()
            Log.i(
                TAG,
                "RETURN_TO_AUTOCALL moveToFront called " +
                    "taskId=${candidate.taskId} " +
                    "baseActivity=${candidate.baseActivity ?: "null"} " +
                    "topActivity=${candidate.topActivity ?: "null"}"
            )
            null
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "RETURN_TO_AUTOCALL moveToFront failed taskId=${candidate.taskId}",
                error
            )
            error.message ?: "move_to_front_failed"
        }
    }

    private fun findExistingAutoCallTask(context: Context): ExistingTaskCandidate? {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            Log.w(TAG, "RETURN_TO_AUTOCALL cannot inspect app tasks: ActivityManager unavailable")
            return null
        }

        val appTasks = activityManager.appTasks
        val packageName = context.packageName
        Log.i(TAG, "RETURN_TO_AUTOCALL app task probe count=${appTasks.size}")
        for (appTask in appTasks) {
            val info = appTask.taskInfo
            val basePackage = info.baseActivity?.packageName
            val topPackage = info.topActivity?.packageName
            val intentPackage = info.baseIntent?.component?.packageName
            val belongsToAutoCall = basePackage == packageName ||
                topPackage == packageName ||
                intentPackage == packageName
            val taskId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.taskId
            } else {
                @Suppress("DEPRECATION")
                info.id
            }

            Log.i(
                TAG,
                "RETURN_TO_AUTOCALL app task probe " +
                    "taskId=$taskId belongsToAutoCall=$belongsToAutoCall " +
                    "baseActivity=${info.baseActivity?.flattenToShortString() ?: "null"} " +
                    "topActivity=${info.topActivity?.flattenToShortString() ?: "null"}"
            )

            if (!belongsToAutoCall) {
                continue
            }
            return ExistingTaskCandidate(
                appTask = appTask,
                taskId = taskId,
                baseActivity = info.baseActivity?.flattenToShortString(),
                topActivity = info.topActivity?.flattenToShortString()
            )
        }
        return null
    }

    private fun verifyMainActivityForeground(context: Context): ForegroundVerificationResult {
        var attempts = 0
        var observedTopPackage: String? = null

        fun capture(): Boolean {
            attempts += 1
            observedTopPackage = observeForegroundPackage(context) ?: observedTopPackage
            return AppForegroundTracker.snapshot().isMainActivityResumed
        }

        if (capture()) {
            return ForegroundVerificationResult(true, attempts, observedTopPackage)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "RETURN_TO_AUTOCALL verification limited because current thread is main")
            return ForegroundVerificationResult(false, attempts, observedTopPackage)
        }

        SystemClock.sleep(FOREGROUND_WAIT_INITIAL_DELAY_MS)
        val deadline = SystemClock.elapsedRealtime() + FOREGROUND_WAIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() <= deadline) {
            if (capture()) {
                return ForegroundVerificationResult(true, attempts, observedTopPackage)
            }
            SystemClock.sleep(FOREGROUND_WAIT_INTERVAL_MS)
        }

        return ForegroundVerificationResult(false, attempts, observedTopPackage)
    }

    private fun observeForegroundPackage(context: Context): String? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val foregroundProcess = activityManager.runningAppProcesses
            ?.firstOrNull { process ->
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            }
        if (foregroundProcess != null) {
            return foregroundProcess.pkgList?.firstOrNull() ?: foregroundProcess.processName
        }

        return activityManager.appTasks
            .firstOrNull()
            ?.taskInfo
            ?.topActivity
            ?.packageName
    }

    private fun buildLifecycleCallbackSummary(
        before: AppForegroundTracker.Snapshot,
        after: AppForegroundTracker.Snapshot
    ): String {
        val createdDelta = after.mainActivityCreateCount - before.mainActivityCreateCount
        val resumedDelta = after.mainActivityResumeCount - before.mainActivityResumeCount
        val pausedDelta = after.mainActivityPauseCount - before.mainActivityPauseCount
        val newIntentDelta = after.mainActivityNewIntentCount - before.mainActivityNewIntentCount
        return "createdDelta=$createdDelta,resumedDelta=$resumedDelta,pausedDelta=$pausedDelta,newIntentDelta=$newIntentDelta"
    }

    private fun successResult(
        webViewWasOpen: Boolean,
        noOp: Boolean,
        reason: String,
        message: String,
        wasAlreadyForeground: Boolean,
        existingTaskFound: Boolean,
        moveToFrontCalled: Boolean,
        trampolineUsed: Boolean,
        startActivityCalled: Boolean,
        intentTarget: String?,
        intentFlags: Int,
        mainActivityInstanceReused: Boolean,
        webViewClosed: Boolean,
        foregroundVerificationAttempts: Int,
        topPackageBefore: String?,
        topPackageAfter: String?,
        lifecycleCallbacksObserved: String,
        finalDecision: String
    ): ReturnToAutoCallResult {
        logDecision(
            wasAlreadyForeground = wasAlreadyForeground,
            existingTaskFound = existingTaskFound,
            moveToFrontCalled = moveToFrontCalled,
            trampolineUsed = trampolineUsed,
            startActivityCalled = startActivityCalled,
            intentTarget = intentTarget,
            intentFlags = intentFlags,
            mainActivityInstanceReused = mainActivityInstanceReused,
            webViewClosed = webViewClosed,
            foregroundVerificationAttempts = foregroundVerificationAttempts,
            topPackageBefore = topPackageBefore,
            topPackageAfter = topPackageAfter,
            lifecycleCallbacksObserved = lifecycleCallbacksObserved,
            finalDecision = finalDecision,
            noOp = noOp,
            failureReason = null
        )
        return ReturnToAutoCallResult(
            success = true,
            reason = reason,
            message = message,
            noOp = noOp,
            webViewWasOpen = webViewWasOpen
        )
    }

    private fun failureResult(
        webViewWasOpen: Boolean,
        reason: String,
        message: String,
        wasAlreadyForeground: Boolean,
        existingTaskFound: Boolean,
        moveToFrontCalled: Boolean,
        trampolineUsed: Boolean,
        startActivityCalled: Boolean,
        intentTarget: String?,
        intentFlags: Int,
        mainActivityInstanceReused: Boolean,
        webViewClosed: Boolean,
        foregroundVerificationAttempts: Int,
        topPackageBefore: String?,
        topPackageAfter: String?,
        lifecycleCallbacksObserved: String,
        finalDecision: String
    ): ReturnToAutoCallResult {
        logDecision(
            wasAlreadyForeground = wasAlreadyForeground,
            existingTaskFound = existingTaskFound,
            moveToFrontCalled = moveToFrontCalled,
            trampolineUsed = trampolineUsed,
            startActivityCalled = startActivityCalled,
            intentTarget = intentTarget,
            intentFlags = intentFlags,
            mainActivityInstanceReused = mainActivityInstanceReused,
            webViewClosed = webViewClosed,
            foregroundVerificationAttempts = foregroundVerificationAttempts,
            topPackageBefore = topPackageBefore,
            topPackageAfter = topPackageAfter,
            lifecycleCallbacksObserved = lifecycleCallbacksObserved,
            finalDecision = finalDecision,
            noOp = false,
            failureReason = reason
        )
        return ReturnToAutoCallResult(
            success = false,
            reason = reason,
            message = message,
            noOp = false,
            webViewWasOpen = webViewWasOpen
        )
    }

    private fun logDecision(
        wasAlreadyForeground: Boolean,
        existingTaskFound: Boolean,
        moveToFrontCalled: Boolean,
        trampolineUsed: Boolean,
        startActivityCalled: Boolean,
        intentTarget: String?,
        intentFlags: Int,
        mainActivityInstanceReused: Boolean,
        webViewClosed: Boolean,
        foregroundVerificationAttempts: Int,
        topPackageBefore: String?,
        topPackageAfter: String?,
        lifecycleCallbacksObserved: String,
        finalDecision: String,
        noOp: Boolean,
        failureReason: String?
    ) {
        Log.i(
            TAG,
            "RETURN_TO_AUTOCALL decision " +
                "wasAlreadyForeground=$wasAlreadyForeground " +
                "existingTaskFound=$existingTaskFound " +
                "moveToFrontCalled=$moveToFrontCalled " +
                "trampolineUsed=$trampolineUsed " +
                "startActivityCalled=$startActivityCalled " +
                "intentTarget=${intentTarget ?: "none"} " +
                "flags=${describeFlags(intentFlags)} " +
                "mainActivityInstanceReused=$mainActivityInstanceReused " +
                "webViewClosed=$webViewClosed " +
                "foregroundVerificationAttempts=$foregroundVerificationAttempts " +
                "topPackageBefore=${topPackageBefore ?: "unknown"} " +
                "topPackageAfter=${topPackageAfter ?: "unknown"} " +
                "lifecycleCallbacksObserved=$lifecycleCallbacksObserved " +
                "finalDecision=$finalDecision " +
                "noOp=$noOp " +
                "failureReason=${failureReason ?: "none"}"
        )
    }

    fun describeFlags(flags: Int): String {
        if (flags == 0) return "none"
        val labels = mutableListOf<String>()
        if ((flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0) labels.add("NEW_TASK")
        if ((flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) labels.add("CLEAR_TOP")
        if ((flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) labels.add("SINGLE_TOP")
        if ((flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) labels.add("REORDER_TO_FRONT")
        if ((flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            labels.add("RESET_TASK_IF_NEEDED")
        }
        if ((flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0) {
            labels.add("EXCLUDE_FROM_RECENTS")
        }
        if ((flags and Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            labels.add("NO_ANIMATION")
        }
        return if (labels.isEmpty()) flags.toString() else labels.joinToString("|")
    }
}
