package com.wwkhub.bluefin

import kotlin.math.roundToInt

/**
 * Geometry and toggle policy for the system overlay.
 *
 * Stage height matches the in-app bridge (220 / 252 / 290 CSS px) so
 * `preferredScale` stays in S 0.7–1.0. The pack's own floor is
 * `stageHeight - 40`, so the floor stays 40px above the stage bottom.
 * Width is at least 360dp when the screen can hold it.
 *
 * Flag values are the platform constants. [OverlayWindow] checks them
 * against `WindowManager.LayoutParams` before the window is added.
 */
internal object OverlayLayout {
    const val MIN_STAGE_WIDTH_DP = 360
    const val MIN_STAGE_HEIGHT_DP = 220
    const val CHROME_DP = 48
    const val FLOOR_INSET_PX = 40
    const val INITIAL_BOTTOM_MARGIN_DP = 48

    const val TYPE_APPLICATION_OVERLAY = 2038
    const val FLAG_NOT_FOCUSABLE = 8
    const val FLAG_NOT_TOUCHABLE = 16
    const val FLAG_NOT_TOUCH_MODAL = 32
    const val FLAG_LAYOUT_IN_SCREEN = 256
    const val FLAG_HARDWARE_ACCELERATED = 16_777_216

    const val WINDOW_FLAGS =
        FLAG_NOT_FOCUSABLE or
            FLAG_NOT_TOUCH_MODAL or
            FLAG_LAYOUT_IN_SCREEN or
            FLAG_HARDWARE_ACCELERATED

    enum class Toggle {
        Show,
        Hide,
        NeedPermission,
    }

    fun stageHeightDp(sizeName: String?): Int = when (sizeName) {
        "small" -> 220
        "large" -> 290
        else -> 252
    }

    fun chromePx(density: Float): Int = (CHROME_DP * density.coerceAtLeast(0.5f)).roundToInt().coerceAtLeast(1)

    fun windowSizePx(
        sizeName: String?,
        density: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Pair<Int, Int> {
        val d = density.coerceAtLeast(0.5f)
        val screenW = screenWidthPx.coerceAtLeast(1)
        val screenH = screenHeightPx.coerceAtLeast(1)
        val widthDp = minOf(MIN_STAGE_WIDTH_DP.toFloat(), screenW / d)
        var heightDp = stageHeightDp(sizeName) + CHROME_DP.toFloat()
        val screenHeightDp = screenH / d
        if (heightDp > screenHeightDp) heightDp = screenHeightDp
        val widthPx = (widthDp * d).roundToInt().coerceIn(1, screenW)
        val heightPx = (heightDp * d).roundToInt().coerceIn(1, screenH)
        return widthPx to heightPx
    }

    /**
     * Same formula as `preferredScale()` in `assets/bluefin/index.html`.
     * Inputs are CSS pixels. With a device-width viewport those match dp.
     */
    fun preferredScale(stageWidthCss: Float, stageHeightCss: Float): Float {
        if (stageHeightCss < 40f || stageWidthCss < 40f) return 1f
        val raw = minOf((stageHeightCss - 28f) / 262f, (stageWidthCss - 12f) / 190f)
        val clamped = raw.coerceIn(0.48f, 1.35f)
        return (clamped * 100f).roundToInt() / 100f
    }

    fun clamp(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    fun initialPosition(
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): Pair<Int, Int> {
        val margin = (INITIAL_BOTTOM_MARGIN_DP * density.coerceAtLeast(0.5f)).roundToInt()
        val x = ((screenWidth - windowWidth) / 2).coerceAtLeast(0)
        val y = (screenHeight - windowHeight - margin).coerceAtLeast(0)
        return clamp(x, y, windowWidth, windowHeight, screenWidth, screenHeight)
    }

    fun decideToggle(showing: Boolean, canDrawOverlays: Boolean): Toggle = when {
        showing -> Toggle.Hide
        canDrawOverlays -> Toggle.Show
        else -> Toggle.NeedPermission
    }
}

/**
 * Permission handoff that can be saved and restored.
 *
 * Opening system settings arms a single re-check. Declining in the app, or
 * coming back without permission, dismisses the guide until Settings resets it.
 */
internal class OverlayPermissionSession(
    awaiting: Boolean = false,
    dismissed: Boolean = false,
) {
    var awaitingSettings: Boolean = awaiting
        private set

    var promptDismissed: Boolean = dismissed
        private set

    fun shouldOfferGuide(): Boolean = !promptDismissed

    fun onOpenSettings() {
        awaitingSettings = true
    }

    fun onDeclinedInApp() {
        awaitingSettings = false
        promptDismissed = true
    }

    fun onResetPrompt() {
        awaitingSettings = false
        promptDismissed = false
    }

    fun onResume(canDraw: Boolean): Result {
        if (!awaitingSettings) return Result.Idle
        awaitingSettings = false
        return if (canDraw) {
            promptDismissed = false
            Result.Granted
        } else {
            promptDismissed = true
            Result.Denied
        }
    }

    enum class Result {
        Idle,
        Granted,
        Denied,
    }
}
