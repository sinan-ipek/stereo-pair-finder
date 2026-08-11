package com.stereopairfinder

import android.content.ContentResolver
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.BandValues
import com.stereopairfinder.image.CropSquare
import com.stereopairfinder.image.Geometry
import com.stereopairfinder.image.ParallaxSample
import com.stereopairfinder.image.StereoSourceCrops
import com.stereopairfinder.image.VerticalCropPlacement
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
    fun `portrait crop preserves the complete image width when it is valid`() {
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
    fun `original output crops only rows and preserves every source column`() {
        assertEquals(
            CropSquare(0, 60, 100, 160),
            Geometry.originalCrop(width, height, VerticalCropPlacement.CUT_TOP)
        )
        assertEquals(
            CropSquare(0, 30, 100, 130),
            Geometry.originalCrop(width, height, VerticalCropPlacement.CENTER)
        )
        assertEquals(
            CropSquare(0, 0, 100, 100),
            Geometry.originalCrop(width, height, VerticalCropPlacement.CUT_BOTTOM)
        )
        assertNull(Geometry.originalCrop(160, 100, VerticalCropPlacement.CENTER))
    }

    @Test
    fun `positive vertical translation uses a lower start row in the right image`() {
        val crops = Geometry.translatedSourceCrops(
            leftWidth = 100,
            leftHeight = 160,
            rightWidth = 100,
            rightHeight = 160,
            analysisHeight = 160,
            verticalOffsetInAnalysis = 6.0,
            placement = VerticalCropPlacement.CENTER
        )

        assertNotNull(crops)
        assertEquals(CropSquare(0, 27, 100, 127), crops!!.left)
        assertEquals(CropSquare(0, 33, 100, 133), crops.right)
        assertEquals(6, crops.right.top - crops.left.top)
    }

    @Test
    fun `negative vertical translation uses a lower start row in the left image`() {
        val crops = Geometry.translatedSourceCrops(
            leftWidth = 100,
            leftHeight = 160,
            rightWidth = 100,
            rightHeight = 160,
            analysisHeight = 160,
            verticalOffsetInAnalysis = -7.0,
            placement = VerticalCropPlacement.CENTER
        )

        assertNotNull(crops)
        assertTrue(kotlin.math.abs((crops!!.left.top - crops.right.top) - 7) <= 1)
        assertEquals(0, crops.left.left)
        assertEquals(100, crops.left.right)
        assertEquals(0, crops.right.left)
        assertEquals(100, crops.right.right)
    }

    @Test
    fun `translation is scaled independently for equal-aspect source sizes`() {
        val crops = Geometry.translatedSourceCrops(
            leftWidth = 100,
            leftHeight = 160,
            rightWidth = 200,
            rightHeight = 320,
            analysisHeight = 160,
            verticalOffsetInAnalysis = 6.0,
            placement = VerticalCropPlacement.CENTER
        )

        assertNotNull(crops)
        assertEquals(CropSquare(0, 27, 100, 127), crops!!.left)
        assertEquals(CropSquare(0, 66, 200, 266), crops.right)
        assertEquals(crops.left.width, crops.left.height)
        assertEquals(crops.right.width, crops.right.height)
    }

    @Test
    fun `translation respects top center and bottom crop choices`() {
        val topKept = Geometry.translatedSourceCrops(
            100, 160, 100, 160, 160, 6.0, VerticalCropPlacement.CUT_BOTTOM
        )!!
        val bottomKept = Geometry.translatedSourceCrops(
            100, 160, 100, 160, 160, 6.0, VerticalCropPlacement.CUT_TOP
        )!!

        assertEquals(0, topKept.left.top)
        assertEquals(6, topKept.right.top)
        assertEquals(54, bottomKept.left.top)
        assertEquals(60, bottomKept.right.top)
    }

    @Test
    fun `translation never accepts an impossible offset or mismatched aspect ratio`() {
        assertNull(
            Geometry.translatedSourceCrops(
                100, 160, 100, 160, 160, 80.0, VerticalCropPlacement.CENTER
            )
        )
        assertNull(
            Geometry.translatedSourceCrops(
                100, 160, 100, 170, 160, 2.0, VerticalCropPlacement.CENTER
            )
        )
    }

    @Test
    fun `output accepts only equal square crops and never relies on resizing`() {
        assertTrue(
            Geometry.outputCropsAreCompatible(
                StereoSourceCrops(
                    CropSquare(0, 20, 100, 120),
                    CropSquare(0, 26, 100, 126)
                )
            )
        )
        assertFalse(
            Geometry.outputCropsAreCompatible(
                StereoSourceCrops(
                    CropSquare(0, 20, 100, 120),
                    CropSquare(0, 52, 200, 252)
                )
            )
        )
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
