//com/example/mobilgaleri/domain/repository/ContactsRepository.kt
package com.example.mobilgaleri.domain.repository

import com.example.mobilgaleri.domain.model.Contact

interface ContactsRepository {
    // Sadece fotoğrafı olan kişileri getirecek fonksiyon
    suspend fun getContactsWithPhotos(): List<Contact>
}
