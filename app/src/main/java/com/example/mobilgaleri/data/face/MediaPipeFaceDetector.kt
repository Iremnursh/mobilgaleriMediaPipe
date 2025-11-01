//com/example/mobilgaleri/data/face/MediaPipeFaceDetector.kt
package com.example.mobilgaleri.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector.FaceDetectorOptions
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.components.containers.Category

data class MediaPipeFaceResult(
    val boundingBox: Rect,
    val rightEye: Pair<Float, Float>? = null,
    val leftEye: Pair<Float, Float>? = null,
    val noseTip: Pair<Float, Float>? = null,
    val mouthCenter: Pair<Float, Float>? = null,
    val rightEarTragion: Pair<Float, Float>? = null,
    val leftEarTragion: Pair<Float, Float>? = null,
    val detectionConfidence: Float? = null
)

class MediaPipeFaceDetector(private val context: Context) {

    companion object {
        private const val TAG = "MediaPipeFaceDetector"
        // Örneklerin kullandığı metadata'lı model
        private const val MODEL_ASSET_PATH = "face_detection_short_range.tflite"

        private const val RIGHT_EYE_INDEX = 0
        private const val LEFT_EYE_INDEX = 1
        private const val NOSE_TIP_INDEX = 2
        private const val MOUTH_CENTER_INDEX = 3
        private const val RIGHT_EAR_TRAGION_INDEX = 4
        private const val LEFT_EAR_TRAGION_INDEX = 5
    }

    private var faceDetector: FaceDetector? = null
    private var isInitializing = false

    init {
        setupFaceDetector()
    }

    @Synchronized
    private fun setupFaceDetector() {
        if (faceDetector != null || isInitializing) return
        isInitializing = true
        Log.d(TAG, "Initializing Face Detector...")

        try {
            Log.d(TAG, "Assets: " + context.assets.list("")?.joinToString())
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_detection_short_range.tflite")
                .build()

            val options = FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.5f)
                .setMinSuppressionThreshold(0.3f)
                .build()

            faceDetector = FaceDetector.createFromOptions(context, options)
            Log.d(TAG, "Face Detector initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Face Detector: ${e.localizedMessage}", e)
            faceDetector = null
        } finally {
            isInitializing = false
        }
    }

    suspend fun detect(bitmap: Bitmap): List<MediaPipeFaceResult> = withContext(Dispatchers.Default) {
        if (faceDetector == null && !isInitializing) {
            Log.w(TAG, "Face Detector was null. Attempting re-initialization.")
            setupFaceDetector()
        }
        if (faceDetector == null || isInitializing) {
            Log.e(TAG, "Face Detector not available (Initializing: $isInitializing). Returning empty list.")
            return@withContext emptyList()
        }

        return@withContext try {
            // Bitmap -> MPImage
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            val detectionResult: FaceDetectorResult? = faceDetector?.detect(mpImage)
            mapResults(detectionResult)
        } catch (e: Exception) {
            Log.e(TAG, "Error during face detection for bitmap (${bitmap.width}x${bitmap.height}): ${e.localizedMessage}", e)
            emptyList()
        }
    }

    private fun mapResults(detectionResult: FaceDetectorResult?): List<MediaPipeFaceResult> {
        if (detectionResult == null) return emptyList()

        val out = mutableListOf<MediaPipeFaceResult>()
        detectionResult.detections().forEach { detection ->
            try {
                val bbox = detection.boundingBox()
                if (bbox == null || bbox.width() <= 0 || bbox.height() <= 0) {
                    Log.w(TAG, "Skipping detection with invalid bounding box: $bbox")
                    return@forEach
                }
                val rect = Rect(
                    bbox.left.toInt().coerceAtLeast(0),
                    bbox.top.toInt().coerceAtLeast(0),
                    bbox.right.toInt(),
                    bbox.bottom.toInt()
                )

                var rightEye: Pair<Float, Float>? = null
                var leftEye: Pair<Float, Float>? = null
                var noseTip: Pair<Float, Float>? = null
                var mouthCenter: Pair<Float, Float>? = null
                var rightEar: Pair<Float, Float>? = null
                var leftEar: Pair<Float, Float>? = null

                val keypointsOpt =
                    detection.keypoints()                    // Optional<List<NormalizedKeypoint>>
                val keypoints: List<NormalizedKeypoint> = keypointsOpt.orElse(emptyList())

                if (keypoints.size > RIGHT_EYE_INDEX) {
                    val p = keypoints[RIGHT_EYE_INDEX]
                    rightEye = Pair(p.x(), p.y())
                }
                if (keypoints.size > LEFT_EYE_INDEX) {
                    val p = keypoints[LEFT_EYE_INDEX]
                    leftEye = Pair(p.x(), p.y())
                }
                if (keypoints.size > NOSE_TIP_INDEX) {
                    val p = keypoints[NOSE_TIP_INDEX]
                    noseTip = Pair(p.x(), p.y())
                }
                if (keypoints.size > MOUTH_CENTER_INDEX) {
                    val p = keypoints[MOUTH_CENTER_INDEX]
                    mouthCenter = Pair(p.x(), p.y())
                }
                if (keypoints.size > RIGHT_EAR_TRAGION_INDEX) {
                    val p = keypoints[RIGHT_EAR_TRAGION_INDEX]
                    rightEar = Pair(p.x(), p.y())
                }
                if (keypoints.size > LEFT_EAR_TRAGION_INDEX) {
                    val p = keypoints[LEFT_EAR_TRAGION_INDEX]
                    leftEar = Pair(p.x(), p.y())
                }


                val confidence = detection.categories()?.firstOrNull()?.score()

                if (rect.width() > 0 && rect.height() > 0 && leftEye != null && rightEye != null) {
                    out.add(
                        MediaPipeFaceResult(
                            boundingBox = rect,
                            rightEye = rightEye,
                            leftEye = leftEye,
                            noseTip = noseTip,
                            mouthCenter = mouthCenter,
                            rightEarTragion = rightEar,
                            leftEarTragion = leftEar,
                            detectionConfidence = confidence
                        )
                    )
                } else {
                    Log.w(TAG, "Skipping detection due to missing required data (bbox or eyes). BBox: $rect, LeftEye: $leftEye, RightEye: $rightEye")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error mapping a specific detection: ${e.localizedMessage}", e)
            }
        }
        return out
    }

    @Synchronized
    fun close() {
        faceDetector?.close()
        faceDetector = null
        Log.d(TAG, "Face Detector closed.")
    }
}
