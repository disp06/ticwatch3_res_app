package com.disp06.rc.wear.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Rolling buffer of encoded batches (brief §6 "Watch DB"). The PPG/IMU/gyro arrays are
 * stored as one compressed [payload] (see BatchCodec) instead of three separate BLOBs, so a
 * batch can be sent to the phone without re-encoding.
 */
@Entity(tableName = "sensor_batch", indices = [Index("is_uploaded"), Index("start_timestamp")])
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_key") val key: String,
    @ColumnInfo(name = "start_timestamp") val startMs: Long,
    @ColumnInfo(name = "end_timestamp") val endMs: Long,
    @ColumnInfo(name = "sampling_rate") val samplingRate: Int,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    @ColumnInfo(name = "is_uploaded") val isUploaded: Boolean = false,
    /** Last time chunks were put into the Data Layer (0 = never). Ack from phone sets is_uploaded. */
    @ColumnInfo(name = "sent_at") val sentAt: Long = 0,
)

data class BufferStats(val pending: Int, val pendingBytes: Long?, val oldestPending: Long?)

@Dao
interface BatchDao {
    @Insert
    fun insert(b: BatchEntity): Long

    @Query("SELECT * FROM sensor_batch WHERE is_uploaded = 0 AND sent_at < :resendBefore ORDER BY start_timestamp LIMIT :limit")
    fun pendingToSend(resendBefore: Long, limit: Int): List<BatchEntity>

    @Query("UPDATE sensor_batch SET sent_at = :t WHERE id = :id")
    fun markSent(id: Long, t: Long)

    @Query("UPDATE sensor_batch SET is_uploaded = 1, payload = x'' WHERE batch_key = :key")
    fun markUploaded(key: String): Int

    @Query("DELETE FROM sensor_batch WHERE (is_uploaded = 1 AND end_timestamp < :uploadedBefore) OR end_timestamp < :hardLimit")
    fun prune(uploadedBefore: Long, hardLimit: Long): Int

    @Query("SELECT COUNT(*) AS pending, SUM(LENGTH(payload)) AS pendingBytes, MIN(start_timestamp) AS oldestPending FROM sensor_batch WHERE is_uploaded = 0")
    fun stats(): BufferStats
}

@Database(entities = [BatchEntity::class], version = 1, exportSchema = false)
abstract class WatchDb : RoomDatabase() {
    abstract fun batches(): BatchDao

    companion object {
        @Volatile private var instance: WatchDb? = null
        fun get(ctx: Context): WatchDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, WatchDb::class.java, "watch.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
