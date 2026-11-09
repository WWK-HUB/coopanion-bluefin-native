package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayLayoutTest {
    @Test
    fun stageHeightsMatchInAppBridge() {
        assertEquals(220, OverlayLayout.stageHeightDp("small"))
        assertEquals(252, OverlayLayout.stageHeightDp("medium"))
        assertEquals(252, OverlayLayout.stageHeightDp(null))
        assertEquals(290, OverlayLayout.stageHeightDp("large"))
        assertTrue(OverlayLayout.stageHeightDp("small") >= OverlayLayout.MIN_STAGE_HEIGHT_DP)
    }

    @Test
    fun approvedSizesLandInScaleBandAndKeepFloorInset() {
        listOf(220f, 252f, 290f).forEach { height ->
            val scale = OverlayLayout.preferredScale(360f, height)
            assertTrue("S=$scale at h=$height", scale in 0.7f..1.0f)
        }
        assertEquals(40, OverlayLayout.FLOOR_INSET_PX)
        assertEquals(0.73f, OverlayLayout.preferredScale(360f, 220f))
        assertEquals(1.0f, OverlayLayout.preferredScale(360f, 290f))
    }

    @Test
    fun windowIsAtLeast360DpWideWhenTheScreenAllowsIt() {
        val density = 2.75f
        val (width, height) = OverlayLayout.windowSizePx("medium", density, 1080, 2400)
        assertEquals((360 * density).toInt(), width)
        val stageDp = OverlayLayout.stageHeightDp("medium")
        val expectedHeight = ((stageDp + OverlayLayout.CHROME_DP) * density).let { kotlin.math.round(it).toInt() }
        assertEquals(expectedHeight, height)
        assertTrue(height > width / 4)
    }

    @Test
    fun narrowOrShortScreensStayInsideTheDisplay() {
        val density = 2f
        val (width, _) = OverlayLayout.windowSizePx("large", density, 320 * 2, 2000)
        assertTrue(width <= 320 * 2)
        val (_, shortHeight) = OverlayLayout.windowSizePx("large", density, 1080, 200)
        assertTrue(shortHeight <= 200)
    }

    @Test
    fun clampKeepsTheWindowOnScreen() {
        assertEquals(0 to 0, OverlayLayout.clamp(-20, -4, 100, 80, 500, 800))
        assertEquals(400 to 700, OverlayLayout.clamp(900, 900, 100, 100, 500, 800))
        assertEquals(12 to 40, OverlayLayout.clamp(12, 40, 100, 100, 500, 800))
        assertEquals(0 to 0, OverlayLayout.clamp(10, 10, 900, 900, 400, 600))
    }

    @Test
    fun initialPositionIsClamped() {
        val (x, y) = OverlayLayout.initialPosition(300, 200, 1000, 1800, 2f)
        val (cx, cy) = OverlayLayout.clamp(x, y, 300, 200, 1000, 1800)
        assertEquals(cx, x)
        assertEquals(cy, y)
        assertTrue(x >= 0 && y >= 0)
        assertTrue(x + 300 <= 1000)
        assertTrue(y + 200 <= 1800)
    }

    @Test
    fun toggleAsksForPermissionOnlyWhenHiddenAndUngranted() {
        assertEquals(OverlayLayout.Toggle.Hide, OverlayLayout.decideToggle(showing = true, canDrawOverlays = true))
        assertEquals(OverlayLayout.Toggle.Hide, OverlayLayout.decideToggle(showing = true, canDrawOverlays = false))
        assertEquals(OverlayLayout.Toggle.Show, OverlayLayout.decideToggle(showing = false, canDrawOverlays = true))
        assertEquals(
            OverlayLayout.Toggle.NeedPermission,
            OverlayLayout.decideToggle(showing = false, canDrawOverlays = false),
        )
    }

    @Test
    fun windowDoesNotAcceptTouchThroughFlags() {
        assertTrue(OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCH_MODAL != 0)
        assertEquals(0, OverlayLayout.WINDOW_FLAGS and OverlayLayout.FLAG_NOT_TOUCHABLE)
        assertEquals(2038, OverlayLayout.TYPE_APPLICATION_OVERLAY)
    }

    @Test
    fun denialDoesNotRearmTheGuide() {
        val session = OverlayPermissionSession()
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = false))
        session.onDeclinedInApp()
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = false))

        session.onOpenSettings()
        assertEquals(OverlayPermissionSession.Result.Denied, session.onResume(canDraw = false))
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = false))
        assertFalse(session.awaitingSettings)
        assertTrue(session.promptDismissed)
    }

    @Test
    fun declinedPromptSurvivesANewSessionUntilReset() {
        val first = OverlayPermissionSession()
        first.onDeclinedInApp()
        val restored = OverlayPermissionSession(
            awaiting = first.awaitingSettings,
            dismissed = first.promptDismissed,
        )
        assertFalse(restored.shouldOfferGuide())
        assertEquals(OverlayPermissionSession.Result.Idle, restored.onResume(canDraw = false))
        restored.onResetPrompt()
        assertTrue(restored.shouldOfferGuide())
        assertFalse(restored.promptDismissed)
    }

    @Test
    fun returningFromSettingsShowsOnce() {
        val session = OverlayPermissionSession()
        session.onOpenSettings()
        assertEquals(OverlayPermissionSession.Result.Granted, session.onResume(canDraw = true))
        assertEquals(OverlayPermissionSession.Result.Idle, session.onResume(canDraw = true))
    }

    @Test
    fun guideCopyAndVersionMatchTheApprovedScope() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("允许蓝鳍显示在其他应用上层？"))
        assertTrue(strings.contains("开启后蓝鳍可以像桌宠一样浮在屏幕上走动、被戳和拖拽。你可以随时在设置里关闭。若暂不允许，仍可在应用内互动。"))
        assertTrue(strings.contains(">去开启<"))
        assertTrue(strings.contains(">先在应用内用<"))
        assertTrue(strings.contains(">显示悬浮窗<"))
        assertTrue(strings.contains(">隐藏悬浮窗<"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"0.3.0-preview\""))
        assertTrue(gradle.contains("versionCode = 5"))
        assertTrue(strings.contains("重新询问悬浮窗权限"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW\" tools:node=\"remove\""))
    }
}
