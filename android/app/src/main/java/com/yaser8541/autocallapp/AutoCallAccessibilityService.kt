package com.yaser8541.autocallapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutoCallAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AutoCall/AccessibilityService"
        private const val RECENTS_SETTLE_MS = 320L
        private const val RESCAN_INTERVAL_MS = 180L
        private const val CLICK_SETTLE_MS = 160L
        private const val VERIFY_WINDOW_MS = 1300L
        private const val GESTURE_DURATION_MS = 40L
        private const val GESTURE_WAIT_MS = 700L
        private const val MAX_NODE_SCAN_PER_ROOT = 650
        private const val SCREEN_MIRROR_SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SCREEN_MIRROR_RETRY_INTERVAL_MS = 250L
        private const val SCREEN_MIRROR_TIMEOUT_MS = 4000L
        private const val SCREEN_MIRROR_TEXT_SHARE_ONE_APP = "Share one app"
        private const val SCREEN_MIRROR_TEXT_SHARE_ENTIRE_SCREEN = "Share entire screen"
        private const val SCREEN_MIRROR_TEXT_SHARE_SCREEN = "Share screen"
        private const val SCREEN_MIRROR_TEXT_NEXT = "Next"

        private val VERIFY_CHECKPOINTS_MS = longArrayOf(220L, 480L, 820L, 1120L)
        private val RECENTS_HINTS = listOf(
            "recents",
            "recent",
            "overview",
            "task",
            "clear all",
            "dismiss all",
            "app switcher",
            "quickstep",
            "launcher"
        )

        @Volatile
        private var connectedService: AutoCallAccessibilityService? = null

        @Volatile
        private var connectedFlag = false

        data class ReturnToAutoCallExecutionResult(
            val success: Boolean,
            val noOp: Boolean,
            val failureReason: String?,
            val message: String,
            val recentsOpened: Boolean,
            val recentsVisibleAfterClick: Boolean,
            val topPackageAfterClick: String?,
            val mainActivityWindowFocusedAfterClick: Boolean
        )

        fun isServiceConnected(): Boolean {
            return connectedFlag && connectedService != null
        }

        fun isServiceInstancePresent(): Boolean {
            return connectedService != null
        }

        fun performReturnToAutoCall(
            targetPackageName: String,
            targetAppLabel: String?,
            timeoutMs: Long
        ): ReturnToAutoCallExecutionResult {
            val service = connectedService
            if (service == null) {
                return ReturnToAutoCallExecutionResult(
                    success = false,
                    noOp = false,
                    failureReason = "accessibility_service_not_connected",
                    message = "Accessibility service is not connected",
                    recentsOpened = false,
                    recentsVisibleAfterClick = false,
                    topPackageAfterClick = null,
                    mainActivityWindowFocusedAfterClick = false
                )
            }
            return service.executeReturnToAutoCall(
                targetPackageName = targetPackageName,
                targetAppLabel = targetAppLabel,
                timeoutMs = timeoutMs
            )
        }
    }

    private data class TaskCardCandidate(
        val node: AccessibilityNodeInfo,
        val clickableAncestor: AccessibilityNodeInfo?,
        val containerAncestor: AccessibilityNodeInfo?,
        val matchedBy: String,
        val nodeDescription: String,
        val nodeClassName: String?,
        val nodeBounds: Rect,
        val nodeClickable: Boolean,
        val ancestorDescription: String?,
        val ancestorBounds: Rect?
    )

    private data class RecentsState(
        val visible: Boolean,
        val evidence: String
    )

    private data class VerificationState(
        val success: Boolean,
        val recentsVisible: Boolean,
        val topPackage: String?,
        val isMainActivityResumed: Boolean,
        val isMainActivityWindowFocused: Boolean,
        val recentsEvidence: String
    )

    @Volatile
    private var lastObservedPackageFromEvents: String? = null
    private val screenMirrorRetryHandler = Handler(Looper.getMainLooper())
    private val screenMirrorAutomationRunning = AtomicBoolean(false)

    @Volatile
    private var screenMirrorDeadlineAtMs = 0L

    @Volatile
    private var screenMirrorShareOneAppDropdownClicked = false

    @Volatile
    private var screenMirrorShareEntireScreenSelected = false

    @Volatile
    private var screenMirrorDetectedLogged = false

    private val screenMirrorRetryRunnable = object : Runnable {
        override fun run() {
            runScreenMirrorAutomationStep()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        connectedFlag = true
        Log.i(TAG, "accessibility connected")
    }

    override fun onInterrupt() {
        Log.w(TAG, "accessibility interrupted")
    }

    override fun onDestroy() {
        stopScreenMirrorAutomation()
        if (connectedService == this) {
            connectedService = null
        }
        connectedFlag = false
        Log.i(TAG, "accessibility destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()?.trim()
        if (!packageName.isNullOrBlank()) {
            lastObservedPackageFromEvents = packageName
        }

        if (!ScreenMirrorAutomationState.isWaitingForPermission) {
            return
        }
        if (!ScreenMirrorAutomationState.isSamsungOneUiTarget()) {
            return
        }
        if (packageName != SCREEN_MIRROR_SYSTEMUI_PACKAGE) {
            return
        }

        if (!screenMirrorDetectedLogged) {
            screenMirrorDetectedLogged = true
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] detected")
        }
        startScreenMirrorAutomation()
    }

    private fun startScreenMirrorAutomation() {
        if (!screenMirrorAutomationRunning.compareAndSet(false, true)) {
            return
        }
        screenMirrorDeadlineAtMs = SystemClock.elapsedRealtime() + SCREEN_MIRROR_TIMEOUT_MS
        screenMirrorShareOneAppDropdownClicked = false
        screenMirrorShareEntireScreenSelected = false
        screenMirrorRetryHandler.removeCallbacks(screenMirrorRetryRunnable)
        screenMirrorRetryHandler.post(screenMirrorRetryRunnable)
    }

    private fun stopScreenMirrorAutomation() {
        screenMirrorRetryHandler.removeCallbacks(screenMirrorRetryRunnable)
        screenMirrorAutomationRunning.set(false)
        screenMirrorDeadlineAtMs = 0L
        screenMirrorShareOneAppDropdownClicked = false
        screenMirrorShareEntireScreenSelected = false
        screenMirrorDetectedLogged = false
    }

    private fun runScreenMirrorAutomationStep() {
        if (!screenMirrorAutomationRunning.get()) {
            return
        }
        if (!ScreenMirrorAutomationState.isWaitingForPermission) {
            stopScreenMirrorAutomation()
            return
        }
        if (!ScreenMirrorAutomationState.isSamsungOneUiTarget()) {
            stopScreenMirrorAutomation()
            return
        }

        if (SystemClock.elapsedRealtime() >= screenMirrorDeadlineAtMs) {
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] timeout")
            ScreenMirrorAutomationState.endPermissionFlow()
            stopScreenMirrorAutomation()
            return
        }

        val roots = collectWindowRoots()
        try {
            if (!screenMirrorShareOneAppDropdownClicked) {
                val clickedShareOneAppDropdown = clickByExactText(
                    roots = roots,
                    targetText = SCREEN_MIRROR_TEXT_SHARE_ONE_APP
                )
                if (clickedShareOneAppDropdown) {
                    screenMirrorShareOneAppDropdownClicked = true
                    Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked share one app dropdown")
                    screenMirrorRetryHandler.postDelayed(
                        screenMirrorRetryRunnable,
                        SCREEN_MIRROR_RETRY_INTERVAL_MS
                    )
                    return
                }
            }

            if (!screenMirrorShareEntireScreenSelected) {
                val clickedShareEntireScreen = clickByExactText(
                    roots = roots,
                    targetText = SCREEN_MIRROR_TEXT_SHARE_ENTIRE_SCREEN
                )
                if (clickedShareEntireScreen) {
                    screenMirrorShareEntireScreenSelected = true
                    Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] selected share entire screen")
                    screenMirrorRetryHandler.postDelayed(
                        screenMirrorRetryRunnable,
                        SCREEN_MIRROR_RETRY_INTERVAL_MS
                    )
                    return
                }
            }

            val clickedShareScreen = clickByExactText(
                roots = roots,
                targetText = SCREEN_MIRROR_TEXT_SHARE_SCREEN
            )
            if (clickedShareScreen) {
                Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked share screen")
                ScreenMirrorAutomationState.endPermissionFlow()
                stopScreenMirrorAutomation()
                return
            }

            val clickedNext = clickByExactText(
                roots = roots,
                targetText = SCREEN_MIRROR_TEXT_NEXT
            )
            if (clickedNext) {
                ScreenMirrorAutomationState.endPermissionFlow()
                stopScreenMirrorAutomation()
                return
            }
        } finally {
            roots.forEach { it.recycle() }
        }

        screenMirrorRetryHandler.postDelayed(screenMirrorRetryRunnable, SCREEN_MIRROR_RETRY_INTERVAL_MS)
    }

    private fun clickByExactText(
        roots: List<AccessibilityNodeInfo>,
        targetText: String
    ): Boolean {
        return clickByExactTexts(roots = roots, targetTexts = listOf(targetText))
    }

    private fun clickByExactTexts(
        roots: List<AccessibilityNodeInfo>,
        targetTexts: List<String>
    ): Boolean {
        if (roots.isEmpty() || targetTexts.isEmpty()) {
            return false
        }
        val normalizedTargets = targetTexts
            .map { normalizeText(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedTargets.isEmpty()) {
            return false
        }

        for (root in roots) {
            if (clickTargetInTree(root, normalizedTargets)) {
                return true
            }
        }
        return false
    }

    private fun clickTargetInTree(
        root: AccessibilityNodeInfo,
        normalizedTargets: Set<String>
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = mutableListOf<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var scannedNodes = 0
        try {
            while (queue.isNotEmpty() && scannedNodes < MAX_NODE_SCAN_PER_ROOT) {
                val node = queue.removeFirst()
                visited.add(node)
                scannedNodes += 1

                if (isSystemUiNode(node) && nodeMatchesTarget(node, normalizedTargets)) {
                    if (clickNodeOrAncestor(node)) {
                        return true
                    }
                }

                for (childIndex in 0 until node.childCount) {
                    node.getChild(childIndex)?.let { child ->
                        queue.add(child)
                    }
                }
            }
        } finally {
            visited.forEach { it.recycle() }
            while (queue.isNotEmpty()) {
                queue.removeFirst().recycle()
            }
        }
        return false
    }

    private fun isSystemUiNode(node: AccessibilityNodeInfo): Boolean {
        return node.packageName?.toString()?.trim() == SCREEN_MIRROR_SYSTEMUI_PACKAGE
    }

    private fun nodeMatchesTarget(
        node: AccessibilityNodeInfo,
        normalizedTargets: Set<String>
    ): Boolean {
        val textValue = normalizeText(node.text?.toString().orEmpty())
        if (textValue in normalizedTargets) {
            return true
        }
        val descriptionValue = normalizeText(node.contentDescription?.toString().orEmpty())
        return descriptionValue in normalizedTargets
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) {
            return try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to click target node", error)
                false
            }
        }

        val clickableAncestor = findClickableAncestor(node) ?: return false
        return try {
            clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to click target ancestor", error)
            false
        } finally {
            clickableAncestor.recycle()
        }
    }

    private fun executeReturnToAutoCall(
        targetPackageName: String,
        targetAppLabel: String?,
        timeoutMs: Long
    ): ReturnToAutoCallExecutionResult {
        val deadlineAtMs = SystemClock.elapsedRealtime() + timeoutMs
        val labelHints = buildLabelHints(targetPackageName, targetAppLabel)

        Log.i(
            TAG,
            "RETURN_TO_AUTOCALL accessibility execution started " +
                "targetPackage=$targetPackageName targetLabel=${targetAppLabel ?: "null"} timeoutMs=$timeoutMs"
        )

        val preVerification = verifyForegroundStrict(
            targetPackageName = targetPackageName,
            deadlineAtMs = minOf(deadlineAtMs, SystemClock.elapsedRealtime() + 250L)
        )
        if (preVerification.success) {
            Log.i(
                TAG,
                "verification success (already foreground) topPackage=${preVerification.topPackage ?: "unknown"}"
            )
            return ReturnToAutoCallExecutionResult(
                success = true,
                noOp = true,
                failureReason = null,
                message = "autocall_already_in_foreground",
                recentsOpened = false,
                recentsVisibleAfterClick = preVerification.recentsVisible,
                topPackageAfterClick = preVerification.topPackage,
                mainActivityWindowFocusedAfterClick = preVerification.isMainActivityWindowFocused
            )
        }

        Log.i(TAG, "opening recents")
        val recentsOpened = try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (error: Throwable) {
            Log.e(TAG, "opening recents failed with exception", error)
            false
        }

        if (!recentsOpened) {
            Log.w(TAG, "recents open failed")
            return ReturnToAutoCallExecutionResult(
                success = false,
                noOp = false,
                failureReason = "failed_to_open_recents",
                message = "Failed to open recents",
                recentsOpened = false,
                recentsVisibleAfterClick = false,
                topPackageAfterClick = resolveTopPackage(),
                mainActivityWindowFocusedAfterClick = AppForegroundTracker.snapshot().isMainActivityWindowFocused
            )
        }

        Log.i(TAG, "recents opened")
        SystemClock.sleep(minOf(RECENTS_SETTLE_MS, remainingTimeout(deadlineAtMs)))

        var clickAttemptNumber = 0
        var strategyUsed: String? = null
        var autocallCardFound = false
        var chosenNodeDescription: String? = null
        var chosenNodeClass: String? = null
        var chosenNodeBounds: String? = null
        var chosenNodeClickable = false
        var ancestorChosen: String? = null
        var latestVerification = verifyForegroundStrict(
            targetPackageName = targetPackageName,
            deadlineAtMs = minOf(deadlineAtMs, SystemClock.elapsedRealtime() + 120L)
        )

        while (SystemClock.elapsedRealtime() < deadlineAtMs) {
            Log.i(TAG, "scanning node tree")
            val candidate = findBestTaskCardCandidate(
                targetPackageName = targetPackageName,
                labelHints = labelHints
            )
            if (candidate == null) {
                Log.i(TAG, "no AutoCall candidate found in current scan")
                val sleepMs = minOf(RESCAN_INTERVAL_MS, remainingTimeout(deadlineAtMs))
                if (sleepMs > 0L) {
                    SystemClock.sleep(sleepMs)
                }
                continue
            }

            autocallCardFound = true
            chosenNodeDescription = candidate.nodeDescription
            chosenNodeClass = candidate.nodeClassName
            chosenNodeBounds = rectToString(candidate.nodeBounds)
            chosenNodeClickable = candidate.nodeClickable
            ancestorChosen = candidate.ancestorDescription ?: "none"

            Log.i(
                TAG,
                "found candidate matchedBy=${candidate.matchedBy} " +
                    "chosenNodeDescription=$chosenNodeDescription " +
                    "chosenNodeClass=${chosenNodeClass ?: "none"} " +
                    "chosenNodeBounds=$chosenNodeBounds " +
                    "chosenNodeClickable=$chosenNodeClickable " +
                    "ancestorChosen=$ancestorChosen"
            )

            val strategies = mutableListOf<Pair<String, () -> Boolean>>()
            strategies.add("node_action_click" to {
                runCatching {
                    candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrElse {
                    Log.e(TAG, "node ACTION_CLICK failed", it)
                    false
                }
            })

            if (candidate.clickableAncestor != null) {
                strategies.add("ancestor_action_click" to {
                    runCatching {
                        candidate.clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrElse {
                        Log.e(TAG, "ancestor ACTION_CLICK failed", it)
                        false
                    }
                })
            }

            strategies.add("node_center_gesture_tap" to {
                performGestureTap(candidate.nodeBounds)
            })

            val containerBounds = candidate.ancestorBounds ?: candidate.nodeBounds
            strategies.add("container_center_gesture_tap" to {
                performGestureTap(containerBounds)
            })

            for ((strategyName, action) in strategies) {
                clickAttemptNumber += 1
                strategyUsed = strategyName
                Log.i(TAG, "trying click strategy #$clickAttemptNumber strategy=$strategyName")
                val clickApplied = action()
                Log.i(TAG, "click strategy result strategy=$strategyName applied=$clickApplied")

                val settleMs = minOf(CLICK_SETTLE_MS, remainingTimeout(deadlineAtMs))
                if (settleMs > 0L) {
                    SystemClock.sleep(settleMs)
                }

                latestVerification = verifyForegroundStrict(
                    targetPackageName = targetPackageName,
                    deadlineAtMs = minOf(deadlineAtMs, SystemClock.elapsedRealtime() + VERIFY_WINDOW_MS)
                )

                Log.i(
                    TAG,
                    "verification after click " +
                        "strategy=$strategyName success=${latestVerification.success} " +
                        "recentsVisibleAfterClick=${latestVerification.recentsVisible} " +
                        "topPackageAfterClick=${latestVerification.topPackage ?: "unknown"} " +
                        "mainActivityWindowFocusedAfterClick=${latestVerification.isMainActivityWindowFocused}"
                )

                if (latestVerification.success) {
                    recycleCandidate(candidate)
                    Log.i(
                        TAG,
                        "verification success " +
                            "recentsOpened=$recentsOpened autocallCardFound=$autocallCardFound " +
                            "clickStrategyUsed=$strategyUsed clickAttemptNumber=$clickAttemptNumber"
                    )
                    return ReturnToAutoCallExecutionResult(
                        success = true,
                        noOp = false,
                        failureReason = null,
                        message = "returned_to_autocall",
                        recentsOpened = recentsOpened,
                        recentsVisibleAfterClick = latestVerification.recentsVisible,
                        topPackageAfterClick = latestVerification.topPackage,
                        mainActivityWindowFocusedAfterClick = latestVerification.isMainActivityWindowFocused
                    )
                }
            }

            recycleCandidate(candidate)

            val sleepMs = minOf(RESCAN_INTERVAL_MS, remainingTimeout(deadlineAtMs))
            if (sleepMs > 0L) {
                SystemClock.sleep(sleepMs)
            }
        }

        val failureReason = if (!autocallCardFound) {
            "recents_opened_but_autocall_task_not_selected"
        } else {
            "recents_task_click_did_not_open_autocall"
        }

        Log.w(
            TAG,
            "verification failed " +
                "recentsOpened=$recentsOpened autocallCardFound=$autocallCardFound " +
                "chosenNodeDescription=${chosenNodeDescription ?: "none"} " +
                "chosenNodeClass=${chosenNodeClass ?: "none"} " +
                "chosenNodeBounds=${chosenNodeBounds ?: "none"} " +
                "chosenNodeClickable=$chosenNodeClickable " +
                "ancestorChosen=${ancestorChosen ?: "none"} " +
                "clickStrategyUsed=${strategyUsed ?: "none"} " +
                "clickAttemptNumber=$clickAttemptNumber " +
                "recentsVisibleAfterClick=${latestVerification.recentsVisible} " +
                "topPackageAfterClick=${latestVerification.topPackage ?: "unknown"} " +
                "mainActivityWindowFocusedAfterClick=${latestVerification.isMainActivityWindowFocused} " +
                "finalDecision=failed failureReason=$failureReason"
        )

        return ReturnToAutoCallExecutionResult(
            success = false,
            noOp = false,
            failureReason = failureReason,
            message = failureReason,
            recentsOpened = recentsOpened,
            recentsVisibleAfterClick = latestVerification.recentsVisible,
            topPackageAfterClick = latestVerification.topPackage,
            mainActivityWindowFocusedAfterClick = latestVerification.isMainActivityWindowFocused
        )
    }

    private fun findBestTaskCardCandidate(
        targetPackageName: String,
        labelHints: Set<String>
    ): TaskCardCandidate? {
        val roots = collectWindowRoots()
        if (roots.isEmpty()) {
            return null
        }

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        var bestMatchedBy = "none"

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var scanned = 0

            while (queue.isNotEmpty() && scanned < MAX_NODE_SCAN_PER_ROOT) {
                val node = queue.removeFirst()
                scanned += 1

                try {
                    val packageName = node.packageName?.toString().orEmpty()
                    val className = node.className?.toString().orEmpty()
                    val text = node.text?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty()
                    val viewId = node.viewIdResourceName.orEmpty()

                    var score = Int.MIN_VALUE
                    var matchedBy = "none"

                    if (packageName.equals(targetPackageName, ignoreCase = true)) {
                        score = 900
                        matchedBy = "package"
                    } else if (containsAnyHint(text, labelHints)) {
                        score = 700
                        matchedBy = "text"
                    } else if (containsAnyHint(description, labelHints)) {
                        score = 620
                        matchedBy = "content_description"
                    } else if (containsAnyHint(viewId, labelHints)) {
                        score = 500
                        matchedBy = "view_id"
                    }

                    if (score != Int.MIN_VALUE) {
                        if (looksLikeTaskSurface(className, viewId, text, description)) {
                            score += 220
                        }
                        if (node.isClickable) {
                            score += 70
                        }

                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        val areaScore = (maxOf(0, bounds.width()) * maxOf(0, bounds.height())) / 9000
                        score += minOf(200, areaScore)

                        if (score > bestScore) {
                            bestNode?.recycle()
                            bestNode = AccessibilityNodeInfo.obtain(node)
                            bestScore = score
                            bestMatchedBy = matchedBy
                        }
                    }

                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        }

        if (bestNode == null) {
            return null
        }

        val nodeBounds = Rect().also { bestNode.getBoundsInScreen(it) }
        val nodeSummary = summarizeNode(bestNode)
        val clickableAncestor = findClickableAncestor(bestNode)
        val containerAncestor = findLargestContainerAncestor(bestNode)
        val ancestorBounds = containerAncestor?.let { ancestor ->
            Rect().also { ancestor.getBoundsInScreen(it) }
        }

        return TaskCardCandidate(
            node = bestNode,
            clickableAncestor = clickableAncestor,
            containerAncestor = containerAncestor,
            matchedBy = bestMatchedBy,
            nodeDescription = nodeSummary,
            nodeClassName = bestNode.className?.toString(),
            nodeBounds = nodeBounds,
            nodeClickable = bestNode.isClickable,
            ancestorDescription = clickableAncestor?.let { summarizeNode(it) },
            ancestorBounds = ancestorBounds
        )
    }

    private fun recycleCandidate(candidate: TaskCardCandidate) {
        candidate.node.recycle()
        candidate.clickableAncestor?.recycle()
        candidate.containerAncestor?.recycle()
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth <= 10) {
            if (current.isClickable) {
                val copy = AccessibilityNodeInfo.obtain(current)
                current.recycle()
                return copy
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth += 1
        }
        return null
    }

    private fun findLargestContainerAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1

        while (current != null && depth <= 10) {
            val rect = Rect()
            current.getBoundsInScreen(rect)
            val area = maxOf(0, rect.width()) * maxOf(0, rect.height())
            if (area > bestArea) {
                best?.recycle()
                best = AccessibilityNodeInfo.obtain(current)
                bestArea = area
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth += 1
        }

        return best
    }

    private fun performGestureTap(bounds: Rect): Boolean {
        if (bounds.width() <= 1 || bounds.height() <= 1) {
            return false
        }

        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f
        val path = Path().apply { moveTo(centerX, centerY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val dispatched = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null
            )
        } catch (error: Throwable) {
            Log.e(TAG, "gesture dispatch failed", error)
            false
        }

        if (!dispatched) {
            return false
        }

        return try {
            latch.await(GESTURE_WAIT_MS, TimeUnit.MILLISECONDS)
            completed.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun verifyForegroundStrict(
        targetPackageName: String,
        deadlineAtMs: Long
    ): VerificationState {
        val startAt = SystemClock.elapsedRealtime()
        var latest = observeState(targetPackageName)

        for (offset in VERIFY_CHECKPOINTS_MS) {
            val checkpoint = minOf(startAt + offset, deadlineAtMs)
            val sleepMs = checkpoint - SystemClock.elapsedRealtime()
            if (sleepMs > 0L) {
                SystemClock.sleep(sleepMs)
            }

            latest = observeState(targetPackageName)
            Log.i(
                TAG,
                "verification checkpoint offset=${offset}ms " +
                    "recentsVisible=${latest.recentsVisible} " +
                    "topPackage=${latest.topPackage ?: "unknown"} " +
                    "mainActivityResumed=${latest.isMainActivityResumed} " +
                    "mainActivityWindowFocused=${latest.isMainActivityWindowFocused}"
            )

            if (
                !latest.recentsVisible &&
                latest.topPackage == targetPackageName &&
                latest.isMainActivityResumed &&
                latest.isMainActivityWindowFocused
            ) {
                return latest.copy(success = true)
            }

            if (SystemClock.elapsedRealtime() >= deadlineAtMs) {
                break
            }
        }

        return latest.copy(success = false)
    }

    private fun observeState(targetPackageName: String): VerificationState {
        val recentsState = detectRecentsVisibility(targetPackageName)
        val foreground = AppForegroundTracker.snapshot()
        return VerificationState(
            success = false,
            recentsVisible = recentsState.visible,
            topPackage = resolveTopPackage(),
            isMainActivityResumed = foreground.isMainActivityResumed,
            isMainActivityWindowFocused = foreground.isMainActivityWindowFocused,
            recentsEvidence = recentsState.evidence
        )
    }

    private fun detectRecentsVisibility(targetPackageName: String): RecentsState {
        val normalizedTarget = normalizeText(targetPackageName)
        val evidence = mutableListOf<String>()

        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                val activePackage = normalizeText(activeRoot.packageName?.toString().orEmpty())
                val activeClass = normalizeText(activeRoot.className?.toString().orEmpty())
                if (
                    activePackage.isNotBlank() &&
                    activePackage != normalizedTarget &&
                    (isRecentsPackage(activePackage) || containsAnyHint(activeClass, RECENTS_HINTS))
                ) {
                    evidence.add("active_root pkg=${activeRoot.packageName ?: "none"} class=${activeRoot.className ?: "none"}")
                    return RecentsState(true, evidence.joinToString(" | "))
                }
            } finally {
                activeRoot.recycle()
            }
        }

        val windowsSnapshot = windows.orEmpty().take(10)
        for (window in windowsSnapshot) {
            val root = window.root
            if (root != null) {
                try {
                    val pkg = normalizeText(root.packageName?.toString().orEmpty())
                    val cls = normalizeText(root.className?.toString().orEmpty())
                    if (
                        pkg.isNotBlank() &&
                        pkg != normalizedTarget &&
                        isRecentsPackage(pkg) &&
                        (window.isActive || window.isFocused || containsAnyHint(cls, RECENTS_HINTS))
                    ) {
                        evidence.add(
                            "window pkg=${root.packageName ?: "none"} class=${root.className ?: "none"} " +
                                "type=${window.type} active=${window.isActive} focused=${window.isFocused}"
                        )
                        return RecentsState(true, evidence.joinToString(" | "))
                    }
                } finally {
                    root.recycle()
                }
            }
        }

        val roots = collectWindowRoots()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var scanned = 0
            while (queue.isNotEmpty() && scanned < MAX_NODE_SCAN_PER_ROOT) {
                val node = queue.removeFirst()
                scanned += 1
                try {
                    val pkg = normalizeText(node.packageName?.toString().orEmpty())
                    val cls = normalizeText(node.className?.toString().orEmpty())
                    val txt = normalizeText(node.text?.toString().orEmpty())
                    val desc = normalizeText(node.contentDescription?.toString().orEmpty())
                    val viewId = normalizeText(node.viewIdResourceName.orEmpty())

                    val packageLooksRecents =
                        pkg.isNotBlank() && pkg != normalizedTarget && isRecentsPackage(pkg)
                    val hasRecentsHints =
                        containsAnyHint(txt, RECENTS_HINTS) ||
                            containsAnyHint(desc, RECENTS_HINTS) ||
                            containsAnyHint(viewId, RECENTS_HINTS) ||
                            containsAnyHint(cls, RECENTS_HINTS)

                    if (packageLooksRecents && hasRecentsHints) {
                        evidence.add(
                            "node pkg=${node.packageName ?: "none"} class=${node.className ?: "none"} " +
                                "text=${node.text ?: "none"} desc=${node.contentDescription ?: "none"} " +
                                "id=${node.viewIdResourceName ?: "none"}"
                        )
                        return RecentsState(true, evidence.joinToString(" | "))
                    }

                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        }

        return RecentsState(false, evidence.ifEmpty { listOf("none") }.joinToString(" | "))
    }

    private fun collectWindowRoots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<String>()

        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                val copy = AccessibilityNodeInfo.obtain(activeRoot)
                result.add(copy)
                seen.add(signatureForNode(copy))
            } finally {
                activeRoot.recycle()
            }
        }

        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            try {
                val signature = signatureForNode(root)
                if (seen.contains(signature)) {
                    continue
                }
                result.add(AccessibilityNodeInfo.obtain(root))
                seen.add(signature)
            } finally {
                root.recycle()
            }
        }

        return result
    }

    private fun signatureForNode(node: AccessibilityNodeInfo): String {
        return "${node.packageName ?: "none"}|${node.className ?: "none"}|${node.viewIdResourceName ?: "none"}"
    }

    private fun resolveTopPackage(): String? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                val pkg = activeRoot.packageName?.toString()?.trim()
                if (!pkg.isNullOrBlank()) {
                    return pkg
                }
            } finally {
                activeRoot.recycle()
            }
        }

        for (window in windows.orEmpty()) {
            if (!window.isActive && !window.isFocused) {
                continue
            }
            val root = window.root ?: continue
            try {
                val pkg = root.packageName?.toString()?.trim()
                if (!pkg.isNullOrBlank()) {
                    return pkg
                }
            } finally {
                root.recycle()
            }
        }

        val manager = getSystemService(ActivityManager::class.java)
        val foregroundProcess = manager?.runningAppProcesses
            ?.firstOrNull { process ->
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            }
        if (foregroundProcess != null) {
            return foregroundProcess.pkgList?.firstOrNull() ?: foregroundProcess.processName
        }

        val taskTop = manager?.appTasks
            ?.firstOrNull()
            ?.taskInfo
            ?.topActivity
            ?.packageName
        if (!taskTop.isNullOrBlank()) {
            return taskTop
        }

        return lastObservedPackageFromEvents
    }

    private fun buildLabelHints(targetPackageName: String, targetAppLabel: String?): Set<String> {
        val hints = linkedSetOf<String>()
        hints.add(targetPackageName)
        hints.add(targetPackageName.substringAfterLast('.'))
        hints.add("autocall")
        targetAppLabel?.trim()?.takeIf { it.isNotBlank() }?.let { hints.add(it) }

        return hints
            .map { normalizeText(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun looksLikeTaskSurface(
        className: String,
        viewId: String,
        text: String,
        description: String
    ): Boolean {
        val cls = normalizeText(className)
        val id = normalizeText(viewId)
        val txt = normalizeText(text)
        val desc = normalizeText(description)
        return cls.contains("task") ||
            cls.contains("card") ||
            cls.contains("overview") ||
            cls.contains("recents") ||
            id.contains("task") ||
            id.contains("card") ||
            id.contains("overview") ||
            id.contains("recents") ||
            txt.contains("open") ||
            desc.contains("open")
    }

    private fun containsAnyHint(value: String, hints: Collection<String>): Boolean {
        if (value.isBlank() || hints.isEmpty()) {
            return false
        }
        val normalized = normalizeText(value)
        return hints.any { hint -> normalized.contains(hint) }
    }

    private fun isRecentsPackage(packageName: String): Boolean {
        val normalized = normalizeText(packageName)
        return normalized.contains("launcher") ||
            normalized.contains("quickstep") ||
            normalized.contains("systemui")
    }

    private fun normalizeText(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun summarizeNode(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return "pkg=${node.packageName ?: "none"} class=${node.className ?: "none"} " +
            "id=${node.viewIdResourceName ?: "none"} text=${node.text ?: "none"} " +
            "desc=${node.contentDescription ?: "none"} clickable=${node.isClickable} bounds=${rectToString(rect)}"
    }

    private fun rectToString(rect: Rect): String {
        return "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
    }

    private fun remainingTimeout(deadlineAtMs: Long): Long {
        return maxOf(0L, deadlineAtMs - SystemClock.elapsedRealtime())
    }
}
