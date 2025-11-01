//com/example/mobilgaleri/data/local/dao/FaceDao.kt
package com.example.mobilgaleri.data.local.dao

import androidx.room.*
import com.example.mobilgaleri.data.local.entity.FaceEntity

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FaceEntity)

    @Update
    suspend fun updateAll(faces: List<FaceEntity>)

    @Query("SELECT DISTINCT photoOwnerId FROM faces")
    suspend fun getAllScannedPhotoIds(): List<Long>

    @Query("SELECT * FROM faces WHERE personOwnerId IN (:personIds)")
    suspend fun getFacesWithPersonIds(personIds: List<Long>): List<FaceEntity>

    // DÜZELTME: getAllFaces'in ihtiyaç duyduğu fonksiyon
    @Query("SELECT * FROM faces")
    suspend fun all(): List<FaceEntity>
}
