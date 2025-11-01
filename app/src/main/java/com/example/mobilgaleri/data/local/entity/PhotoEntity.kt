//com/example/mobilgaleri/data/local/entity/PhotoEntity.kt
package com.example.mobilgaleri.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo")
data class PhotoEntity(
    @PrimaryKey val id: Long,
    val contentUri: String,
    val dateTaken: Long?
)
