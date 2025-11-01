//com/example/mobilgaleri/presentation/debug/CompareFromGalleryScreen.kt
package com.example.mobilgaleri.presentation.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mobilgaleri.data.face.MediaPipeFaceLandmarker
import com.example.mobilgaleri.data.face.TfliteEmbedder
import com.example.mobilgaleri.util.alignFace112FiveOrThree
import com.example.mobilgaleri.util.averageEmbeddings
import com.example.mobilgaleri.util.cosSim
import com.example.mobilgaleri.util.flipX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CompareFromGalleryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var uri1 by remember { mutableStateOf<Uri?>(null) }
    var uri2 by remember { mutableStateOf<Uri?>(null) }
    var bmp1 by remember { mutableStateOf<Bitmap?>(null) }
    var bmp2 by remember { mutableStateOf<Bitmap?>(null) }

    var status by remember { mutableStateOf("İki foto seçiniz.") }
    var score by remember { mutableFloatStateOf(0f) }
    var verdict by remember { mutableStateOf("?") }

    val pick1 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri1 = uri
        bmp1 = uri?.let { decodeUriToBitmap(ctx, it) }
    }
    val pick2 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri2 = uri
        bmp2 = uri?.let { decodeUriToBitmap(ctx, it) }
    }

    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(160.dp), contentAlignment = Alignment.Center) {
                    if (bmp1 != null) {
                        Image(bmp1!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxHeight())
                    } else Text("Foto 1")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { pick1.launch("image/*") }) { Text("Foto 1 Seç") }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(160.dp), contentAlignment = Alignment.Center) {
                    if (bmp2 != null) {
                        Image(bmp2!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxHeight())
                    } else Text("Foto 2")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { pick2.launch("image/*") }) { Text("Foto 2 Seç") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text("cosSim: $score   →   karar: $verdict", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        Button(
            enabled = (bmp1 != null && bmp2 != null),
            onClick = {
                scope.launch(Dispatchers.Default) {
                    try {
                        status = "Landmarks & hizalama…"
                        val lm = MediaPipeFaceLandmarker(ctx)
                        val p1 = lm.detectFivePoints(bmp1!!)
                        val p2 = lm.detectFivePoints(bmp2!!)
                        lm.close()
                        require(p1 != null && p2 != null) { "Yüz/landmark bulunamadı." }

                        val a1 = alignFace112FiveOrThree(
                            bmp1!!,
                            leftEye = p1.leftEye.x to p1.leftEye.y,
                            rightEye = p1.rightEye.x to p1.rightEye.y,
                            nose = p1.noseTip.x to p1.noseTip.y,
                            mouthLeft = p1.mouthLeft.x to p1.mouthLeft.y,
                            mouthRight = p1.mouthRight.x to p1.mouthRight.y,
                            margin = 1.10f
                        )
                        val a2 = alignFace112FiveOrThree(
                            bmp2!!,
                            leftEye = p2.leftEye.x to p2.leftEye.y,
                            rightEye = p2.rightEye.x to p2.rightEye.y,
                            nose = p2.noseTip.x to p2.noseTip.y,
                            mouthLeft = p2.mouthLeft.x to p2.mouthLeft.y,
                            mouthRight = p2.mouthRight.x to p2.mouthRight.y,
                            margin = 1.10f
                        )

                        status = "AdaFace (flip-ortalama)…"
                        val emb = TfliteEmbedder(ctx, "adaface_ir18.tflite")
                        val e1 = emb.embedAligned112(a1.bitmap)
                        val e1f = emb.embedAligned112(a1.bitmap.flipX())
                        val avg1 = averageEmbeddings(e1, e1f)

                        val e2 = emb.embedAligned112(a2.bitmap)
                        val e2f = emb.embedAligned112(a2.bitmap.flipX())
                        val avg2 = averageEmbeddings(e2, e2f)

                        val thr = 0.60f
                        score = cosSim(avg1, avg2)
                        verdict = if (score >= thr) "Aynı kişi ✅" else "Farklı kişi ❌"
                        status = "Bitti ✅ (eşik=$thr)"
                    } catch (t: Throwable) {
                        status = "Hata: ${t.message}"
                        verdict = "?"
                        score = 0f
                    }
                }
            }
        ) { Text("Karşılaştır") }
    }
}

private fun decodeUriToBitmap(ctx: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
        }
    } catch (e: Exception) { null }
}
