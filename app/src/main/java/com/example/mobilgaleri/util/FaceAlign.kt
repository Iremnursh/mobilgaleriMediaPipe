//com/example/mobilgaleri/util/FaceAlign.kt
package com.example.mobilgaleri.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.sqrt

/** ArcFace 112×112 canonical 5-nokta (px) */
private val ARC112 = floatArrayOf(
    38.2946f, 51.6963f,   // left eye
    73.5318f, 51.5014f,   // right eye
    56.0252f, 71.7366f,   // nose
    41.5493f, 92.3655f,   // mouth left
    70.7299f, 92.2041f    // mouth right
)

/** 5-nokta ile similarity (s,R,t) tahmin edip 112×112’e hizalar. */
fun alignFace112Five(
    src: Bitmap,
    leftEye: Pair<Float, Float>,
    rightEye: Pair<Float, Float>,
    nose: Pair<Float, Float>,
    mouthLeft: Pair<Float, Float>,
    mouthRight: Pair<Float, Float>,
    margin: Float = 1.0f
): Bitmap {
    // Kaynak ve hedef noktalar
    val srcPts = floatArrayOf(
        leftEye.first, leftEye.second,
        rightEye.first, rightEye.second,
        nose.first, nose.second,
        mouthLeft.first, mouthLeft.second,
        mouthRight.first, mouthRight.second
    )
    val dstPts = ARC112.copyOf()

    // Umeyama similarity tahmini
    val M = estimateSimilarity5(srcPts, dstPts) ?: run {
        // Fallback: 3-nokta affine (gözler+burun)
        return alignFace112Three(src, leftEye, rightEye, nose, margin)
    }

    if (margin != 1.0f) {
        // 56,56 merkez etrafında biraz zoom in/out
        M.postScale(margin, margin, 56f, 56f)
    }

    val out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, M, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

/** Gözler+burun ile 3-nokta affine (güvenli yedek) */
private fun alignFace112Three(
    src: Bitmap,
    leftEye: Pair<Float, Float>,
    rightEye: Pair<Float, Float>,
    nose: Pair<Float, Float>,
    margin: Float
): Bitmap {
    val dst = floatArrayOf(
        ARC112[0], ARC112[1],   // left eye
        ARC112[2], ARC112[3],   // right eye
        ARC112[4], ARC112[5]    // nose
    )
    val frm = floatArrayOf(
        leftEye.first, leftEye.second,
        rightEye.first, rightEye.second,
        nose.first, nose.second
    )
    val M = Matrix().apply { setPolyToPoly(frm, 0, dst, 0, 3) }
    if (margin != 1.0f) M.postScale(margin, margin, 56f, 56f)

    val out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, M, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

/** Umeyama: 5 karşılık noktadan similarity (s,R,t) -> Android Matrix. */
private fun estimateSimilarity5(src5: FloatArray, dst5: FloatArray): Matrix? {
    // 5 nokta -> 10 sayı (x,y)*5
    if (src5.size != 10 || dst5.size != 10) return null

    // Ortalamalar
    var mxs = 0f; var mys = 0f; var mxd = 0f; var myd = 0f
    for (k in 0 until 5) {
        mxs += src5[2*k]; mys += src5[2*k+1]
        mxd += dst5[2*k]; myd += dst5[2*k+1]
    }
    mxs /= 5f; mys /= 5f; mxd /= 5f; myd /= 5f

    // Merkezden arındır
    val xs = FloatArray(5); val ys = FloatArray(5)
    val xd = FloatArray(5); val yd = FloatArray(5)
    for (k in 0 until 5) {
        xs[k] = src5[2*k]   - mxs
        ys[k] = src5[2*k+1] - mys
        xd[k] = dst5[2*k]   - mxd
        yd[k] = dst5[2*k+1] - myd
    }

    // Kovaryans ve ölçekler
    var sxx = 0f; var sxy = 0f; var syx = 0f; var syy = 0f
    var ns = 0f; var nd = 0f
    for (k in 0 until 5) {
        sxx += xs[k]*xd[k]; sxy += xs[k]*yd[k]
        syx += ys[k]*xd[k]; syy += ys[k]*yd[k]
        ns  += xs[k]*xs[k] + ys[k]*ys[k]
        nd  += xd[k]*xd[k] + yd[k]*yd[k]
    }
    if (ns < 1e-6f) return null

    // R = [a -b; b a] formunda (2x2), s*R tahmini
    // (Procrustes kapalı form)
    val a = (sxx + syy) / ns
    val b = (sxy - syx) / ns
    val scale = sqrt((nd / ns).coerceAtLeast(1e-12f))  // isteğe bağlı global ölçek
    val ra = a                                     // scale’ı R’ye katmayabiliriz
    val rb = b

    // t = mu_dst - R*mu_src
    val tx = mxd - (ra*mxs - rb*mys)
    val ty = myd - (rb*mxs + ra*mys)

    // Android Matrix’e yaz ( [ ra -rb tx; rb ra ty ] )
    return Matrix().apply {
        setValues(floatArrayOf(
            ra, -rb, tx,
            rb,  ra, ty,
            0f,  0f, 1f
        ))
    }
}

// ---- EKLE: görünüm ve fallback'i görmek için küçük yardımcı ----
data class AlignResult(val bitmap: Bitmap, val usedFivePoint: Boolean)

fun alignFace112FiveOrThree(
    src: Bitmap,
    leftEye: Pair<Float, Float>,
    rightEye: Pair<Float, Float>,
    nose: Pair<Float, Float>,
    mouthLeft: Pair<Float, Float>,
    mouthRight: Pair<Float, Float>,
    margin: Float = 1.0f
): AlignResult {
    val srcPts = floatArrayOf(
        leftEye.first, leftEye.second,
        rightEye.first, rightEye.second,
        nose.first, nose.second,
        mouthLeft.first, mouthLeft.second,
        mouthRight.first, mouthRight.second
    )
    val dstPts = ARC112.copyOf()

    val M = estimateSimilarity5(srcPts, dstPts)
    return if (M != null) {
        if (margin != 1.0f) M.postScale(margin, margin, 56f, 56f)
        val out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, M, Paint(Paint.FILTER_BITMAP_FLAG))
        AlignResult(out, true)  // 5-nokta kullanıldı
    } else {
        AlignResult(
            alignFace112Three(src, leftEye, rightEye, nose, margin),
            false                 // 3-nokta fallback
        )
    }
}
