package com.stereopairfinder.image

import android.graphics.Rect
import kotlin.math.min

object Geometry {
    fun centeredSquare(common: Rect): Rect {
        val side = min(common.width(), common.height())
        val x = common.left + (common.width()-side)/2
        val y = common.top + (common.height()-side)/2
        return Rect(x,y,x+side,y+side)
    }
    fun outputSide(sourceSide: Int) = min(sourceSide, 2048)
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val s=values.sorted(); return if(s.size%2==1)s[s.size/2] else (s[s.size/2-1]+s[s.size/2])/2
    }
    fun saneHomography(values: DoubleArray): Boolean = values.size == 9 && values.all { it.isFinite() } &&
        kotlin.math.abs(values[8]) > 1e-8 && values.maxOf { kotlin.math.abs(it) } < 1e5
}
