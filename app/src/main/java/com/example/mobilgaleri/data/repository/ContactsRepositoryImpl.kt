//com/example/mobilgaleri/data/repository/ContactsRepositoryImpl.kt
package com.example.mobilgaleri.data.repository

import android.content.ContentResolver
import android.provider.ContactsContract
import com.example.mobilgaleri.domain.model.Contact
import com.example.mobilgaleri.domain.repository.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsRepositoryImpl(private val contentResolver: ContentResolver) : ContactsRepository {

    override suspend fun getContactsWithPhotos(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI
        )
        // Sadece fotoğrafı olanları (PHOTO_URI'si null olmayanları) seç
        val selection = "${ContactsContract.Contacts.PHOTO_URI} IS NOT NULL"

        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            null,
            ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val photoUriColumn = it.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn)
                val photoUri = it.getString(photoUriColumn)
                contacts.add(Contact(id, name, photoUri))
            }
        }
        return@withContext contacts
    }
}
