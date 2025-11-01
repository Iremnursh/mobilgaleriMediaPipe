//com/example/mobilgaleri/di/AppModule.kt
package com.example.mobilgaleri.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.example.mobilgaleri.data.face.MediaPipeFaceDetector
import com.example.mobilgaleri.data.face.TfliteEmbedder
import com.example.mobilgaleri.data.gallery.GalleryRepositoryImpl
import com.example.mobilgaleri.data.local.dao.FaceDao
import com.example.mobilgaleri.data.local.dao.PersonDao
import com.example.mobilgaleri.data.local.db.AppDatabase
import com.example.mobilgaleri.data.repository.ContactsRepositoryImpl
import com.example.mobilgaleri.data.repository.FaceRepositoryImpl
import com.example.mobilgaleri.data.repository.PersonRepositoryImpl
import com.example.mobilgaleri.domain.repository.ContactsRepository
import com.example.mobilgaleri.domain.repository.FaceRepository
import com.example.mobilgaleri.domain.repository.GalleryRepository
import com.example.mobilgaleri.domain.repository.PersonRepository
import com.example.mobilgaleri.domain.usecase.ClusterFacesUseCase
import com.example.mobilgaleri.domain.usecase.GetPersonDetailsUseCase
import com.example.mobilgaleri.domain.usecase.GetSavedClustersUseCase
import com.example.mobilgaleri.domain.usecase.MatchContactsUseCase
import com.example.mobilgaleri.domain.usecase.SyncAndClusterNewFacesUseCase
import com.example.mobilgaleri.data.face.MediaPipeFaceLandmarker  // ADDED

/**
 * Proje bağımlılıklarını sağlayan ana modül.
 * Basitlik için Hilt/Dagger yerine manuel DI (Singleton Object) kullanır.
 * Bu yapı, tüm bağımlılıkların tek bir yerden yönetilmesini sağlar.
 */
object AppModule {

    // --- VERİTABANI VE DAO SAĞLAYICILARI (Singleton Veritabanı) ---

    @Volatile
    private var INSTANCE: AppDatabase? = null

    /**
     * Veritabanı nesnesini singleton (tekil) olarak sağlar.
     * Uygulama yaşam döngüsü boyunca sadece bir tane AppDatabase örneği oluşturulur.
     */
    @Synchronized
    fun provideDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "gallery_database"
            )
                .fallbackToDestructiveMigration()
                .build()
            INSTANCE = instance
            instance
        }
    }

    fun provideFaceDao(database: AppDatabase): FaceDao = database.faceDao()
    fun providePersonDao(database: AppDatabase): PersonDao = database.personDao()

    // --- SİSTEM SERVİSLERİ ---

    fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

    // --- REPOSITORY (VERİ KATMANI) SAĞLAYICILARI ---

    fun provideGalleryRepository(cr: ContentResolver): GalleryRepository = GalleryRepositoryImpl(cr)
    fun provideContactsRepository(cr: ContentResolver): ContactsRepository = ContactsRepositoryImpl(cr)

    fun provideFaceRepository(faceDao: FaceDao): FaceRepository {
        return FaceRepositoryImpl(faceDao) // data/repository/FaceRepositoryImpl.kt dosyanı kullanır
    }

    fun providePersonRepository(personDao: PersonDao): PersonRepository {
        return PersonRepositoryImpl(personDao) // data/repository/PersonRepositoryImpl.kt dosyanı kullanır
    }

    // --- AI MODELLERİ VE KÜMELEYİCİ (SINGLETON) ---

    @Volatile private var mediaPipeDetector: MediaPipeFaceDetector? = null
    @Volatile private var tfliteEmbedder: TfliteEmbedder? = null
    @Volatile private var clusterer: ClusterFacesUseCase? = null

    @Volatile private var faceLandmarker: MediaPipeFaceLandmarker? = null   // ADDED


    @Synchronized
    fun provideFaceDetector(context: Context): MediaPipeFaceDetector {
        return mediaPipeDetector ?: synchronized(this) {
            MediaPipeFaceDetector(context.applicationContext).also { mediaPipeDetector = it }
        }
    }

    @Synchronized
    fun provideEmbedder(context: Context): TfliteEmbedder {
        return tfliteEmbedder ?: synchronized(this) {
            TfliteEmbedder(context.applicationContext).also { tfliteEmbedder = it }
        }
    }

    @Synchronized
    fun provideClusterer(): ClusterFacesUseCase {
        return clusterer ?: synchronized(this) {
            ClusterFacesUseCase().also { clusterer = it }
        }
    }

    // ADDED: MediaPipe 5-nokta landmarker
    @Synchronized
    fun provideFaceLandmarker(context: Context): MediaPipeFaceLandmarker {
        return faceLandmarker ?: synchronized(this) {
            MediaPipeFaceLandmarker(context.applicationContext).also { faceLandmarker = it }
        }
    }

    // (opsiyonel) kapatıcı
    fun closeVision() {
        faceLandmarker?.close()
        faceLandmarker = null
        mediaPipeDetector?.close()
        mediaPipeDetector = null
    }

    // --- USECASE (İŞ MANTIĞI) SAĞLAYICILARI ---

    // Not: UseCase'ler state tutmadığı için her istendiğinde yeniden oluşturulabilir.

    fun provideSyncAndClusterNewFacesUseCase(context: Context): SyncAndClusterNewFacesUseCase {
        val appContext = context.applicationContext
        val db = provideDatabase(appContext)
        val cr = provideContentResolver(appContext)

        return SyncAndClusterNewFacesUseCase(
            galleryRepo = provideGalleryRepository(cr),
            faceRepo = provideFaceRepository(provideFaceDao(db)),
            personRepo = providePersonRepository(providePersonDao(db)),
            cr = cr,
            detector = provideFaceDetector(appContext), // Yeni dedektör
            embedder = provideEmbedder(appContext),
            onlineClusterer = provideClusterer(),
            landmarker = provideFaceLandmarker(appContext)      // ADDED

        )
    }

    fun provideGetSavedClustersUseCase(context: Context): GetSavedClustersUseCase {
        val appContext = context.applicationContext
        val db = provideDatabase(appContext)
        val cr = provideContentResolver(appContext)

        return GetSavedClustersUseCase(
            // Constructor'daki isimlerle eşleşen parametre adları
            faceRepository = provideFaceRepository(provideFaceDao(db)),
            galleryRepository = provideGalleryRepository(cr),
            personRepository = providePersonRepository(providePersonDao(db))
        )
    }

    fun provideGetPersonDetailsUseCase(context: Context): GetPersonDetailsUseCase {
        val appContext = context.applicationContext
        val db = provideDatabase(appContext)
        val cr = provideContentResolver(appContext)

        return GetPersonDetailsUseCase(
            // Constructor'daki isimlerle eşleşen parametre adları
            personRepository = providePersonRepository(providePersonDao(db)),
            contactsRepository = provideContactsRepository(cr)
        )
    }

    fun provideMatchContactsUseCase(context: Context): MatchContactsUseCase {
        val appContext = context.applicationContext
        val db = provideDatabase(appContext)
        val cr = provideContentResolver(appContext)

        return MatchContactsUseCase(
            // Constructor'daki isimlerle eşleşen parametre adları
            personRepository = providePersonRepository(providePersonDao(db)),
            contactsRepository = provideContactsRepository(cr),
            embedder = provideEmbedder(appContext),
            contentResolver = cr,
            landmarker = provideFaceLandmarker(appContext)
        )
    }
}
