//com/example/mobilgaleri/domain/model/PersonDetails.kt
package com.example.mobilgaleri.domain.model

data class PersonDetails(
    val displayName: String, // Ekranda görünecek isim (örn: "Kişi 1")
    val matchInfo: String?   // Benzerlik bilgisi (örn: "Benzerlik: Ahmet Yılmaz (%78)") veya null
)
