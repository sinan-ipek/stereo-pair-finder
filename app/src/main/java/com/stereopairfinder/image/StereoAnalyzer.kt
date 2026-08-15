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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class StereoAnalyzer {
    init {
        check(org.opencv.android.OpenCVLoader.initLocal()) { "OpenCV başlatılamadı" }
    }

    fun analyze(
        pair: PairCandidate,
        leftBitmap: Bitmap,
        rightBitmap: Bitmap,
        maxSeconds: Int,
        threshold: Int,
        enforceSelectionFilters: Boolean = true
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

        val evidence = inlierMatches.size >= MIN_GEOMETRIC_INLIERS
        val similarity = if (!evidence || good.isEmpty()) {
            0.0
        } else {
            (100.0 * inlierMatches.size / good.size.toDouble()).coerceIn(0.0, 100.0)
        }

        var aligned = false
        var confidence = 0.0
        var median = Double.POSITIVE_INFINITY
        var area = 0.0
        var sbsPreview: Bitmap? = null
        var sbsJpeg: ByteArray? = null

        if (evidence && !fundamental.empty()) {
            val samples = inlierMatches.map { match ->
                val lp = pL[match.queryIdx].pt
                val rp = pR[match.trainIdx].pt
                MatchSample(lp.x, lp.y, rp.x, rp.y)
            }

            val alignment = Geometry.estimateRigidAlignment(samples, targetW, targetH)
            if (alignment != null) {
                val matrix = rigidMatrix(targetW, targetH, alignment)
                val warpedRight = Mat()
                Imgproc.warpAffine(
                    right,
                    warpedRight,
                    matrix,
                    Size(targetW.toDouble(), targetH.toDouble()),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT,
                    Scalar.all(0.0)
                )

                val one = Mat.ones(targetH, targetW, CvType.CV_8U)
                val validRight = Mat()
                Imgproc.warpAffine(
                    one,
                    validRight,
                    matrix,
                    Size(targetW.toDouble(), targetH.toDouble()),
                    Imgproc.INTER_NEAREST,
                    Core.BORDER_CONSTANT,
                    Scalar.all(0.0)
                )

                area = Core.countNonZero(validRight).toDouble() / (targetW * targetH)
                val validPixels = ByteArray(targetW * targetH)
                validRight.get(0, 0, validPixels)

                val transformedPairs = samples.map { sample ->
                    val transformed = transformRightPoint(
                        sample.rightX,
                        sample.rightY,
                        targetW,
                        targetH,
                        alignment
                    )
                    Pair(Point(sample.leftX, sample.leftY), transformed)
                }

                median = Geometry.median(
                    transformedPairs.map { (lp, rp) -> abs(lp.y - rp.y) }
                )
                val parallaxSamples = transformedPairs.map { (lp, rp) ->
                    ParallaxSample(
                        x = (lp.x + rp.x) / 2.0,
                        y = (lp.y + rp.y) / 2.0,
                        disparity = abs(lp.x - rp.x)
                    )
                }
                val square = Geometry.largestValidSquare(
                    validPixels,
                    targetW,
                    targetH,
                    parallaxSamples
                )

                if (square != null) {
                    confidence = (100.0 - median * 15.0).coerceIn(0.0, 100.0) *
                        (inlierMatches.size / (inlierMatches.size + 15.0))
                    aligned = median <= MAX_VERTICAL_ERROR_PX && area >= .35

                    if (aligned) {
                        val cropL = left.submat(square.top, square.bottom, square.left, square.right)
                        val cropR = warpedRight.submat(square.top, square.bottom, square.left, square.right)
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
                            pair = pair,
                            similarity = similarity,
                            evidence = evidence,
                            aligned = aligned,
                            confidence = confidence,
                            area = area,
                            maxSeconds = maxSeconds,
                            threshold = threshold,
                            enforceTime = enforceSelectionFilters,
                            enforceSimilarity = enforceSelectionFilters
                        )
                        if (candidateStatus == PairStatus.MATCHED) {
                            sbsJpeg = buildFullResolutionJpeg(
                                fullLeft,
                                fullRight,
                                alignment,
                                square,
                                targetW,
                                targetH,
                                fullTargetW,
                                fullTargetH
                            )
                        }

                        joinedPreview.release()
                        outL.release()
                        outR.release()
                        cropL.release()
                        cropR.release()
                    }
                }

                matrix.release()
                warpedRight.release()
                one.release()
                validRight.release()
            }
        }

        val status = PairPolicy.status(
            pair = pair,
            similarity = similarity,
            evidence = evidence,
            aligned = aligned,
            confidence = confidence,
            area = area,
            maxSeconds = maxSeconds,
            threshold = threshold,
            enforceTime = enforceSelectionFilters,
            enforceSimilarity = enforceSelectionFilters
        )

        listOf(fullLeft, fullRight, left, right, grayL, grayR, kpL, kpR, dL, dR, src, dst, mask, fundamental)
            .forEach { it.release() }

        return AnalysisResult(
            pair,
            similarity,
            inlierMatches.size,
            confidence,
            median,
            area,
            status,
            leftPreview,
            rightPreview,
            sbsPreview,
            sbsJpeg
        )
    }

    private fun buildFullResolutionJpeg(
        fullLeft: Mat,
        fullRight: Mat,
        alignment: RigidAlignment,
        square: CropSquare,
        analysisW: Int,
        analysisH: Int,
        fullW: Int,
        fullH: Int
    ): ByteArray {
        val scaleX = fullW.toDouble() / analysisW
        val scaleY = fullH.toDouble() / analysisH
        val fullAlignment = alignment.copy(
            translateX = alignment.translateX * scaleX,
            translateY = alignment.translateY * scaleY,
            medianVerticalError = alignment.medianVerticalError * scaleY,
            translationOnlyVerticalError = alignment.translationOnlyVerticalError * scaleY
        )

        val matrix = rigidMatrix(fullW, fullH, fullAlignment)
        val warpedRight = Mat()
        Imgproc.warpAffine(
            fullRight,
            warpedRight,
            matrix,
            Size(fullW.toDouble(), fullH.toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            Scalar.all(0.0)
        )
        matrix.release()

        val safeSquare = insetSquare(square, SAFE_CROP_INSET_PX)
        val scaledLeft = floor(safeSquare.left * scaleX).toInt().coerceIn(0, fullW - 1)
        val scaledTop = floor(safeSquare.top * scaleY).toInt().coerceIn(0, fullH - 1)
        val scaledRight = ceil(safeSquare.right * scaleX).toInt().coerceIn(scaledLeft + 1, fullW)
        val scaledBottom = ceil(safeSquare.bottom * scaleY).toInt().coerceIn(scaledTop + 1, fullH)
        val rawSide = min(scaledRight - scaledLeft, scaledBottom - scaledTop).coerceAtLeast(1)
        val centerX = (scaledLeft + scaledRight) / 2
        val centerY = (scaledTop + scaledBottom) / 2
        val cropLeft = (centerX - rawSide / 2).coerceIn(0, fullW - rawSide)
        val cropTop = (centerY - rawSide / 2).coerceIn(0, fullH - rawSide)
        val cropRight = cropLeft + rawSide
        val cropBottom = cropTop + rawSide

        val cropL = fullLeft.submat(cropTop, cropBottom, cropLeft, cropRight)
        val cropR = warpedRight.submat(cropTop, cropBottom, cropLeft, cropRight)
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
        warpedRight.release()

        val joined = Mat()
        Core.hconcat(listOf(outL, outR), joined)
        outL.release()
        outR.release()

        val bitmap = joined.bitmap()
        joined.release()
        return try {
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    "Yüksek çözünürlüklü JPEG kodlanamadı"
                }
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun rigidMatrix(width: Int, height: Int, alignment: RigidAlignment): Mat {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val radians = Math.toRadians(alignment.angleDegrees)
        val c = cos(radians)
        val s = sin(radians)
        val offsetX = centerX - c * centerX + s * centerY + alignment.translateX
        val offsetY = centerY - s * centerX - c * centerY + alignment.translateY

        return Mat(2, 3, CvType.CV_64F).also { matrix ->
            matrix.put(
                0,
                0,
                c,
                -s,
                offsetX,
                s,
                c,
                offsetY
            )
        }
    }

    private fun transformRightPoint(
        x: Double,
        y: Double,
        width: Int,
        height: Int,
        alignment: RigidAlignment
    ): Point {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val radians = Math.toRadians(alignment.angleDegrees)
        val c = cos(radians)
        val s = sin(radians)
        val localX = x - centerX
        val localY = y - centerY
        return Point(
            c * localX - s * localY + centerX + alignment.translateX,
            s * localX + c * localY + centerY + alignment.translateY
        )
    }

    private fun insetSquare(square: CropSquare, inset: Int): CropSquare {
        val usableInset = inset.coerceAtMost((square.width - 1) / 2).coerceAtLeast(0)
        return CropSquare(
            left = square.left + usableInset,
            top = square.top + usableInset,
            right = square.right - usableInset,
            bottom = square.bottom - usableInset
        )
    }

    private fun bitmapToCommonMat(bitmap: Bitmap, width: Int, height: Int): Mat {
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)
        if (raw.cols() == width && raw.rows() == height) return raw

        val left = ((raw.cols() - width) / 2).coerceAtLeast(0)
        val top = ((raw.rows() - height) / 2).coerceAtLeast(0)
        val roi = raw.submat(top, top + height, left, left + width)
        val cropped = roi.clone()
        roi.release()
        raw.release()
        return cropped
    }

    private fun resized(source: Mat, width: Int, height: Int): Mat {
        if (source.cols() == width && source.rows() == height) return source.clone()
        return Mat().also { Imgproc.resize(source, it, Size(width.toDouble(), height.toDouble())) }
    }

    private fun Mat.bitmap() =
        Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888).also {
            Utils.matToBitmap(this, it)
        }

    companion object {
        private const val ANALYSIS_MAX_SIDE = 2048
        private const val MIN_GEOMETRIC_INLIERS = 18
        private const val MAX_VERTICAL_ERROR_PX = 2.5
        private const val SAFE_CROP_INSET_PX = 2
    }
}
