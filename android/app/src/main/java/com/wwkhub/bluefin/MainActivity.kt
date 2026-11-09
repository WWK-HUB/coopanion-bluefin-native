package com.wwkhub.bluefin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wwkhub.bluefin.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity(), BluefinHost {
    private lateinit var binding: ActivityMainBinding
    private lateinit var assetLoader: WebViewAssetLoader
    private val cuePlayer = CuePlayer()
    private val bridge = BluefinBridge(this)

    @Volatile
    private var soundOn = true
    private var previewReady = false
    private var entryPath = PreviewEntry.ROUTE_B
    private var pollGeneration = 0
    private var lastProblem: String? = null
    private var tipIndex = 0
    private var roamOn = true
    private var petHidden = false
    private var petSize = "medium"
    private var pendingOverlayGuide = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) BluefinForegroundService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        soundOn = prefs().getBoolean(KEY_SOUND, true)
        roamOn = prefs().getBoolean(KEY_ROAM, true)
        petHidden = prefs().getBoolean(KEY_HIDDEN, false)
        petSize = prefs().getString(KEY_SIZE, "medium") ?: "medium"
        applyInsets()
        ensureForegroundService()
        setupWebView()
        binding.openActions.setOnClickListener { showActions() }
        binding.openOverlay.setOnClickListener { OverlayLauncher.toggle(this) }
        binding.openSettings.setOnClickListener { showSettings() }
        binding.tipsSkip.setOnClickListener { finishTips() }
        binding.tipsNext.setOnClickListener { advanceTip() }
        binding.statusRetry.setOnClickListener { loadEntry() }
        binding.openActions.isEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.web.canGoBack()) binding.web.goBack() else finish()
            }
        })

        loadEntry()
        consumeOverlayIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOverlayIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.web.onResume()
        OverlayLauncher.onResume(this)
        if (pendingOverlayGuide) {
            pendingOverlayGuide = false
            window.decorView.post {
                if (isFinishing || isDestroyed) return@post
                if (Settings.canDrawOverlays(this)) OverlayLauncher.show(this) else OverlayLauncher.showGuide(this)
            }
        }
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pollGeneration++
        cuePlayer.release()
        if (::binding.isInitialized) {
            binding.web.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeJavascriptInterface(BRIDGE_NAME)
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
        }
        super.onDestroy()
    }

    override fun isSoundOn(): Boolean = soundOn

    override fun onScriptMessage(message: String) {
        Log.w(TAG, message)
        runOnUiThread {
            if (!previewReady && message.isNotBlank()) lastProblem = message
        }
    }

    override fun onPlayCue(name: String, kind: String) {
        if (!soundOn) return
        runOnUiThread { cuePlayer.play(name.ifBlank { kind }) }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (8 * resources.displayMetrics.density).toInt()
            binding.topBar.setPadding(bars.left + extra, bars.top + extra, bars.right + extra, 0)
            val belowBar = bars.top + extra + (56 * resources.displayMetrics.density).toInt()
            binding.bottomChrome.setPadding(bars.left + extra * 2, belowBar, bars.right + extra * 2, extra)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetMimeHandler(this))
            .build()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        binding.web.setBackgroundColor(getColor(R.color.stage))
        binding.web.overScrollMode = View.OVER_SCROLL_NEVER
        binding.web.isVerticalScrollBarEnabled = false
        binding.web.isHorizontalScrollBarEnabled = false
        binding.web.isLongClickable = false
        binding.web.isHapticFeedbackEnabled = false
        binding.web.setOnLongClickListener { true }
        binding.web.settings.apply {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) forceDark = WebSettings.FORCE_DARK_OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed = false
        }
        binding.web.addJavascriptInterface(bridge, BRIDGE_NAME)
        binding.web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "${consoleMessage.messageLevel()} ${consoleMessage.message()}")
                return true
            }
        }
        binding.web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                return host != "appassets.androidplatform.net"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!url.contains(entryPath.substringAfterLast('/'))) return
                injectBridge()
                pollReady()
            }
        }
    }

    private fun loadEntry() {
        pollGeneration++
        lastProblem = null
        previewReady = false
        binding.openActions.isEnabled = false
        binding.tipsCard.visibility = View.GONE
        val path = PreviewEntry.choose(assetExists(PreviewEntry.ROUTE_B), assetExists(PreviewEntry.ROUTE_A))
        entryPath = path
        val selfTest = PreviewEntry.isSelfTest(path) && assetExists(path)
        binding.selfTestBanner.visibility = if (selfTest) View.VISIBLE else View.GONE
        if (!assetExists(path)) {
            showStatus(missingMessage(path), error = true)
            return
        }
        showStatus(getString(R.string.loading), error = false)
        val url = PreviewEntry.urlFor(path)
        Log.i(TAG, "load $url")
        binding.web.loadUrl(url)
    }

    private fun injectBridge() {
        val script = assets.open("native/bridge.js").bufferedReader().use { it.readText() }
        binding.web.evaluateJavascript(script, null)
        applySound()
    }

    private fun pollReady() {
        val generation = ++pollGeneration
        var tries = 0
        fun tick() {
            if (generation != pollGeneration || isDestroyed) return
            binding.web.evaluateJavascript(READY_JS) { raw ->
                if (generation != pollGeneration || isDestroyed) return@evaluateJavascript
                if (raw == "true") {
                    onPreviewReady()
                } else if (++tries < 200) {
                    binding.web.postDelayed({ tick() }, 200)
                } else {
                    val detail = lastProblem?.let { "\n$it" }.orEmpty()
                    showStatus(getString(R.string.load_timeout) + detail, error = true)
                }
            }
        }
        tick()
    }

    private fun onPreviewReady() {
        if (previewReady) return
        previewReady = true
        binding.web.evaluateJavascript("window.__bluefinNative&&window.__bluefinNative.fitStage()", null)
        applySound()
        showStatus("", error = false)
        binding.statusOverlay.visibility = View.GONE
        binding.openActions.isEnabled = true
        applyPetPrefs()
        if (!prefs().getBoolean(KEY_TIPS_DONE, false)) showTips()
    }

    private fun showActions() {
        if (!previewReady) return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, null)
        listOf(
            R.id.action_wave to "wave",
            R.id.action_walk to "walk",
            R.id.action_run to "run",
            R.id.action_dance to "dance",
            R.id.action_sleep to "sleep",
        ).forEach { (id, word) ->
            view.findViewById<View>(id).setOnClickListener {
                trigger(word)
                dialog.dismiss()
            }
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun trigger(word: String) {
        val quoted = JSONObject.quote(word)
        binding.web.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.act($quoted)",
            null,
        )
    }

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_settings, null)
        val sound = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.sound_switch)
        val roam = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.roam_switch)
        val hide = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.hide_switch)
        val overlay = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.overlay_switch)
        val sizes = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.size_group)
        val about = view.findViewById<android.widget.TextView>(R.id.about_body)
        sound.isChecked = soundOn
        roam.isChecked = roamOn
        hide.isChecked = petHidden
        var overlaySync = false
        val overlayListener = OverlayLauncher.Listener { showing ->
            if (overlay.isChecked == showing) return@Listener
            overlaySync = true
            overlay.isChecked = showing
            overlaySync = false
        }
        overlay.isChecked = OverlayLauncher.isShowing()
        sizes.check(
            when (petSize) {
                "small" -> R.id.size_small
                "large" -> R.id.size_large
                else -> R.id.size_medium
            },
        )
        overlay.setOnCheckedChangeListener { _, checked ->
            if (overlaySync || checked == OverlayLauncher.isShowing()) return@setOnCheckedChangeListener
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    overlaySync = true
                    overlay.isChecked = false
                    overlaySync = false
                    OverlayLauncher.showGuide(this)
                } else if (!OverlayLauncher.show(this)) {
                    overlaySync = true
                    overlay.isChecked = false
                    overlaySync = false
                }
            } else {
                OverlayLauncher.hide(this)
            }
        }
        OverlayLauncher.addListener(overlayListener)
        dialog.setOnDismissListener { OverlayLauncher.removeListener(overlayListener) }
        sound.setOnCheckedChangeListener { _, checked ->
            soundOn = checked
            prefs().edit().putBoolean(KEY_SOUND, checked).apply()
            applySound()
        }
        roam.setOnCheckedChangeListener { _, checked ->
            roamOn = checked
            prefs().edit().putBoolean(KEY_ROAM, checked).apply()
            applyPetPrefs()
        }
        hide.setOnCheckedChangeListener { _, checked ->
            petHidden = checked
            prefs().edit().putBoolean(KEY_HIDDEN, checked).apply()
            applyPetPrefs()
        }
        sizes.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            petSize = when (checkedId) {
                R.id.size_small -> "small"
                R.id.size_large -> "large"
                else -> "medium"
            }
            prefs().edit().putString(KEY_SIZE, petSize).apply()
            applyPetPrefs()
        }
        about.text = getString(R.string.about, BuildConfig.VERSION_NAME)
        view.findViewById<View>(R.id.reset_button).setOnClickListener {
            dialog.dismiss()
            confirmReset()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> performReset() }
            .show()
    }

    private fun performReset() {
        prefs().edit().putBoolean(KEY_TIPS_DONE, false).apply()
        if (!previewReady) {
            showTips()
            return
        }
        binding.web.evaluateJavascript(RESET_JS) { raw ->
            if (raw?.contains("missing") == true) binding.web.reload()
            showTips()
        }
    }

    private fun ensureForegroundService() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (granted == PackageManager.PERMISSION_GRANTED) {
                BluefinForegroundService.start(this)
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            BluefinForegroundService.start(this)
        }
    }

    private fun applyPetPrefs() {
        if (!::binding.isInitialized) return
        val quoted = org.json.JSONObject.quote(petSize)
        binding.web.evaluateJavascript(
            "window.__bluefinNative&&window.__bluefinNative.applyPrefs($roamOn,$quoted,$petHidden)",
            null,
        )
        OverlayLauncher.sync(this)
    }

    private fun applySound() {
        if (!::binding.isInitialized) return
        binding.web.evaluateJavascript(
            "window.__bluefinApplySound&&window.__bluefinApplySound($soundOn)",
            null,
        )
        OverlayLauncher.sync(this)
    }

    private fun consumeOverlayIntent(source: Intent?) {
        if (source?.action != OverlayLauncher.ACTION_OVERLAY_GUIDE) return
        source.action = null
        pendingOverlayGuide = true
    }

    private fun showTips() {
        tipIndex = 0
        binding.tipsCard.visibility = View.VISIBLE
        renderTip()
    }

    private fun renderTip() {
        binding.tipsStep.text = getString(R.string.tip_step, tipIndex + 1, TIP_BODIES.size)
        binding.tipsBody.setText(TIP_BODIES[tipIndex])
        binding.tipsNext.setText(if (tipIndex == TIP_BODIES.lastIndex) R.string.tip_done else R.string.tip_next)
    }

    private fun advanceTip() {
        if (tipIndex >= TIP_BODIES.lastIndex) finishTips() else {
            tipIndex += 1
            renderTip()
        }
    }

    private fun finishTips() {
        prefs().edit().putBoolean(KEY_TIPS_DONE, true).apply()
        binding.tipsCard.visibility = View.GONE
    }

    private fun showStatus(message: String, error: Boolean) {
        if (!error && message.isEmpty()) {
            binding.statusOverlay.visibility = View.GONE
            return
        }
        binding.statusOverlay.visibility = View.VISIBLE
        binding.statusText.text = message
        binding.statusProgress.visibility = if (error) View.GONE else View.VISIBLE
        binding.statusRetry.visibility = if (error) View.VISIBLE else View.GONE
    }

    private fun missingMessage(path: String): String {
        return if (path == PreviewEntry.ROUTE_B) getString(R.string.missing_route_b)
        else getString(R.string.missing_asset, path)
    }

    private fun assetExists(path: String): Boolean {
        return try {
            assets.open(path).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "Bluefin"
        const val BRIDGE_NAME = "BluefinNative"
        const val PREFS = BluefinStore.PREFS
        const val KEY_TIPS_DONE = BluefinStore.KEY_TIPS_DONE
        const val KEY_SOUND = BluefinStore.KEY_SOUND
        const val KEY_ROAM = BluefinStore.KEY_ROAM
        const val KEY_HIDDEN = BluefinStore.KEY_HIDDEN
        const val KEY_SIZE = BluefinStore.KEY_SIZE
        const val READY_JS =
            "(function(){return !!(window.__bluefinNative&&window.__bluefinNative.ready&&window.__bluefinNative.ready());})()"
        const val RESET_JS =
            "(function(){return window.__bluefinNative?String(window.__bluefinNative.reset()):'missing';})()"
        val TIP_BODIES = intArrayOf(R.string.tip_1, R.string.tip_2, R.string.tip_3)
    }
}
