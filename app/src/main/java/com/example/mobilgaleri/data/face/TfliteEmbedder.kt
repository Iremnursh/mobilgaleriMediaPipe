//com/example/mobilgaleri/data/face/TfliteEmbedder.kt

package com.example.mobilgaleri.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TfliteEmbedder(
    context: Context,
    private val modelFileName: String = "adaface_ir18.tflite",
    private val inputSize: Int = 112
) {
    companion object { private const val TAG = "TfliteEmbedder" }

    private val interpreter: Interpreter
    private val inputShape: IntArray
    private val isNHWC: Boolean
    private val outDim: Int

    init {
        val opts = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(FileUtil.loadMappedFile(context, modelFileName), opts)
        inputShape = interpreter.getInputTensor(0).shape() // [1,112,112,3] veya [1,3,112,112]
        isNHWC = inputShape.size == 4 && inputShape[3] == 3
        outDim = interpreter.getOutputTensor(0).shape()[1]
        Log.d(TAG, "Loaded $modelFileName, inputShape=${inputShape.contentToString()}, isNHWC=$isNHWC, outDim=$outDim")
    }

    fun embeddingDim(): Int = outDim

    /** Zaten 112×112 align edilmiş yüz ver (crop istemez). */
    fun embedAligned112(aligned112: Bitmap): FloatArray {
        // Kırpma yok; doğrudan 112×112 bekliyoruz
        val input = if (isNHWC) bitmapToNHWC(aligned112) else bitmapToCHW(aligned112)
        val output = Array(1) { FloatArray(outDim) }
        synchronized(this) { interpreter.run(input, output) }

        val v = output[0]
        var n = 0f; for (x in v) n += x*x
        val inv = 1f / (kotlin.math.sqrt(n) + 1e-12f)
        for (i in v.indices) v[i] *= inv
        return v
    }

    /** Kutu ver, kırp + resize + embed. (Hizalama yapmıyorsan bunu kullan) */
    fun embed(bitmap: Bitmap, faceBox: Rect): FloatArray {
        val face = safeCrop(bitmap, faceBox, padding = 0.15f)
        val resized = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
        return embedAligned112(resized)
    }

    /** [-1,1] normalizasyon; RGB, NHWC */
    private fun bitmapToNHWC(bmp: Bitmap): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w*h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var idx = 0
        for (i in 0 until h) {
            for (j in 0 until w) {
                val p = pixels[idx++]
                val r = ((p shr 16) and 0xFF) / 127.5f - 1f
                val g = ((p shr  8) and 0xFF) / 127.5f - 1f
                val b = ( p         and 0xFF) / 127.5f - 1f
                bb.putFloat(r); bb.putFloat(g); bb.putFloat(b)
            }
        }
        bb.rewind(); return bb
    }

    /** [-1,1] normalizasyon; RGB, CHW */
    private fun bitmapToCHW(bmp: Bitmap): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w*h); bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var o = 0
        // R
        for (i in 0 until h) for (j in 0 until w) { val p = pixels[o++]; bb.putFloat(((p shr 16) and 0xFF)/127.5f - 1f) }
        // G
        o = 0
        for (i in 0 until h) for (j in 0 until w) { val p = pixels[o++]; bb.putFloat(((p shr 8) and 0xFF)/127.5f - 1f) }
        // B
        o = 0
        for (i in 0 until h) for (j in 0 until w) { val p = pixels[o++]; bb.putFloat((p and 0xFF)/127.5f - 1f) }
        bb.rewind(); return bb
    }

    private fun safeCrop(src: Bitmap, box: Rect, padding: Float): Bitmap {
        val padX = (box.width() * padding).toInt()
        val padY = (box.height() * padding).toInt()
        val l = max(0, box.left - padX)
        val t = max(0, box.top - padY)
        val r = min(src.width,  box.right + padX)
        val b = min(src.height, box.bottom + padY)
        return Bitmap.createBitmap(src, l, t, r-l, b-t)
    }
}
