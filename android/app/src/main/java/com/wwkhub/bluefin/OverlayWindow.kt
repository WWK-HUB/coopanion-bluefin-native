package com.wwkhub.bluefin

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * A small translucent [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * hosting the same offline preview as the in-app WebView.
 *
 * The window is owned by an application / window context, not an Activity,
 * so a configuration change does not destroy it. Position is clamped to the
 * current display on every drag and whenever the display size changes.
 * Touches outside the window pass through (`FLAG_NOT_TOUCH_MODAL`); touches
 * inside hit either the preview or the chrome, never a full-screen layer.
 */
internal class OverlayWindow(
    context: Context,
    private val onCloseRequested: () -> Unit,
    private val onUnexpectedDetach: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val uiContext = createUiContext(appContext)
    private val themedContext = ContextThemeWrapper(uiContext, R.style.Theme_Bluefin)
    private val windowManager = uiContext.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cuePlayer = CuePlayer()
    private val root: FrameLayout
    private val web: WebView
    private val handle: View
    private val chromeBar: View
    private val closeButton: View
    private val params: WindowManager.LayoutParams
    private var attached = false
    private var destroyed = false
    private var closing = false
    private var pageReady = false
    private var placed = false
    private var dragging = false
    private var startX = 0
    private var startY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastScreenW = 0
    private var lastScreenH = 0

    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            relayout()
        }

        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) = Unit
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = screenSize()
            if (w == lastScreenW && h == lastScreenH) return
            relayout()
        }
    }

    init {
        checkPlatformFlags()
        root = inflateRoot()
        web = root.findViewById(R.id.overlay_web)
        handle = root.findViewById(R.id.overlay_handle)
        chromeBar = root.findViewById(R.id.overlay_chrome)
        closeButton = root.findViewById(R.id.overlay_close)
        params = buildParams()
        setupWebView()
        setupDrag()
        closeButton.setOnClickListener {
            if (destroyed) return@setOnClickListener
            root.post { if (!destroyed) onCloseRequested() }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (closing || destroyed) return
                attached = false
                handler.post { onUnexpectedDetach() }
            }
        })
    }

    fun show() {
        if (attached || destroyed) return
        applyWindowSize(initial = true)
        registerDisplayCallbacks()
        windowManager.addView(root, params)
        attached = true
        web.loadUrl(PreviewEntry.urlFor(PreviewEntry.ROUTE_B))
    }

    fun close() {
        if (destroyed) return
        closing = true
        val wasAttached = attached
        attached = false
        unregisterDisplayCallbacks()
        if (wasAttached) {
            try {
                windowManager.removeViewImmediate(root)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "overlay was already detached", error)
            }
        }
        destroyWebView()
        destroyed = true
    }

    fun syncFromPrefs() {
        if (destroyed) return
        applyWindowSize(initial = false)
        if (attached) updateLayout()
        if (pageReady) pushPrefs()
    }

    @SuppressLint("InflateParams")
    private fun inflateRoot(): FrameLayout {
        return android.view.LayoutInflater.from(themedContext)
            .inflate(R.layout.overlay_window, null) as FrameLayout
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetMimeHandler(uiContext))
            .build()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        web.setBackgroundColor(0)
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isLongClickable = false
        web.isHapticFeedbackEnabled = false
        web.isFocusableInTouchMode = false
        web.setOnLongClickListener { true }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = false
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) forceDark = WebSettings.FORCE_DARK_OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed = false
        }
        web.addJavascriptInterface(
            BluefinBridge(object : BluefinHost {
                override fun isSoundOn(): Boolean = soundOn()

                override fun onScriptMessage(message: String) {
                    Log.w(TAG, message)
                }

                override fun onPlayCue(name: String, kind: String) {
                    if (!soundOn()) return
                    val cue = name.ifBlank { kind }
                    handler.post { cuePlayer.play(cue) }
                }
            }),
            BRIDGE_NAME,
        )
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ) = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != "appassets.androidplatform.net"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed || !attached) return
                if (!url.contains("index.html")) return
                pageReady = true
                injectPage()
            }
        }
    }

    private fun injectPage() {
        val bridge = uiContext.assets.open("native/bridge.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(bridge, null)
        web.evaluateJavascript(OVERLAY_CHROME_JS, null)
        pushPrefs()
    }

    private fun pushPrefs() {
        if (destroyed) return
        val roam = prefs().getBoolean(BluefinStore.KEY_ROAM, true)
        val hidden = prefs().getBoolean(BluefinStore.KEY_HIDDEN, false)
        val size = JSONObject.quote(sizeName())
        val sound = soundOn()
        web.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.applyPrefs($roam,$size,$hidden)",
            null,
        )
        web.evaluateJavascript(
            "window.__bluefinApplySound&&window.__bluefinApplySound($sound)",
            null,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        handle.setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging || !attached) return@setOnTouchListener true
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    val (sw, sh) = screenSize()
                    val nx = startX + (event.rawX - downRawX).roundToInt()
                    val ny = startY + (event.rawY - downRawY).roundToInt()
                    val (cx, cy) = OverlayLayout.clamp(nx, ny, params.width, params.height, sw, sh)
                    if (cx != params.x || cy != params.y) {
                        params.x = cx
                        params.y = cy
                        updateLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun buildParams(): WindowManager.LayoutParams {
        val (sw, sh) = screenSize()
        val (w, h) = OverlayLayout.windowSizePx(sizeName(), density(), sw, sh)
        return WindowManager.LayoutParams(
            w,
            h,
            OverlayLayout.TYPE_APPLICATION_OVERLAY,
            OverlayLayout.WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            @Suppress("DEPRECATION")
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun applyWindowSize(initial: Boolean) {
        val (sw, sh) = screenSize()
        lastScreenW = sw
        lastScreenH = sh
        val (w, h) = OverlayLayout.windowSizePx(sizeName(), density(), sw, sh)
        val chrome = OverlayLayout.chromePx(density())
        val stage = (h - chrome).coerceAtLeast(1)
        val webParams = web.layoutParams as FrameLayout.LayoutParams
        val chromeParams = chromeBar.layoutParams as FrameLayout.LayoutParams
        params.width = w
        params.height = h
        if (chromeParams.height != chrome) {
            chromeParams.height = chrome
            chromeBar.layoutParams = chromeParams
        }
        if (webParams.height != stage) {
            webParams.height = stage
            web.layoutParams = webParams
        }
        val (x, y) = if (initial && !placed) {
            placed = true
            OverlayLayout.initialPosition(w, h, sw, sh, density())
        } else {
            OverlayLayout.clamp(params.x, params.y, w, h, sw, sh)
        }
        if (dragging && (x != params.x || y != params.y)) {
            startX = x
            startY = y
            downRawX = lastRawX
            downRawY = lastRawY
        }
        params.x = x
        params.y = y
    }

    private fun relayout() {
        if (!attached || destroyed) return
        applyWindowSize(initial = false)
        updateLayout()
        if (pageReady) {
            web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
        }
    }

    private fun updateLayout() {
        if (!attached || destroyed) return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay layout update skipped", error)
            attached = false
        } catch (error: SecurityException) {
            Log.w(TAG, "overlay permission lost during layout", error)
            attached = false
            if (!closing && !destroyed) handler.post { onUnexpectedDetach() }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            point.x.coerceAtLeast(1) to point.y.coerceAtLeast(1)
        }
    }

    private fun registerDisplayCallbacks() {
        uiContext.registerComponentCallbacks(componentCallbacks)
        uiContext.getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, handler)
    }

    private fun unregisterDisplayCallbacks() {
        try {
            uiContext.unregisterComponentCallbacks(componentCallbacks)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay callbacks already gone", error)
        }
        try {
            uiContext.getSystemService(DisplayManager::class.java)
                .unregisterDisplayListener(displayListener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay display listener already gone", error)
        }
    }

    private var webDestroyed = false

    private fun destroyWebView() {
        if (webDestroyed) return
        webDestroyed = true
        cuePlayer.release()
        try {
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.removeJavascriptInterface(BRIDGE_NAME)
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        } catch (error: RuntimeException) {
            Log.w(TAG, "overlay WebView cleanup failed", error)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)

    private fun sizeName(): String = prefs().getString(BluefinStore.KEY_SIZE, "medium") ?: "medium"

    private fun soundOn(): Boolean = prefs().getBoolean(BluefinStore.KEY_SOUND, true)

    private fun density(): Float = uiContext.resources.displayMetrics.density

    private companion object {
        const val TAG = "BluefinOverlay"
        const val BRIDGE_NAME = "BluefinNative"
        val OVERLAY_CHROME_JS = """
            (function () {
              var id = 'bluefin-overlay-style';
              var style = document.getElementById(id);
              if (!style) {
                style = document.createElement('style');
                style.id = id;
                document.head.appendChild(style);
              }
              style.textContent = [
                'html,body{background:transparent!important;overflow:hidden!important;margin:0!important}',
                'main{padding:0!important;height:100%!important;display:block!important}',
                '#wrap{height:100%!important;display:block!important}',
                'header,#panel,#hint,#events,#stage:after{display:none!important}',
                '#stage{position:fixed!important;left:0!important;top:0!important;',
                'right:0!important;bottom:0!important;width:100%!important;height:100%!important;',
                'min-width:0!important;min-height:0!important;background:transparent!important;border:0!important}'
              ].join('');
              if (window.__bluefinOverlayResize) clearInterval(window.__bluefinOverlayResize);
              var tries = 0;
              window.__bluefinOverlayResize = setInterval(function () {
                window.dispatchEvent(new Event('resize'));
                if (++tries >= 12) {
                  clearInterval(window.__bluefinOverlayResize);
                  window.__bluefinOverlayResize = 0;
                }
              }, 300);
            })();
        """.trimIndent()

        fun createUiContext(app: Context): Context {
            // Application context is not a display context, so the one-arg
            // createWindowContext fails. API 31+ can bind the overlay type to
            // the default display and follow rotation independently of the
            // portrait-locked activity.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return app
            return try {
                val display = app.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return app
                app.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null,
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "window context unavailable, using application context", error)
                app
            }
        }

        fun checkPlatformFlags() {
            check(OverlayLayout.TYPE_APPLICATION_OVERLAY == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            check(OverlayLayout.FLAG_NOT_FOCUSABLE == WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            check(OverlayLayout.FLAG_NOT_TOUCHABLE == WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            check(OverlayLayout.FLAG_NOT_TOUCH_MODAL == WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            check(OverlayLayout.FLAG_LAYOUT_IN_SCREEN == WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            check(OverlayLayout.FLAG_HARDWARE_ACCELERATED == WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            check(OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCHABLE == 0)
        }
    }
}
