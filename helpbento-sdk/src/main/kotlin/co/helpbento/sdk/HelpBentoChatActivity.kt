package co.helpbento.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONException
import org.json.JSONObject
import java.net.URL

/**
 * Internal Activity hosting the HelpBento chat. Loads a small generated HTML page that pulls in
 * widget.js and drives it through a JS bridge — this Activity renders no chat UI of its own.
 *
 * Not part of the public API; use [HelpBento.open] to show it.
 */
class HelpBentoChatActivity : Activity() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True once the widget's 'ready' event has fired and evaluateJavascript calls are safe. */
    private var pageReady = false

    /** Guards [handleCloseEvent] so onClose fires exactly once regardless of the trigger. */
    private var closeNotified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val companyId = HelpBento.companyId
        if (companyId == null) {
            // HelpBento.init() was never called; nothing to show.
            finish()
            return
        }

        applyWindowStyling()

        webView = createWebView()
        setContentView(webView)
        applyWindowInsets(webView)

        HelpBento.attachActivity(this)

        val html = buildHtml(companyId, HelpBento.agentId, HelpBento.scriptUrl)
        webView.loadDataWithBaseURL(originOf(HelpBento.scriptUrl), html, "text/html", "utf-8", null)
    }

    override fun onStart() {
        super.onStart()
        HelpBento.setVisible(true)
    }

    override fun onStop() {
        super.onStop()
        HelpBento.setVisible(false)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        requestClose()
    }

    override fun onDestroy() {
        // Covers teardown paths that don't go through requestClose() (e.g. the task being
        // removed from Recents), so onClose still fires exactly once.
        handleCloseEvent()
        HelpBento.detachActivity(this)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    /** Ask the widget to close itself; falls back to an immediate finish if the page never loaded. */
    internal fun requestClose() {
        // Callable from any thread via HelpBento.close(); evaluateJavascript is main-thread-only.
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            if (pageReady) {
                webView.evaluateJavascript("window.HelpBento && window.HelpBento.close();", null)
            } else {
                handleCloseEvent()
            }
        }
    }

    /** Called by [HelpBento] whenever identify()/clearUser() is invoked while this Activity is live. */
    internal fun onIdentityChanged() {
        // Callable from any thread via HelpBento.identify()/clearUser().
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            if (pageReady) applyIdentity()
        }
    }

    private fun handleCloseEvent() {
        if (closeNotified) return
        closeNotified = true
        HelpBento.notifyClose()
        finish()
    }

    private fun onWidgetReady() {
        pageReady = true
        applyIdentity()
    }

    private fun applyIdentity() {
        val identity = HelpBento.pendingIdentity()
        val script = if (identity == null) {
            "window.HelpBento && window.HelpBento.clearUser();"
        } else {
            val payload = JSONObject().apply {
                put("userId", identity.userId)
                put("email", identity.email)
                put("name", identity.name)
                put("hmac", identity.hmac)
            }
            "window.HelpBento && window.HelpBento.identify(${payload});"
        }
        webView.evaluateJavascript(script, null)
    }

    private fun createWebView(): WebView {
        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        // The widget persists its visitor id in localStorage — it must survive app restarts.
        view.settings.domStorageEnabled = true
        view.addJavascriptInterface(HelpBentoBridge(), "HelpBentoBridge")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Only the initial loadDataWithBaseURL content renders in the webview; any
                // subsequent navigation (links inside chat messages) opens externally.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    HelpBento.notifyError("Failed to load chat: ${error.description}")
                }
            }
        }
        return view
    }

    /** Handles system bars: transparent status bar, icon contrast matched to the OS theme. */
    private fun applyWindowStyling() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }

        @Suppress("DEPRECATION")
        run {
            var flags = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = if (isDarkMode) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Pads the webview by the system bar AND keyboard insets so its content
     * isn't drawn underneath them. The IME inset must be handled here on
     * API 30+: setDecorFitsSystemWindows(false) disables adjustResize, so
     * without it the keyboard would cover the chat input.
     *
     * On API 30+ the IME inset is applied per-frame via a
     * [WindowInsetsAnimation.Callback], so the webview (and the chat input
     * inside it, which follows the visual viewport) slides in sync with the
     * keyboard instead of jumping once it has fully opened. The static
     * listener stays for non-animated inset changes (rotation, bar changes)
     * but defers to the callback while an IME animation is running —
     * otherwise it would apply the end state on the animation's first frame.
     */
    private fun applyWindowInsets(view: View) {
        var imeAnimating = false

        fun pad(insets: WindowInsets) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setWindowInsetsAnimationCallback(
                object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                    override fun onPrepare(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                            imeAnimating = true
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsets,
                        runningAnimations: MutableList<WindowInsetsAnimation>,
                    ): WindowInsets {
                        pad(insets)
                        return insets
                    }

                    override fun onEnd(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                            imeAnimating = false
                        }
                    }
                },
            )
        }

        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!imeAnimating) pad(insets)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    /** Resolve the origin (`scheme://authority`) of [scriptUrl], used as the WebView's base URL. */
    private fun originOf(scriptUrl: String): String {
        return try {
            val url = URL(scriptUrl)
            "${url.protocol}://${url.authority}"
        } catch (e: Exception) {
            scriptUrl
        }
    }

    private fun escapeHtmlAttribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Builds the chat page: loads widget.js from [scriptUrl], wires its events to the JS bridge,
     * initializes it, and opens the panel as soon as it reports ready. companyId/agentId are
     * JSON-encoded via [JSONObject.quote] — never raw-interpolated — since they may be
     * developer-supplied at runtime.
     */
    private fun buildHtml(companyId: String, agentId: String?, scriptUrl: String): String {
        val companyIdJson = JSONObject.quote(companyId)
        val agentIdJson = if (agentId != null) JSONObject.quote(agentId) else "null"
        val scriptSrc = escapeHtmlAttribute(scriptUrl)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
              <style>html,body{margin:0;padding:0;height:100%;}</style>
            </head>
            <body>
              <script src="$scriptSrc"></script>
              <script>
                (function () {
                  function forward(eventName) {
                    return function (payload) {
                      try {
                        HelpBentoBridge.postMessage(JSON.stringify({ event: eventName, payload: payload || null }));
                      } catch (e) {}
                    };
                  }
                  window.HelpBento.on('ready', forward('ready'));
                  window.HelpBento.on('open', forward('open'));
                  window.HelpBento.on('close', forward('close'));
                  window.HelpBento.on('message', forward('message'));
                  window.HelpBento.on('error', forward('error'));
                  // The Activity IS the chat panel, so open it as soon as it can render.
                  window.HelpBento.on('ready', function () { window.HelpBento.open(); });
                  window.HelpBento.init({
                    companyId: $companyIdJson,
                    agentId: $agentIdJson,
                    platform: 'android',
                    showLauncher: false,
                    animations: true
                  });
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /** JS bridge target. Methods run on a background thread — dispatch to the main thread. */
    private inner class HelpBentoBridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            mainHandler.post { handleBridgeMessage(json) }
        }
    }

    private fun handleBridgeMessage(json: String) {
        val message = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return
        }
        val payload = message.optJSONObject("payload")
        when (message.optString("event")) {
            "ready" -> onWidgetReady()
            "open" -> HelpBento.notifyOpen()
            "close" -> handleCloseEvent()
            "message" -> HelpBento.notifyMessage(payload?.optString("content").orEmpty())
            "error" -> HelpBento.notifyError(payload?.optString("message") ?: "Unknown error")
        }
    }
}
