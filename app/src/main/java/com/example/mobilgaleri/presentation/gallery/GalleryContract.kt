//com/example/mobilgaleri/presentation/gallery/GalleryContract.kt
package com.example.mobilgaleri.presentation.gallery

import com.example.mobilgaleri.domain.model.PersonDetails
import com.example.mobilgaleri.domain.model.Photo

// ClusteringUpdate sealed class'ı aynı kalıyor...
sealed class ClusteringUpdate {
    data class Progress(val processed: Int, val total: Int) : ClusteringUpdate()
    // DEĞİŞİKLİK: 'object' yerine 'data class' oldu ve taranan yüz sayısını taşıyor.
    data class Completed(val newFacesScanned: Int) : ClusteringUpdate()
}

interface GalleryContract {
    interface View {
        fun showLoading(isLoading: Boolean)
        fun showError(message: String)
        fun updateProgress(processed: Int, total: Int)
        fun hideProgress()

        fun showContactsMatchPrompt()


        // DEĞİŞİKLİK: Eski fonksiyonlar yerine bu yeni fonksiyon geldi.
        // Hem kümeleri hem de kişi detaylarını tek seferde günceller.
        fun displayClusters(clusters: Map<Int, List<Photo>>, details: Map<Int, PersonDetails>)

        // YENİ: Tarama bittiğinde özet bir mesaj göstermek için.
        fun showSummaryToast(message: String)
    }

    interface Presenter {
        fun attach(view: View)
        fun detach()
        fun loadAndSyncClusters()
        fun matchFacesWithContacts()

    }
}