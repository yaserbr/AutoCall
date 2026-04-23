package com.yaser8541.autocallapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

object AutoCallAccessibilityHelper {
    private const val TAG = "AutoCall/AccessibilityHelper"

    data class StatusSnapshot(
        val enabled: Boolean,
        val connected: Boolean,
        val serviceInstancePresent: Boolean,
        val ready: Boolean,
        val expectedServiceComponent: String,
        val enabledServicesFromAccessibilityManager: List<String>,
        val enabledAccessibilityServicesRaw: String
    )

    fun snapshot(context: Context): StatusSnapshot {
        val expectedComponent = ComponentName(context, AutoCallAccessibilityService::class.java)
        val expectedComponentFull = expectedComponent.flattenToString()
        val enabledServicesFromManager = queryEnabledServicesFromAccessibilityManager(context)
        val enabledServicesRaw = readEnabledAccessibilityServicesRaw(context)

        val exactMatchFromManager = enabledServicesFromManager.any { candidate ->
            matchesExpectedComponent(candidate, expectedComponent)
        }
        val exactMatchFromSettings = parseEnabledServicesRaw(enabledServicesRaw).any { candidate ->
            matchesExpectedComponent(candidate, expectedComponent)
        }

        val connected = AutoCallAccessibilityService.isServiceConnected()
        val instancePresent = AutoCallAccessibilityService.isServiceInstancePresent()
        val enabled = exactMatchFromManager || exactMatchFromSettings || connected
        val ready = enabled && connected && instancePresent

        Log.i(
            TAG,
            "Accessibility status " +
                "expectedServiceComponent=$expectedComponentFull " +
                "enabledServicesFromAccessibilityManager=" +
                "${enabledServicesFromManager.ifEmpty { listOf("none") }.joinToString(",")} " +
                "enabledAccessibilityServicesRaw=${enabledServicesRaw.ifBlank { "null_or_empty" }} " +
                "enabled=$enabled connected=$connected serviceInstancePresent=$instancePresent ready=$ready"
        )

        return StatusSnapshot(
            enabled = enabled,
            connected = connected,
            serviceInstancePresent = instancePresent,
            ready = ready,
            expectedServiceComponent = expectedComponentFull,
            enabledServicesFromAccessibilityManager = enabledServicesFromManager,
            enabledAccessibilityServicesRaw = enabledServicesRaw
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        return snapshot(context).enabled
    }

    fun isAccessibilityReady(context: Context): Boolean {
        return snapshot(context).ready
    }

    private fun queryEnabledServicesFromAccessibilityManager(context: Context): List<String> {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return emptyList()
        return try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .flatMap { info ->
                    val results = mutableListOf<String>()
                    val id = info.id?.trim()
                    if (!id.isNullOrBlank()) {
                        results.add(id)
                    }

                    val serviceInfo = info.resolveInfo?.serviceInfo
                    val packageName = serviceInfo?.packageName
                    var className = serviceInfo?.name
                    if (!packageName.isNullOrBlank() && !className.isNullOrBlank()) {
                        if (className.startsWith(".")) {
                            className = packageName + className
                        }
                        val component = ComponentName(packageName, className)
                        results.add(component.flattenToString())
                        results.add(component.flattenToShortString())
                    }
                    results
                }
                .distinct()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to query enabled services from AccessibilityManager", error)
            emptyList()
        }
    }

    private fun readEnabledAccessibilityServicesRaw(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read ENABLED_ACCESSIBILITY_SERVICES", error)
            ""
        }
    }

    private fun parseEnabledServicesRaw(rawValue: String): List<String> {
        if (rawValue.isBlank()) {
            return emptyList()
        }
        return rawValue.split(':').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun matchesExpectedComponent(
        candidate: String,
        expectedComponent: ComponentName
    ): Boolean {
        val normalized = candidate.trim()
        if (normalized.isBlank()) {
            return false
        }

        val expectedFull = expectedComponent.flattenToString()
        val expectedShort = expectedComponent.flattenToShortString()
        if (normalized.equals(expectedFull, ignoreCase = true)) {
            return true
        }
        if (normalized.equals(expectedShort, ignoreCase = true)) {
            return true
        }

        val parsed = ComponentName.unflattenFromString(normalized)
        if (parsed != null) {
            return componentsEqual(parsed, expectedComponent)
        }

        val slashIndex = normalized.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= normalized.length - 1) {
            return false
        }
        val packageName = normalized.substring(0, slashIndex).trim()
        var className = normalized.substring(slashIndex + 1).trim()
        if (className.startsWith(".")) {
            className = packageName + className
        }

        return packageName.equals(expectedComponent.packageName, ignoreCase = true) &&
            className.equals(expectedComponent.className, ignoreCase = true)
    }

    private fun componentsEqual(left: ComponentName, right: ComponentName): Boolean {
        return left.packageName.equals(right.packageName, ignoreCase = true) &&
            left.className.equals(right.className, ignoreCase = true)
    }
}
