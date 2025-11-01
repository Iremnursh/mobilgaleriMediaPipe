//com/example/mobilgaleri/domain/usecase/SyncAndClusterNewFacesUseCase.kt
package com.example.mobilgaleri.domain.usecase

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.example.mobilgaleri.data.face.MediaPipeFaceDetector
import com.example.mobilgaleri.data.face.MediaPipeFaceResult
import com.example.mobilgaleri.data.face.TfliteEmbedder
import com.example.mobilgaleri.data.local.entity.FaceEntity
import com.example.mobilgaleri.data.local.entity.PersonEntity
import com.example.mobilgaleri.domain.model.Photo
import com.example.mobilgaleri.domain.repository.FaceRepository
import com.example.mobilgaleri.domain.repository.GalleryRepository
import com.example.mobilgaleri.domain.repository.PersonRepository
import com.example.mobilgaleri.presentation.gallery.ClusteringUpdate
import com.example.mobilgaleri.util.toSoftwareArgb8888Mutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.atan2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.DBSCANClusterer
import org.apache.commons.math3.ml.distance.DistanceMeasure
import kotlin.math.min
import kotlin.math.sqrt
import android.graphics.BitmapFactory

// ADDED (debug pipeline ile aynı hizalama/embedding parçaları)
import com.example.mobilgaleri.data.face.MediaPipeFaceLandmarker       // ADDED
import com.example.mobilgaleri.util.alignFace112FiveOrThree            // ADDED
import com.example.mobilgaleri.util.averageEmbeddings                  // ADDED
import com.example.mobilgaleri.util.flipX                              // ADDED

