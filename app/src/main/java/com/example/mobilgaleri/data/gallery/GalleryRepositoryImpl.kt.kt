//com/example/mobilgaleri/data/gallery/GalleryRepositoryImpl.kt.kt
package com.example.mobilgaleri.data.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.example.mobilgaleri.domain.model.Photo
import com.example.mobilgaleri.domain.repository.GalleryRepository

class GalleryRepositoryImpl(
    private val contentResolver: ContentResolver
) : GalleryRepository {

    override suspend fun getAllPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC"

        fun query(uri: android.net.Uri) {
            contentResolver.query(uri, projection, null, null, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val dateTaken = runCatching { c.getLong(dateCol) }.getOrNull()
                    val contentUri = android.content.ContentUris
                        .withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    photos += Photo(id, contentUri.toString(), dateTaken)
                }
            }
        }

        // Dış ve iç depolama
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Images.Media.INTERNAL_CONTENT_URI)

        // Tarihe göre yeniden sırala (iki sorguyu birleştirdik)
        return photos.sortedByDescending { it.dateTaken ?: Long.MIN_VALUE }
    }

}
