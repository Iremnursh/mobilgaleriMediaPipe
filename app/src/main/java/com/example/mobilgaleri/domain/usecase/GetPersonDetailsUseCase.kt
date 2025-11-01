//com/example/mobilgaleri/domain/usecase/GetPersonDetailsUseCase.kt
package com.example.mobilgaleri.domain.usecase

import com.example.mobilgaleri.domain.model.PersonDetails
import com.example.mobilgaleri.domain.repository.ContactsRepository
import com.example.mobilgaleri.domain.repository.PersonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GetPersonDetailsUseCase(
    private val personRepository: PersonRepository,
    private val contactsRepository: ContactsRepository
) {
    suspend operator fun invoke(personIds: List<Int>): Map<Int, PersonDetails> = withContext(Dispatchers.IO) {
        if (personIds.isEmpty()) return@withContext emptyMap()

        val allPersons = personRepository.getAllPersons().associateBy { it.personId.toInt() }
        val contactsMap = contactsRepository.getContactsWithPhotos().associateBy({ it.id }, { it.name })
        val detailsMap = mutableMapOf<Int, PersonDetails>()

        personIds.forEach { id ->
            val person = allPersons[id]

            // DÜZELTME: `person.name`'i de kullanarak, eğer bir isim atanmışsa onu gösteriyoruz.
            val displayName = person?.name ?: "Kişi ${id + 1}"
            var matchInfo: String? = null

            // DÜZELTME: `matchedContactId` -> `contactId` olarak değiştirildi.
            // DÜZELTME: `matchConfidence` -> `contactMatchConfidence` olarak değiştirildi.
            if (person?.contactId != null && person.contactMatchConfidence != null) {
                val contactName = contactsMap[person.contactId]
                if (contactName != null) {
                    val percentage = (person.contactMatchConfidence!! * 100).toInt()
                    matchInfo = "Benzerlik: $contactName (%$percentage)"
                }
            }
            detailsMap[id] = PersonDetails(displayName, matchInfo)
        }
        return@withContext detailsMap
    }
}