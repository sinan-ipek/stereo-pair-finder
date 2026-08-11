package com.stereopairfinder

import android.content.ContentResolver
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.BandValues
import com.stereopairfinder.image.CropSquare
import com.stereopairfinder.image.Geometry
import com.stereopairfinder.image.ParallaxSample
import com.stereopairfinder.model.CameraFolderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID

class PolicyAndOutputTest {
    private val width = 100
    private val height = 160
    private val fullMask = ByteArray(width * height) { 1 }

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
    }

    @Test
    fun `lower parallax keeps the bottom and crops only the top`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 1.0, middle = 1.2, bottom = 5.0),
            BandValues(top = 20.0, middle = 20.0, bottom = 20.0)
        )

        assertNotNull(decision)
        assertEquals(CropSquare(0, 60, 100, 160), decision!!.square)
        assertTrue(decision.mode.startsWith("üstten kırp"))
    }

    @Test
    fun `upper parallax keeps the top and crops only the bottom`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 6.0, middle = 1.0, bottom = 1.1),
            BandValues(top = 20.0, middle = 20.0, bottom = 20.0)
        )

        assertEquals(CropSquare(0, 0, 100, 100), decision!!.square)
        assertTrue(decision.mode.startsWith("alttan kırp"))
    }

    @Test
    fun `middle parallax crops top and bottom equally`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 1.0, middle = 5.0, bottom = 1.0),
            BandValues(top = 20.0, middle = 20.0, bottom = 20.0)
        )

        assertEquals(CropSquare(0, 30, 100, 130), decision!!.square)
        assertTrue(decision.mode.startsWith("merkezden kırp"))
    }

    @Test
    fun `similar parallax values use a centered crop`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 3.0, middle = 3.2, bottom = 3.1),
            BandValues(top = 15.0, middle = 16.0, bottom = 16.0)
        )

        assertEquals(CropSquare(0, 30, 100, 130), decision!!.square)
        assertEquals("merkezden kırp · fark belirgin değil", decision.mode)
    }

    @Test
    fun `flat sky at the top is cropped when parallax is indecisive`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 2.0, middle = 2.1, bottom = 2.0),
            BandValues(top = 5.0, middle = 18.0, bottom = 28.0)
        )

        assertEquals(CropSquare(0, 60, 100, 160), decision!!.square)
        assertEquals("üstten kırp · üst bölge daha tekdüze", decision.mode)
    }

    @Test
    fun `flat area at the bottom is cropped when parallax is indecisive`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 2.0, middle = 2.1, bottom = 2.0),
            BandValues(top = 30.0, middle = 18.0, bottom = 4.0)
        )

        assertEquals(CropSquare(0, 0, 100, 100), decision!!.square)
        assertEquals("alttan kırp · alt bölge daha tekdüze", decision.mode)
    }

    @Test
    fun `clear parallax wins over an opposing flat-area suggestion`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            samples(top = 1.0, middle = 1.0, bottom = 7.0),
            BandValues(top = 35.0, middle = 20.0, bottom = 3.0)
        )

        assertEquals(CropSquare(0, 60, 100, 160), decision!!.square)
        assertEquals("üstten kırp · alt paralaks güçlü", decision.mode)
    }

    @Test
    fun `portrait crop preserves the complete image width`() {
        val decision = Geometry.verticalCrop(
            fullMask,
            width,
            height,
            emptyList(),
            BandValues(top = 20.0, middle = 20.0, bottom = 20.0)
        )

        assertEquals(0, decision!!.square.left)
        assertEquals(width, decision.square.right)
        assertEquals(width, decision.square.width)
    }

    @Test
    fun `an empty common mask has no crop`() {
        assertNull(
            Geometry.verticalCrop(
                ByteArray(width * height),
                width,
                height
            )
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

    private fun samples(top: Double, middle: Double, bottom: Double): List<ParallaxSample> =
        bandSamples(y = 15.0, disparity = top) +
            bandSamples(y = 75.0, disparity = middle) +
            bandSamples(y = 140.0, disparity = bottom)

    private fun bandSamples(y: Double, disparity: Double) = (0 until 8).map { index ->
        ParallaxSample(
            x = 10.0 + index * 10.0,
            y = y + (index % 2),
            disparity = disparity + (index % 3 - 1) * 0.04
        )
    }
}
