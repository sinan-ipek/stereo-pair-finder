package com.stereopairfinder.image

import android.graphics.Bitmap
import android.graphics.Rect
import com.stereopairfinder.model.*
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min

class StereoAnalyzer {
    init { check(org.opencv.android.OpenCVLoader.initLocal()) { "OpenCV başlatılamadı" } }

    fun analyze(pair: PairCandidate, leftBitmap: Bitmap, rightBitmap: Bitmap, maxSeconds: Int, threshold: Int): AnalysisResult {
        var left = Mat(); var right = Mat(); Utils.bitmapToMat(leftBitmap,left); Utils.bitmapToMat(rightBitmap,right)
        val targetW=min(left.cols(),right.cols()); val targetH=min(left.rows(),right.rows())
        Imgproc.resize(left,left,Size(targetW.toDouble(),targetH.toDouble())); Imgproc.resize(right,right,Size(targetW.toDouble(),targetH.toDouble()))
        val grayL=Mat(); val grayR=Mat(); Imgproc.cvtColor(left,grayL,Imgproc.COLOR_RGBA2GRAY); Imgproc.cvtColor(right,grayR,Imgproc.COLOR_RGBA2GRAY)
        val orb=ORB.create(2500); val kpL=MatOfKeyPoint(); val kpR=MatOfKeyPoint(); val dL=Mat(); val dR=Mat()
        orb.detectAndCompute(grayL,Mat(),kpL,dL); orb.detectAndCompute(grayR,Mat(),kpR,dR)
        val good=mutableListOf<DMatch>()
        if(!dL.empty() && !dR.empty()) {
            val forward=ArrayList<MatOfDMatch>(); BFMatcher.create(Core.NORM_HAMMING,false).knnMatch(dL,dR,forward,2)
            forward.forEach { row -> val a=row.toArray(); if(a.size>=2 && a[0].distance < .75f*a[1].distance) good += a[0] }
        }
        val pL=kpL.toArray(); val pR=kpR.toArray(); val src=MatOfPoint2f(); val dst=MatOfPoint2f()
        src.fromList(good.map { pL[it.queryIdx].pt }); dst.fromList(good.map { pR[it.trainIdx].pt })
        val mask=Mat(); val fundamental = if(good.size>=12) Calib3d.findFundamentalMat(src,dst,Calib3d.FM_RANSAC,1.5,.995,mask) else Mat()
        val inlierMatches = if(mask.empty()) emptyList() else good.filterIndexed { i,_ -> mask.get(i,0)[0] != 0.0 }
        val evidence=inlierMatches.size>=18
        val similarity = if(!evidence) 0.0 else (100.0 * inlierMatches.size / min(pL.size,pR.size).coerceAtLeast(1) * 8.0).coerceAtMost(100.0)
        var aligned=false; var confidence=0.0; var median=Double.POSITIVE_INFINITY; var area=0.0; var sbs: Bitmap?=null
        var alignedL: Bitmap?=null; var alignedR: Bitmap?=null
        if(evidence && !fundamental.empty()) {
            val inL=MatOfPoint2f(); val inR=MatOfPoint2f()
            inL.fromList(inlierMatches.map { pL[it.queryIdx].pt }); inR.fromList(inlierMatches.map { pR[it.trainIdx].pt })
            val h1=Mat(); val h2=Mat()
            val ok=Calib3d.stereoRectifyUncalibrated(inL,inR,fundamental,Size(targetW.toDouble(),targetH.toDouble()),h1,h2,5.0)
            if(ok && sane(h1) && sane(h2)) {
                val warpL=Mat(); val warpR=Mat(); Imgproc.warpPerspective(left,warpL,h1,Size(targetW.toDouble(),targetH.toDouble()))
                Imgproc.warpPerspective(right,warpR,h2,Size(targetW.toDouble(),targetH.toDouble()))
                val one=Mat.ones(targetH,targetW,CvType.CV_8U); val m1=Mat(); val m2=Mat(); Imgproc.warpPerspective(one,m1,h1,Size(targetW.toDouble(),targetH.toDouble()),Imgproc.INTER_NEAREST)
                Imgproc.warpPerspective(one,m2,h2,Size(targetW.toDouble(),targetH.toDouble()),Imgproc.INTER_NEAREST); val common=Mat(); Core.bitwise_and(m1,m2,common)
                val nz=Mat(); Core.findNonZero(common,nz)
                if(!nz.empty()) {
                    val bounds=Imgproc.boundingRect(nz); val square=Geometry.centeredSquare(Rect(bounds.x,bounds.y,bounds.x+bounds.width,bounds.y+bounds.height))
                    val roi=common.submat(square.top,square.bottom,square.left,square.right); area=Core.countNonZero(roi).toDouble()/(targetW*targetH)
                    val transformedL=MatOfPoint2f(); val transformedR=MatOfPoint2f(); Core.perspectiveTransform(inL,transformedL,h1); Core.perspectiveTransform(inR,transformedR,h2)
                    median=Geometry.median(transformedL.toArray().zip(transformedR.toArray()).map { abs(it.first.y-it.second.y) })
                    confidence=(100.0 - median*15.0).coerceIn(0.0,100.0) * (inlierMatches.size/(inlierMatches.size+15.0))
                    aligned = median<=2.5 && area>=.35 && Core.countNonZero(roi) >= square.width()*square.height()*.97
                    if(aligned) {
                        val cropL=warpL.submat(square.top,square.bottom,square.left,square.right); val cropR=warpR.submat(square.top,square.bottom,square.left,square.right)
                        val side=Geometry.outputSide(square.width()); val outL=Mat(); val outR=Mat(); Imgproc.resize(cropL,outL,Size(side.toDouble(),side.toDouble())); Imgproc.resize(cropR,outR,Size(side.toDouble(),side.toDouble()))
                        alignedL=outL.bitmap(); alignedR=outR.bitmap(); val joined=Mat(); Core.hconcat(listOf(outL,outR),joined); sbs=joined.bitmap(); joined.release(); outL.release(); outR.release()
                    }
                    roi.release()
                }
                warpL.release(); warpR.release(); one.release(); m1.release(); m2.release(); common.release(); nz.release()
            }
            h1.release(); h2.release(); inL.release(); inR.release()
        }
        val status=PairPolicy.status(pair,similarity,evidence,aligned,confidence,area,maxSeconds,threshold)
        listOf(left,right,grayL,grayR,kpL,kpR,dL,dR,src,dst,mask,fundamental).forEach { it.release() }
        return AnalysisResult(pair,similarity,inlierMatches.size,confidence,median,area,status,alignedL ?: leftBitmap,alignedR ?: rightBitmap,sbs)
    }
    private fun sane(h: Mat): Boolean { val v=DoubleArray(9); if(h.rows()!=3||h.cols()!=3)return false; h.get(0,0,v); return Geometry.saneHomography(v) }
    private fun Mat.bitmap()=Bitmap.createBitmap(cols(),rows(),Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(this,it) }
}
