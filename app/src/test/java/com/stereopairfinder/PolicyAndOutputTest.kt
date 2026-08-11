package com.stereopairfinder

import android.content.ContentResolver
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.CropSquare
import com.stereopairfinder.image.Geometry
import com.stereopairfinder.image.ParallaxSample
import com.stereopairfinder.model.CameraFolderPolicy
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID

class PolicyAndOutputTest {
    @Test
    fun `camera policy only accepts exact primary path`() {
        assertTrue(CameraFolderPolicy.accepts("external_primary", "DCIM/Camera/"))
        listOf(
            "DCIM/Camera/Subfolder/",
            "DCIM/Screenshots/",
            "Pictures/",
            "WhatsApp/"
        ).forEach {
            assertFalse(CameraFolderPolicy.accepts("external_primary", it))
        }
        assertFalse(CameraFolderPolicy.accepts("external", "DCIM/Camera/"))
    }

    @Test
    fun `output names are unique and dimensions remain bounded`() {
        val saver = SbsSaver(mock(ContentResolver::class.java))
        val now = Instant.EPOCH
        assertNotEquals(
            saver.uniqueName(now, UUID.randomUUID()),
            saver.uniqueName(now, UUID.randomUUID())
        )
        assertEquals(2048, Geometry.outputSide(5000))
        assertEquals(640, Geometry.outputSide(640))
        assertEquals(
            2.0,
            (Geometry.outputSide(5000) * 2).toDouble() / Geometry.outputSide(5000),
            0.0
        )
    }

    @Test
    fun `largest valid square avoids invalid perspective corners`() {
        val rows = listOf(
            "11100",
            "11100",
            "11111",
            "00111"
        )
        val mask = rows.joinToString("").map { if (it == '1') 1.toByte() else 0.toByte() }
            .toByteArray()

        assertEquals(
            CropSquare(left = 0, top = 0, right = 3, bottom = 3),
            Geometry.largestValidSquare(mask, width = 5, height = 4)
        )
        assertNull(Geometry.largestValidSquare(ByteArray(12), width = 4, height = 3))
    }

    @Test
    fun `dominant background shift is removed and lower foreground is centered`() {
        val mask = ByteArray(4 * 8) { 1 }
        val background = listOf(
            ParallaxSample(0.5, 0.5, 31.125),
            ParallaxSample(1.5, 0.5, 33.125),
            ParallaxSample(2.5, 0.5, 35.125),
            ParallaxSample(3.5, 0.5, 37.125),
            ParallaxSample(0.5, 2.5, 31.625),
            ParallaxSample(1.5, 2.5, 33.625),
            ParallaxSample(2.5, 2.5, 35.625),
            ParallaxSample(3.5, 2.5, 37.625),
            ParallaxSample(0.5, 4.0, 32.0),
            ParallaxSample(3.5, 4.0, 38.0)
        )
        val lowerForeground = listOf(
            ParallaxSample(1.2, 6.2, 21.95),
            ParallaxSample(2.0, 6.7, 23.42),
            ParallaxSample(2.8, 7.2, 24.90),
            ParallaxSample(2.2, 7.6, 23.60)
        )

        assertEquals(
            CropSquare(left = 1, top = 5, right = 4, bottom = 8),
            Geometry.largestValidSquare(
                mask,
                width = 4,
                height = 8,
                parallaxSamples = background + lowerForeground
            )
        )
    }

    @Test
    fun `movable crop escapes a unique largest square and reaches lower foreground`() {
        val rows = List(6) { "111111" } + List(4) { "011111" }
        val mask = rows.joinToString("").map { if (it == '1') 1.toByte() else 0.toByte() }
            .toByteArray()
        val background = listOf(
            ParallaxSample(0.5, 0.5, 20.0),
            ParallaxSample(2.5, 0.5, 21.0),
            ParallaxSample(4.5, 0.5, 22.0),
            ParallaxSample(0.5, 2.5, 20.5),
            ParallaxSample(2.5, 2.5, 21.5),
            ParallaxSample(4.5, 2.5, 22.5),
            ParallaxSample(0.5, 4.5, 21.0),
            ParallaxSample(4.5, 4.5, 23.0)
        )
        val lowerForeground = listOf(
            ParallaxSample(2.0, 8.0, 8.0),
            ParallaxSample(3.0, 8.5, 8.5),
            ParallaxSample(4.0, 9.0, 9.0),
            ParallaxSample(3.5, 9.5, 8.8)
        )

        assertEquals(
            CropSquare(left = 1, top = 5, right = 6, bottom = 10),
            Geometry.largestValidSquare(
                mask,
                width = 6,
                height = 10,
                parallaxSamples = background + lowerForeground
            )
        )
    }

    @Test
    fun `an affine background disparity alone keeps the largest centered crop`() {
        val mask = ByteArray(4 * 8) { 1 }
        val background = (0 until 4).flatMap { y ->
            (0 until 4).map { x ->
                ParallaxSample(
                    x = x + 0.5,
                    y = y * 2.0 + 0.5,
                    disparity = 30.0 + 2.0 * (x + 0.5) + 0.25 * (y * 2.0 + 0.5)
                )
            }
        }

        assertEquals(
            CropSquare(left = 0, top = 2, right = 4, bottom = 6),
            Geometry.largestValidSquare(mask, width = 4, height = 8, parallaxSamples = background)
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
    fun `median vertical residual and unsafe transform are handled`() {
        assertEquals(2.5, Geometry.median(listOf(1.0, 2.0, 3.0, 99.0)), 0.0)
        assertFalse(Geometry.saneHomography(doubleArrayOf(1.0, 2.0)))
        assertFalse(
            Geometry.saneHomography(
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, Double.NaN)
            )
        )
    }
}
