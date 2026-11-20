package com.disp06.rc.wear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import com.disp06.rc.shared.Protocol
import com.disp06.rc.shared.SamplingMode
import com.disp06.rc.wear.data.Uploader
import com.disp06.rc.wear.data.WatchDb
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Receives commands, acks, reminders and config from the phone. Callbacks run on a binder thread. */
class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val body = String(event.data)
        Log.i(TAG, "Message ${event.path}")
        when (event.path) {
            Protocol.MSG_START -> {
                SensorCollectorService.send(this, SensorCollectorService.ACTION_START)
                StatusReporter.sendAsync(this)
            }
            Protocol.MSG_STOP -> {
                SensorCollectorService.send(this, SensorCollectorService.ACTION_STOP)
                StatusReporter.sendAsync(this)
            }
            Protocol.MSG_FLUSH -> {
                if (CollectorState.running) SensorCollectorService.send(this, SensorCollectorService.ACTION_FLUSH)
                Uploader.enqueueUpload(this, force = true)
            }
            Protocol.MSG_STATUS_REQUEST -> StatusReporter.sendAsync(this, includeSensors = body == "sensors")
            Protocol.MSG_ACK -> runBlocking { Uploader.onAck(this@WatchListenerService, body) }
            Protocol.MSG_NOTIFY -> runCatching {
                val o = JSONObject(body)
                Reminders.show(
                    this, o.optLong("ruleId", 0), o.optString("title", "Research Companion"), o.getString("text"),
                    if (o.optBoolean("alert")) WatchApp.CH_ALERTS else WatchApp.CH_REMINDERS,
                )
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED) continue
            val item = e.dataItem
            val map = DataMapItem.fromDataItem(item).dataMap
            when (item.uri.path) {
                Protocol.PATH_CONFIG -> {
                    val p = Prefs(this)
                    val before = Triple(p.mode, p.gyroEnabled, p.ppgSensorType)
                    p.mode = runCatching { SamplingMode.valueOf(map.getString("mode", "STANDARD")) }.getOrDefault(SamplingMode.STANDARD)
                    p.gyroEnabled = map.getBoolean("gyro", true)
                    p.ppgSensorType = map.getInt("ppgSensor", 0)
                    p.syncMinutes = map.getInt("syncMinutes", 15)
                    if (CollectorState.running && before != Triple(p.mode, p.gyroEnabled, p.ppgSensorType)) {
                        SensorCollectorService.send(this, SensorCollectorService.ACTION_RECONFIGURE)
                    }
                    StatusReporter.sendAsync(this)
                }
                Protocol.PATH_SCHEDULE -> {
                    val rules = map.getDataMapArrayList("rules")?.map {
                        Reminders.Rule(it.getLong("id"), it.getString("type", ""), it.getString("value", ""), it.getString("message", ""))
                    } ?: emptyList()
                    Reminders.replaceAll(this, rules)
                }
            }
        }
    }

    companion object { private const val TAG = "RC.Listener" }
}

/** Sends a JSON status snapshot to the phone. */
object StatusReporter {
    fun snapshot(ctx: Context, includeSensors: Boolean = false): JSONObject {
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val stats = WatchDb.get(ctx).batches().stats()
        val p = Prefs(ctx)
        return JSONObject().apply {
            put("collecting", CollectorState.running && p.collecting)
            put("paused", CollectorState.paused)
            put("mode", p.mode.name)
            put("source", CollectorState.source.name)
            put("sourceName", CollectorState.sourceName)
            put("ppgHz", CollectorState.ppgHz)
            put("motion", CollectorState.inMotion)
            put("battery", level)
            put("charging", charging)
            put("pendingBatches", stats.pending)
            put("pendingKb", (stats.pendingBytes ?: 0) / 1024)
            put("bufferedSamples", CollectorState.bufferedSamples)
            put("lastUpload", p.lastUploadMs)
            put("gaps", CollectorState.gapCount)
            put("maxGapS", CollectorState.maxGapS)
            put("wakeLock", CollectorState.wakeLockMode)
            put("lastHr", CollectorState.lastHr.toDouble())
            put("time", System.currentTimeMillis())
            if (includeSensors) put("sensors", SensorProbe.describeAll(ctx.getSystemService(SensorManager::class.java)))
        }
    }

    suspend fun send(ctx: Context, includeSensors: Boolean = false) {
        runCatching {
            val bytes = snapshot(ctx, includeSensors).toString().toByteArray()
            for (n in Wearable.getNodeClient(ctx).connectedNodes.await()) {
                Wearable.getMessageClient(ctx).sendMessage(n.id, Protocol.MSG_STATUS, bytes).await()
            }
        }
    }

    fun sendAsync(ctx: Context, includeSensors: Boolean = false) {
        appScope.launch { send(ctx.applicationContext, includeSensors) }
    }
}
