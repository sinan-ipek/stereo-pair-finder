package com.stereopairfinder.image

import android.graphics.Bitmap
import com.stereopairfinder.model.*
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

class StereoAnalyzer {
    init {
        check(org.opencv.android.OpenCVLoader.initLocal()) { "OpenCV başlatılamadı" }
    }

    fun analyze(
        pair: PairCandidate,
        leftBitmap: Bitmap,
        rightBitmap: Bitmap,
        maxSeconds: Int,
        threshold: Int
    ): AnalysisResult {
        val fullTargetW = min(leftBitmap.width, rightBitmap.width)
        val fullTargetH = min(leftBitmap.height, rightBitmap.height)
        require(fullTargetW > 0 && fullTargetH > 0) { "Geçersiz fotoğraf boyutu" }

        val fullLeft = bitmapToCommonMat(leftBitmap, fullTargetW, fullTargetH)
        val fullRight = bitmapToCommonMat(rightBitmap, fullTargetW, fullTargetH)

        val analysisScale = min(1.0, ANALYSIS_MAX_SIDE.toDouble() / maxOf(fullTargetW, fullTargetH))
        val targetW = (fullTargetW * analysisScale).roundToInt().coerceAtLeast(1)
        val targetH = (fullTargetH * analysisScale).roundToInt().coerceAtLeast(1)
        val left = resized(fullLeft, targetW, targetH)
        val right = resized(fullRight, targetW, targetH)

        var leftPreview: Bitmap? = left.bitmap()
        var rightPreview: Bitmap? = right.bitmap()

        val grayL = Mat()
        val grayR = Mat()
        Imgproc.cvtColor(left, grayL, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(right, grayR, Imgproc.COLOR_RGBA2GRAY)

        val orb = ORB.create(2500)
        val kpL = MatOfKeyPoint()
        val kpR = MatOfKeyPoint()
        val dL = Mat()
        val dR = Mat()
        orb.detectAndCompute(grayL, Mat(), kpL, dL)
        orb.detectAndCompute(grayR, Mat(), kpR, dR)

        val good = mutableListOf<DMatch>()
        if (!dL.empty() && !dR.empty()) {
            val forward = ArrayList<MatOfDMatch>()
            BFMatcher.create(Core.NORM_HAMMING, false).knnMatch(dL, dR, forward, 2)
            forward.forEach { row ->
                val matches = row.toArray()
                if (matches.size >= 2 && matches[0].distance < .75f * matches[1].distance) {
                    good += matches[0]
                }
                row.release()
            }
        }

        val pL = kpL.toArray()
        val pR = kpR.toArray()
        val src = MatOfPoint2f()
        val dst = MatOfPoint2f()
        src.fromList(good.map { pL[it.queryIdx].pt })
        dst.fromList(good.map { pR[it.trainIdx].pt })

        val mask = Mat()
        val fundamental = if (good.size >= 12) {
            Calib3d.findFundamentalMat(src, dst, Calib3d.FM_RANSAC, 1.5, .995, mask)
        } else {
            Mat()
        }
        val inlierMatches = if (mask.empty()) {
            emptyList()
        } else {
            good.filterIndexed { index, _ -> mask.get(index, 0)[0] != 0.0 }
        }

        val evidence = inlierMatches.size >= 18
        val similarity = if (!evidence) {
            0.0
        } else {
            (100.0 * inlierMatches.size / min(pL.size, pR.size).coerceAtLeast(1) * 8.0)
                .coerceAtMost(100.0)
        }

        var aligned = false
        var confidence = 0.0
        var median = Double.POSITIVE_INFINITY
        var area = 0.0
        var sbsPreview: Bitmap? = null
        var sbsJpeg: ByteArray? = null

        if (evidence && !fundamental.empty()) {
            val inL = MatOfPoint2f()
            val inR = MatOfPoint2f()
            inL.fromList(inlierMatches.map { pL[it.queryIdx].pt })
            inR.fromList(inlierMatches.map { pR[it.trainIdx].pt })

            val h1 = Mat()
            val h2 = Mat()
            val ok = Calib3d.stereoRectifyUncalibrated(
                inL,
                inR,
                fundamental,
                Size(targetW.toDouble(), targetH.toDouble()),
                h1,
                h2,
                5.0
            )

            if (ok && sane(h1) && sane(h2)) {
                val warpL = Mat()
                val warpR = Mat()
                Imgproc.warpPerspective(left, warpL, h1, Size(targetW.toDouble(), targetH.toDouble()))
                Imgproc.warpPerspective(right, warpR, h2, Size(targetW.toDouble(), targetH.toDouble()))

                val one = Mat.ones(targetH, targetW, CvType.CV_8U)
                val m1 = Mat()
                val m2 = Mat()
                Imgproc.warpPerspective(one, m1, h1, Size(targetW.toDouble(), targetH.toDouble()), Imgproc.INTER_NEAREST)
                Imgproc.warpPerspective(one, m2, h2, Size(targetW.toDouble(), targetH.toDouble()), Imgproc.INTER_NEAREST)

                val common = Mat()
                Core.bitwise_and(m1, m2, common)
                area = Core.countNonZero(common).toDouble() / (targetW * targetH)

                val validPixels = ByteArray(targetW * targetH)
                common.get(0, 0, validPixels)

                val transformedL = MatOfPoint2f()
                val transformedR = MatOfPoint2f()
                Core.perspectiveTransform(inL, transformedL, h1)
                Core.perspectiveTransform(inR, transformedR, h2)
                val rectifiedPairs = transformedL.toArray().zip(transformedR.toArray())

                median = Geometry.median(rectifiedPairs.map { abs(it.first.y - it.second.y) })
                val parallaxSamples = rectifiedPairs.map { (leftPoint, rightPoint) ->
                    ParallaxSample(
                        x = (leftPoint.x + rightPoint.x) / 2.0,
                        y = (leftPoint.y + rightPoint.y) / 2.0,
                        disparity = abs(leftPoint.x - rightPoint.x)
                    )
                }
                val square = Geometry.largestValidSquare(validPixels, targetW, targetH, parallaxSamples)

                if (square != null) {
                    confidence = (100.0 - median * 15.0).coerceIn(0.0, 100.0) *
                        (inlierMatches.size / (inlierMatches.size + 15.0))
                    aligned = median <= 2.5 && area >= .35

                    if (aligned) {
                        val cropL = warpL.submat(square.top, square.bottom, square.left, square.right)
                        val cropR = warpR.submat(square.top, square.bottom, square.left, square.right)
                        val previewSide = Geometry.outputSide(square.width)
                        val outL = Mat()
                        val outR = Mat()
                        Imgproc.resize(cropL, outL, Size(previewSide.toDouble(), previewSide.toDouble()))
                        Imgproc.resize(cropR, outR, Size(previewSide.toDouble(), previewSide.toDouble()))

                        leftPreview?.recycle()
                        rightPreview?.recycle()
                        leftPreview = outL.bitmap()
                        rightPreview = outR.bitmap()

                        val joinedPreview = Mat()
                        Core.hconcat(listOf(outL, outR), joinedPreview)
                        sbsPreview = joinedPreview.bitmap()

                        val candidateStatus = PairPolicy.status(
                            pair, similarity, evidence, aligned, confidence, area, maxSeconds, threshold
                        )
                        if (candidateStatus == PairStatus.MATCHED) {
                            sbsJpeg = buildFullResolutionJpeg(
                                fullLeft, fullRight, h1, h2, square,
                                targetW, targetH, fullTargetW, fullTargetH
                            )
                        }

                        joinedPreview.release()
                        outL.release()
                        outR.release()
                        cropL.release()
                        cropR.release()
                    }
                }

                transformedL.release()
                transformedR.release()
                warpL.release()
                warpR.release()
                one.release()
                m1.release()
                m2.release()
                common.release()
            }

            h1.release()
            h2.release()
            inL.release()
            inR.release()
        }

        val status = PairPolicy.status(
            pair, similarity, evidence, aligned, confidence, area, maxSeconds, threshold
        )

        listOf(fullLeft, fullRight, left, right, grayL, grayR, kpL, kpR, dL, dR, src, dst, mask, fundamental)
            .forEach { it.release() }

        return AnalysisResult(
            pair, similarity, inlierMatches.size, confidence, median, area, status,
            leftPreview, rightPreview, sbsPreview, sbsJpeg
        )
    }

    private fun buildFullResolutionJpeg(
        fullLeft: Mat,
        fullRight: Mat,
        h1: Mat,
        h2: Mat,
        square: CropSquare,
        analysisW: Int,
        analysisH: Int,
        fullW: Int,
        fullH: Int
    ): ByteArray {
        val scaleX = fullW.toDouble() / analysisW
        val scaleY = fullH.toDouble() / analysisH
        val fullH1 = scaleHomography(h1, scaleX, scaleY)
        val fullH2 = scaleHomography(h2, scaleX, scaleY)

        val warpL = Mat()
        val warpR = Mat()
        Imgproc.warpPerspective(fullLeft, warpL, fullH1, Size(fullW.toDouble(), fullH.toDouble()))
        Imgproc.warpPerspective(fullRight, warpR, fullH2, Size(fullW.toDouble(), fullH.toDouble()))
        fullH1.release()
        fullH2.release()

        val scaledLeft = floor(square.left * scaleX).toInt().coerceIn(0, fullW - 1)
        val scaledTop = floor(square.top * scaleY).toInt().coerceIn(0, fullH - 1)
        val scaledRight = ceil(square.right * scaleX).toInt().coerceIn(scaledLeft + 1, fullW)
        val scaledBottom = ceil(square.bottom * scaleY).toInt().coerceIn(scaledTop + 1, fullH)
        val rawSide = min(scaledRight - scaledLeft, scaledBottom - scaledTop).coerceAtLeast(1)
        val centerX = (scaledLeft + scaledRight) / 2
        val centerY = (scaledTop + scaledBottom) / 2
        val cropLeft = (centerX - rawSide / 2).coerceIn(0, fullW - rawSide)
        val cropTop = (centerY - rawSide / 2).coerceIn(0, fullH - rawSide)
        val cropRight = cropLeft + rawSide
        val cropBottom = cropTop + rawSide

        val cropL = warpL.submat(cropTop, cropBottom, cropLeft, cropRight)
        val cropR = warpR.submat(cropTop, cropBottom, cropLeft, cropRight)
        val finalSide = Geometry.fullOutputSide(rawSide)
        val outL = Mat()
        val outR = Mat()
        if (finalSide == rawSide) {
            cropL.copyTo(outL)
            cropR.copyTo(outR)
        } else {
            Imgproc.resize(cropL, outL, Size(finalSide.toDouble(), finalSide.toDouble()))
            Imgproc.resize(cropR, outR, Size(finalSide.toDouble(), finalSide.toDouble()))
        }

        cropL.release()
        cropR.release()
        warpL.release()
        warpR.release()

        val joined = Mat()
        Core.hconcat(listOf(outL, outR), joined)
        outL.release()
        outR.release()

        val bitmap = joined.bitmap()
        joined.release()
        return try {
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "Yüksek çözünürlüklü JPEG kodlanamadı" }
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun scaleHomography(source: Mat, scaleX: Double, scaleY: Double): Mat {
        val toFull = Mat.eye(3, 3, CvType.CV_64F)
        val toAnalysis = Mat.eye(3, 3, CvType.CV_64F)
        toFull.put(0, 0, scaleX)
        toFull.put(1, 1, scaleY)
        toAnalysis.put(0, 0, 1.0 / scaleX)
        toAnalysis.put(1, 1, 1.0 / scaleY)

        val temp = Mat()
        val result = Mat()
        Core.gemm(toFull, source, 1.0, Mat(), 0.0, temp)
        Core.gemm(temp, toAnalysis, 1.0, Mat(), 0.0, result)
        toFull.release()
        toAnalysis.release()
        temp.release()
        return result
    }

    private fun bitmapToCommonMat(bitmap: Bitmap, width: Int, height: Int): Mat {
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)
        if (raw.cols() == width && raw.rows() == height) return raw
        val resized = Mat()
        Imgproc.resize(raw, resized, Size(width.toDouble(), height.toDouble()))
        raw.release()
        return resized
    }

    private fun resized(source: Mat, width: Int, height: Int): Mat {
        if (source.cols() == width && source.rows() == height) return source.clone()
        return Mat().also { Imgproc.resize(source, it, Size(width.toDouble(), height.toDouble())) }
    }

    private fun sane(h: Mat): Boolean {
        val values = DoubleArray(9)
        if (h.rows() != 3 || h.cols() != 3) return false
        h.get(0, 0, values)
        return Geometry.saneHomography(values)
    }

    private fun Mat.bitmap() =
        Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888).also {
            Utils.matToBitmap(this, it)
        }

    companion object { private const val ANALYSIS_MAX_SIDE = 2048 }
}
