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
    fun `output names are unique and preview and full dimensions remain bounded`() {
        val saver = SbsSaver(mock(ContentResolver::class.java))
        val now = Instant.EPOCH
        assertNotEquals(
            saver.uniqueName(now, UUID.randomUUID()),
            saver.uniqueName(now, UUID.randomUUID())
        )
        assertEquals(2048, Geometry.outputSide(5000))
        assertEquals(640, Geometry.outputSide(640))
        assertEquals(3072, Geometry.fullOutputSide(5000))
        assertEquals(3024, Geometry.fullOutputSide(3024))
        assertEquals(
            2.0,
            (Geometry.fullOutputSide(3024) * 2).toDouble() / Geometry.fullOutputSide(3024),
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
