package com.stereopairfinder

import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.Geometry
import com.stereopairfinder.model.CameraFolderPolicy
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import android.content.ContentResolver
import java.time.Instant
import java.util.UUID

class PolicyAndOutputTest {
    @Test fun `camera policy only accepts exact primary path`() {
        assertTrue(CameraFolderPolicy.accepts("external_primary","DCIM/Camera/"))
        listOf("DCIM/Camera/Subfolder/","DCIM/Screenshots/","Pictures/","WhatsApp/").forEach { assertFalse(CameraFolderPolicy.accepts("external_primary",it)) }
        assertFalse(CameraFolderPolicy.accepts("external","DCIM/Camera/"))
    }
    @Test fun `output names are unique and dimensions remain bounded`() {
        val saver=SbsSaver(mock(ContentResolver::class.java));val now=Instant.EPOCH
        assertNotEquals(saver.uniqueName(now,UUID.randomUUID()),saver.uniqueName(now,UUID.randomUUID()))
        assertEquals(2048,Geometry.outputSide(5000));assertEquals(640,Geometry.outputSide(640))
        assertEquals(2.0, (Geometry.outputSide(5000)*2).toDouble()/Geometry.outputSide(5000),0.0)
    }
    @Test fun `median vertical residual and unsafe transform are handled`() {
        assertEquals(2.5,Geometry.median(listOf(1.0,2.0,3.0,99.0)),0.0)
        assertFalse(Geometry.saneHomography(doubleArrayOf(1.0,2.0)))
        assertFalse(Geometry.saneHomography(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,Double.NaN)))
    }
}
