//com/example/mobilgaleri/data/face/MediaPipeFaceLandmarker.kt
package com.example.mobilgaleri.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

data class FivePoints(
    val leftEye: PointF, val rightEye: PointF, val noseTip: PointF,
    val mouthLeft: PointF, val mouthRight: PointF
)

class MediaPipeFaceLandmarker(private val context: Context) {
    companion object {
        private const val TAG = "MPFaceLM"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val IDX_LEFT_EYE = 33
        private const val IDX_RIGHT_EYE = 263
        private const val IDX_NOSE_TIP = 1
        private const val IDX_MOUTH_LEFT = 61
        private const val IDX_MOUTH_RIGHT = 291
    }

    private val landmarker: FaceLandmarker by lazy {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.20f)   // daha küçük yüzler için
            .setMinFacePresenceConfidence(0.30f)
            .build()
        FaceLandmarker.createFromOptions(context, opts)
    }

    fun detectFivePoints(bmp: Bitmap): FivePoints? {
        return try {
            val mp: MPImage = BitmapImageBuilder(bmp).build()
            val res: FaceLandmarkerResult = landmarker.detect(mp) ?: return null
            if (res.faceLandmarks().isEmpty()) return null
            val lm = res.faceLandmarks()[0]

            fun pt(i: Int): PointF? = lm.getOrNull(i)?.let { PointF(it.x() * bmp.width, it.y() * bmp.height) }
            val pL = pt(IDX_LEFT_EYE) ?: return null
            val pR = pt(IDX_RIGHT_EYE) ?: return null
            val pN = pt(IDX_NOSE_TIP) ?: return null
            val pML = pt(IDX_MOUTH_LEFT) ?: return null
            val pMR = pt(IDX_MOUTH_RIGHT) ?: return null
            FivePoints(pL, pR, pN, pML, pMR)
        } catch (e: Throwable) {
            Log.e(TAG, "detectFivePoints error: ${e.message}", e); null
        }
    }

    fun close() = runCatching { landmarker.close() }
}
