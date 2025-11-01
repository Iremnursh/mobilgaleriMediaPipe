// com/example/mobilgaleri/util/Math.kt
package com.example.mobilgaleri.util

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** (İsteğe bağlı) Noktasal çarpım. L2-normalize ettiysen dot = cosine. */
fun dot(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** FloatArray <-> ByteArray (LE) yardımcıları. */
fun floatArrayToByteArray(arr: FloatArray): ByteArray {
    val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in arr) bb.putFloat(f)
    return bb.array()
}
fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(bytes.size / 4)
    for (i in out.indices) out[i] = bb.getFloat()
    return out
}

/** Basit döndürme yardımcıcısı. */
fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = android.graphics.Matrix()
    matrix.postRotate(angle, source.width / 2f, source.height / 2f)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
