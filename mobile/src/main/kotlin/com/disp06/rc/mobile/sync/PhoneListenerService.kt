package com.disp06.rc.mobile.sync

import android.net.Uri
import android.util.Log
import com.disp06.rc.mobile.data.AppDb
import com.disp06.rc.mobile.data.DiaryEntry
import com.disp06.rc.mobile.data.SensorBatchEntity
import com.disp06.rc.shared.BatchCodec
import com.disp06.rc.shared.Chunker
import com.disp06.rc.shared.Protocol
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Receives batch chunks (DataItems) and status/reminder messages from the watch. The Data Layer
 * keeps every chunk until it is deleted, so reassembly needs no local chunk storage: on each
 * chunk we look up all chunks of that batch, and once complete we verify CRC, decode, store,
 * ack, and delete the DataItems. Nothing is deleted on the watch until the ack arrives.
 */
class PhoneListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val keys = mutableSetOf<Pair<String, String>>()
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED) continue
            val path = e.dataItem.uri.path ?: continue
            if (!path.startsWith(Protocol.PATH_BATCH_PREFIX)) continue
            val map = DataMapItem.fromDataItem(e.dataItem).dataMap
            keys += e.dataItem.uri.host!! to map.getString(Protocol.KEY_BATCH_KEY, "")
        }
        // Callbacks run on a background thread; blocking here is the documented pattern.
        runBlocking { for ((node, key) in keys) if (key.isNotEmpty()) tryAssemble(node, key) }
    }

    private suspend fun tryAssemble(nodeId: String, key: String) {
        val db = AppDb.get(this)
        val client = Wearable.getDataClient(this)
        val prefix = Uri.parse("wear://$nodeId${Protocol.PATH_BATCH_PREFIX}$key/")
        if (db.batches().exists(key) > 0) { // duplicate (watch re-sent before our ack arrived)
            WatchLink.ack(this, nodeId, key)
            runCatching { client.deleteDataItems(prefix, DataClient.FILTER_PREFIX).await() }
            return
        }
        val items = client.getDataItems(prefix, DataClient.FILTER_PREFIX).await()
        val chunks = HashMap<Int, ByteArray>()
        var total = -1
        var crc = 0L
        try {
            for (item in items) {
                val m = DataMapItem.fromDataItem(item).dataMap
                total = m.getInt(Protocol.KEY_TOTAL)
                crc = m.getLong(Protocol.KEY_CRC)
                chunks[m.getInt(Protocol.KEY_SEQ)] = m.getByteArray(Protocol.KEY_BYTES) ?: ByteArray(0)
            }
        } finally {
            items.release()
        }
        if (total < 0) return
        val bytes = Chunker.join(chunks, total) ?: return // wait for remaining chunks
        if (BatchCodec.crc(bytes) != crc) {
            Log.w(TAG, "CRC mismatch for $key; waiting for resend")
            return
        }
        val batch = runCatching { BatchCodec.decode(bytes) }.getOrElse {
            Log.e(TAG, "Undecodable batch $key", it); return
        }
        db.batches().insert(
            SensorBatchEntity(
                key = key, startMs = batch.startMs, endMs = batch.endMs,
                samplingRate = batch.mode.ppgStillHz, ppgSource = batch.ppgSource.name,
                sampleCount = batch.sampleCount, payload = bytes, receivedMs = System.currentTimeMillis(),
            )
        )
        WatchLink.ack(this, nodeId, key)
        runCatching { client.deleteDataItems(prefix, DataClient.FILTER_PREFIX).await() }
        Log.i(TAG, "Stored batch $key (${batch.sampleCount} samples, ${bytes.size / 1024} KB)")
        runCatching { Processing.processRange(this, batch.startMs, batch.endMs) }
            .onFailure { Log.e(TAG, "Processing failed for $key", it) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val body = String(event.data)
        when (event.path) {
            Protocol.MSG_STATUS -> WatchLink.onStatus(this, body)
            Protocol.MSG_REMINDER_ACTION -> runBlocking { ReminderEngine.onWatchAction(this@PhoneListenerService, JSONObject(body)) }
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        // Watch came back: flush queued commands immediately instead of waiting for backoff.
        if (info.nodes.isNotEmpty()) runBlocking {
            for (c in AppDb.get(this@PhoneListenerService).commands().all()) {
                if (WatchLink.trySend(this@PhoneListenerService, c.path, c.payload)) {
                    AppDb.get(this@PhoneListenerService).commands().delete(c.id)
                }
            }
        }
    }

    companion object { private const val TAG = "RC.PhoneListener" }
}

internal fun manualNote(text: String, metric: String = "reminder", tags: String = "") =
    DiaryEntry(timestamp = System.currentTimeMillis(), type = "auto", metric = metric, value = null, note = text, tags = tags)
