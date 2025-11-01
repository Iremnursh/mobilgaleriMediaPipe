//com/example/mobilgaleri/data/local/db/AppDatabase.kt
package com.example.mobilgaleri.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.mobilgaleri.data.local.dao.FaceDao
import com.example.mobilgaleri.data.local.dao.PhotoDao
import com.example.mobilgaleri.data.local.entity.FaceEntity
import com.example.mobilgaleri.data.local.entity.PersonEntity
import com.example.mobilgaleri.data.local.entity.PhotoEntity
import androidx.room.TypeConverters
import com.example.mobilgaleri.data.local.dao.PersonDao

import androidx.room.migration.Migration // YENİ IMPORT
import androidx.sqlite.db.SupportSQLiteDatabase // YENİ IMPORT

@Database(
    entities = [PhotoEntity::class, FaceEntity::class, PersonEntity::class],
    version = 3, // <-- SÜRÜMÜ 2'DEN 3'E YÜKSELT
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun faceDao(): FaceDao   // <-- eklendi
    abstract fun personDao(): PersonDao
}

// VERİTABANI SÜRÜM 2'DEN 3'E GEÇİŞ İÇİN GEREKLİ KOD
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE persons ADD COLUMN matchedContactId INTEGER")
        db.execSQL("ALTER TABLE persons ADD COLUMN matchConfidence REAL")
    }
}
