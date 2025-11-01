//com/example/mobilgaleri/domain/repository/GalleryRepository.kt
package com.example.mobilgaleri.domain.repository

import com.example.mobilgaleri.domain.model.Photo

interface GalleryRepository {
    suspend fun getAllPhotos(): List<Photo>
}
