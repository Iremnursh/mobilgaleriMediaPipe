//com/example/mobilgaleri/data/local/dao/PhotoDao.kt
package com.example.mobilgaleri.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mobilgaleri.data.local.entity.PhotoEntity

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PhotoEntity>)

    @Query("SELECT * FROM photo ORDER BY dateTaken DESC")
    suspend fun all(): List<PhotoEntity>
}
