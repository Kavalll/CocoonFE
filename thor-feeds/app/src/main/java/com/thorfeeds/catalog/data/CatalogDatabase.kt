package com.thorfeeds.catalog.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "releases",
    indices = [
        Index("feedId"),
        Index("canonicalTitle"),
        Index(value = ["feedId", "rawTitle", "uploadDate"], unique = true),
    ],
)
data class ReleaseEntity(
    @PrimaryKey val id: String,
    val feedId: String,
    val rawTitle: String,
    val canonicalTitle: String,
    val uploadDate: String,
    val uploadEpochMs: Long,
    val fileSizeLabel: String,
    val fileSizeBytes: Long?,
    val urisJson: String,
)

@Dao
abstract class ReleaseDao {
    @Query("SELECT * FROM releases")
    abstract fun observeAll(): Flow<List<ReleaseEntity>>

    @Query("SELECT * FROM releases WHERE id = :id LIMIT 1")
    abstract suspend fun get(id: String): ReleaseEntity?

    @Query("SELECT canonicalTitle FROM releases")
    abstract suspend fun canonicalTitles(): List<String>

    @Query("DELETE FROM releases WHERE feedId = :feedId")
    abstract suspend fun deleteFeed(feedId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(rows: List<ReleaseEntity>)

    @Transaction
    open suspend fun replaceFeed(feedId: String, rows: List<ReleaseEntity>) {
        deleteFeed(feedId)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Database(entities = [ReleaseEntity::class], version = 1, exportSchema = false)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun releases(): ReleaseDao
}
