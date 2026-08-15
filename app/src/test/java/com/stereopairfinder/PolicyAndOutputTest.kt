package com.stereopairfinder

import android.content.ContentResolver
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.CropRect
import com.stereopairfinder.image.CropSquare
import com.stereopairfinder.image.Geometry
import com.stereopairfinder.image.MatchSample
import com.stereopairfinder.image.ParallaxSample
import com.stereopairfinder.model.CameraFolderPolicy
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class PolicyAndOutputTest {
    @Test
    fun `camera policy only accepts exact primary path`() {
        assertTrue(CameraFolderPolicy.accepts("external_primary", "DCIM/Camera/"))
        listOf(
            "DCIM/Camera/Subfolder/",
            "DCIM/Screenshots/",
            "Pictures/",
            "WhatsApp/"
        ).forEach { assertFalse(CameraFolderPolicy.accepts("external_primary", it)) }
        assertFalse(CameraFolderPolicy.accepts("external", "DCIM/Camera/"))
    }

    @Test
    fun `output names are unique and preview and full dimensions remain bounded`() {
        val saver = SbsSaver(mock(ContentResolver::class.java))
        val now = Instant.EPOCH
        assertNotEquals(saver.uniqueName(now, UUID.randomUUID()), saver.uniqueName(now, UUID.randomUUID()))
        assertEquals(2048, Geometry.outputSide(5000))
        assertEquals(640, Geometry.outputSide(640))
        assertEquals(3072, Geometry.fullOutputSide(5000))
        assertEquals(3024, Geometry.fullOutputSide(3024))
        assertEquals(2.0, (Geometry.fullOutputSide(3024) * 2).toDouble() / Geometry.fullOutputSide(3024), 0.0)
    }

    @Test
    fun `largest valid rectangle preserves portrait background instead of forcing a square`() {
        val mask = ByteArray(4 * 7) { 1 }
        assertEquals(
            CropRect(left = 0, top = 0, right = 4, bottom = 7),
            Geometry.largestValidRectangle(mask, width = 4, height = 7)
        )
    }

    @Test
    fun `fill square can move from top through center to bottom`() {
        val rect = CropRect(0, 0, 4, 8)
        assertEquals(CropSquare(0, 0, 4, 4), Geometry.squareInside(rect, -1f))
        assertEquals(CropSquare(0, 2, 4, 6), Geometry.squareInside(rect, 0f))
        assertEquals(CropSquare(0, 4, 4, 8), Geometry.squareInside(rect, 1f))
    }

    @Test
    fun `largest valid square avoids invalid corners`() {
        val rows = listOf("11100", "11100", "11111", "00111")
        val mask = rows.joinToString("").map { if (it == '1') 1.toByte() else 0.toByte() }.toByteArray()
        assertEquals(
            CropSquare(left = 0, top = 0, right = 3, bottom = 3),
            Geometry.largestValidSquare(mask, width = 5, height = 4)
        )
        assertNull(Geometry.largestValidSquare(ByteArray(12), width = 4, height = 3))
    }

    @Test
    fun `largest valid square prefers the region with stronger parallax`() {
        val mask = ByteArray(4 * 6) { 1 }
        val samples = listOf(
            ParallaxSample(x = 1.0, y = 1.0, disparity = 1.0),
            ParallaxSample(x = 1.5, y = 5.2, disparity = 12.0),
            ParallaxSample(x = 2.5, y = 4.8, disparity = 8.0)
        )
        assertEquals(
            CropSquare(left = 0, top = 2, right = 4, bottom = 6),
            Geometry.largestValidSquare(mask, width = 4, height = 6, parallaxSamples = samples)
        )
    }

    @Test
    fun `largest valid square falls back to the image center without parallax`() {
        val mask = ByteArray(4 * 6) { 1 }
        assertEquals(
            CropSquare(left = 0, top = 1, right = 4, bottom = 5),
            Geometry.largestValidSquare(mask, width = 4, height = 6)
        )
    }

    @Test
    fun `translation remains the default alignment`() {
        val samples = buildList {
            for (y in listOf(100.0, 400.0, 700.0, 1000.0)) {
                for (x in listOf(150.0, 500.0, 900.0, 1400.0, 1800.0)) {
                    add(MatchSample(x + 42.0, y - 7.0, x, y))
                }
            }
        }
        val alignment = Geometry.estimateRigidAlignment(samples, 2000, 1200)
        assertNotNull(alignment)
        alignment!!
        assertFalse(alignment.rotationApplied)
        assertEquals(0.0, alignment.angleDegrees, 1e-9)
        assertEquals(42.0, alignment.translateX, 1e-9)
        assertEquals(-7.0, alignment.translateY, 1e-9)
        assertEquals(0.0, alignment.medianVerticalError, 1e-9)
    }

    @Test
    fun `tiny rotation is used only when it clearly improves vertical alignment`() {
        val width = 2000
        val height = 1200
        val angle = 0.60
        val radians = Math.toRadians(angle)
        val c = cos(radians)
        val s = sin(radians)
        val cx = width / 2.0
        val cy = height / 2.0
        val tx = 28.0
        val ty = -5.0
        val samples = buildList {
            for (y in listOf(120.0, 350.0, 650.0, 980.0)) {
                for (x in listOf(120.0, 450.0, 850.0, 1250.0, 1700.0, 1900.0)) {
                    val localX = x - cx
                    val localY = y - cy
                    val leftX = c * localX - s * localY + cx + tx
                    val leftY = s * localX + c * localY + cy + ty
                    add(MatchSample(leftX, leftY, x, y))
                }
            }
        }
        val alignment = Geometry.estimateRigidAlignment(samples, width, height)
        assertNotNull(alignment)
        alignment!!
        assertTrue(alignment.rotationApplied)
        assertEquals(angle, alignment.angleDegrees, 0.08)
        assertEquals(tx, alignment.translateX, 0.8)
        assertEquals(ty, alignment.translateY, 0.8)
        assertTrue(alignment.medianVerticalError < alignment.translationOnlyVerticalError)
        assertTrue(kotlin.math.abs(alignment.angleDegrees) <= Geometry.MAX_ROTATION_DEG)
    }

    @Test
    fun `rotation beyond one degree is never applied`() {
        val width = 2000
        val height = 1200
        val angle = 2.0
        val radians = Math.toRadians(angle)
        val c = cos(radians)
        val s = sin(radians)
        val cx = width / 2.0
        val cy = height / 2.0
        val samples = buildList {
            for (y in listOf(100.0, 400.0, 800.0, 1050.0)) {
                for (x in listOf(100.0, 500.0, 1000.0, 1500.0, 1900.0)) {
                    val localX = x - cx
                    val localY = y - cy
                    add(
                        MatchSample(
                            leftX = c * localX - s * localY + cx,
                            leftY = s * localX + c * localY + cy,
                            rightX = x,
                            rightY = y
                        )
                    )
                }
            }
        }
        val alignment = Geometry.estimateRigidAlignment(samples, width, height)
        assertNotNull(alignment)
        alignment!!
        assertFalse(alignment.rotationApplied)
        assertEquals(0.0, alignment.angleDegrees, 1e-9)
    }

    @Test
    fun `median handles even sample counts`() {
        assertEquals(2.5, Geometry.median(listOf(1.0, 2.0, 3.0, 99.0)), 0.0)
    }
}
