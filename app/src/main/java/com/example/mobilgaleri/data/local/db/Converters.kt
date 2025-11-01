//com/example/mobilgaleri/data/local/db/Converters.kt
package com.example.mobilgaleri.data.local.db

import androidx.room.TypeConverter
import java.nio.ByteBuffer

class Converters {
    // FloatArray'i veritabanına yazmak için ByteArray'e çevirir
    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * 4) // Her float 4 byte
        value.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    // Veritabanından okunan ByteArray'i FloatArray'e geri çevirir
    @TypeConverter
    fun toFloatArray(value: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(value)
        val floatArray = FloatArray(value.size / 4)
        for (i in floatArray.indices) {
            floatArray[i] = buffer.getFloat()
        }
        return floatArray
    }
}
