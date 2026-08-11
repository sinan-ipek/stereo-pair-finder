package com.stereopairfinder.image

import android.graphics.Bitmap
import com.stereopairfinder.BuildConfig
import com.stereopairfinder.model.*
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
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
        var left = Mat()
        var right = Mat()
        Utils.bitmapToMat(leftBitmap, left)
        Utils.bitmapToMat(rightBitmap, right)

        val targetW = min(left.cols(), right.cols())
        val targetH = min(left.rows(), right.rows())
        Imgproc.resize(left, left, Size(targetW.toDouble(), targetH.toDouble()))
        Imgproc.resize(right, right, Size(targetW.toDouble(), targetH.toDouble()))

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
        var sbs: Bitmap? = null
        var alignedL: Bitmap? = null
        var alignedR: Bitmap? = null
        var cropCenterXPercent: Double? = null
        var cropCenterYPercent: Double? = null
        var cropSidePercent: Double? = null
        var parallaxEvidenceCount = 0
        var framingMode = "merkez / en büyük kare"
        var subjectConfidence = 0.0
        var subjectEvidenceCount = 0

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
                Imgproc.warpPerspective(
                    one,
                    m1,
                    h1,
                    Size(targetW.toDouble(), targetH.toDouble()),
                    Imgproc.INTER_NEAREST
                )
                Imgproc.warpPerspective(
                    one,
                    m2,
                    h2,
                    Size(targetW.toDouble(), targetH.toDouble()),
                    Imgproc.INTER_NEAREST
                )

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

                median = Geometry.median(
                    rectifiedPairs.map { abs(it.first.y - it.second.y) }
                )
                val featureSamples = rectifiedPairs.map { (leftPoint, rightPoint) ->
                    ParallaxSample(
                        x = (leftPoint.x + rightPoint.x) / 2.0,
                        y = (leftPoint.y + rightPoint.y) / 2.0,
                        disparity = leftPoint.x - rightPoint.x
                    )
                }

                val rectifiedGrayL = Mat()
                val rectifiedGrayR = Mat()
                Imgproc.warpPerspective(
                    grayL,
                    rectifiedGrayL,
                    h1,
                    Size(targetW.toDouble(), targetH.toDouble())
                )
                Imgproc.warpPerspective(
                    grayR,
                    rectifiedGrayR,
                    h2,
                    Size(targetW.toDouble(), targetH.toDouble())
                )
                val denseSamples = runCatching {
                    denseParallaxSamples(rectifiedGrayL, rectifiedGrayR, common)
                }.getOrDefault(emptyList())
                val subject = runCatching {
                    visualSubjectGuidance(rectifiedGrayL, rectifiedGrayR, common)
                }.getOrNull()
                rectifiedGrayL.release()
                rectifiedGrayR.release()

                val parallaxSamples = if (denseSamples.size >= 12) denseSamples else featureSamples
                parallaxEvidenceCount = parallaxSamples.size
                val framing = Geometry.adaptiveValidSquare(
                    validPixels,
                    targetW,
                    targetH,
                    parallaxSamples,
                    subject
                )
                val square = framing?.square

                if (square != null) {
                    framingMode = framing.mode
                    subjectConfidence = framing.subjectConfidence
                    subjectEvidenceCount = framing.subjectEvidenceCount
                    cropCenterXPercent = 100.0 * (square.left + square.right) / 2.0 / targetW
                    cropCenterYPercent = 100.0 * (square.top + square.bottom) / 2.0 / targetH
                    cropSidePercent = 100.0 * square.width / min(targetW, targetH)

                    confidence = (100.0 - median * 15.0).coerceIn(0.0, 100.0) *
                        (inlierMatches.size / (inlierMatches.size + 15.0))

                    aligned = median <= 2.5 && area >= .35
                    if (aligned) {
                        val cropL = warpL.submat(square.top, square.bottom, square.left, square.right)
                        val cropR = warpR.submat(square.top, square.bottom, square.left, square.right)
                        val side = Geometry.outputSide(square.width)
                        val outL = Mat()
                        val outR = Mat()
                        Imgproc.resize(cropL, outL, Size(side.toDouble(), side.toDouble()))
                        Imgproc.resize(cropR, outR, Size(side.toDouble(), side.toDouble()))

                        alignedL = outL.bitmap()
                        alignedR = outR.bitmap()
                        val joined = Mat()
                        Core.hconcat(listOf(outL, outR), joined)
                        sbs = joined.bitmap()

                        joined.release()
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
            pair,
            similarity,
            evidence,
            aligned,
            confidence,
            area,
            maxSeconds,
            threshold
        )

        listOf(left, right, grayL, grayR, kpL, kpR, dL, dR, src, dst, mask, fundamental)
            .forEach { it.release() }

        return AnalysisResult(
            pair = pair,
            similarity = similarity,
            reliableMatches = inlierMatches.size,
            alignmentConfidence = confidence,
            medianVerticalError = median,
            commonAreaRatio = area,
            status = status,
            leftPreview = alignedL ?: leftBitmap,
            rightPreview = alignedR ?: rightBitmap,
            sbsPreview = sbs,
            algorithmVersion = BuildConfig.VERSION_NAME,
            cropCenterXPercent = cropCenterXPercent,
            cropCenterYPercent = cropCenterYPercent,
            cropSidePercent = cropSidePercent,
            parallaxEvidenceCount = parallaxEvidenceCount,
            framingMode = framingMode,
            subjectConfidence = subjectConfidence,
            subjectEvidenceCount = subjectEvidenceCount
        )
    }

    /**
     * Finds a visually distinct subject without using disparity. Local tonal
     * contrast and edges are measured in both rectified views, then compact hot
     * regions are compared with the surrounding low-information background.
     */
    private fun visualSubjectGuidance(
        grayL: Mat,
        grayR: Mat,
        commonMask: Mat
    ): SubjectGuidance? {
        val maxDimension = max(grayL.cols(), grayL.rows())
        if (maxDimension <= 0) return null
        val scale = min(1.0, 480.0 / maxDimension)
        val size = Size(
            max(1, (grayL.cols() * scale).roundToInt()).toDouble(),
            max(1, (grayL.rows() * scale).roundToInt()).toDouble()
        )
        val left = Mat()
        val right = Mat()
        val mask = Mat()
        Imgproc.resize(grayL, left, size, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(grayR, right, size, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(commonMask, mask, size, 0.0, 0.0, Imgproc.INTER_NEAREST)

        val average = Mat()
        Core.addWeighted(left, 0.5, right, 0.5, 0.0, average)
        val broad = Mat()
        Imgproc.GaussianBlur(average, broad, Size(0.0, 0.0), 9.0)
        val contrast = Mat()
        Core.absdiff(average, broad, contrast)

        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(average, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(average, gy, CvType.CV_32F, 0, 1, 3)
        val gradient = Mat()
        Core.magnitude(gx, gy, gradient)
        val contrast32 = Mat()
        contrast.convertTo(contrast32, CvType.CV_32F)
        Core.normalize(contrast32, contrast32, 0.0, 255.0, Core.NORM_MINMAX)
        Core.normalize(gradient, gradient, 0.0, 255.0, Core.NORM_MINMAX)

        val saliency32 = Mat()
        Core.addWeighted(contrast32, 0.68, gradient, 0.32, 0.0, saliency32)
        Imgproc.GaussianBlur(saliency32, saliency32, Size(7.0, 7.0), 1.6)
        val saliency = Mat()
        saliency32.convertTo(saliency, CvType.CV_8U)
        Core.bitwise_and(saliency, mask, saliency)

        val scoreBytes = ByteArray(saliency.rows() * saliency.cols())
        val maskBytes = ByteArray(mask.rows() * mask.cols())
        saliency.get(0, 0, scoreBytes)
        mask.get(0, 0, maskBytes)
        val validScores = scoreBytes.indices
            .filter { (maskBytes[it].toInt() and 0xff) != 0 }
            .map { scoreBytes[it].toInt() and 0xff }
            .sorted()
        if (validScores.size < 100) {
            listOf(left, right, mask, average, broad, contrast, gx, gy, gradient,
                contrast32, saliency32, saliency).forEach { it.release() }
            return null
        }
        val medianScore = validScores[validScores.size / 2]
        val hotThreshold = max(28, validScores[(validScores.size * 0.84).toInt()
            .coerceAtMost(validScores.lastIndex)])

        val binary = Mat()
        Imgproc.threshold(saliency, binary, hotThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(binary, mask, binary)
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val componentCount = Imgproc.connectedComponentsWithStats(
            binary,
            labels,
            stats,
            centroids,
            8,
            CvType.CV_32S
        )

        val validArea = Core.countNonZero(mask).coerceAtLeast(1)
        data class Component(val id: Int, val area: Int, val compactness: Double)
        val components = (1 until componentCount).mapNotNull { id ->
            val area = stats.get(id, Imgproc.CC_STAT_AREA)[0].toInt()
            val width = stats.get(id, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val height = stats.get(id, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (area < validArea * 0.0015 || area > validArea * 0.55) return@mapNotNull null
            Component(id, area, area.toDouble() / (width * height).coerceAtLeast(1))
        }

        val labelValues = IntArray(labels.rows() * labels.cols())
        labels.get(0, 0, labelValues)
        val scored = components.map { component ->
            var weight = 0.0
            var weightedX = 0.0
            var weightedY = 0.0
            var hotPixels = 0
            for (y in 0 until saliency.rows()) {
                val offset = y * saliency.cols()
                for (x in 0 until saliency.cols()) {
                    val index = offset + x
                    if (labelValues[index] != component.id) continue
                    val value = (scoreBytes[index].toInt() and 0xff).toDouble()
                    if (value < hotThreshold) continue
                    val excess = value - medianScore
                    weight += excess
                    weightedX += x * excess
                    weightedY += y * excess
                    hotPixels++
                }
            }
            Triple(component, doubleArrayOf(weight, weightedX, weightedY), hotPixels)
        }.filter { it.second[0] > SCORE_FLOOR && it.third >= 8 }

        val totalScore = scored.sumOf { it.second[0] }
        val best = scored.maxByOrNull { it.second[0] }
        val result = if (best == null || totalScore <= SCORE_FLOOR) {
            null
        } else {
            val component = best.first
            val weight = best.second[0]
            val dominance = (weight / totalScore).coerceIn(0.0, 1.0)
            val meanHot = weight / best.third + medianScore
            val separation = ((meanHot - medianScore) / 90.0).coerceIn(0.0, 1.0)
            val occupancy = component.area.toDouble() / validArea
            val occupancyQuality = when {
                occupancy < 0.004 -> occupancy / 0.004
                occupancy <= 0.30 -> 1.0
                else -> ((0.55 - occupancy) / 0.25).coerceIn(0.0, 1.0)
            }
            val confidence = (
                0.50 * dominance +
                    0.32 * separation +
                    0.10 * occupancyQuality +
                    0.08 * component.compactness.coerceIn(0.0, 1.0)
                ).coerceIn(0.0, 1.0)
            SubjectGuidance(
                x = best.second[1] / weight / scale,
                y = best.second[2] / weight / scale,
                confidence = confidence,
                evidenceCount = best.third
            )
        }

        listOf(left, right, mask, average, broad, contrast, gx, gy, gradient,
            contrast32, saliency32, saliency, binary, closeKernel, labels, stats, centroids)
            .forEach { it.release() }
        return result
    }

    private companion object {
        const val SCORE_FLOOR = 1e-6
    }

    /**
     * Builds a dense, forward/backward-consistent rectified disparity sample set.
     * Texture filtering avoids letting blank sky dominate; the reverse-flow check
     * rejects ambiguous or occluded correspondences.
     */
    private fun denseParallaxSamples(
        grayL: Mat,
        grayR: Mat,
        commonMask: Mat
    ): List<ParallaxSample> {
        val maxDimension = max(grayL.cols(), grayL.rows())
        if (maxDimension <= 0) return emptyList()
        val scale = min(1.0, 640.0 / maxDimension)
        val smallSize = Size(
            max(1, (grayL.cols() * scale).roundToInt()).toDouble(),
            max(1, (grayL.rows() * scale).roundToInt()).toDouble()
        )

        val smallL = Mat()
        val smallR = Mat()
        val smallMask = Mat()
        Imgproc.resize(grayL, smallL, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(grayR, smallR, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(commonMask, smallMask, smallSize, 0.0, 0.0, Imgproc.INTER_NEAREST)

        val forward = Mat()
        val reverse = Mat()
        Video.calcOpticalFlowFarneback(smallL, smallR, forward, 0.5, 5, 21, 5, 7, 1.5, 0)
        Video.calcOpticalFlowFarneback(smallR, smallL, reverse, 0.5, 5, 21, 5, 7, 1.5, 0)

        val gradientX = Mat()
        val gradientY = Mat()
        Imgproc.Sobel(
            smallL,
            gradientX,
            CvType.CV_32F,
            1,
            0,
            3,
            1.0,
            0.0,
            Core.BORDER_DEFAULT
        )
        Imgproc.Sobel(
            smallL,
            gradientY,
            CvType.CV_32F,
            0,
            1,
            3,
            1.0,
            0.0,
            Core.BORDER_DEFAULT
        )

        val samples = ArrayList<ParallaxSample>()
        val step = 4
        for (y in (step until (smallL.rows() - step)) step step) {
            for (x in (step until (smallL.cols() - step)) step step) {
                if (smallMask.get(y, x)[0] == 0.0) continue
                val texture = abs(gradientX.get(y, x)[0]) + abs(gradientY.get(y, x)[0])
                if (texture < 12.0) continue

                val flow = forward.get(y, x)
                if (flow.size < 2 || !flow[0].isFinite() || !flow[1].isFinite()) continue
                if (abs(flow[1]) > 2.5) continue

                val targetX = (x + flow[0]).roundToInt()
                val targetY = (y + flow[1]).roundToInt()
                if (
                    targetX !in (1 until (smallL.cols() - 1)) ||
                    targetY !in (1 until (smallL.rows() - 1))
                ) {
                    continue
                }
                if (smallMask.get(targetY, targetX)[0] == 0.0) continue

                val backward = reverse.get(targetY, targetX)
                if (backward.size < 2) continue
                val consistency = hypot(flow[0] + backward[0], flow[1] + backward[1])
                if (!consistency.isFinite() || consistency > 1.5) continue

                samples += ParallaxSample(
                    x = x / scale,
                    y = y / scale,
                    disparity = -flow[0] / scale
                )
            }
        }

        listOf(smallL, smallR, smallMask, forward, reverse, gradientX, gradientY)
            .forEach { it.release() }
        return samples
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
}