class SyncAndClusterNewFacesUseCase(
    private val galleryRepo: GalleryRepository,
    private val faceRepo: FaceRepository,
    private val personRepo: PersonRepository,
    private val cr: ContentResolver,
    private val detector: MediaPipeFaceDetector,
    private val embedder: TfliteEmbedder,
    private val onlineClusterer: ClusterFacesUseCase,
    private val landmarker: MediaPipeFaceLandmarker                    // ADDED
) {
    private class CentroidWrapper(val personEntity: PersonEntity) : Clusterable {
        override fun getPoint(): DoubleArray = personEntity.centroid.map { it.toDouble() }.toDoubleArray()
    }

    private class CosineDistance : DistanceMeasure {
        override fun compute(a: DoubleArray, b: DoubleArray): Double {
            var dot=0.0; var na=0.0; var nb=0.0
            for(i in a.indices){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i] }
            val den=sqrt(na)*sqrt(nb)
            val sim=if(den==0.0)0.0 else dot/den
            return 1.0 - sim
        }
    }

    fun run(): Flow<ClusteringUpdate> = flow {
        val allDevicePhotos = galleryRepo.getAllPhotos()
        val scannedPhotoIds = faceRepo.getAllScannedPhotoIds().toSet()
        val newPhotosToScan = allDevicePhotos.filter { !scannedPhotoIds.contains(it.id) }

        if (newPhotosToScan.isEmpty()) {
            emit(ClusteringUpdate.Completed(0))
            return@flow
        }

        val totalPhotos = newPhotosToScan.size
        var processedCount = 0

        // CHANGED: sıralı tarama (stabil bellek/ısı için)
        Log.d("SyncUseCase", "Starting sequential scan for $totalPhotos new photos...")
        for (photo in newPhotosToScan) {
            try {
                processPhoto(photo)
            } catch (e: Exception) {
                Log.e("SyncUseCase", "Failed to process photo ${photo.id} in loop: ${e.localizedMessage}", e)
            }
            processedCount++
            if (processedCount % 20 == 0 || processedCount == totalPhotos) {
                emit(ClusteringUpdate.Progress(processedCount, totalPhotos))
            }
        }

        // DBSCAN ince ayar (aynı)
        Log.d("SyncUseCase", "Scan finished. Starting DBSCAN clustering...")
        val roughPersonClusters = personRepo.getAllPersons()
        if (roughPersonClusters.size <= 1) {
            Log.d("SyncUseCase", "DBSCAN skipped (not enough clusters).")
            emit(ClusteringUpdate.Completed(totalPhotos))
            return@flow
        }

        val dbscan = DBSCANClusterer<CentroidWrapper>(0.40, 1, CosineDistance())
        val finalClusters = dbscan.cluster(roughPersonClusters.map { CentroidWrapper(it) })

        val faceUpdates = mutableListOf<FaceEntity>()
        val personsToDelete = mutableListOf<PersonEntity>()

        for (cluster in finalClusters) {
            if (cluster.points.size > 1) {
                val mainPerson = cluster.points.first().personEntity
                val mergedPersons = cluster.points.drop(1).map { it.personEntity }
                personsToDelete.addAll(mergedPersons)

                val mergedPersonIds = mergedPersons.map { it.personId }
                val facesToUpdate = faceRepo.getFacesWithPersonIds(mergedPersonIds)
                facesToUpdate.forEach { it.personOwnerId = mainPerson.personId }
                faceUpdates.addAll(facesToUpdate)
            }
        }

        if (faceUpdates.isNotEmpty()) {
            Log.d("SyncUseCase", "DBSCAN merging ${faceUpdates.size} faces into ${finalClusters.size} clusters.")
            faceRepo.updateFaces(faceUpdates)
        }
        if (personsToDelete.isNotEmpty()) {
            Log.d("SyncUseCase", "DBSCAN deleting ${personsToDelete.size} redundant clusters.")
            personRepo.deletePersons(personsToDelete)
        }

        Log.d("SyncUseCase", "Clustering completed.")
        emit(ClusteringUpdate.Completed(totalPhotos))
    }.flowOn(Dispatchers.IO).flowOn(Dispatchers.IO)

    // ADDED — Debug hattı ile birebir embedding pipeline
    private fun embedFaceHighQualityFromDetectionBitmap(
        detectionBitmap: Bitmap,
        detBox: Rect,
        landmarker: MediaPipeFaceLandmarker,
        embedder: TfliteEmbedder,
        alignMargin: Float = 1.10f,
        flipAverage: Boolean = true
    ): FloatArray? {
        val padX = (detBox.width() * 0.12f).toInt()
        val padY = (detBox.height() * 0.12f).toInt()
        val l = (detBox.left - padX).coerceAtLeast(0)
        val t = (detBox.top - padY).coerceAtLeast(0)
        val r = (detBox.right + padX).coerceAtMost(detectionBitmap.width)
        val b = (detBox.bottom + padY).coerceAtMost(detectionBitmap.height)
        if (r <= l || b <= t) return null

        val roi = Bitmap.createBitmap(detectionBitmap, l, t, r - l, b - t)

        val five = landmarker.detectFivePoints(roi)
        val aligned112 = if (five != null) {
            alignFace112FiveOrThree(
                src = roi,
                leftEye    = five.leftEye.x to five.leftEye.y,
                rightEye   = five.rightEye.x to five.rightEye.y,
                nose       = five.noseTip.x to five.noseTip.y,
                mouthLeft  = five.mouthLeft.x to five.mouthLeft.y,
                mouthRight = five.mouthRight.x to five.mouthRight.y,
                margin     = alignMargin
            ).bitmap
        } else {
            // 5 nokta bulunmazsa güvenli yedek
            Bitmap.createScaledBitmap(roi, 112, 112, true)
        }

        val e = embedder.embedAligned112(aligned112)
        if (!flipAverage) return e

        val ef = embedder.embedAligned112(aligned112.flipX())
        return averageEmbeddings(e, ef)
    }

    private suspend fun processPhoto(photo: Photo) {
        val photoUri = Uri.parse(photo.contentUri)
        val (detectionBitmap, scale) = loadScaledBitmapForDetection(cr, photoUri, 1024)
        if (detectionBitmap == null) {
            Log.w("SyncUseCase", "Scaled bitmap failed to load for ${photo.id}")
            saveDummyFaceRecord(photo.id)
            return
        }

        var aFaceWasSuccessfullySaved = false
        try {
            if (detectionBitmap.width < 32 || detectionBitmap.height < 32) {
                Log.w("SyncUseCase", "Scaled bitmap is too small for detection: ${photo.id}")
                return
            }

            val faces: List<MediaPipeFaceResult> = detector.detect(detectionBitmap)
            if (faces.isEmpty()) return

            for (face in faces.take(1)) { // tek yüz (ilk)
                if (!isFaceGoodQuality(face)) {
                    Log.d("SyncUseCase", "Skipping face, low confidence/size: ${face.detectionConfidence}")
                    continue
                }

                val emb = embedFaceHighQualityFromDetectionBitmap(
                    detectionBitmap = detectionBitmap,
                    detBox = face.boundingBox,
                    landmarker = landmarker,
                    embedder = embedder,
                    alignMargin = 1.10f,
                    flipAverage = true
                ) ?: continue

                // Kayıt ve cluster
                val cid = onlineClusterer.assign(emb)
                onlineClusterer.getCentroid(cid)?.let { updatedCentroid ->
                    personRepo.upsert(PersonEntity(personId = cid.toLong(), centroid = updatedCentroid))
                }

                val originalFaceRect = scaleRect(face.boundingBox, scale) // UI için
                faceRepo.insertFace(
                    FaceEntity(
                        photoOwnerId = photo.id,
                        personOwnerId = cid.toLong(),
                        embedding = emb,
                        left = originalFaceRect.left,
                        top = originalFaceRect.top,
                        right = originalFaceRect.right,
                        bottom = originalFaceRect.bottom
                    )
                )
                aFaceWasSuccessfullySaved = true
            }
        } catch (e: Exception) {
            Log.e("SyncUseCase", "Error processing photo ${photo.id}: ${e.localizedMessage}", e)
        } finally {
            if (!aFaceWasSuccessfullySaved) {
                saveDummyFaceRecord(photo.id)
            }
            detectionBitmap.recycle()
        }
    }

    private suspend fun saveDummyFaceRecord(photoId: Long) {
        val dummyFaceRecord = FaceEntity(
            photoOwnerId = photoId,
            personOwnerId = -1,
            embedding = FloatArray(0),
            left = 0, top = 0, right = 0, bottom = 0
        )
        faceRepo.insertFace(dummyFaceRecord)
    }

    // CHANGED: min box filtresi eklendi
    private fun isFaceGoodQuality(face: MediaPipeFaceResult): Boolean {
        val okConf = (face.detectionConfidence ?: 0.0f) >= 0.5f
        val minBox = 48
        val okSize = face.boundingBox.width() >= minBox && face.boundingBox.height() >= minBox
        return okConf && okSize
    }

    private fun scaleRect(rect: Rect, scale: Float): Rect {
        return Rect(
            (rect.left * scale).toInt(),
            (rect.top * scale).toInt(),
            (rect.right * scale).toInt(),
            (rect.bottom * scale).toInt()
        )
    }

    private fun loadScaledBitmapForDetection(cr: ContentResolver, uri: Uri, reqSize: Int): Pair<Bitmap?, Float> {
        try {
            var inputStream = cr.openInputStream(uri) ?: return Pair(null, 1f)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            inputStream.use { BitmapFactory.decodeStream(it, null, options) }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return Pair(null, 1f)

            var inSampleSize = 1
            if (originalHeight > reqSize || originalWidth > reqSize) {
                val halfHeight: Int = originalHeight / 2
                val halfWidth: Int = originalWidth / 2
                while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                    inSampleSize *= 2
                }
            }

            val finalOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            inputStream = cr.openInputStream(uri) ?: return Pair(null, 1f)
            val bitmap = inputStream.use { BitmapFactory.decodeStream(it, null, finalOptions) }
            val mutableBitmap = if (bitmap?.isMutable == true) bitmap else bitmap?.copy(Bitmap.Config.ARGB_8888, true)
            return Pair(mutableBitmap, inSampleSize.toFloat())
        } catch (e: Exception) {
            Log.e("SyncUseCase", "Error loading scaled bitmap: ${e.localizedMessage}", e)
            return Pair(null, 1f)
        } catch (oom: OutOfMemoryError) {
            Log.e("SyncUseCase", "OOM loading scaled bitmap", oom)
            return Pair(null, 1f)
        }
    }
}
