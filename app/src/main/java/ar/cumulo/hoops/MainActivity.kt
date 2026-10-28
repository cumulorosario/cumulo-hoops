package ar.cumulo.hoops

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla principal: arranca el servidor y muestra la página "Conectar" (QR para TVs y
 * operadores). Desde ahí se puede abrir la mesa de control en este mismo celular.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var battery: Button
    private val handler = Handler(Looper.getMainLooper())
    private var pageLoaded = false
    private var lastIp: String? = null

    private val statusLoop = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(12), dp(6), dp(6), dp(6))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(getString(R.string.hotspot)) { openHotspot() })
        battery = button(getString(R.string.battery)) { askBatteryExemption() }
        bar.addView(battery)
        toggle = button(getString(R.string.stop)) { toggleServer() }
        bar.addView(toggle)

        web = WebView(this).apply {
            setBackgroundColor(BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
            // Sin esto, confirm() y prompt() de la mesa de control no se muestran
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host ?: return false
                    if (host == "127.0.0.1" || host == "localhost") return false
                    // Enlaces externos, al navegador
                    return try { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true } catch (_: Exception) { false }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = url.startsWith("http://127.0.0.1")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        pageLoaded = false
                        if (ServerService.isRunning) handler.postDelayed({ loadHome() }, 700)
                    }
                }
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Android 15 dibuja la app detrás de las barras del sistema: dejamos el espacio.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        ServerService.listener = { runOnUiThread { onServerStopped() } }
        startServer()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusLoop)
        handler.post(statusLoop)
        if (ServerService.isRunning && !pageLoaded) loadHome()
    }

    override fun onPause() {
        handler.removeCallbacks(statusLoop)
        super.onPause()
    }

    override fun onDestroy() {
        ServerService.listener = null
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Navegación hacia atrás dentro de las páginas")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ servidor
    private fun startServer() {
        ServerService.lastError = null
        ServerService.start(this)
        toggle.text = getString(R.string.stop)
        status.text = getString(R.string.status_starting)
        handler.postDelayed({ loadHome() }, 400)
    }

    private fun toggleServer() {
        if (ServerService.isRunning) ServerService.stop(this) else startServer()
    }

    private fun onServerStopped() {
        pageLoaded = false
        toggle.text = getString(R.string.start)
        web.loadDataWithBaseURL(null, stoppedPage(), "text/html", "utf-8", null)
        updateStatus()
    }

    private fun loadHome() {
        if (ServerService.isRunning) web.loadUrl("http://127.0.0.1:${ServerService.PORT}/")
        else ServerService.lastError?.let { updateStatus() }
    }

    private fun updateStatus() {
        val svc = ServerService.instance
        if (svc == null || !ServerService.isRunning) {
            status.text = ServerService.lastError ?: getString(R.string.status_stopped)
            toggle.text = getString(R.string.start)
        } else {
            val ip = svc.ips().firstOrNull()
            val screens = svc.screens()
            status.text = if (ip != null) {
                getString(R.string.status_running, "$ip:${ServerService.PORT}", screens)
            } else {
                getString(R.string.status_noip)
            }
            toggle.text = getString(R.string.stop)
            if (ip != lastIp) { lastIp = ip; svc.refreshNotification() }
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ ajustes del sistema
    private fun openHotspot() {
        val attempts = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in attempts) {
            try { startActivity(i); return } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ utilidades
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun stoppedPage() = """
        <html><body style="margin:0;background:#060912;color:#e6ebf4;font-family:sans-serif;display:flex;
        align-items:center;justify-content:center;height:100vh;text-align:center">
        <div><div style="font-size:48px">🏀</div><h2>${getString(R.string.status_stopped)}</h2>
        <p style="color:#8391a8">${getString(R.string.stopped_hint)}</p></div></body></html>
    """.trimIndent()

    companion object {
        private val BG = Color.parseColor("#060912")
        private val BAR = Color.parseColor("#0b1020")
    }
}
