package com.yaser8541.autocallapp

import android.content.Context
import android.util.Log
import android.webkit.URLUtil
import java.lang.ref.WeakReference
import java.util.Locale

object InAppWebViewController {
    private const val TAG = "AutoCall/InAppWebView"

    data class WebViewState(
        val isOpen: Boolean,
        val currentUrl: String?
    )

    data class WebViewCommandResult(
        val success: Boolean,
        val reason: String,
        val message: String,
        val state: WebViewState,
        val url: String? = null,
        val replacedExisting: Boolean = false,
        val closed: Boolean = false,
        val noOp: Boolean = false
    )

    private val lock = Any()

    @Volatile
    private var isOpen: Boolean = false

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var activityRef: WeakReference<InAppWebViewActivity>? = null

    fun normalizeHttpUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) {
            return null
        }

        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (!URLUtil.isValidUrl(trimmed)) {
            return null
        }

        val lower = trimmed.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null
        }

        return trimmed
    }

    fun snapshot(): WebViewState {
        return synchronized(lock) {
            WebViewState(
                isOpen = isOpen,
                currentUrl = currentUrl
            )
        }
    }

    fun openUrl(context: Context, rawUrl: String?): WebViewCommandResult {
        val normalizedUrl = normalizeHttpUrl(rawUrl)
        if (normalizedUrl == null) {
            Log.w(TAG, "OPEN_URL rejected invalid url=$rawUrl")
            return WebViewCommandResult(
                success = false,
                reason = "invalid_url",
                message = "OPEN_URL requires a valid http:// or https:// URL",
                state = snapshot()
            )
        }

        val previousState = snapshot()
        val replacedExisting = previousState.isOpen
        return try {
            Log.i(
                TAG,
                "OPEN_URL command received url=$normalizedUrl replacingExisting=$replacedExisting"
            )

            synchronized(lock) {
                isOpen = true
                currentUrl = normalizedUrl
            }

            val launchIntent = InAppWebViewActivity.createOpenIntent(
                context = context.applicationContext,
                url = normalizedUrl
            )
            context.applicationContext.startActivity(launchIntent)

            val stateAfterLaunch = snapshot()
            Log.i(
                TAG,
                "OPEN_URL launch requested url=$normalizedUrl replacedExisting=$replacedExisting"
            )
            WebViewCommandResult(
                success = true,
                reason = "opened",
                message = if (replacedExisting) {
                    "WebView URL replaced"
                } else {
                    "WebView opened"
                },
                state = stateAfterLaunch,
                url = normalizedUrl,
                replacedExisting = replacedExisting
            )
        } catch (error: Throwable) {
            synchronized(lock) {
                isOpen = previousState.isOpen
                currentUrl = previousState.currentUrl
            }
            Log.e(TAG, "OPEN_URL launch failed url=$normalizedUrl", error)
            WebViewCommandResult(
                success = false,
                reason = "open_failed",
                message = error.message ?: "Failed to open in-app WebView",
                state = snapshot(),
                url = normalizedUrl,
                replacedExisting = replacedExisting
            )
        }
    }

    fun closeWebView(context: Context): WebViewCommandResult {
        val stateBeforeClose = snapshot()
        if (!stateBeforeClose.isOpen) {
            Log.i(TAG, "CLOSE_WEBVIEW ignored because no active WebView was found")
            return WebViewCommandResult(
                success = true,
                reason = "noop",
                message = "No active WebView to close",
                state = stateBeforeClose,
                closed = false,
                noOp = true
            )
        }

        return try {
            val activeActivity = activityRef?.get()
            if (activeActivity != null && !activeActivity.isFinishing) {
                activeActivity.runOnUiThread {
                    activeActivity.finishFromServerCommand()
                }
            } else {
                Log.i(TAG, "CLOSE_WEBVIEW fallback close intent requested")
                val closeIntent = InAppWebViewActivity.createCloseIntent(context.applicationContext)
                context.applicationContext.startActivity(closeIntent)
            }

            synchronized(lock) {
                isOpen = false
                currentUrl = null
            }
            val stateAfterClose = snapshot()
            Log.i(TAG, "CLOSE_WEBVIEW command executed successfully")
            WebViewCommandResult(
                success = true,
                reason = "closed",
                message = "WebView closed",
                state = stateAfterClose,
                closed = true,
                noOp = false
            )
        } catch (error: Throwable) {
            Log.e(TAG, "CLOSE_WEBVIEW command failed", error)
            WebViewCommandResult(
                success = false,
                reason = "close_failed",
                message = error.message ?: "Failed to close WebView",
                state = snapshot(),
                closed = false,
                noOp = false
            )
        }
    }

    fun onActivityOpened(activity: InAppWebViewActivity, openedUrl: String) {
        synchronized(lock) {
            activityRef = WeakReference(activity)
            isOpen = true
            currentUrl = openedUrl
        }
        Log.i(TAG, "WebView activity opened url=$openedUrl")
    }

    fun onActivityUrlUpdated(updatedUrl: String) {
        synchronized(lock) {
            isOpen = true
            currentUrl = updatedUrl
        }
        Log.i(TAG, "WebView URL updated url=$updatedUrl")
    }

    fun onActivityClosed(activity: InAppWebViewActivity) {
        synchronized(lock) {
            val currentActivity = activityRef?.get()
            if (currentActivity == null || currentActivity == activity) {
                activityRef = null
            }
            isOpen = false
            currentUrl = null
        }
        Log.i(TAG, "WebView activity closed")
    }
}
