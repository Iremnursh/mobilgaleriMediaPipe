// com/example/mobilgaleri/presentation/debug/EmbedderDebugScreen.kt

package com.example.mobilgaleri.presentation.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mobilgaleri.data.face.MediaPipeFaceLandmarker
import com.example.mobilgaleri.data.face.TfliteEmbedder
import com.example.mobilgaleri.util.alignFace112Five
import com.example.mobilgaleri.util.alignFace112FiveOrThree
import com.example.mobilgaleri.util.averageEmbeddings
import com.example.mobilgaleri.util.cosSim
import com.example.mobilgaleri.util.flipX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "EmbedderDebugScreen"
private const val THRESHOLD = 0.60f

@Composable
fun EmbedderDebugScreen() {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Hazır") }
    var lastCos by remember { mutableFloatStateOf(0f) }
    var verdict by remember { mutableStateOf("?") }
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp)) {
        Text(status)
        Text("cosSim: $lastCos  →  karar: $verdict (T=$THRESHOLD)")

        Button(onClick = {
            scope.launch(Dispatchers.Default) {
                fun say(step: String) { status = step; Log.d(TAG, step) }

                try {
                    // 0) Assets doğrula
                    val assets = ctx.assets.list("")?.toList().orEmpty()
                    Log.d(TAG, "ASSETS = $assets")
                    require(assets.contains("adaface_ir18.tflite")) { "assets/adaface_ir18.tflite yok" }
                    require(assets.contains("face_landmarker.task")) { "assets/face_landmarker.task yok" }
                    require(assets.contains("test_face1.jpg")) { "assets/test_face1.jpg yok" }
                    require(assets.contains("test_face2.jpg")) { "assets/test_face2.jpg yok" }

                    say("Görseller yükleniyor…")
                    val b1 = loadBitmap(ctx, "test_face1.jpg")
                    val b2 = loadBitmap(ctx, "test_face2.jpg")

                    say("Landmarks (5-point)…")
                    val lm = MediaPipeFaceLandmarker(ctx)
                    val p1 = lm.detectFivePoints(b1)
                    val p2 = lm.detectFivePoints(b2)
                    lm.close()
                    require(p1 != null && p2 != null) { "5-nokta bulunamadı; eşiği düşürmeyi deneyin." }

                    say("Hizalama (112×112, margin=1.10)…")
                    val a1 = alignFace112FiveOrThree(
                        b1,
                        leftEye = p1.leftEye.x to p1.leftEye.y,
                        rightEye = p1.rightEye.x to p1.rightEye.y,
                        nose = p1.noseTip.x to p1.noseTip.y,
                        mouthLeft = p1.mouthLeft.x to p1.mouthLeft.y,
                        mouthRight = p1.mouthRight.x to p1.mouthRight.y,
                        margin = 1.10f
                    )
                    val a2 = alignFace112FiveOrThree(
                        b2,
                        leftEye = p2.leftEye.x to p2.leftEye.y,
                        rightEye = p2.rightEye.x to p2.rightEye.y,
                        nose = p2.noseTip.x to p2.noseTip.y,
                        mouthLeft = p2.mouthLeft.x to p2.mouthLeft.y,
                        mouthRight = p2.mouthRight.x to p2.mouthRight.y,
                        margin = 1.10f
                    )
                    android.util.Log.d("Align", "img1 fivePoint=${a1.usedFivePoint}, img2 fivePoint=${a2.usedFivePoint}")

                    say("AdaFace yükleniyor…")
                    val emb = TfliteEmbedder(ctx, "adaface_ir18.tflite")
                    Log.d(TAG, "Embedder dim=${emb.embeddingDim()}")

                    say("AdaFace (flip-ortalama)…")
                    val e1 = emb.embedAligned112(a1.bitmap)
                    val e1f = emb.embedAligned112(a1.bitmap.flipX())
                    val e1avg = averageEmbeddings(e1, e1f)

                    val e2 = emb.embedAligned112(a2.bitmap)
                    val e2f = emb.embedAligned112(a2.bitmap.flipX())
                    val e2avg = averageEmbeddings(e2, e2f)

                    lastCos = cosSim(e1avg, e2avg)
                    verdict = if (lastCos >= THRESHOLD) "Aynı kişi ✅" else "Farklı kişi ❌"
                    say("Bitti ✅")
                } catch (t: Throwable) {
                    val msg = "${t::class.simpleName}: ${t.message ?: "(no message)"}"
                    val trace = android.util.Log.getStackTraceString(t)
                    Log.e(TAG, "HATA: $msg\n$trace")
                    status = "Hata: $msg\n$trace"
                    verdict = "?"
                    lastCos = 0f
                }
            }
        }) { Text("Embed Testi Çalıştır") }
    }
}

private fun loadBitmap(context: Context, name: String): Bitmap {
    context.assets.open(name).use { inp ->
        val bytes = inp.readBytes()
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Decode edilemedi: $name"
        }
    }
}
