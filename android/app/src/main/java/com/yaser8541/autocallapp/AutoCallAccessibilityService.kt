package com.yaser8541.autocallapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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
        private const val REMOTE_TAP_DURATION_MS = 50L
        private const val REMOTE_GESTURE_MIN_DURATION_MS = 50L
        private const val REMOTE_GESTURE_MAX_DURATION_MS = 10000L
        private const val MAX_NODE_SCAN_PER_ROOT = 650
        private const val SCREEN_MIRROR_SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SCREEN_MIRROR_PERMISSION_CONTROLLER_PACKAGE =
            "com.google.android.permissioncontroller"
        private const val SCREEN_MIRROR_RETRY_INTERVAL_MS = 250L
        private const val SCREEN_MIRROR_TIMEOUT_MS = 5000L
        private const val SCREEN_MIRROR_MANUFACTURER_SAMSUNG = "samsung"
        private const val SCREEN_MIRROR_MANUFACTURER_MOTOROLA = "motorola"

        private const val SAMSUNG_TEXT_SHARE_ONE_APP = "Share one app"
        private const val SAMSUNG_TEXT_SHARE_ENTIRE_SCREEN = "Share entire screen"
        private const val SAMSUNG_TEXT_SHARE_SCREEN = "Share screen"
        private const val SAMSUNG_TEXT_NEXT = "Next"

        private const val MOTOROLA_DIALOG_TITLE = "Start recording or casting with autocall-app?"
        private const val MOTOROLA_TEXT_SINGLE_APP = "A single app"
        private const val MOTOROLA_TEXT_ENTIRE_SCREEN = "Entire screen"
        private const val MOTOROLA_TEXT_FULL_SCREEN = "Full screen"
        private const val MOTOROLA_TEXT_WHOLE_SCREEN = "Whole screen"
        private const val MOTOROLA_TEXT_START = "Start"

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

        data class RemoteControlExecutionResult(
            val success: Boolean,
            val failureReason: String?,
            val message: String
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

        fun performRemoteTap(x: Int, y: Int): RemoteControlExecutionResult {
            val service = connectedService
            if (service == null) {
                return RemoteControlExecutionResult(
                    success = false,
                    failureReason = "accessibility_service_not_connected",
                    message = "Accessibility service is not connected"
                )
            }

            return service.executeRemoteTap(x, y)
        }

        fun performRemoteSwipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            durationMs: Int
        ): RemoteControlExecutionResult {
            val service = connectedService
            if (service == null) {
                return RemoteControlExecutionResult(
                    success = false,
                    failureReason = "accessibility_service_not_connected",
                    message = "Accessibility service is not connected"
                )
            }

            return service.executeRemoteSwipe(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = durationMs
            )
        }

        fun performRemoteGlobalAction(target: String): RemoteControlExecutionResult {
            val service = connectedService
            if (service == null) {
                return RemoteControlExecutionResult(
                    success = false,
                    failureReason = "accessibility_service_not_connected",
                    message = "Accessibility service is not connected"
                )
            }

            return service.executeRemoteGlobalAction(target)
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

    private enum class ScreenMirrorPermissionStep {
        STEP_OPEN_DROPDOWN,
        STEP_SELECT_ENTIRE_SCREEN,
        STEP_CONFIRM
    }

    @Volatile
    private var lastObservedPackageFromEvents: String? = null
    private val screenMirrorRetryHandler = Handler(Looper.getMainLooper())
    private val screenMirrorAutomationRunning = AtomicBoolean(false)

    @Volatile
    private var screenMirrorDeadlineAtMs = 0L

    @Volatile
    private var screenMirrorPermissionStep = ScreenMirrorPermissionStep.STEP_OPEN_DROPDOWN

    @Volatile
    private var screenMirrorManufacturer: String? = null

    @Volatile
    private var motorolaEntireScreenClickCount = 0

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
        val manufacturer = resolveScreenMirrorManufacturer() ?: return
        if (!isAllowedScreenMirrorPackage(packageName)) {
            return
        }
        if (!screenMirrorDetectedLogged) {
            screenMirrorDetectedLogged = true
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] detected")
            if (manufacturer == SCREEN_MIRROR_MANUFACTURER_SAMSUNG) {
                Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] manufacturer=samsung")
            } else if (manufacturer == SCREEN_MIRROR_MANUFACTURER_MOTOROLA) {
                Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] manufacturer=motorola")
            }
        }
        startScreenMirrorAutomation(manufacturer)
    }

    private fun startScreenMirrorAutomation(manufacturer: String) {
        if (!screenMirrorAutomationRunning.compareAndSet(false, true)) {
            return
        }
        screenMirrorDeadlineAtMs = SystemClock.elapsedRealtime() + SCREEN_MIRROR_TIMEOUT_MS
        screenMirrorManufacturer = manufacturer
        screenMirrorPermissionStep = ScreenMirrorPermissionStep.STEP_OPEN_DROPDOWN
        motorolaEntireScreenClickCount = 0
        screenMirrorRetryHandler.removeCallbacks(screenMirrorRetryRunnable)
        screenMirrorRetryHandler.post(screenMirrorRetryRunnable)
    }

    private fun stopScreenMirrorAutomation() {
        screenMirrorRetryHandler.removeCallbacks(screenMirrorRetryRunnable)
        screenMirrorAutomationRunning.set(false)
        screenMirrorDeadlineAtMs = 0L
        screenMirrorManufacturer = null
        screenMirrorPermissionStep = ScreenMirrorPermissionStep.STEP_OPEN_DROPDOWN
        motorolaEntireScreenClickCount = 0
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
        val manufacturer = screenMirrorManufacturer ?: resolveScreenMirrorManufacturer()
        if (manufacturer == null) {
            stopScreenMirrorAutomation()
            return
        }
        screenMirrorManufacturer = manufacturer

        if (SystemClock.elapsedRealtime() >= screenMirrorDeadlineAtMs) {
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] timeout")
            ScreenMirrorAutomationState.endPermissionFlow()
            stopScreenMirrorAutomation()
            return
        }

        val roots = collectWindowRoots()
        try {
            if (manufacturer == SCREEN_MIRROR_MANUFACTURER_MOTOROLA &&
                !isMotorolaPermissionContextVisible(roots)
            ) {
                scheduleScreenMirrorRetry()
                return
            }

            when (screenMirrorPermissionStep) {
                ScreenMirrorPermissionStep.STEP_OPEN_DROPDOWN -> {
                    val clickedDropdown = clickByExactTexts(
                        roots = roots,
                        targetTexts = dropdownTextsForManufacturer(manufacturer)
                    )
                    if (clickedDropdown) {
                        screenMirrorPermissionStep = ScreenMirrorPermissionStep.STEP_SELECT_ENTIRE_SCREEN
                        Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked dropdown")
                    }
                }

                ScreenMirrorPermissionStep.STEP_SELECT_ENTIRE_SCREEN -> {
                    val selectedEntireScreen = if (manufacturer == SCREEN_MIRROR_MANUFACTURER_MOTOROLA) {
                        handleMotorolaEntireScreenSelectionStep(roots)
                    } else {
                        clickByExactTexts(
                            roots = roots,
                            targetTexts = entireScreenTextsForManufacturer(manufacturer)
                        )
                    }
                    if (selectedEntireScreen) {
                        screenMirrorPermissionStep = ScreenMirrorPermissionStep.STEP_CONFIRM
                        if (manufacturer == SCREEN_MIRROR_MANUFACTURER_MOTOROLA) {
                            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] entire screen selection confirmed")
                            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] moving to confirm")
                        } else {
                            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] selected entire screen")
                            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] moved to confirm step")
                        }
                    }
                }

                ScreenMirrorPermissionStep.STEP_CONFIRM -> {
                    val clickedConfirm = clickConfirmForManufacturer(
                        roots = roots,
                        manufacturer = manufacturer
                    )
                    if (clickedConfirm) {
                        Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked confirm")
                        ScreenMirrorAutomationState.endPermissionFlow()
                        stopScreenMirrorAutomation()
                        return
                    }
                }
            }
        } finally {
            roots.forEach { it.recycle() }
        }

        scheduleScreenMirrorRetry()
    }

    private fun scheduleScreenMirrorRetry() {
        screenMirrorRetryHandler.postDelayed(screenMirrorRetryRunnable, SCREEN_MIRROR_RETRY_INTERVAL_MS)
    }

    private fun resolveScreenMirrorManufacturer(): String? {
        val manufacturer = Build.MANUFACTURER?.trim()?.lowercase(Locale.US).orEmpty()
        return when (manufacturer) {
            SCREEN_MIRROR_MANUFACTURER_SAMSUNG -> SCREEN_MIRROR_MANUFACTURER_SAMSUNG
            SCREEN_MIRROR_MANUFACTURER_MOTOROLA -> SCREEN_MIRROR_MANUFACTURER_MOTOROLA
            else -> null
        }
    }

    private fun isAllowedScreenMirrorPackage(packageName: String?): Boolean {
        val normalized = packageName?.trim().orEmpty()
        return normalized == SCREEN_MIRROR_SYSTEMUI_PACKAGE ||
            normalized == SCREEN_MIRROR_PERMISSION_CONTROLLER_PACKAGE
    }

    private fun dropdownTextsForManufacturer(manufacturer: String): List<String> {
        return when (manufacturer) {
            SCREEN_MIRROR_MANUFACTURER_SAMSUNG -> listOf(SAMSUNG_TEXT_SHARE_ONE_APP)
            SCREEN_MIRROR_MANUFACTURER_MOTOROLA -> listOf(MOTOROLA_TEXT_SINGLE_APP)
            else -> emptyList()
        }
    }

    private fun entireScreenTextsForManufacturer(manufacturer: String): List<String> {
        return when (manufacturer) {
            SCREEN_MIRROR_MANUFACTURER_SAMSUNG -> listOf(SAMSUNG_TEXT_SHARE_ENTIRE_SCREEN)
            SCREEN_MIRROR_MANUFACTURER_MOTOROLA -> listOf(
                MOTOROLA_TEXT_ENTIRE_SCREEN,
                MOTOROLA_TEXT_FULL_SCREEN,
                MOTOROLA_TEXT_WHOLE_SCREEN
            )
            else -> emptyList()
        }
    }

    private fun clickConfirmForManufacturer(
        roots: List<AccessibilityNodeInfo>,
        manufacturer: String
    ): Boolean {
        return when (manufacturer) {
            SCREEN_MIRROR_MANUFACTURER_SAMSUNG -> {
                val shareScreenClicked = clickByExactText(roots, SAMSUNG_TEXT_SHARE_SCREEN)
                if (shareScreenClicked) {
                    true
                } else {
                    clickByExactText(roots, SAMSUNG_TEXT_NEXT)
                }
            }

            SCREEN_MIRROR_MANUFACTURER_MOTOROLA -> {
                clickByExactText(roots, MOTOROLA_TEXT_START)
            }

            else -> false
        }
    }

    private fun hasMotorolaDialog(roots: List<AccessibilityNodeInfo>): Boolean {
        return hasExactTextInTree(
            roots = roots,
            targetTexts = listOf(MOTOROLA_DIALOG_TITLE)
        )
    }

    private fun isMotorolaPermissionContextVisible(roots: List<AccessibilityNodeInfo>): Boolean {
        if (hasMotorolaDialog(roots)) {
            return true
        }
        if (isMotorolaEntireScreenMenuVisible(roots)) {
            return true
        }
        return hasExactTextInTree(
            roots = roots,
            targetTexts = listOf(MOTOROLA_TEXT_START)
        )
    }

    private fun handleMotorolaEntireScreenSelectionStep(
        roots: List<AccessibilityNodeInfo>
    ): Boolean {
        val entireScreenVisible = isMotorolaEntireScreenMenuVisible(roots)

        if (!entireScreenVisible) {
            return motorolaEntireScreenClickCount > 0
        }

        if (motorolaEntireScreenClickCount == 0) {
            val firstClickApplied = clickMotorolaEntireScreenOption(roots)
            if (firstClickApplied) {
                motorolaEntireScreenClickCount = 1
                Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] motorola first entire screen click")
            }
            return false
        }

        if (motorolaEntireScreenClickCount == 1) {
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] entire screen still visible")
            val secondClickApplied = clickMotorolaEntireScreenOption(roots)
            if (secondClickApplied) {
                motorolaEntireScreenClickCount = 2
                Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] motorola second entire screen click")
            }
            return false
        }

        return false
    }

    private fun isMotorolaEntireScreenMenuVisible(roots: List<AccessibilityNodeInfo>): Boolean {
        val hasSingleAppOption = hasExactTextInTree(
            roots = roots,
            targetTexts = listOf(MOTOROLA_TEXT_SINGLE_APP)
        )
        val hasEntireScreenOption = hasExactTextInTree(
            roots = roots,
            targetTexts = listOf(MOTOROLA_TEXT_ENTIRE_SCREEN)
        )
        return hasSingleAppOption && hasEntireScreenOption
    }

    private fun clickMotorolaEntireScreenOption(roots: List<AccessibilityNodeInfo>): Boolean {
        if (roots.isEmpty()) {
            return false
        }
        val targets = setOf(normalizeText(MOTOROLA_TEXT_ENTIRE_SCREEN))
        val menuCandidates = mutableListOf<AccessibilityNodeInfo>()
        val fallbackCandidates = mutableListOf<AccessibilityNodeInfo>()

        for (root in roots) {
            collectMotorolaEntireScreenCandidatesInTree(
                root = root,
                normalizedTargets = targets,
                menuCandidates = menuCandidates,
                fallbackCandidates = fallbackCandidates
            )
        }

        val orderedCandidates = mutableListOf<AccessibilityNodeInfo>()
        orderedCandidates.addAll(menuCandidates)
        orderedCandidates.addAll(fallbackCandidates)

        for (candidate in orderedCandidates) {
            try {
                if (clickMotorolaEntireScreenCandidate(candidate)) {
                    return true
                }
            } finally {
                candidate.recycle()
            }
        }

        return false
    }

    private fun collectMotorolaEntireScreenCandidatesInTree(
        root: AccessibilityNodeInfo,
        normalizedTargets: Set<String>,
        menuCandidates: MutableList<AccessibilityNodeInfo>,
        fallbackCandidates: MutableList<AccessibilityNodeInfo>
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = mutableListOf<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var scannedNodes = 0
        try {
            while (queue.isNotEmpty() && scannedNodes < MAX_NODE_SCAN_PER_ROOT) {
                val node = queue.removeFirst()
                visited.add(node)
                scannedNodes += 1

                if (isAllowedScreenMirrorPackage(node.packageName?.toString()) &&
                    nodeMatchesTarget(node, normalizedTargets)
                ) {
                    val copy = AccessibilityNodeInfo.obtain(node)
                    if (isMotorolaEntireScreenMenuCandidate(node)) {
                        menuCandidates.add(copy)
                    } else {
                        fallbackCandidates.add(copy)
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
    }

    private fun clickMotorolaEntireScreenCandidate(node: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] found entire screen node")

        val clickedNode = performMotorolaSelectionAction(node)
        if (clickedNode) {
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked entire screen node")
            return true
        }

        val clickedParent = clickClickableParent(node)
        if (clickedParent) {
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked entire screen parent")
            return true
        }

        val bounds = resolveMotorolaEntireScreenGestureBounds(node)
        val clickedByGesture = bounds?.let { performGestureTap(it) } == true
        if (clickedByGesture) {
            Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] clicked entire screen by gesture")
            return true
        }

        Log.i(TAG, "[SCREEN_MIRROR_ACCESSIBILITY] entire screen click failed")
        return false
    }

    private fun performMotorolaSelectionAction(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                true
            } else {
                node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed Motorola selection action", error)
            false
        }
    }

    private fun isMotorolaEntireScreenMenuCandidate(node: AccessibilityNodeInfo): Boolean {
        val singleAppTargets = setOf(normalizeText(MOTOROLA_TEXT_SINGLE_APP))
        val entireScreenTargets = setOf(normalizeText(MOTOROLA_TEXT_ENTIRE_SCREEN))
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        try {
            while (current != null && depth <= 6) {
                val hasSingleApp = treeContainsTargetText(current, singleAppTargets)
                val hasEntireScreen = treeContainsTargetText(current, entireScreenTargets)
                if (hasSingleApp && hasEntireScreen) {
                    return true
                }

                val parent = current.parent
                current.recycle()
                current = parent
                depth += 1
            }
        } finally {
            current?.recycle()
        }
        return false
    }

    private fun resolveMotorolaEntireScreenGestureBounds(node: AccessibilityNodeInfo): Rect? {
        val nodeBounds = Rect().also { node.getBoundsInScreen(it) }
        var bestBounds: Rect? = if (nodeBounds.width() > 1 && nodeBounds.height() > 1) {
            Rect(nodeBounds)
        } else {
            null
        }
        var bestArea = bestBounds?.let { it.width() * it.height() } ?: -1

        var current = node.parent
        var depth = 0
        while (current != null && depth <= 6) {
            try {
                if (isAllowedScreenMirrorPackage(current.packageName?.toString())) {
                    val parentBounds = Rect().also { current.getBoundsInScreen(it) }
                    val area = parentBounds.width() * parentBounds.height()
                    if (parentBounds.width() > 1 && parentBounds.height() > 1 && area > bestArea) {
                        bestBounds = Rect(parentBounds)
                        bestArea = area
                    }
                }
                val parent = current.parent
                current.recycle()
                current = parent
                depth += 1
            } catch (_: Throwable) {
                current.recycle()
                current = null
            }
        }

        return bestBounds
    }

    private fun clickClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth <= 10) {
            if (current.isEnabled) {
                return try {
                    performMotorolaSelectionAction(current)
                } catch (error: Throwable) {
                    Log.w(TAG, "Failed to click Motorola entire screen parent", error)
                    false
                } finally {
                    current.recycle()
                }
            }

            val parent = current.parent
            current.recycle()
            current = parent
            depth += 1
        }
        return false
    }

    private fun hasExactTextInTree(
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
            if (treeContainsTargetText(root, normalizedTargets)) {
                return true
            }
        }
        return false
    }

    private fun treeContainsTargetText(
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

                if (isAllowedScreenMirrorPackage(node.packageName?.toString()) &&
                    nodeMatchesTarget(node, normalizedTargets)
                ) {
                    return true
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
        return isAllowedScreenMirrorPackage(node.packageName?.toString())
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

    private fun executeRemoteTap(x: Int, y: Int): RemoteControlExecutionResult {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val dispatched = dispatchGesturePath(path, REMOTE_TAP_DURATION_MS)
        return if (dispatched) {
            RemoteControlExecutionResult(
                success = true,
                failureReason = null,
                message = "tap_dispatched"
            )
        } else {
            RemoteControlExecutionResult(
                success = false,
                failureReason = "gesture_dispatch_failed",
                message = "Failed to dispatch tap gesture"
            )
        }
    }

    private fun executeRemoteSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int
    ): RemoteControlExecutionResult {
        val safeDurationMs = durationMs.toLong()
            .coerceAtLeast(REMOTE_GESTURE_MIN_DURATION_MS)
            .coerceAtMost(REMOTE_GESTURE_MAX_DURATION_MS)
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val dispatched = dispatchGesturePath(path, safeDurationMs)
        return if (dispatched) {
            RemoteControlExecutionResult(
                success = true,
                failureReason = null,
                message = "swipe_dispatched"
            )
        } else {
            RemoteControlExecutionResult(
                success = false,
                failureReason = "gesture_dispatch_failed",
                message = "Failed to dispatch swipe gesture"
            )
        }
    }

    private fun executeRemoteGlobalAction(target: String): RemoteControlExecutionResult {
        val normalizedTarget = target.trim().lowercase(Locale.US)
        val globalAction = when (normalizedTarget) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> null
        }
        if (globalAction == null) {
            return RemoteControlExecutionResult(
                success = false,
                failureReason = "unsupported_touch_target",
                message = "Unsupported touch target: $target"
            )
        }

        val success = try {
            performGlobalAction(globalAction)
        } catch (error: Throwable) {
            Log.e(TAG, "performGlobalAction failed for target=$normalizedTarget", error)
            false
        }

        return if (success) {
            RemoteControlExecutionResult(
                success = true,
                failureReason = null,
                message = "global_action_dispatched:$normalizedTarget"
            )
        } else {
            RemoteControlExecutionResult(
                success = false,
                failureReason = "global_action_failed",
                message = "Failed to perform global action: $normalizedTarget"
            )
        }
    }

    private fun dispatchGesturePath(path: Path, durationMs: Long): Boolean {
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDurationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                dispatchGesture(gesture, null, null)
            } catch (error: Throwable) {
                Log.e(TAG, "gesture dispatch failed", error)
                false
            }
        }

        val callbackCompleted = AtomicBoolean(false)
        val dispatchRequested = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val dispatchRunnable = Runnable {
            val dispatched = try {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            callbackCompleted.set(true)
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            callbackCompleted.set(false)
                            latch.countDown()
                        }
                    },
                    null
                )
            } catch (error: Throwable) {
                Log.e(TAG, "gesture dispatch failed", error)
                false
            }

            dispatchRequested.set(dispatched)
            if (!dispatched) {
                latch.countDown()
            }
        }

        screenMirrorRetryHandler.post(dispatchRunnable)

        val waitTimeoutMs = safeDurationMs + GESTURE_WAIT_MS + 1000L
        return try {
            latch.await(waitTimeoutMs, TimeUnit.MILLISECONDS)
            dispatchRequested.get() && callbackCompleted.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun performGestureTap(bounds: Rect): Boolean {
        if (bounds.width() <= 1 || bounds.height() <= 1) {
            return false
        }

        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f
        val path = Path().apply { moveTo(centerX, centerY) }
        return dispatchGesturePath(path, GESTURE_DURATION_MS)
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
