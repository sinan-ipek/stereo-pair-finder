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
import kotlin.math.max
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
        enforceSelectionFilters: Boolean = true,
        renderSettings: RenderSettings = RenderSettings(),
        produceJpeg: Boolean = true
    ): AnalysisResult {
        val settings = renderSettings.normalized()
        val fullTargetW = min(leftBitmap.width, rightBitmap.width)
        val fullTargetH = min(leftBitmap.height, rightBitmap.height)
        require(fullTargetW > 0 && fullTargetH > 0) { "Geçersiz fotoğraf boyutu" }

        val fullLeft = bitmapToCommonMat(leftBitmap, fullTargetW, fullTargetH)
        val fullRight = bitmapToCommonMat(rightBitmap, fullTargetW, fullTargetH)

        val analysisScale = min(1.0, ANALYSIS_MAX_SIDE.toDouble() / max(fullTargetW, fullTargetH))
        val targetW = (fullTargetW * analysisScale).roundToInt().coerceAtLeast(1)
        val targetH = (fullTargetH * analysisScale).roundToInt().coerceAtLeast(1)
        val left = resized(fullLeft, targetW, targetH)
        val right = resized(fullRight, targetW, targetH)

        var leftPreview: Bitmap? = resizedToMaxSide(left, RAW_PREVIEW_MAX_SIDE).bitmap()
        var rightPreview: Bitmap? = resizedToMaxSide(right, RAW_PREVIEW_MAX_SIDE).bitmap()

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

        // Fundamental matrix is only an outlier filter. It is never used to warp the image.
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
            val inlierRatioScore = 100.0 * inlierMatches.size / good.size.toDouble()
            val evidenceCountScore =
                100.0 * inlierMatches.size / (inlierMatches.size + EVIDENCE_SCORE_HALF_SATURATION)
            (SIMILARITY_RATIO_WEIGHT * inlierRatioScore +
                SIMILARITY_COUNT_WEIGHT * evidenceCountScore).coerceIn(0.0, 100.0)
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
                val validRect = Geometry.largestValidRectangle(validPixels, targetW, targetH)

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

                median = Geometry.median(transformedPairs.map { (lp, rp) -> abs(lp.y - rp.y) })
                confidence = (100.0 - median * VERTICAL_ERROR_CONFIDENCE_PENALTY)
                    .coerceIn(0.0, 100.0) *
                    (inlierMatches.size / (inlierMatches.size + 15.0))
                aligned = median <= MAX_VERTICAL_ERROR_PX && area >= .35 && validRect != null

                if (aligned && validRect != null) {
                    val cropL = left.submat(validRect.top, validRect.bottom, validRect.left, validRect.right)
                    val cropR = warpedRight.submat(validRect.top, validRect.bottom, validRect.left, validRect.right)

                    val previewL = resizedToMaxSide(cropL, RAW_PREVIEW_MAX_SIDE)
                    val previewR = resizedToMaxSide(cropR, RAW_PREVIEW_MAX_SIDE)
                    leftPreview?.recycle()
                    rightPreview?.recycle()
                    leftPreview = previewL.bitmap()
                    rightPreview = previewR.bitmap()
                    previewL.release()
                    previewR.release()

                    val previewBasis = if (settings.cropMode == CropMode.FIT) {
                        max(cropL.cols(), cropL.rows())
                    } else {
                        min(cropL.cols(), cropL.rows())
                    }
                    val previewSide = min(PREVIEW_EYE_SIDE, previewBasis).coerceAtLeast(1)
                    sbsPreview = buildSbsBitmap(cropL, cropR, previewSide, settings)

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

                    if (candidateStatus == PairStatus.MATCHED && produceJpeg) {
                        sbsJpeg = buildFullResolutionJpeg(
                            fullLeft = fullLeft,
                            fullRight = fullRight,
                            alignment = alignment,
                            validRect = validRect,
                            analysisW = targetW,
                            analysisH = targetH,
                            fullW = fullTargetW,
                            fullH = fullTargetH,
                            settings = settings
                        )
                    }

                    cropL.release()
                    cropR.release()
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
            pair = pair,
            similarity = similarity,
            reliableMatches = inlierMatches.size,
            alignmentConfidence = confidence,
            medianVerticalError = median,
            commonAreaRatio = area,
            status = status,
            leftPreview = leftPreview,
            rightPreview = rightPreview,
            sbsPreview = sbsPreview,
            sbsJpeg = sbsJpeg
        )
    }

    private fun buildFullResolutionJpeg(
        fullLeft: Mat,
        fullRight: Mat,
        alignment: RigidAlignment,
        validRect: CropRect,
        analysisW: Int,
        analysisH: Int,
        fullW: Int,
        fullH: Int,
        settings: RenderSettings
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

        val safe = insetRect(validRect, SAFE_CROP_INSET_PX)
        val left = floor(safe.left * scaleX).toInt().coerceIn(0, fullW - 1)
        val top = floor(safe.top * scaleY).toInt().coerceIn(0, fullH - 1)
        val right = ceil(safe.right * scaleX).toInt().coerceIn(left + 1, fullW)
        val bottom = ceil(safe.bottom * scaleY).toInt().coerceIn(top + 1, fullH)

        val cropL = fullLeft.submat(top, bottom, left, right)
        val cropR = warpedRight.submat(top, bottom, left, right)
        val basis = if (settings.cropMode == CropMode.FIT) {
            max(cropL.cols(), cropL.rows())
        } else {
            min(cropL.cols(), cropL.rows())
        }
        val finalSide = Geometry.fullOutputSide(basis).coerceAtLeast(1)
        val joined = renderSbs(cropL, cropR, finalSide, settings)

        cropL.release()
        cropR.release()
        warpedRight.release()

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

    private fun buildSbsBitmap(left: Mat, right: Mat, side: Int, settings: RenderSettings): Bitmap {
        val joined = renderSbs(left, right, side, settings)
        return try {
            joined.bitmap()
        } finally {
            joined.release()
        }
    }

    private fun renderSbs(left: Mat, right: Mat, side: Int, settings: RenderSettings): Mat {
        val outL = renderEye(left, side, settings)
        val outR = renderEye(right, side, settings)
        val joined = Mat()
        Core.hconcat(listOf(outL, outR), joined)
        outL.release()
        outR.release()
        return joined
    }

    private fun renderEye(source: Mat, side: Int, settings: RenderSettings): Mat {
        require(side > 0)
        val bias = settings.verticalBias.coerceIn(-1f, 1f)
        val sourceW = source.cols()
        val sourceH = source.rows()

        return if (settings.cropMode == CropMode.FIT) {
            val scale = min(side.toDouble() / sourceW, side.toDouble() / sourceH)
            val outW = (sourceW * scale).roundToInt().coerceIn(1, side)
            val outH = (sourceH * scale).roundToInt().coerceIn(1, side)
            val resized = Mat()
            Imgproc.resize(source, resized, Size(outW.toDouble(), outH.toDouble()))
            val canvas = Mat.zeros(side, side, source.type())
            val x = (side - outW) / 2
            val freeY = side - outH
            val y = ((bias + 1f) * .5f * freeY).roundToInt().coerceIn(0, freeY)
            val roi = canvas.submat(y, y + outH, x, x + outW)
            resized.copyTo(roi)
            roi.release()
            resized.release()
            canvas
        } else {
            val scale = max(side.toDouble() / sourceW, side.toDouble() / sourceH)
            val outW = max(side, (sourceW * scale).roundToInt())
            val outH = max(side, (sourceH * scale).roundToInt())
            val resized = Mat()
            Imgproc.resize(source, resized, Size(outW.toDouble(), outH.toDouble()))
            val x = ((outW - side) / 2).coerceAtLeast(0)
            val freeY = (outH - side).coerceAtLeast(0)
            val y = ((bias + 1f) * .5f * freeY).roundToInt().coerceIn(0, freeY)
            val roi = resized.submat(y, y + side, x, x + side)
            val cropped = roi.clone()
            roi.release()
            resized.release()
            cropped
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
            matrix.put(0, 0, c, -s, offsetX, s, c, offsetY)
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

    private fun insetRect(rect: CropRect, inset: Int): CropRect {
        val maxInset = min((rect.width - 1) / 2, (rect.height - 1) / 2).coerceAtLeast(0)
        val safeInset = inset.coerceIn(0, maxInset)
        return CropRect(
            left = rect.left + safeInset,
            top = rect.top + safeInset,
            right = rect.right - safeInset,
            bottom = rect.bottom - safeInset
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

    private fun resizedToMaxSide(source: Mat, maxSide: Int): Mat {
        val longest = max(source.cols(), source.rows())
        if (longest <= maxSide) return source.clone()
        val scale = maxSide.toDouble() / longest
        val width = (source.cols() * scale).roundToInt().coerceAtLeast(1)
        val height = (source.rows() * scale).roundToInt().coerceAtLeast(1)
        return resized(source, width, height)
    }

    private fun Mat.bitmap() =
        Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888).also {
            Utils.matToBitmap(this, it)
        }

    companion object {
        private const val ANALYSIS_MAX_SIDE = 2048
        private const val RAW_PREVIEW_MAX_SIDE = 1200
        private const val PREVIEW_EYE_SIDE = 900
        private const val MIN_GEOMETRIC_INLIERS = 18
        private const val EVIDENCE_SCORE_HALF_SATURATION = 40.0
        private const val SIMILARITY_RATIO_WEIGHT = 0.60
        private const val SIMILARITY_COUNT_WEIGHT = 0.40
        private const val MAX_VERTICAL_ERROR_PX = 4.0
        private const val VERTICAL_ERROR_CONFIDENCE_PENALTY = 10.0
        private const val SAFE_CROP_INSET_PX = 2
    }
}
