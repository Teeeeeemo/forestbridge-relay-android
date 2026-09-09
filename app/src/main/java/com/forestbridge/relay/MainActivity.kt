package com.forestbridge.relay

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PHONE_STATE = 1001
        private const val REQUEST_AUDIO_CAPTURE = 1002
    }

    private lateinit var webView: WebView
    private lateinit var offlineView: View
    private lateinit var callStateMonitor: CallStateMonitor
    private val allowedOrigin = Uri.parse(BuildConfig.WEB_APP_URL)

    private var pendingAudioRequest: PermissionRequest? = null
    private var lastCallPayload: String? = null
    private var weChatReceiverRegistered = false

    private val weChatCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (
                intent.action !=
                WeChatNotificationListenerService.ACTION_CALL_STATE_CHANGED
            ) {
                return
            }

            val phase = intent.getStringExtra(
                WeChatNotificationListenerService.EXTRA_PHASE
            ) ?: return
            val kind = intent.getStringExtra(
                WeChatNotificationListenerService.EXTRA_KIND
            )
            dispatchNativeCall("wechat", phase, kind)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        webView = WebView(this)
        offlineView = createOfflineView()

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            offlineView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        configureWebView()
        registerWeChatCallReceiver()

        callStateMonitor = CallStateMonitor(applicationContext) {
            phase -> runOnUiThread { dispatchCallPhase(phase) }
        }
        ensureCallMonitoringPermission()

        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            loadRobotPage()
        }
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = userAgentString + " ForestBridgeAndroid/0.1"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !isAllowedUrl(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean = !isAllowedUrl(Uri.parse(url))

            override fun onPageFinished(view: WebView, url: String) {
                if (isAllowedUrl(Uri.parse(url))) {
                    offlineView.visibility = View.GONE
                    lastCallPayload?.let(::sendCallPayloadToWeb)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showOfflineView()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    showOfflineView()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsAudio = request.resources.contains(
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    )
                    if (!wantsAudio || !isAllowedUrl(request.origin)) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(
                            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        )
                    } else {
                        pendingAudioRequest?.deny()
                        pendingAudioRequest = request
                        requestPermissions(
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            REQUEST_AUDIO_CAPTURE
                        )
                    }
                }
            }

            override fun onPermissionRequestCanceled(
                request: PermissionRequest
            ) {
                if (pendingAudioRequest == request) pendingAudioRequest = null
            }
        }
    }

    private fun ensureCallMonitoringPermission() {
        if (
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            callStateMonitor.start()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                REQUEST_PHONE_STATE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )
        val granted =
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            REQUEST_PHONE_STATE -> {
                if (granted) callStateMonitor.start()
            }

            REQUEST_AUDIO_CAPTURE -> {
                val request = pendingAudioRequest
                pendingAudioRequest = null
                if (granted) {
                    request?.grant(
                        arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    )
                } else {
                    request?.deny()
                }
            }
        }
    }

    private fun registerWeChatCallReceiver() {
        val filter = IntentFilter(
            WeChatNotificationListenerService.ACTION_CALL_STATE_CHANGED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                weChatCallReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(weChatCallReceiver, filter)
        }
        weChatReceiverRegistered = true

        WeChatNotificationListenerService.readActiveCall(this)?.let { state ->
            lastCallPayload = buildCallPayload(
                source = "wechat",
                phase = state.phase,
                kind = state.kind
            )
        }
    }

    private fun dispatchCallPhase(phase: CallPhase) {
        dispatchNativeCall(
            source = "cellular",
            phase = phase.wireValue
        )
    }

    private fun dispatchNativeCall(
        source: String,
        phase: String,
        kind: String? = null
    ) {
        val payload = buildCallPayload(source, phase, kind)
        lastCallPayload = if (phase == "ended") null else payload
        sendCallPayloadToWeb(payload)

        if (BuildConfig.DEBUG) {
            val sourceLabel = if (source == "wechat") "微信" else "手机"
            Toast.makeText(
                this,
                "$sourceLabel 来电状态：$phase",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildCallPayload(
        source: String,
        phase: String,
        kind: String? = null
    ): String {
        val payload = JSONObject()
            .put("source", source)
            .put("phase", phase)
            .put("observed_at_epoch_ms", System.currentTimeMillis())
        if (!kind.isNullOrBlank()) payload.put("kind", kind)
        return payload.toString()
    }

    private fun sendCallPayloadToWeb(payload: String) {
        val script =
            "window.dispatchEvent(new CustomEvent(" +
                "'forestbridge:native-call', { detail: " +
                payload +
                " }));"
        webView.evaluateJavascript(script, null)
    }

    private fun isAllowedUrl(uri: Uri): Boolean {
        if (uri.scheme != allowedOrigin.scheme) return false
        if (!uri.host.equals(allowedOrigin.host, ignoreCase = true)) return false
        return effectivePort(uri) == effectivePort(allowedOrigin)
    }

    private fun effectivePort(uri: Uri): Int =
        when {
            uri.port != -1 -> uri.port
            uri.scheme == "https" -> 443
            uri.scheme == "http" -> 80
            else -> -1
        }

    private fun loadRobotPage() {
        offlineView.visibility = View.GONE
        webView.loadUrl(BuildConfig.WEB_APP_URL)
    }

    private fun showOfflineView() {
        offlineView.visibility = View.VISIBLE
    }

    private fun createOfflineView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        val title = TextView(this).apply {
            setText(R.string.offline_title)
            setTextColor(Color.rgb(107, 255, 176))
            textSize = 24f
            gravity = Gravity.CENTER
        }

        val detail = TextView(this).apply {
            setText(R.string.offline_detail)
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }

        val retry = Button(this).apply {
            setText(R.string.retry)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(107, 255, 176))
            setOnClickListener { loadRobotPage() }
        }

        container.addView(title)
        container.addView(detail)
        container.addView(retry)
        return container
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        pendingAudioRequest?.deny()
        pendingAudioRequest = null
        callStateMonitor.stop()
        if (weChatReceiverRegistered) {
            unregisterReceiver(weChatCallReceiver)
            weChatReceiverRegistered = false
        }

        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
