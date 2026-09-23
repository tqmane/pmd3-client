package dev.tqmane.pmd3client

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val PREFS = "pmd3_client"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PASSWORD = "password"
        private const val DEFAULT_PORT = 8131
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var webView: WebView

    private var volumeUpHeld = false
    private var volumeDownHeld = false
    private var settingsChordLatched = false

    private val minimalViewerScript = """
        (function () {
          if (document.getElementById('pmd3-client-style')) return;
          var style = document.createElement('style');
          style.id = 'pmd3-client-style';
          style.textContent = `
            html, body {
              margin: 0 !important;
              padding: 0 !important;
              width: 100% !important;
              height: 100% !important;
              overflow: hidden !important;
              background: #000 !important;
              overscroll-behavior: none !important;
              touch-action: none !important;
            }
            #topbar, #left-tray, #right-tray, #bottom-row,
            #hw-tooltip, #help-overlay, .hw {
              display: none !important;
            }
            #workspace, #stage-wrap, #stage, #device-frame {
              position: fixed !important;
              inset: 0 !important;
              width: 100vw !important;
              height: 100vh !important;
              margin: 0 !important;
              padding: 0 !important;
              display: flex !important;
              align-items: center !important;
              justify-content: center !important;
              overflow: hidden !important;
              background: #000 !important;
            }
            #device-frame::before, #device-frame::after {
              display: none !important;
            }
            #c {
              display: block !important;
              width: auto !important;
              height: auto !important;
              max-width: 100vw !important;
              max-height: 100vh !important;
              margin: 0 !important;
              padding: 0 !important;
              touch-action: none !important;
              overscroll-behavior: none !important;
              user-select: none !important;
              -webkit-user-select: none !important;
            }
          `;
          document.head.appendChild(style);
          document.body.classList.remove('frame-on');
          var canvas = document.getElementById('c');
          if (canvas) {
            canvas.style.touchAction = 'none';
            try { canvas.focus({preventScroll: true}); } catch (_) { canvas.focus(); }
          }
          try { history.scrollRestoration = 'manual'; } catch (_) {}
          window.scrollTo(0, 0);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = object : WebView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return super.onTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            isNestedScrollingEnabled = false

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(minimalViewerScript, null)
                    view?.evaluateJavascript("window.isSecureContext === true") { secureResult ->
                        view.evaluateJavascript("typeof VideoDecoder !== 'undefined'") { decoderResult ->
                            val message = when {
                                secureResult != "true" ->
                                    "WebCodecs requires HTTPS. Start serve-web with --https and connect with https://."
                                decoderResult != "true" ->
                                    "This Android WebView does not expose WebCodecs VideoDecoder."
                                else -> null
                            }
                            if (message != null) {
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    updateGestureExclusion()
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView?,
                    handler: HttpAuthHandler?,
                    host: String?,
                    realm: String?,
                ) {
                    val password = prefs.getString(KEY_PASSWORD, "").orEmpty()
                    if (password.isNotEmpty()) {
                        handler?.proceed("pmd3", password)
                    } else {
                        promptForPassword(handler)
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    val configured = runCatching {
                        Uri.parse(prefs.getString(KEY_SERVER_URL, "").orEmpty())
                    }.getOrNull()
                    val errorHost = runCatching { Uri.parse(error?.url.orEmpty()).host }.getOrNull()
                    val configuredHost = configured?.host

                    val isConfiguredHttps = configured?.scheme.equals("https", ignoreCase = true)
                    val isConfiguredHost = configuredHost != null &&
                        errorHost != null &&
                        configuredHost.equals(errorHost, ignoreCase = true)
                    val isExpectedSelfSignedError = error?.primaryError == SslError.SSL_UNTRUSTED

                    if (isConfiguredHttps && isConfiguredHost && isExpectedSelfSignedError) {
                        handler?.proceed()
                    } else {
                        handler?.cancel()
                    }
                }
            }
        }

        setContentView(webView)
        webView.post {
            enterImmersiveMode()
            updateGestureExclusion()
        }

        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        if (savedUrl.isNullOrBlank()) {
            showSettingsDialog(required = true)
        } else {
            val normalized = normalizeServerUrl(savedUrl)
            if (normalized == null) {
                showSettingsDialog(required = true)
            } else {
                if (normalized != savedUrl.trimEnd('/')) {
                    prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
                }
                loadServer(normalized)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
            updateGestureExclusion()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpHeld = true
                maybeOpenSettingsFromChord()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownHeld = true
                maybeOpenSettingsFromChord()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpHeld = false
                if (!volumeDownHeld) settingsChordLatched = false
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownHeld = false
                if (!volumeUpHeld) settingsChordLatched = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun maybeOpenSettingsFromChord() {
        if (volumeUpHeld && volumeDownHeld && !settingsChordLatched) {
            settingsChordLatched = true
            showSettingsDialog(required = false)
        }
    }

    private fun loadServer(serverUrl: String) {
        webView.stopLoading()
        val viewerUrl = serverUrl.trimEnd('/') + "/?lockcanvas=1"
        webView.loadUrl(viewerUrl)
    }

    private fun showSettingsDialog(required: Boolean) {
        val currentUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        val currentPassword = prefs.getString(KEY_PASSWORD, "").orEmpty()

        val padding = dp(24)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
        }

        val help = TextView(this).apply {
            text = "Enter the pymobiledevice3 serve-web address. WebCodecs requires HTTPS; host-only input gets https:// and port 8131 automatically.\n\nPress Android Volume Up + Volume Down together any time to reopen this screen."
        }
        val urlInput = EditText(this).apply {
            hint = "192.168.1.10 or https://192.168.1.10:8131"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(currentUrl)
        }
        val passwordInput = EditText(this).apply {
            hint = "serve-web password (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(currentPassword)
        }

        container.addView(help)
        container.addView(urlInput)
        container.addView(passwordInput)

        val builder = AlertDialog.Builder(this)
            .setTitle("pmd3 server")
            .setView(container)
            .setPositiveButton("Save & connect", null)

        if (!required) {
            builder.setNegativeButton("Cancel", null)
        }

        val dialog = builder.create()
        dialog.setCancelable(!required)
        dialog.setCanceledOnTouchOutside(!required)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = normalizeServerUrl(urlInput.text.toString())
                if (normalized == null) {
                    urlInput.error = "Enter a valid HTTP/HTTPS host"
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString(KEY_SERVER_URL, normalized)
                    .putString(KEY_PASSWORD, passwordInput.text.toString())
                    .apply()

                loadServer(normalized)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun promptForPassword(handler: HttpAuthHandler?) {
        val input = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("pymobiledevice3 authentication")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val password = input.text.toString()
                prefs.edit().putString(KEY_PASSWORD, password).apply()
                handler?.proceed("pmd3", password)
            }
            .setNegativeButton("Cancel") { _, _ -> handler?.cancel() }
            .setOnCancelListener { handler?.cancel() }
            .show()
    }

    private fun normalizeServerUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "https://$value"
        }

        val parsed = Uri.parse(value)
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = parsed.host ?: return null
        if (host.isBlank()) return null

        // WebCodecs VideoDecoder is a secure-context API. Upgrade old or pasted
        // HTTP serve-web URLs so existing installs migrate to the HTTPS endpoint.
        val port = if (parsed.port != -1) parsed.port else DEFAULT_PORT
        val authorityHost = if (host.contains(':')) "[$host]" else host
        return Uri.Builder()
            .scheme("https")
            .encodedAuthority("$authorityHost:$port")
            .build()
            .toString()
            .trimEnd('/')
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ::webView.isInitialized) {
            webView.post {
                if (webView.width > 0 && webView.height > 0) {
                    webView.systemGestureExclusionRects = listOf(Rect(0, 0, webView.width, webView.height))
                }
            }
        }
    }

    private fun enterImmersiveMode() {
        val decorView = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
