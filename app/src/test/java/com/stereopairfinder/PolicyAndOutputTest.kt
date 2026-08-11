package com.stereopairfinder

import android.content.ContentResolver
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.CropSquare
import com.stereopairfinder.image.Geometry
import com.stereopairfinder.image.ParallaxSample
import com.stereopairfinder.image.SubjectGuidance
import com.stereopairfinder.image.SubjectCell
import com.stereopairfinder.image.RegionalSubject
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
    fun `a clear visual subject recenters without parallax and without extra crop`() {
        val mask = ByteArray(10 * 16) { 1 }
        val decision = Geometry.adaptiveValidSquare(
            mask = mask,
            width = 10,
            height = 16,
            subject = SubjectGuidance(
                x = 5.0,
                y = 10.0,
                confidence = 0.90,
                evidenceCount = 240
            )
        )

        assertNotNull(decision)
        assertEquals(CropSquare(0, 5, 10, 15), decision!!.square)
        assertEquals("uyarlanabilir: belirgin konu", decision.mode)
    }

    @Test
    fun `adaptive framing uses the largest side that unlocks subject movement`() {
        val width = 100
        val height = 160
        val mask = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (y < 100 || x > 0) 1 else 0
        }
        val decision = Geometry.adaptiveValidSquare(
            mask = mask,
            width = width,
            height = height,
            subject = SubjectGuidance(
                x = 50.0,
                y = 120.0,
                confidence = 0.92,
                evidenceCount = 400
            )
        )

        assertNotNull(decision)
        assertEquals(99, decision!!.square.width)
        assertTrue((decision.square.top + decision.square.bottom) / 2.0 > 105.0)
    }

    @Test
    fun `guitar-like clear subject owns framing when depth supports its region`() {
        val width = 100
        val height = 160
        val mask = ByteArray(width * height) { 1 }
        val background = (0 until 5).flatMap { row ->
            (0 until 5).map { column ->
                val x = column * 20.0 + 10.0
                val y = row * 24.0 + 10.0
                ParallaxSample(x, y, 20.0 + 0.02 * x + 0.01 * y)
            }
        }
        val guitarDepth = listOf(
            ParallaxSample(18.0, 112.0, 7.0),
            ParallaxSample(24.0, 120.0, 7.5),
            ParallaxSample(30.0, 128.0, 8.0),
            ParallaxSample(34.0, 136.0, 8.4),
            ParallaxSample(26.0, 144.0, 7.8)
        )

        val decision = Geometry.adaptiveValidSquare(
            mask = mask,
            width = width,
            height = height,
            parallaxSamples = background + guitarDepth,
            subject = SubjectGuidance(
                x = 27.0,
                y = 128.0,
                confidence = 0.90,
                evidenceCount = 600,
                radiusX = 18.0,
                radiusY = 30.0
            )
        )

        assertNotNull(decision)
        assertEquals("uyarlanabilir: belirgin konu", decision!!.mode)
        assertEquals(27.0, decision.targetX, 0.0)
        assertEquals(128.0, decision.targetY, 0.0)
    }

    @Test
    fun `aircraft-like stationary wing is rejected and cloud depth owns framing`() {
        val width = 100
        val height = 160
        val mask = ByteArray(width * height) { 1 }
        val background = (0 until 5).flatMap { row ->
            (0 until 5).map { column ->
                val x = column * 20.0 + 10.0
                val y = row * 20.0 + 10.0
                ParallaxSample(x, y, 18.0 + 0.015 * x + 0.01 * y)
            }
        }
        val cloudDepth = listOf(
            ParallaxSample(35.0, 118.0, 8.0),
            ParallaxSample(45.0, 126.0, 8.4),
            ParallaxSample(55.0, 134.0, 8.1),
            ParallaxSample(65.0, 142.0, 8.6),
            ParallaxSample(50.0, 150.0, 8.2)
        )

        val decision = Geometry.adaptiveValidSquare(
            mask = mask,
            width = width,
            height = height,
            parallaxSamples = background + cloudDepth,
            subject = SubjectGuidance(
                x = 78.0,
                y = 62.0,
                confidence = 0.92,
                evidenceCount = 800,
                radiusX = 24.0,
                radiusY = 12.0
            )
        )

        assertNotNull(decision)
        assertEquals("uyarlanabilir: paralaks (sabit konu elendi)", decision!!.mode)
        assertTrue(decision.targetY > 115.0)
        assertTrue(decision.targetX in 35.0..65.0)
    }

    @Test
    fun `regional analysis joins an elongated lower-left subject`() {
        val cells = (0 until 12).flatMap { row ->
            (0 until 8).map { column ->
                val isSubject = column in 1..2 && row in 7..10
                SubjectCell(
                    column = column,
                    row = row,
                    centerX = column * 10.0 + 5.0,
                    centerY = row * 10.0 + 5.0,
                    validPixels = 100,
                    score = if (isSubject) 58.0 else 5.0 + (column + row) % 3
                )
            }
        }

        val subject = RegionalSubject.select(cells)

        assertNotNull(subject)
        assertTrue(subject!!.confidence >= 0.35)
        assertTrue(subject.x < 30.0)
        assertTrue(subject.y > 70.0)
        assertEquals(800, subject.evidenceCount)
    }

    @Test
    fun `regional analysis rejects a uniformly detailed scene`() {
        val cells = (0 until 10).flatMap { row ->
            (0 until 10).map { column ->
                SubjectCell(column, row, column * 10.0, row * 10.0, 100, 8.0)
            }
        }

        assertNull(RegionalSubject.select(cells))
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
