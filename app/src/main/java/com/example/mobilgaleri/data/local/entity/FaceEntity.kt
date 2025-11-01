//com/example/mobilgaleri/data/local/entity/FaceEntity.kt
package com.example.mobilgaleri.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "faces")
data class FaceEntity(
    @PrimaryKey(autoGenerate = true)
    val faceId: Long = 0,
    val photoOwnerId: Long, // Hangi fotoğrafa ait
    var personOwnerId: Long, // Hangi kişiye ait (cluster id)
    val embedding: FloatArray, // Yüzün parmak izi

    // YENİ: Yüzün koordinatlarını tutacak alanlar
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
