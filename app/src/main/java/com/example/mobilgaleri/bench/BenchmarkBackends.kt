package com.example.mobilgaleri.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

// MediaPipe
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector as MpFaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector.FaceDetectorOptions as MpFaceDetectorOptions

/** Benchmark içinde her iki dedektörü aynı imzayla kullanmak için ortak arayüz */
interface FaceDetectorBackend {
    suspend fun detect(bitmap: Bitmap): List<Box>
    fun name(): String
    fun close()
}

enum class BackendChoice { MLKit, MediaPipe }

/** ML Kit (FAST) — landmarks/contours/classification kapalı: benchmark ile hizalı */
class MlkitBackend : FaceDetectorBackend {
    private val opts: FaceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(BenchConfig.MIN_FACE_SIZE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector: FaceDetector = FaceDetection.getClient(opts)

    override suspend fun detect(bitmap: Bitmap): List<Box> {
        val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        return faces.map { f ->
            val bb: Rect = f.boundingBox
            Box(
                x1 = bb.left.coerceAtLeast(0),
                y1 = bb.top.coerceAtLeast(0),
                x2 = bb.right.coerceAtMost(bitmap.width),
                y2 = bb.bottom.coerceAtMost(bitmap.height)
            )
        }
    }

    override fun name() = "MLKit_FAST"
    override fun close() = detector.close()
}

/** MediaPipe Tasks — CPU (sürüm uyumluluğu için GPU satırı yok) */
class MediaPipeBackend(context: Context) : FaceDetectorBackend {
    private val detector: MpFaceDetector

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_detection_short_range.tflite") // assets/ içine koy
            .build()

        val opts = MpFaceDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(0.5f)
            .setMinSuppressionThreshold(0.3f)
            .build()

        detector = MpFaceDetector.createFromOptions(context, opts)
    }

    override suspend fun detect(bitmap: Bitmap): List<Box> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val res = detector.detect(mpImage) ?: return emptyList()
        val dets = res.detections() ?: return emptyList()
        return dets.mapNotNull { d ->
            val r = d.boundingBox() ?: return@mapNotNull null
            Box(
                x1 = r.left.toInt().coerceAtLeast(0),
                y1 = r.top.toInt().coerceAtLeast(0),
                x2 = r.right.toInt().coerceAtMost(bitmap.width),
                y2 = r.bottom.toInt().coerceAtMost(bitmap.height)
            )
        }
    }

    override fun name() = "MediaPipe"
    override fun close() = detector.close()
}
