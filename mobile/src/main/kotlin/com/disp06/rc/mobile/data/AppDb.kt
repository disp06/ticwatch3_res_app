package com.disp06.rc.mobile.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Raw batch as received from the watch (permanent). Payload is the BatchCodec encoding. */
@Entity(tableName = "sensor_batch", indices = [Index(value = ["batch_key"], unique = true), Index("start_timestamp"), Index("end_timestamp")])
data class SensorBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_key") val key: String,
    @ColumnInfo(name = "start_timestamp") val startMs: Long,
    @ColumnInfo(name = "end_timestamp") val endMs: Long,
    @ColumnInfo(name = "sampling_rate") val samplingRate: Int,
    @ColumnInfo(name = "ppg_source") val ppgSource: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    @ColumnInfo(name = "received_timestamp") val receivedMs: Long,
)

/** Light projection without the BLOB (Room's CursorWindow is 2 MB). */
data class BatchInfo(
    val id: Long,
    @ColumnInfo(name = "batch_key") val key: String,
    @ColumnInfo(name = "start_timestamp") val startMs: Long,
    @ColumnInfo(name = "end_timestamp") val endMs: Long,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
)

/** One 5-minute analysis window. */
@Entity(tableName = "metrics", indices = [Index(value = ["timestamp"], unique = true)])
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "heart_rate") val heartRate: Double?,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    @ColumnInfo(name = "stress_score") val stressScore: Double?,
    val activity: String,
    val steps: Int?,
    val quality: Double,
    @ColumnInfo(name = "motion_fraction") val motionFraction: Double,
    val source: String,
)

@Entity(tableName = "diary_entry", indices = [Index("timestamp"), Index(value = ["auto_key"], unique = true)])
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** 'auto' or 'manual' */
    val type: String,
    /** hr, hrv, stress, activity, note, reminder, alert */
    val metric: String,
    val value: Double?,
    val note: String = "",
    /** Comma separated. */
    val tags: String = "",
    /** Natural key for auto entries so re-processing updates instead of duplicating. */
    @ColumnInfo(name = "auto_key") val autoKey: String? = null,
)

@Entity(tableName = "reminder_rule")
data class ReminderRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 'time', 'threshold', 'interval' */
    val type: String,
    @ColumnInfo(name = "trigger_value") val triggerValue: String,
    val message: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "last_fired") val lastFired: Long = 0,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: Long = 0,
)

/** Commands waiting for the watch to become reachable. */
@Entity(tableName = "pending_command")
data class PendingCommand(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    val created: Long = System.currentTimeMillis(),
)

@Dao
interface BatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(b: SensorBatchEntity): Long

    @Query("SELECT COUNT(*) FROM sensor_batch WHERE batch_key = :key")
    suspend fun exists(key: String): Int

    @Query("SELECT id, batch_key, start_timestamp, end_timestamp, sample_count FROM sensor_batch WHERE end_timestamp > :from AND start_timestamp < :to ORDER BY start_timestamp")
    suspend fun overlapping(from: Long, to: Long): List<BatchInfo>

    @Query("SELECT id, batch_key, start_timestamp, end_timestamp, sample_count FROM sensor_batch ORDER BY start_timestamp")
    suspend fun allInfo(): List<BatchInfo>

    @Query("SELECT payload FROM sensor_batch WHERE id = :id")
    suspend fun payload(id: Long): ByteArray

    @Query("SELECT MAX(received_timestamp) FROM sensor_batch")
    fun lastReceived(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM sensor_batch")
    fun count(): Flow<Int>

    @Query("DELETE FROM sensor_batch")
    suspend fun clear()
}

@Dao
interface MetricDao {
    @Upsert
    suspend fun upsert(m: List<MetricEntity>)

    @Query("SELECT * FROM metrics WHERE timestamp = :ts")
    suspend fun at(ts: Long): MetricEntity?

    @Query("SELECT * FROM metrics WHERE timestamp >= :from ORDER BY timestamp")
    fun since(from: Long): Flow<List<MetricEntity>>

    @Query("SELECT * FROM metrics WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    suspend fun range(from: Long, to: Long): List<MetricEntity>

    @Query("SELECT * FROM metrics ORDER BY timestamp DESC LIMIT 1")
    fun latest(): Flow<MetricEntity?>

    @Query("SELECT * FROM metrics WHERE heart_rate IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    fun latestWithHr(): Flow<MetricEntity?>

    @Query("SELECT * FROM metrics ORDER BY timestamp")
    suspend fun all(): List<MetricEntity>

    @Query("DELETE FROM metrics")
    suspend fun clear()
}

@Dao
interface DiaryDao {
    @Insert
    suspend fun insert(e: DiaryEntry): Long

    @Upsert
    suspend fun upsertAuto(e: List<DiaryEntry>)

    @Query("SELECT id FROM diary_entry WHERE auto_key = :key")
    suspend fun idForKey(key: String): Long?

    @Update
    suspend fun update(e: DiaryEntry)

    @Query("DELETE FROM diary_entry WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """SELECT * FROM diary_entry WHERE timestamp >= :from
           AND (:metric = '' OR metric = :metric)
           AND (:query = '' OR note LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' OR metric LIKE '%' || :query || '%')
           ORDER BY timestamp DESC LIMIT 500"""
    )
    fun search(from: Long, metric: String, query: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entry ORDER BY timestamp")
    suspend fun all(): List<DiaryEntry>

    @Query("DELETE FROM diary_entry")
    suspend fun clear()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM reminder_rule ORDER BY id")
    fun observe(): Flow<List<ReminderRule>>

    @Query("SELECT * FROM reminder_rule ORDER BY id")
    suspend fun all(): List<ReminderRule>

    @Query("SELECT * FROM reminder_rule WHERE id = :id")
    suspend fun get(id: Long): ReminderRule?

    @Upsert
    suspend fun upsert(r: ReminderRule): Long

    @Query("DELETE FROM reminder_rule WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CommandDao {
    @Insert
    suspend fun insert(c: PendingCommand)

    @Query("SELECT * FROM pending_command ORDER BY id")
    suspend fun all(): List<PendingCommand>

    @Query("DELETE FROM pending_command WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_command WHERE created < :before")
    suspend fun expire(before: Long)
}

@Database(
    entities = [SensorBatchEntity::class, MetricEntity::class, DiaryEntry::class, ReminderRule::class, PendingCommand::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun batches(): BatchDao
    abstract fun metrics(): MetricDao
    abstract fun diary(): DiaryDao
    abstract fun rules(): RuleDao
    abstract fun commands(): CommandDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "research.db")
                .build().also { instance = it }
        }
    }
}
