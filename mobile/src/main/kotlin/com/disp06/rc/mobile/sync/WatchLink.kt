package com.disp06.rc.mobile.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.disp06.rc.mobile.Settings
import com.disp06.rc.mobile.data.AppDb
import com.disp06.rc.mobile.data.PendingCommand
import com.disp06.rc.mobile.data.ReminderRule
import com.disp06.rc.shared.Protocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WatchSensor(val name: String, val type: Int, val wakeUp: Boolean, val vendor: String)

data class WatchStatus(
    val collecting: Boolean,
    val paused: Boolean,
    val mode: String,
    val source: String,
    val sourceName: String,
    val ppgHz: Int,
    val battery: Int,
    val charging: Boolean,
    val pendingBatches: Int,
    val pendingKb: Long,
    val lastUpload: Long,
    val gaps: Int,
    val maxGapS: Int,
    val wakeLock: String,
    val receivedAt: Long,
    val sensors: List<WatchSensor>?,
) {
    companion object {
        fun parse(json: String, receivedAt: Long): WatchStatus? = runCatching {
            val o = JSONObject(json)
            if (!o.has("mode")) return null
            WatchStatus(
                collecting = o.optBoolean("collecting"), paused = o.optBoolean("paused"),
                mode = o.optString("mode"), source = o.optString("source"), sourceName = o.optString("sourceName"),
                ppgHz = o.optInt("ppgHz"), battery = o.optInt("battery", -1), charging = o.optBoolean("charging"),
                pendingBatches = o.optInt("pendingBatches"), pendingKb = o.optLong("pendingKb"),
                lastUpload = o.optLong("lastUpload"), gaps = o.optInt("gaps"), maxGapS = o.optInt("maxGapS"),
                wakeLock = o.optString("wakeLock"), receivedAt = o.optLong("receivedAt", receivedAt),
                sensors = o.optJSONArray("sensors")?.let { a ->
                    (0 until a.length()).map {
                        val s = a.getJSONObject(it)
                        WatchSensor(s.optString("name"), s.optInt("type"), s.optBoolean("wakeUp"), s.optString("vendor"))
                    }
                },
            )
        }.getOrNull()
    }
}

/** Everything the phone sends to the watch goes through here. */
object WatchLink {
    private const val TAG = "RC.WatchLink"

    private val _status = MutableStateFlow<WatchStatus?>(null)
    val status: StateFlow<WatchStatus?> = _status
    private val _sensors = MutableStateFlow<List<WatchSensor>?>(null)
    val sensors: StateFlow<List<WatchSensor>?> = _sensors

    fun init(ctx: Context) {
        if (_status.value == null) _status.value = WatchStatus.parse(Settings.get(ctx).watchStatusJson, 0)
    }

    fun onStatus(ctx: Context, json: String) {
        val now = System.currentTimeMillis()
        val o = JSONObject(json).put("receivedAt", now)
        val st = WatchStatus.parse(o.toString(), now) ?: return
        st.sensors?.let { _sensors.value = it }
        o.remove("sensors")
        Settings.get(ctx).watchStatusJson = o.toString()
        _status.value = st.copy(sensors = null)
    }

    /** Watch nodes advertising our capability; falls back to any connected node if capabilities aren't visible. */
    suspend fun watchNodes(ctx: Context): List<String> {
        val cap = runCatching {
            Wearable.getCapabilityClient(ctx)
                .getCapability(Protocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await()
                .nodes.map { it.id }
        }.getOrDefault(emptyList())
        if (cap.isNotEmpty()) return cap
        return runCatching { Wearable.getNodeClient(ctx).connectedNodes.await().map { it.id } }.getOrDefault(emptyList())
    }

    /** Sends a message to every reachable watch. If none is reachable the command is queued and retried with backoff. */
    suspend fun send(ctx: Context, path: String, payload: ByteArray = ByteArray(0), queue: Boolean = true): Boolean {
        val ok = trySend(ctx, path, payload)
        if (!ok && queue) {
            AppDb.get(ctx).commands().insert(PendingCommand(path = path, payload = payload))
            scheduleFlush(ctx)
        }
        return ok
    }

    internal suspend fun trySend(ctx: Context, path: String, payload: ByteArray): Boolean {
        val nodes = watchNodes(ctx)
        if (nodes.isEmpty()) return false
        var ok = false
        for (n in nodes) {
            ok = runCatching { Wearable.getMessageClient(ctx).sendMessage(n, path, payload).await(); true }
                .onFailure { Log.w(TAG, "send $path failed", it) }.getOrDefault(false) || ok
        }
        return ok
    }

    private fun scheduleFlush(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "command-flush", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CommandFlushWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    suspend fun start(ctx: Context) = send(ctx, Protocol.MSG_START)
    suspend fun stop(ctx: Context) = send(ctx, Protocol.MSG_STOP)
    suspend fun syncNow(ctx: Context) = send(ctx, Protocol.MSG_FLUSH, queue = false)
    suspend fun requestStatus(ctx: Context, withSensors: Boolean = false) =
        send(ctx, Protocol.MSG_STATUS_REQUEST, if (withSensors) "sensors".toByteArray() else ByteArray(0), queue = false)

    suspend fun notify(ctx: Context, text: String, title: String = "Research Companion", ruleId: Long = 0, alert: Boolean = false) =
        send(ctx, Protocol.MSG_NOTIFY, JSONObject().put("text", text).put("title", title).put("ruleId", ruleId).put("alert", alert).toString().toByteArray())

    suspend fun ack(ctx: Context, nodeId: String, key: String) {
        runCatching { Wearable.getMessageClient(ctx).sendMessage(nodeId, Protocol.MSG_ACK, key.toByteArray()).await() }
    }

    /** Persistent DataItem: the watch receives it whenever it next connects. */
    suspend fun pushConfig(ctx: Context) {
        val s = Settings.get(ctx).current
        val req = PutDataMapRequest.create(Protocol.PATH_CONFIG).apply {
            dataMap.putString("mode", s.mode.name)
            dataMap.putBoolean("gyro", s.gyro)
            dataMap.putInt("ppgSensor", s.ppgSensor)
            dataMap.putInt("syncMinutes", s.syncMinutes)
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(ctx).putDataItem(req).await() }
    }

    /** Time & interval rules are scheduled on the watch itself so they fire even when disconnected. */
    suspend fun pushSchedule(ctx: Context, rules: List<ReminderRule>) {
        val list = ArrayList(rules.filter { it.enabled && it.type != "threshold" }.map { r ->
            DataMap().apply {
                putLong("id", r.id); putString("type", r.type); putString("value", r.triggerValue); putString("message", r.message)
            }
        })
        val req = PutDataMapRequest.create(Protocol.PATH_SCHEDULE).apply {
            dataMap.putDataMapArrayList("rules", list)
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(ctx).putDataItem(req).await() }
    }
}

/** Retries queued commands; WorkManager applies exponential backoff between attempts. */
class CommandFlushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val dao = AppDb.get(applicationContext).commands()
        dao.expire(System.currentTimeMillis() - 24 * 3_600_000L)
        for (c in dao.all()) {
            if (WatchLink.trySend(applicationContext, c.path, c.payload)) dao.delete(c.id) else return Result.retry()
        }
        return Result.success()
    }
}
