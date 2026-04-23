package com.yaser8541.autocallapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlin.math.abs

object InstalledAppLauncher {
    private const val TAG = "AutoCall/AppLauncher"
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
    private val GENERIC_WORDS_REGEX = Regex("\\b(app|application|android|mobile)\\b")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9\\s.]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
        Intent.FLAG_ACTIVITY_SINGLE_TOP
    private val KNOWN_ALIASES = mapOf(
        "youtube" to setOf("yt"),
        "whatsapp" to setOf("wa", "whats", "whatsapp"),
        "google chrome" to setOf("chrome", "googlechrome")
    )

    data class OpenAppResult(
        val success: Boolean,
        val reason: String,
        val message: String,
        val appName: String?,
        val packageName: String? = null,
        val matchedLabel: String? = null,
        val attemptedResolvedPackageName: String? = null
    )

    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val looseLabel: String,
        val normalizedPackage: String,
        val normalizedLabel: String,
        val normalizedLabelWithoutGenericWords: String,
        val compactNormalizedLabel: String,
        val compactNormalizedLabelWithoutGenericWords: String,
        val aliases: Set<String>
    )

    private data class MatchCandidate(
        val app: LaunchableApp,
        val rank: Int,
        val tieBreaker: Int,
        val reason: String
    )

    private data class PackageLaunchResult(
        val launched: Boolean,
        val packageExists: Boolean,
        val launchIntentFound: Boolean,
        val resolvedActivity: String?,
        val explicitLauncherActivity: String?,
        val launchMethod: String?,
        val errorMessage: String?
    )

    fun openApp(
        context: Context,
        appName: String?,
        resolvedPackageName: String?
    ): OpenAppResult {
        val normalizedAppName = normalizeInput(appName)
        val looseAppName = normalizeLooseLabel(appName)
        val normalizedSearchAppName = normalizeSearchQuery(normalizedAppName).ifBlank {
            normalizedAppName
        }
        val normalizedResolvedPackage = normalizePackageName(resolvedPackageName)

        Log.i(
            TAG,
            "OPEN_APP received appName=${appName ?: "null"} " +
                "normalizedAppName=$normalizedAppName searchAppName=$normalizedSearchAppName " +
                "resolvedPackageName=${normalizedResolvedPackage ?: "null"}"
        )

        if (normalizedAppName.isEmpty() && normalizedResolvedPackage == null) {
            return OpenAppResult(
                success = false,
                reason = "invalid_app_name",
                message = "OPEN_APP command missing appName",
                appName = appName,
                attemptedResolvedPackageName = normalizedResolvedPackage
            )
        }

        if (normalizedResolvedPackage != null) {
            Log.i(TAG, "Trying resolved package first package=$normalizedResolvedPackage")
            val resolvedPackageAttempt = launchByPackageName(
                context = context,
                packageName = normalizedResolvedPackage,
                source = "resolved_package"
            )
            logPackageLaunchAttempt(
                packageName = normalizedResolvedPackage,
                source = "resolved_package",
                result = resolvedPackageAttempt
            )
            if (resolvedPackageAttempt.launched) {
                Log.i(TAG, "OPEN_APP opened using resolved package=$normalizedResolvedPackage")
                return OpenAppResult(
                    success = true,
                    reason = "opened",
                    message = "App opened",
                    appName = appName,
                    packageName = normalizedResolvedPackage,
                    matchedLabel = null,
                    attemptedResolvedPackageName = normalizedResolvedPackage
                )
            }
            Log.i(
                TAG,
                "Resolved package not installed or not launchable package=$normalizedResolvedPackage; fallback to local search"
            )
        }

        if (normalizedResolvedPackage == null && PACKAGE_NAME_REGEX.matches(normalizedAppName)) {
            val normalizedAppPackage = normalizedAppName.lowercase(Locale.US)
            Log.i(TAG, "Trying direct appName package fallback package=$normalizedAppPackage")
            val directPackageAttempt = launchByPackageName(
                context = context,
                packageName = normalizedAppPackage,
                source = "direct_app_name_package"
            )
            logPackageLaunchAttempt(
                packageName = normalizedAppPackage,
                source = "direct_app_name_package",
                result = directPackageAttempt
            )
            if (directPackageAttempt.launched) {
                Log.i(TAG, "OPEN_APP opened using direct appName package=$normalizedAppPackage")
                return OpenAppResult(
                    success = true,
                    reason = "opened",
                    message = "App opened",
                    appName = appName,
                    packageName = normalizedAppPackage,
                    matchedLabel = null,
                    attemptedResolvedPackageName = normalizedResolvedPackage
                )
            }
        }

        val launchableApps = collectLaunchableApps(context)
        if (launchableApps.isEmpty()) {
            Log.w(TAG, "No launchable apps found while processing OPEN_APP")
            return OpenAppResult(
                success = false,
                reason = "app_not_installed",
                message = "App not installed",
                appName = appName,
                attemptedResolvedPackageName = normalizedResolvedPackage
            )
        }

        val bestMatchCandidate = findBestMatch(
            requestedLooseLabel = looseAppName,
            requestedNormalizedLabel = normalizedSearchAppName,
            requestedRawNormalizedLabel = normalizedAppName,
            launchableApps = launchableApps
        )
        if (bestMatchCandidate == null) {
            Log.i(
                TAG,
                "No local app match found for appName=${appName ?: "null"}"
            )
            return OpenAppResult(
                success = false,
                reason = "app_not_installed",
                message = "App not installed",
                appName = appName,
                attemptedResolvedPackageName = normalizedResolvedPackage
            )
        }

        val bestMatch = bestMatchCandidate.app
        Log.i(
            TAG,
            "Local app match selected package=${bestMatch.packageName} label=${bestMatch.label} " +
                "reason=${bestMatchCandidate.reason} rank=${bestMatchCandidate.rank}"
        )
        val launchByMatchResult = launchByPackageName(
            context = context,
            packageName = bestMatch.packageName,
            source = "best_match_${bestMatchCandidate.reason}"
        )
        logPackageLaunchAttempt(
            packageName = bestMatch.packageName,
            source = "best_match_${bestMatchCandidate.reason}",
            result = launchByMatchResult
        )
        if (!launchByMatchResult.launched) {
            Log.w(
                TAG,
                "Failed to launch matched app package=${bestMatch.packageName} label=${bestMatch.label}"
            )
            return OpenAppResult(
                success = false,
                reason = "launch_failed",
                message = launchByMatchResult.errorMessage ?: "Failed to open app",
                appName = appName,
                packageName = bestMatch.packageName,
                matchedLabel = bestMatch.label,
                attemptedResolvedPackageName = normalizedResolvedPackage
            )
        }

        Log.i(
            TAG,
            "OPEN_APP launched package=${bestMatch.packageName} label=${bestMatch.label}"
        )
        return OpenAppResult(
            success = true,
            reason = "opened",
            message = "App opened",
            appName = appName,
            packageName = bestMatch.packageName,
            matchedLabel = bestMatch.label,
            attemptedResolvedPackageName = normalizedResolvedPackage
        )
    }

    private fun findBestMatch(
        requestedLooseLabel: String,
        requestedNormalizedLabel: String,
        requestedRawNormalizedLabel: String,
        launchableApps: List<LaunchableApp>
    ): MatchCandidate? {
        if (requestedLooseLabel.isBlank() && requestedNormalizedLabel.isBlank()) {
            return null
        }

        val compactRequested = requestedNormalizedLabel.replace(" ", "")
        val requestedAliases = buildRequestedAliases(
            requestedLooseLabel = requestedLooseLabel,
            requestedNormalizedLabel = requestedNormalizedLabel,
            requestedRawNormalizedLabel = requestedRawNormalizedLabel
        )

        val candidates = launchableApps.mapNotNull { app ->
            computeMatchCandidate(
                requestedLooseLabel = requestedLooseLabel,
                requestedNormalizedLabel = requestedNormalizedLabel,
                compactRequested = compactRequested,
                requestedAliases = requestedAliases,
                app = app
            )
        }

        val selectedCandidate = candidates
            .sortedWith(
                compareBy<MatchCandidate> { it.rank }
                    .thenBy { it.tieBreaker }
                    .thenBy { it.app.label.length }
                    .thenBy { it.app.packageName }
            )
            .firstOrNull()

        if (selectedCandidate != null) {
            Log.i(
                TAG,
                "Match candidates found count=${candidates.size} selected=${selectedCandidate.app.packageName} " +
                    "reason=${selectedCandidate.reason} rank=${selectedCandidate.rank}"
            )
        } else {
            Log.i(TAG, "Match candidates found count=0")
        }
        return selectedCandidate
    }

    private fun computeMatchCandidate(
        requestedLooseLabel: String,
        requestedNormalizedLabel: String,
        compactRequested: String,
        requestedAliases: Set<String>,
        app: LaunchableApp
    ): MatchCandidate? {
        if (requestedLooseLabel.isNotBlank() && app.looseLabel == requestedLooseLabel) {
            return MatchCandidate(
                app = app,
                rank = 1,
                tieBreaker = 0,
                reason = "exact_label"
            )
        }

        if (requestedNormalizedLabel.isNotBlank()) {
            if (
                app.normalizedLabel == requestedNormalizedLabel ||
                app.normalizedLabelWithoutGenericWords == requestedNormalizedLabel ||
                app.compactNormalizedLabel == compactRequested ||
                app.compactNormalizedLabelWithoutGenericWords == compactRequested
            ) {
                return MatchCandidate(
                    app = app,
                    rank = 2,
                    tieBreaker = 0,
                    reason = "exact_normalized_label"
                )
            }

            if (
                app.normalizedPackage == requestedNormalizedLabel ||
                app.normalizedPackage == compactRequested
            ) {
                return MatchCandidate(
                    app = app,
                    rank = 3,
                    tieBreaker = 0,
                    reason = "exact_package"
                )
            }

            val containsMatch = when {
                app.normalizedLabelWithoutGenericWords.startsWith(requestedNormalizedLabel) -> {
                    MatchCandidate(
                        app = app,
                        rank = 4,
                        tieBreaker = abs(
                            app.normalizedLabelWithoutGenericWords.length - requestedNormalizedLabel.length
                        ),
                        reason = "contains_prefix_label"
                    )
                }

                app.normalizedLabel.startsWith(requestedNormalizedLabel) -> {
                    MatchCandidate(
                        app = app,
                        rank = 4,
                        tieBreaker = abs(app.normalizedLabel.length - requestedNormalizedLabel.length) + 2,
                        reason = "contains_prefix_normalized_label"
                    )
                }

                app.normalizedLabelWithoutGenericWords.contains(requestedNormalizedLabel) -> {
                    MatchCandidate(
                        app = app,
                        rank = 4,
                        tieBreaker = abs(
                            app.normalizedLabelWithoutGenericWords.length - requestedNormalizedLabel.length
                        ) + 4,
                        reason = "contains_label"
                    )
                }

                app.normalizedLabel.contains(requestedNormalizedLabel) -> {
                    MatchCandidate(
                        app = app,
                        rank = 4,
                        tieBreaker = abs(app.normalizedLabel.length - requestedNormalizedLabel.length) + 6,
                        reason = "contains_normalized_label"
                    )
                }

                app.normalizedPackage.contains(requestedNormalizedLabel) -> {
                    MatchCandidate(
                        app = app,
                        rank = 4,
                        tieBreaker = abs(app.normalizedPackage.length - requestedNormalizedLabel.length) + 8,
                        reason = "contains_package"
                    )
                }

                else -> null
            }

            if (containsMatch != null) {
                return containsMatch
            }
        }

        if (requestedAliases.isNotEmpty() && app.aliases.any { alias -> requestedAliases.contains(alias) }) {
            return MatchCandidate(
                app = app,
                rank = 5,
                tieBreaker = abs(app.aliases.size - requestedAliases.size),
                reason = "alias_match"
            )
        }

        return null
    }

    private fun collectLaunchableApps(context: Context): List<LaunchableApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = queryLauncherActivities(
            packageManager = packageManager,
            intent = launcherIntent
        )
        val deduplicated = LinkedHashMap<String, LaunchableApp>()

        for (resolveInfo in resolveInfos) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val packageName = normalizePackageName(activityInfo.packageName) ?: continue
            if (deduplicated.containsKey(packageName)) continue

            val label = resolveInfo
                .loadLabel(packageManager)
                ?.toString()
                ?.trim()
                .orEmpty()

            val looseLabel = normalizeLooseLabel(label)
            val normalizedLabel = normalizeInput(label)
            val normalizedLabelWithoutGenericWords = normalizeSearchQuery(normalizedLabel).ifBlank {
                normalizedLabel
            }
            val compactLabel = normalizedLabel.replace(" ", "")
            val compactLabelWithoutGenericWords = normalizedLabelWithoutGenericWords.replace(" ", "")
            deduplicated[packageName] = LaunchableApp(
                packageName = packageName,
                label = if (label.isNotBlank()) label else packageName,
                looseLabel = looseLabel,
                normalizedPackage = packageName.lowercase(Locale.US),
                normalizedLabel = normalizedLabel,
                normalizedLabelWithoutGenericWords = normalizedLabelWithoutGenericWords,
                compactNormalizedLabel = compactLabel,
                compactNormalizedLabelWithoutGenericWords = compactLabelWithoutGenericWords,
                aliases = buildAliases(
                    label = if (label.isNotBlank()) label else packageName,
                    normalizedPackage = packageName.lowercase(Locale.US),
                    normalizedLabel = normalizedLabel,
                    normalizedLabelWithoutGenericWords = normalizedLabelWithoutGenericWords
                )
            )
        }

        Log.i(TAG, "Collected launchable apps count=${deduplicated.size}")
        return deduplicated.values.toList()
    }

    private fun launchByPackageName(
        context: Context,
        packageName: String,
        source: String
    ): PackageLaunchResult {
        val packageManager = context.packageManager
        val packageExists = isPackageInstalled(packageManager, packageName)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val resolvedActivity = launchIntent
            ?.resolveActivity(packageManager)
            ?.flattenToShortString()

        Log.i(
            TAG,
            "Package probe source=$source package=$packageName " +
                "packageExists=$packageExists launchIntentNull=${launchIntent == null} " +
                "resolvedActivity=${resolvedActivity ?: "null"}"
        )

        if (launchIntent != null) {
            val started = startIntent(
                context = context,
                intent = launchIntent,
                source = source,
                packageName = packageName,
                method = "launch_intent_for_package"
            )
            if (started == null) {
                return PackageLaunchResult(
                    launched = true,
                    packageExists = packageExists,
                    launchIntentFound = true,
                    resolvedActivity = resolvedActivity,
                    explicitLauncherActivity = null,
                    launchMethod = "launch_intent_for_package",
                    errorMessage = null
                )
            }

            Log.w(
                TAG,
                "Start using launch intent failed source=$source package=$packageName error=$started"
            )
        }

        val explicitLauncherActivity = findLauncherActivity(packageManager, packageName)
        if (explicitLauncherActivity != null) {
            val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, explicitLauncherActivity)
            }
            val started = startIntent(
                context = context,
                intent = explicitIntent,
                source = source,
                packageName = packageName,
                method = "explicit_launcher_activity"
            )
            if (started == null) {
                return PackageLaunchResult(
                    launched = true,
                    packageExists = packageExists,
                    launchIntentFound = launchIntent != null,
                    resolvedActivity = resolvedActivity,
                    explicitLauncherActivity = explicitLauncherActivity,
                    launchMethod = "explicit_launcher_activity",
                    errorMessage = null
                )
            }

            Log.w(
                TAG,
                "Start using explicit launcher failed source=$source package=$packageName " +
                    "activity=$explicitLauncherActivity error=$started"
            )
        }

        val failure = buildString {
            append("Failed to open app")
            if (!packageExists) {
                append(" (package missing or not visible)")
            } else if (launchIntent == null && explicitLauncherActivity == null) {
                append(" (no launch activity)")
            }
        }

        return PackageLaunchResult(
            launched = false,
            packageExists = packageExists,
            launchIntentFound = launchIntent != null,
            resolvedActivity = resolvedActivity,
            explicitLauncherActivity = explicitLauncherActivity,
            launchMethod = null,
            errorMessage = failure
        )
    }

    private fun normalizePackageName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim().lowercase(Locale.US)
        if (trimmed.isBlank()) return null
        return trimmed
    }

    private fun normalizeInput(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .trim()
            .lowercase(Locale.US)
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun normalizeSearchQuery(value: String): String {
        return value
            .replace(GENERIC_WORDS_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun normalizeLooseLabel(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .trim()
            .lowercase(Locale.US)
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun buildAliases(
        label: String,
        normalizedPackage: String,
        normalizedLabel: String,
        normalizedLabelWithoutGenericWords: String
    ): Set<String> {
        val aliases = linkedSetOf<String>()
        val words = normalizedLabelWithoutGenericWords.split(" ").filter { it.isNotBlank() }
        aliases.add(normalizedLabel)
        aliases.add(normalizedLabelWithoutGenericWords)
        aliases.add(normalizedLabelWithoutGenericWords.replace(" ", ""))

        if (words.size > 1) {
            aliases.add(words.joinToString("") { it.first().toString() })
        }
        words.forEach { token ->
            aliases.add(token)
        }

        val packageTokens = normalizedPackage.split('.').filter { token ->
            token.isNotBlank() && token !in setOf("com", "org", "net", "app", "android")
        }
        packageTokens.forEach { token ->
            aliases.add(token)
        }

        val knownAliasKeyCandidates = setOf(
            normalizedLabelWithoutGenericWords,
            normalizedLabel,
            label.trim().lowercase(Locale.US),
            packageTokens.lastOrNull().orEmpty()
        )
        for (key in knownAliasKeyCandidates) {
            KNOWN_ALIASES[key]?.let { known -> aliases.addAll(known) }
        }

        return aliases
            .map { normalizeSearchQuery(normalizeInput(it)).ifBlank { normalizeInput(it) } }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun buildRequestedAliases(
        requestedLooseLabel: String,
        requestedNormalizedLabel: String,
        requestedRawNormalizedLabel: String
    ): Set<String> {
        val aliases = linkedSetOf<String>()
        val normalizedLoose = normalizeSearchQuery(normalizeInput(requestedLooseLabel))
            .ifBlank { normalizeInput(requestedLooseLabel) }
        aliases.add(normalizedLoose)
        aliases.add(requestedNormalizedLabel)
        aliases.add(requestedNormalizedLabel.replace(" ", ""))
        aliases.add(requestedRawNormalizedLabel)
        aliases.add(requestedRawNormalizedLabel.replace(" ", ""))

        val words = requestedNormalizedLabel.split(" ").filter { it.isNotBlank() }
        if (words.size > 1) {
            aliases.add(words.joinToString("") { it.first().toString() })
        }
        words.forEach { token -> aliases.add(token) }

        KNOWN_ALIASES[requestedNormalizedLabel]?.let { aliases.addAll(it) }
        KNOWN_ALIASES[requestedRawNormalizedLabel]?.let { aliases.addAll(it) }
        KNOWN_ALIASES[requestedLooseLabel]?.let { aliases.addAll(it) }

        return aliases
            .map { normalizeSearchQuery(normalizeInput(it)).ifBlank { normalizeInput(it) } }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun queryLauncherActivities(
        packageManager: PackageManager,
        intent: Intent
    ): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun findLauncherActivity(
        packageManager: PackageManager,
        packageName: String
    ): String? {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }

        val activities = queryLauncherActivities(packageManager, launcherIntent)
        val bestActivity = activities
            .mapNotNull { it.activityInfo }
            .firstOrNull()
            ?.name

        Log.i(
            TAG,
            "Launcher activity lookup package=$packageName found=${bestActivity ?: "null"} count=${activities.size}"
        )
        return bestActivity
    }

    private fun startIntent(
        context: Context,
        intent: Intent,
        source: String,
        packageName: String,
        method: String
    ): String? {
        return try {
            val launchIntent = Intent(intent).apply {
                addFlags(LAUNCH_FLAGS)
            }
            val resolvedActivity = launchIntent.resolveActivity(context.packageManager)
                ?.flattenToShortString()
            Log.i(
                TAG,
                "startActivity requested source=$source method=$method package=$packageName " +
                    "resolvedActivity=${resolvedActivity ?: "null"}"
            )
            context.startActivity(launchIntent)
            Log.i(
                TAG,
                "startActivity called successfully source=$source method=$method package=$packageName"
            )
            null
        } catch (error: ActivityNotFoundException) {
            Log.e(
                TAG,
                "startActivity failed (activity not found) source=$source method=$method package=$packageName",
                error
            )
            error.message ?: "Activity not found"
        } catch (error: SecurityException) {
            Log.e(
                TAG,
                "startActivity failed (security) source=$source method=$method package=$packageName",
                error
            )
            error.message ?: "SecurityException"
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "startActivity failed source=$source method=$method package=$packageName",
                error
            )
            error.message ?: "Unknown launch error"
        }
    }

    private fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: Throwable) {
            Log.w(TAG, "Package probe failed package=$packageName", error)
            false
        }
    }

    private fun logPackageLaunchAttempt(
        packageName: String,
        source: String,
        result: PackageLaunchResult
    ) {
        Log.i(
            TAG,
            "OPEN_APP launch attempt source=$source package=$packageName " +
                "packageExists=${result.packageExists} launchIntentFound=${result.launchIntentFound} " +
                "resolvedActivity=${result.resolvedActivity ?: "null"} " +
                "explicitLauncherActivity=${result.explicitLauncherActivity ?: "null"} " +
                "launchMethod=${result.launchMethod ?: "null"} launched=${result.launched} " +
                "error=${result.errorMessage ?: "null"}"
        )
    }
}
