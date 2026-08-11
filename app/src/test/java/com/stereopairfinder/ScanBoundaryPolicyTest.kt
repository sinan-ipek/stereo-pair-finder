package com.stereopairfinder

import com.stereopairfinder.data.ScanBoundaryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBoundaryPolicyTest {
    @Test
    fun `a single new photo stays pending for the next adjacent photo`() {
        assertFalse(ScanBoundaryPolicy.shouldCommit(1))
        assertTrue(ScanBoundaryPolicy.shouldCommit(0))
        assertTrue(ScanBoundaryPolicy.shouldCommit(2))
        assertTrue(ScanBoundaryPolicy.shouldCommit(40))
    }

    @Test
    fun `only application output folder is excluded`() {
        assertTrue(ScanBoundaryPolicy.isApplicationOutput("Pictures/StereoPairFinder/"))
        assertTrue(ScanBoundaryPolicy.isApplicationOutput("pictures/stereopairfinder"))
        assertFalse(ScanBoundaryPolicy.isApplicationOutput("DCIM/Camera/"))
        assertFalse(ScanBoundaryPolicy.isApplicationOutput("Pictures/StereoPairFinder Extra/"))
        assertFalse(ScanBoundaryPolicy.isApplicationOutput(null))
    }
}
