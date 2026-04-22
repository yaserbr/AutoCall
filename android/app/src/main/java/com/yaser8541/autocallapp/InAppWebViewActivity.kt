package com.yaser8541.autocallapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class InAppWebViewActivity : Activity() {
    companion object {
        private const val TAG = "AutoCall/InAppWebViewActivity"

        private const val ACTION_OPEN_URL = "com.yaser8541.autocallapp.OPEN_URL"
        private const val ACTION_CLOSE_WEBVIEW = "com.yaser8541.autocallapp.CLOSE_WEBVIEW"
        private const val EXTRA_URL = "url"

        fun createOpenIntent(context: Context, url: String): Intent {
            return Intent(context, InAppWebViewActivity::class.java).apply {
                action = ACTION_OPEN_URL
                putExtra(EXTRA_URL, url)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        }

        fun createCloseIntent(context: Context): Intent {
            return Intent(context, InAppWebViewActivity::class.java).apply {
                action = ACTION_CLOSE_WEBVIEW
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        }
    }

    private lateinit var webView: WebView
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = buildWebView()
        setContentView(buildLayout(webView))
        processIncomingIntent(intent, isNewIntent = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingIntent(intent, isNewIntent = true)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        InAppWebViewController.onActivityClosed(this)
        super.onDestroy()
    }

    fun finishFromServerCommand() {
        Log.i(TAG, "WebView close requested by server command")
        finish()
    }

    private fun processIncomingIntent(intent: Intent?, isNewIntent: Boolean) {
        val resolvedAction = intent?.action
        if (resolvedAction == ACTION_CLOSE_WEBVIEW) {
            Log.i(TAG, "Received CLOSE_WEBVIEW intent isNewIntent=$isNewIntent")
            finishFromServerCommand()
            return
        }

        val rawUrl = intent?.getStringExtra(EXTRA_URL)
        val normalizedUrl = InAppWebViewController.normalizeHttpUrl(rawUrl)
        if (normalizedUrl == null) {
            Log.w(TAG, "Ignoring invalid OPEN_URL intent url=$rawUrl")
            finish()
            return
        }

        val previousUrl = currentUrl
        val isReplacement = !previousUrl.isNullOrBlank()
        currentUrl = normalizedUrl

        InAppWebViewController.onActivityOpened(this, normalizedUrl)
        InAppWebViewController.onActivityUrlUpdated(normalizedUrl)

        if (isReplacement) {
            Log.i(TAG, "Replacing current WebView URL old=$previousUrl new=$normalizedUrl")
        } else {
            Log.i(TAG, "Opening WebView URL=$normalizedUrl")
        }

        webView.loadUrl(normalizedUrl)
    }

    private fun buildLayout(view: WebView): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101B2F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0C1423"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        }

        val title = TextView(this).apply {
            text = "In-App WebView"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener {
                Log.i(TAG, "Manual WebView close clicked")
                finish()
            }
        }

        header.addView(title)
        header.addView(
            closeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(header)
        root.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return root
    }

    private fun buildWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // Keep navigation inside this in-app WebView.
                    return false
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
