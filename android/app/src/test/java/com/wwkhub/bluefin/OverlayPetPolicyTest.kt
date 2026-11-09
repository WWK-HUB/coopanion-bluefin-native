package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPetPolicyTest {
    @Test
    fun scaleStaysInsideTheApprovedBandAndPersistsBySize() {
        assertEquals(0.70f, OverlayPetPolicy.scaleFor("small"))
        assertEquals(0.85f, OverlayPetPolicy.scaleFor("medium"))
        assertEquals(0.85f, OverlayPetPolicy.scaleFor(null))
        assertEquals(1.00f, OverlayPetPolicy.scaleFor("large"))
        listOf("small", "medium", "large").forEach { size ->
            val scale = OverlayPetPolicy.scaleFor(size)
            assertTrue(scale in OverlayPetPolicy.SCALE_MIN..OverlayPetPolicy.SCALE_MAX)
        }
    }

    @Test
    fun edgesTurnAroundInsteadOfSticking() {
        assertEquals(400f, OverlayPetPolicy.edgeTarget(10f, facing = -1, minX = 0f, maxX = 416f, mode = "walk"))
        assertEquals(16f, OverlayPetPolicy.edgeTarget(410f, facing = 1, minX = 0f, maxX = 416f, mode = "run"))
        assertNull(OverlayPetPolicy.edgeTarget(200f, facing = 1, minX = 0f, maxX = 416f, mode = "walk"))
        assertEquals(400f, OverlayPetPolicy.edgeTarget(10f, facing = 1, minX = 0f, maxX = 416f, mode = "idle"))
        assertNull(OverlayPetPolicy.edgeTarget(10f, facing = -1, minX = 0f, maxX = 416f, mode = "drag"))
    }

    @Test
    fun landingStaysOnTheFloor() {
        val (x, y) = OverlayPetPolicy.clampLanding(900f, 2000f, minX = 20f, maxX = 400f, floorY = 800f)
        assertEquals(400f, x)
        assertEquals(800f, y)
        assertEquals(40, OverlayPetPolicy.FLOOR_INSET_PX)
    }

    @Test
    fun sleepStartsOnlyAfterAQuietStretch() {
        assertFalse(OverlayPetPolicy.shouldStartSleep(1000, "idle", resting = false))
        assertTrue(OverlayPetPolicy.shouldStartSleep(OverlayPetPolicy.SLEEP_AFTER_MS, "walk", resting = false))
        assertFalse(OverlayPetPolicy.shouldStartSleep(OverlayPetPolicy.SLEEP_AFTER_MS, "walk", resting = true))
        assertFalse(OverlayPetPolicy.shouldStartSleep(OverlayPetPolicy.SLEEP_AFTER_MS, "drag", resting = false))
    }
}
