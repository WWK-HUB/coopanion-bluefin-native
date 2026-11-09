package com.wwkhub.bluefin

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Full-screen transparent stage plus a pet-sized touch window.
 * The engine draws the character on the screen (roam, lift, throw, poke).
 * Touches outside the hit circles pass through to other apps.
 */
internal class OverlayWindow(
    context: Context,
    private val onCloseRequested: () -> Unit,
    private val onUnexpectedDetach: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val uiContext = createUiContext(appContext)
    private val themedContext = android.view.ContextThemeWrapper(uiContext, R.style.Theme_Bluefin)
    private val windowManager = uiContext.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cuePlayer = CuePlayer()
    private val root: FrameLayout
    private val web: WebView
    private val params: WindowManager.LayoutParams
    private val hit = View(themedContext)
    private val hitParams: WindowManager.LayoutParams
    private val actions: View
    private val actionsParams: WindowManager.LayoutParams
    private var actionsExpanded = false
    private var attached = false
    private var hitAttached = false
    private var actionsAttached = false
    private var destroyed = false
    private var closing = false
    private var pageReady = false
    private var tracking = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var lastMode = ""
    private var resting = false
    private var lastInteract = SystemClock.uptimeMillis()
    private var lastTurnAt = 0L
    private var webDestroyed = false

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
            relayout()
        }
    }

    init {
        checkPlatformFlags()
        root = inflateRoot()
        web = root.findViewById(R.id.overlay_web)
        params = fullscreenParams(touchable = false)
        hitParams = fullscreenParams(touchable = true)
        actions = android.view.LayoutInflater.from(themedContext).inflate(R.layout.overlay_actions, null)
        actionsParams = fullscreenParams(touchable = true)
        setupWebView()
        setupHit()
        setupActions()
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
        applyStageSize()
        registerDisplayCallbacks()
        windowManager.addView(root, params)
        attached = true
        addActions()
        web.loadUrl(PreviewEntry.urlFor(PreviewEntry.ROUTE_B))
    }

    fun close() {
        if (destroyed) return
        closing = true
        val wasAttached = attached
        attached = false
        unregisterDisplayCallbacks()
        removeIfAttached(hit, hitAttached)
        hitAttached = false
        removeIfAttached(actions, actionsAttached)
        actionsAttached = false
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
        root.setBackgroundColor(0)
        web.setBackgroundColor(0)
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isLongClickable = false
        web.isHapticFeedbackEnabled = false
        web.isFocusable = false
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
        web.addJavascriptInterface(OverlayLayoutSink { json -> handler.post { onLayout(json) } }, OVERLAY_BRIDGE)
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                Log.i(TAG, "console ${consoleMessage.message()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

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
        val stage = uiContext.assets.open("native/overlay-stage.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(bridge, null)
        web.evaluateJavascript(stage, null)
        pushPrefs()
    }

    private fun pushPrefs() {
        if (destroyed) return
        val roam = prefs().getBoolean(BluefinStore.KEY_ROAM, true)
        val hidden = prefs().getBoolean(BluefinStore.KEY_HIDDEN, false)
        val size = sizeName()
        val quoted = JSONObject.quote(size)
        val scale = OverlayPetPolicy.scaleFor(size)
        web.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.applyPrefs($roam,$quoted,$hidden)",
            null,
        )
        web.evaluateJavascript(
            "window.__bluefinApplySound&&window.__bluefinApplySound(${soundOn()})",
            null,
        )
        web.evaluateJavascript(
            "window.__bluefinOverlay&&window.__bluefinOverlay.setScale($scale)",
            null,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHit() {
        hit.setBackgroundColor(0)
        hit.setOnTouchListener { _, event ->
            val cssX = cssX(event.rawX)
            val cssY = cssY(event.rawY)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    resting = false
                    lastInteract = SystemClock.uptimeMillis()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWinX = hitParams.x
                    downWinY = hitParams.y
                    sendPointer("down", cssX, cssY)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking) moveHitWithFinger(event.rawX, event.rawY)
                    sendPointer("move", cssX, cssY)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    tracking = false
                    lastInteract = SystemClock.uptimeMillis()
                    sendPointer("up", cssX, cssY)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    sendPointer("cancel", cssX, cssY)
                    true
                }

                else -> false
            }
        }
    }

    private fun setupActions() {
        actions.findViewById<View>(R.id.overlay_actions_bar).setOnClickListener {
            actionsExpanded = !actionsExpanded
            Log.i(TAG, "actions expanded=$actionsExpanded")
            actions.findViewById<View>(R.id.overlay_actions_panel).visibility =
                if (actionsExpanded) View.VISIBLE else View.GONE
            resizeActions()
        }
        actions.findViewById<View>(R.id.overlay_close).setOnClickListener {
            if (!destroyed) onCloseRequested()
        }
        listOf(
            R.id.overlay_action_wave to "wave",
            R.id.overlay_action_walk to "walk",
            R.id.overlay_action_run to "run",
            R.id.overlay_action_dance to "dance",
            R.id.overlay_action_sleep to "sleep",
        ).forEach { (id, word) ->
            actions.findViewById<View>(id).setOnClickListener {
                lastInteract = SystemClock.uptimeMillis()
                resting = false
                actionsExpanded = false
                actions.findViewById<View>(R.id.overlay_actions_panel).visibility = View.GONE
                resizeActions()
                web.evaluateJavascript(
                    "window.__bluefinOverlay&&window.__bluefinOverlay.act(${JSONObject.quote(word)})",
                    null,
                )
                Log.i(TAG, "action $word")
            }
        }
    }

    private fun onLayout(json: String) {
        if (destroyed || !attached) return
        val parsed = try {
            JSONObject(json)
        } catch (error: RuntimeException) {
            Log.w(TAG, "bad layout", error)
            return
        }
        val mode = parsed.optString("mode")
        val facing = parsed.optInt("facing", 1)
        val x = parsed.optDouble("x").toFloat()
        val minX = parsed.optDouble("minX").toFloat()
        val maxX = parsed.optDouble("maxX").toFloat()
        if (mode != lastMode) {
            Log.i(TAG, "mode $lastMode -> $mode x=$x facing=$facing")
            if (resting && (mode == "idle" || mode == "walk" || mode == "run")) resting = false
            lastMode = mode
        }
        if (!tracking) placeHit(parsed)
        val now = SystemClock.uptimeMillis()
        val target = OverlayPetPolicy.edgeTarget(x, facing, minX, maxX, mode)
        if (target != null && !resting && now - lastTurnAt > 450) {
            lastTurnAt = now
            val run = mode == "run"
            Log.i(TAG, "edge turn x=$x -> $target")
            web.evaluateJavascript(
                "window.__bluefinOverlay&&window.__bluefinOverlay.walkTo($target, $run)",
                null,
            )
        }
        if (OverlayPetPolicy.shouldStartSleep(now - lastInteract, mode, resting)) {
            resting = true
            Log.i(TAG, "idle rest")
            web.evaluateJavascript("window.__bluefinOverlay&&window.__bluefinOverlay.rest()", null)
        }
    }

    private fun placeHit(parsed: JSONObject) {
        val hits = parsed.optJSONArray("hits") ?: return
        if (hits.length() == 0) return
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until hits.length()) {
            val hit = hits.getJSONObject(i)
            val hx = hit.optDouble("x").toFloat()
            val hy = hit.optDouble("y").toFloat()
            val r = hit.optDouble("r").toFloat()
            minX = minOf(minX, hx - r)
            minY = minOf(minY, hy - r)
            maxX = maxOf(maxX, hx + r)
            maxY = maxOf(maxY, hy + r)
        }
        val d = density()
        val pad = 12f * d
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        val left = (loc[0] + minX * d - pad).roundToInt()
        val top = (loc[1] + minY * d - pad).roundToInt()
        val right = (loc[0] + maxX * d + pad).roundToInt()
        val bottom = (loc[1] + maxY * d + pad).roundToInt()
        val (sw, sh) = screenSize()
        val width = (right - left).coerceAtLeast(dp(48))
        val height = (bottom - top).coerceAtLeast(dp(48))
        val (x, y) = OverlayLayout.clamp(left, top, width, height, sw, sh)
        hitParams.x = x
        hitParams.y = y
        hitParams.width = width.coerceAtMost(sw)
        hitParams.height = height.coerceAtMost(sh)
        if (!hitAttached) {
            try {
                windowManager.addView(hit, hitParams)
                hitAttached = true
                bringActionsToFront()
            } catch (error: RuntimeException) {
                Log.w(TAG, "hit window failed", error)
            }
        } else {
            try {
                windowManager.updateViewLayout(hit, hitParams)
            } catch (error: IllegalArgumentException) {
                hitAttached = false
            }
        }
    }

    private fun moveHitWithFinger(rawX: Float, rawY: Float) {
        if (!hitAttached) return
        val (sw, sh) = screenSize()
        val nx = downWinX + (rawX - downRawX).roundToInt()
        val ny = downWinY + (rawY - downRawY).roundToInt()
        val (x, y) = OverlayLayout.clamp(nx, ny, hitParams.width, hitParams.height, sw, sh)
        hitParams.x = x
        hitParams.y = y
        try {
            windowManager.updateViewLayout(hit, hitParams)
        } catch (error: IllegalArgumentException) {
            hitAttached = false
        }
    }

    private fun sendPointer(type: String, x: Float, y: Float) {
        if (!pageReady) return
        web.evaluateJavascript(
            "window.__bluefinOverlay&&window.__bluefinOverlay.pointer(${JSONObject.quote(type)},$x,$y)",
            null,
        )
    }

    private fun bringActionsToFront() {
        if (!actionsAttached) return
        try {
            windowManager.removeViewImmediate(actions)
            windowManager.addView(actions, actionsParams)
        } catch (error: RuntimeException) {
            Log.w(TAG, "could not raise actions", error)
            actionsAttached = false
        }
    }

    private fun addActions() {
        resizeActions()
        if (actionsAttached) return
        try {
            windowManager.addView(actions, actionsParams)
            actionsAttached = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "actions window failed", error)
        }
    }

    private fun resizeActions() {
        val (sw, _) = screenSize()
        val width = dp(if (actionsExpanded) 168 else 132)
        val height = dp(if (actionsExpanded) 48 + 8 + 48 * 5 else 48)
        actionsParams.width = width
        actionsParams.height = height
        val (x, y) = OverlayLayout.clamp(sw - width - dp(12), dp(64), width, height, sw, screenSize().second)
        actionsParams.x = x
        actionsParams.y = y
        if (actionsAttached) {
            try {
                windowManager.updateViewLayout(actions, actionsParams)
            } catch (error: IllegalArgumentException) {
                actionsAttached = false
            }
        }
    }

    private fun fullscreenParams(touchable: Boolean): WindowManager.LayoutParams {
        val flags = if (touchable) {
            OverlayLayout.WINDOW_FLAGS
        } else {
            OverlayLayout.WINDOW_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayLayout.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = 1f
            @Suppress("DEPRECATION")
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun applyStageSize() {
        val (sw, sh) = screenSize()
        params.width = sw
        params.height = sh
        params.x = 0
        params.y = 0
    }

    private fun relayout() {
        if (!attached || destroyed) return
        applyStageSize()
        try {
            windowManager.updateViewLayout(root, params)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "stage update skipped", error)
        }
        resizeActions()
        if (pageReady) web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
    }

    private fun removeIfAttached(view: View, isAttached: Boolean) {
        if (!isAttached) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "extra window already gone", error)
        }
    }

    private fun cssX(rawX: Float): Float {
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        return (rawX - loc[0]) / density()
    }

    private fun cssY(rawY: Float): Float {
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        return (rawY - loc[1]) / density()
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
        uiContext.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
    }

    private fun unregisterDisplayCallbacks() {
        try {
            uiContext.unregisterComponentCallbacks(componentCallbacks)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay callbacks already gone", error)
        }
        try {
            uiContext.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "overlay display listener already gone", error)
        }
    }

    private fun destroyWebView() {
        if (webDestroyed) return
        webDestroyed = true
        cuePlayer.release()
        try {
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.removeJavascriptInterface(BRIDGE_NAME)
            web.removeJavascriptInterface(OVERLAY_BRIDGE)
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        } catch (error: RuntimeException) {
            Log.w(TAG, "overlay WebView cleanup failed", error)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
    private fun sizeName(): String = prefs().getString(BluefinStore.KEY_SIZE, "medium") ?: "medium"
    private fun soundOn(): Boolean = prefs().getBoolean(BluefinStore.KEY_SOUND, true)
    private fun density(): Float = uiContext.resources.displayMetrics.density.coerceAtLeast(0.5f)
    private fun dp(value: Int): Int = (value * density()).roundToInt()

    private companion object {
        const val TAG = "BluefinOverlay"
        const val BRIDGE_NAME = "BluefinNative"
        const val OVERLAY_BRIDGE = "BluefinOverlay"

        fun createUiContext(app: Context): Context {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return app
            return try {
                val display = app.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return app
                app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
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
        }
    }
}

@Keep
internal class OverlayLayoutSink(private val deliver: (String) -> Unit) {
    @JavascriptInterface
    fun layout(json: String?) {
        if (!json.isNullOrBlank()) deliver(json)
    }
}
