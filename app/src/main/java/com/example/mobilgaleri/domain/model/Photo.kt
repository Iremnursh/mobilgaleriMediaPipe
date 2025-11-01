//com/example/mobilgaleri/domain/model/Photo.kt
package com.example.mobilgaleri.domain.model

data class Photo(
    val id: Long,
    val contentUri: String,
    val dateTaken: Long?
)
