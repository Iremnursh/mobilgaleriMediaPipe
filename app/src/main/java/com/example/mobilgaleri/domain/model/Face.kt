//com/example/mobilgaleri/domain/model/Face.kt
package com.example.mobilgaleri.domain.model

data class Face(
    val id: Long = 0L,
    val photoId: Long,
    val boundingBox: android.graphics.Rect,
    val embedding: FloatArray? = null
)
