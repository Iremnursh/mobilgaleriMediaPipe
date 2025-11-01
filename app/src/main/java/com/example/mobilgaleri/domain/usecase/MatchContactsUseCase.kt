//com/example/mobilgaleri/domain/usecase/MatchContactsUseCase.kt
package com.example.mobilgaleri.domain.usecase

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri // YENİ: Uri sınıfını import et
import com.example.mobilgaleri.data.face.MediaPipeFaceLandmarker
import com.example.mobilgaleri.data.face.TfliteEmbedder
import com.example.mobilgaleri.domain.repository.ContactsRepository
import com.example.mobilgaleri.domain.repository.PersonRepository
import com.example.mobilgaleri.util.alignFace112FiveOrThree
import com.example.mobilgaleri.util.averageEmbeddings
import com.example.mobilgaleri.util.flipX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class MatchContactsUseCase(
    private val personRepository: PersonRepository,
    private val contactsRepository: ContactsRepository,
    private val embedder: TfliteEmbedder,
    private val contentResolver: ContentResolver,
    private val landmarker: MediaPipeFaceLandmarker   // ← eklendi
) {
    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        val den = sqrt(norm1) * sqrt(norm2)
        return if (den == 0f) 0f else dot / den
    }

    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        val allPersons = personRepository.getAllPersons()
        val contactsWithPhotos = contactsRepository.getContactsWithPhotos()

        if (allPersons.isEmpty() || contactsWithPhotos.isEmpty()) return@withContext

        allPersons.forEach { person ->
            var bestMatchContactId: Long? = null
            var highestSimilarity = 0f

            contactsWithPhotos.forEach { contact ->
                try {
                    // DÜZELTME: String? olan photoUri'yi Uri nesnesine çeviriyoruz.
                    // `let` bloğu, contact.photoUri'nin null olmamasını garanti eder.
                    contact.photoUri?.let { uriString ->
                        val contactUri = Uri.parse(uriString)
                        contentResolver.openInputStream(contactUri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins) ?: return@let

                            // 1) 5-nokta landmark
                            val five = landmarker.detectFivePoints(bmp)

                            // 2) Hizalama (112x112). 5 nokta yoksa güvenli fallback çalışır.
                            val aligned = if (five != null) {
                                alignFace112FiveOrThree(
                                    bmp,
                                    leftEye    = five.leftEye.x to five.leftEye.y,
                                    rightEye   = five.rightEye.x to five.rightEye.y,
                                    nose       = five.noseTip.x to five.noseTip.y,
                                    mouthLeft  = five.mouthLeft.x to five.mouthLeft.y,
                                    mouthRight = five.mouthRight.x to five.mouthRight.y,
                                    margin     = 1.10f
                                ).bitmap
                            } else {
                                // Yüz tespit edilemezse şansı dene ama hizalı değil → zayıf
                                Bitmap.createScaledBitmap(bmp, 112, 112, true)
                            }

                            // 3) Embedding + flip-ortalama + L2 normalize
                            val e  = embedder.embedAligned112(aligned)
                            val ef = embedder.embedAligned112(aligned.flipX())
                            val emb = averageEmbeddings(e, ef)

                            // 4) Benzerlik ve en iyiyi seç
                            val similarity = cosineSimilarity(person.centroid, emb)
                            if (similarity > highestSimilarity) {
                                highestSimilarity = similarity
                                bestMatchContactId = contact.id
                            }

                            // temizlik
                            if (aligned !== bmp) aligned.recycle()
                            bmp.recycle()
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val matchThreshold = 0.60f
            if (bestMatchContactId != null && highestSimilarity > matchThreshold) {
                personRepository.updateContactMatch(person.personId, bestMatchContactId!!, highestSimilarity)
            }
        }
    }
}