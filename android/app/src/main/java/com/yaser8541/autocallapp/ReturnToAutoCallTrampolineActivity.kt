package com.yaser8541.autocallapp

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

class ReturnToAutoCallTrampolineActivity : Activity() {
    companion object {
        private const val TAG = "AutoCall/ReturnTrampoline"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_EXPECTED_TASK_ID = "expected_task_id"
        private const val EXTRA_SOURCE = "source"

        fun createIntent(
            context: Context,
            requestId: String,
            expectedTaskId: Int
        ): Intent {
            return Intent(context, ReturnToAutoCallTrampolineActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_EXPECTED_TASK_ID, expectedTaskId)
                putExtra(EXTRA_SOURCE, "return_to_autocall")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        processIntent(intent, isNewIntent = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent, isNewIntent = true)
    }

    private fun processIntent(intent: Intent?, isNewIntent: Boolean) {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val expectedTaskId = intent?.getIntExtra(EXTRA_EXPECTED_TASK_ID, -1) ?: -1
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "unknown"

        if (requestId.isNullOrBlank() || expectedTaskId <= 0) {
            Log.w(
                TAG,
                "Invalid trampoline intent requestId=${requestId ?: "null"} expectedTaskId=$expectedTaskId " +
                    "isNewIntent=$isNewIntent source=$source"
            )
            finishQuietly()
            return
        }

        val topPackageBefore = observeForegroundPackage(applicationContext)
        var moveToFrontCalled = false
        var startActivityCalled = false
        var intentTarget: String? = null
        var intentFlags = 0
        var failureReason: String? = null

        Log.i(
            TAG,
            "Trampoline processing requestId=$requestId expectedTaskId=$expectedTaskId " +
                "isNewIntent=$isNewIntent source=$source topPackageBefore=${topPackageBefore ?: "unknown"}"
        )

        try {
            val targetTask = findExistingAutoCallTaskById(applicationContext, expectedTaskId)
            if (targetTask == null) {
                failureReason = "existing_autocall_task_not_found_in_trampoline"
            } else {
                moveToFrontCalled = true
                targetTask.appTask.moveToFront()

                val handoffIntent = Intent(this, MainActivity::class.java).apply {
                    action = "com.yaser8541.autocallapp.RETURN_TO_AUTOCALL_HANDOFF"
                    putExtra("return_to_autocall_request_id", requestId)
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                intentTarget = MainActivity::class.java.name
                intentFlags = handoffIntent.flags
                startActivity(handoffIntent)
                startActivityCalled = true
                Log.i(
                    TAG,
                    "Trampoline handoff startActivity called requestId=$requestId " +
                        "intentTarget=$intentTarget flags=${AutoCallAppNavigator.describeFlags(intentFlags)}"
                )
            }
        } catch (error: Throwable) {
            failureReason = error.message ?: "trampoline_handoff_exception"
            Log.e(TAG, "Trampoline handoff failed requestId=$requestId", error)
        } finally {
            val topPackageAfter = observeForegroundPackage(applicationContext)
            ReturnToAutoCallHandoffStore.publishReport(
                ReturnToAutoCallHandoffStore.TrampolineReport(
                    requestId = requestId,
                    moveToFrontCalled = moveToFrontCalled,
                    startActivityCalled = startActivityCalled,
                    intentTarget = intentTarget,
                    intentFlags = intentFlags,
                    topPackageBefore = topPackageBefore,
                    topPackageAfter = topPackageAfter,
                    failureReason = failureReason
                )
            )
            Log.i(
                TAG,
                "Trampoline completed requestId=$requestId moveToFrontCalled=$moveToFrontCalled " +
                    "startActivityCalled=$startActivityCalled failureReason=${failureReason ?: "none"} " +
                    "topPackageAfter=${topPackageAfter ?: "unknown"}"
            )
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }

    private data class ExistingTaskCandidate(
        val appTask: ActivityManager.AppTask,
        val taskId: Int
    )

    private fun findExistingAutoCallTaskById(
        context: Context,
        expectedTaskId: Int
    ): ExistingTaskCandidate? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val packageName = context.packageName
        for (appTask in activityManager.appTasks) {
            val info = appTask.taskInfo
            val taskId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.taskId
            } else {
                @Suppress("DEPRECATION")
                info.id
            }
            if (taskId != expectedTaskId) continue
            val basePackage = info.baseActivity?.packageName
            val topPackage = info.topActivity?.packageName
            val intentPackage = info.baseIntent?.component?.packageName
            val belongsToAutoCall = basePackage == packageName ||
                topPackage == packageName ||
                intentPackage == packageName
            if (!belongsToAutoCall) continue
            return ExistingTaskCandidate(
                appTask = appTask,
                taskId = taskId
            )
        }
        return null
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
}
