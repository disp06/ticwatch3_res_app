package com.disp06.rc.wear.data

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.disp06.rc.shared.BatchCodec
import com.disp06.rc.shared.Chunker
import com.disp06.rc.shared.Protocol
import com.disp06.rc.wear.Prefs
import com.disp06.rc.wear.StatusReporter
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object Uploader {
    private const val TAG = "RC.Uploader"
    /** Re-put a batch if no ack arrived within this time (phone may have missed it). */
    private const val RESEND_AFTER_MS = 45 * 60_000L
    private const val BUFFER_HOURS = 24L

    suspend fun phoneReachable(ctx: Context): Boolean {
        val cap = runCatching {
            Wearable.getCapabilityClient(ctx)
                .getCapability(Protocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
                .nodes.isNotEmpty()
        }.getOrDefault(false)
        if (cap) return true
        // Fallback when capability info isn't available: any connected node (the paired phone).
        return runCatching { Wearable.getNodeClient(ctx).connectedNodes.await().isNotEmpty() }.getOrDefault(false)
    }

    /** Puts pending batches into the Data Layer as <= 50 KB chunks. Returns number of batches sent. */
    suspend fun uploadPending(ctx: Context, force: Boolean = false): Int {
        if (!phoneReachable(ctx)) {
            Log.i(TAG, "Phone not reachable; keeping data buffered")
            return 0
        }
        val dao = WatchDb.get(ctx).batches()
        val data = Wearable.getDataClient(ctx)
        val now = System.currentTimeMillis()
        val resendBefore = if (force) now else now - RESEND_AFTER_MS
        val wl = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rc:upload")
        wl.acquire(60_000)
        var sent = 0
        try {
            while (true) {
                val batch = dao.pendingToSend(resendBefore, 6)
                if (batch.isEmpty()) break
                for (b in batch) {
                    putBatch(data, b.key, b.payload)
                    dao.markSent(b.id, now)
                    sent++
                }
            }
        } finally {
            if (wl.isHeld) wl.release()
        }
        if (sent > 0) Prefs(ctx).lastUploadMs = now
        Log.i(TAG, "Put $sent batches into the Data Layer")
        return sent
    }

    private suspend fun putBatch(data: DataClient, key: String, payload: ByteArray) {
        val chunks = Chunker.split(payload)
        val crc = BatchCodec.crc(payload)
        chunks.forEachIndexed { seq, bytes ->
            val req = PutDataMapRequest.create("${Protocol.PATH_BATCH_PREFIX}$key/$seq").apply {
                dataMap.putString(Protocol.KEY_BATCH_KEY, key)
                dataMap.putInt(Protocol.KEY_SEQ, seq)
                dataMap.putInt(Protocol.KEY_TOTAL, chunks.size)
                dataMap.putLong(Protocol.KEY_CRC, crc)
                dataMap.putByteArray(Protocol.KEY_BYTES, bytes)
            }.asPutDataRequest().setUrgent()
            data.putDataItem(req).await()
        }
    }

    /** Called when the phone acknowledges a stored batch. */
    suspend fun onAck(ctx: Context, key: String) {
        WatchDb.get(ctx).batches().markUploaded(key)
        runCatching {
            Wearable.getDataClient(ctx).deleteDataItems(
                Uri.parse("wear://*${Protocol.PATH_BATCH_PREFIX}$key/"), DataClient.FILTER_PREFIX
            ).await()
        }
    }

    fun prune(ctx: Context) {
        val now = System.currentTimeMillis()
        WatchDb.get(ctx).batches().prune(uploadedBefore = now - 3_600_000L, hardLimit = now - BUFFER_HOURS * 3_600_000L)
    }

    fun enqueueUpload(ctx: Context, force: Boolean = false) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "upload",
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>().setInputData(androidx.work.workDataOf("force" to force)).build(),
        )
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Uploader.uploadPending(applicationContext, inputData.getBoolean("force", false))
        StatusReporter.send(applicationContext)
        return Result.success()
    }
}

/** Periodic (15 min) housekeeping: retry uploads that were never acked, prune the rolling buffer. */
class HousekeepingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        Uploader.prune(applicationContext)
        if (System.currentTimeMillis() - prefs.lastUploadMs >= prefs.syncMinutes * 60_000L - 60_000L) {
            Uploader.uploadPending(applicationContext)
        }
        return Result.success()
    }
}
