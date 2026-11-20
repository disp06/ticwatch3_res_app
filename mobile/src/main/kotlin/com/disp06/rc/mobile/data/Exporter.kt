package com.disp06.rc.mobile.data

import android.content.Context
import android.net.Uri
import com.disp06.rc.shared.BatchCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.time.Instant

/** CSV / JSON export and JSON import via the Storage Access Framework (no storage permission). */
object Exporter {
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun d(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""
    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun writer(ctx: Context, uri: Uri): BufferedWriter =
        BufferedWriter(OutputStreamWriter(ctx.contentResolver.openOutputStream(uri, "wt")!!, Charsets.UTF_8))

    /** Metrics and diary in one CSV (section header row per table). */
    suspend fun exportCsv(ctx: Context, uri: Uri): Int {
        val db = AppDb.get(ctx)
        val metrics = db.metrics().all()
        val diary = db.diary().all()
        writer(ctx, uri).use { w ->
            w.write("# metrics (5-minute windows)\n")
            w.write("timestamp,iso,heart_rate,rmssd,sdnn,pnn50,stress_score,activity,steps,quality,motion_fraction,source\n")
            for (m in metrics) {
                w.write("${m.timestamp},${iso(m.timestamp)},${d(m.heartRate)},${d(m.rmssd)},${d(m.sdnn)},${d(m.pnn50)},${d(m.stressScore)},${m.activity},${m.steps ?: ""},${d(m.quality)},${d(m.motionFraction)},${m.source}\n")
            }
            w.write("\n# diary\n")
            w.write("timestamp,iso,type,metric,value,note,tags\n")
            for (e in diary) {
                w.write("${e.timestamp},${iso(e.timestamp)},${e.type},${e.metric},${d(e.value)},${csv(e.note)},${csv(e.tags)}\n")
            }
        }
        return metrics.size + diary.size
    }

    /** Raw samples, one row per sample: timestamp, stream, ch0..ch3. Can be large (≈ 100 MB/day). */
    suspend fun exportRawCsv(ctx: Context, uri: Uri): Int {
        val db = AppDb.get(ctx)
        var rows = 0
        writer(ctx, uri).use { w ->
            w.write("timestamp,stream,ch0,ch1,ch2,ch3\n")
            for (info in db.batches().allInfo()) {
                val b = runCatching { BatchCodec.decode(db.batches().payload(info.id)) }.getOrNull() ?: continue
                for (s in b.streams) {
                    for (i in 0 until s.size) {
                        w.write(s.timestampsMs[i].toString()); w.write(","); w.write(s.type.name)
                        for (c in 0 until 4) { w.write(","); if (c < s.channels) w.write(s.value(i, c).toString()) }
                        w.write("\n")
                        rows++
                    }
                }
            }
        }
        return rows
    }

    suspend fun exportJson(ctx: Context, uri: Uri): Int {
        val db = AppDb.get(ctx)
        val root = JSONObject().put("format", "research-companion/1").put("exported", iso(System.currentTimeMillis()))
        root.put("metrics", JSONArray().apply {
            db.metrics().all().forEach { m ->
                put(JSONObject().put("timestamp", m.timestamp).put("heart_rate", m.heartRate).put("rmssd", m.rmssd)
                    .put("sdnn", m.sdnn).put("pnn50", m.pnn50).put("stress_score", m.stressScore).put("activity", m.activity)
                    .put("steps", m.steps).put("quality", m.quality).put("motion_fraction", m.motionFraction).put("source", m.source))
            }
        })
        root.put("diary", JSONArray().apply {
            db.diary().all().forEach { e ->
                put(JSONObject().put("timestamp", e.timestamp).put("type", e.type).put("metric", e.metric).put("value", e.value)
                    .put("note", e.note).put("tags", e.tags).put("auto_key", e.autoKey))
            }
        })
        root.put("reminder_rules", JSONArray().apply {
            db.rules().all().forEach { r ->
                put(JSONObject().put("type", r.type).put("trigger_value", r.triggerValue).put("message", r.message).put("enabled", r.enabled))
            }
        })
        writer(ctx, uri).use { it.write(root.toString(1)) }
        return root.getJSONArray("metrics").length() + root.getJSONArray("diary").length()
    }

    /** Imports a JSON export. Metrics are merged by timestamp; diary auto entries by key; manual entries appended. */
    suspend fun importJson(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        require(root.optString("format").startsWith("research-companion")) { "Not a Research Companion export" }
        val db = AppDb.get(ctx)
        fun JSONObject.dbl(k: String) = if (isNull(k)) null else optDouble(k)
        var n = 0
        root.optJSONArray("metrics")?.let { arr ->
            val rows = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ts = o.getLong("timestamp")
                MetricEntity(
                    id = db.metrics().at(ts)?.id ?: 0, timestamp = ts, heartRate = o.dbl("heart_rate"), rmssd = o.dbl("rmssd"),
                    sdnn = o.dbl("sdnn"), pnn50 = o.dbl("pnn50"), stressScore = o.dbl("stress_score"),
                    activity = o.optString("activity", "UNKNOWN"), steps = if (o.isNull("steps")) null else o.optInt("steps"),
                    quality = o.optDouble("quality", 0.0), motionFraction = o.optDouble("motion_fraction", 0.0),
                    source = o.optString("source", "NONE"),
                )
            }
            db.metrics().upsert(rows); n += rows.size
        }
        root.optJSONArray("diary")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = if (o.isNull("auto_key")) null else o.optString("auto_key")
                val e = DiaryEntry(
                    id = key?.let { db.diary().idForKey(it) } ?: 0, timestamp = o.getLong("timestamp"), type = o.optString("type", "manual"),
                    metric = o.optString("metric", "note"), value = o.dbl("value"), note = o.optString("note"), tags = o.optString("tags"), autoKey = key,
                )
                if (e.id != 0L) db.diary().update(e) else db.diary().insert(e)
                n++
            }
        }
        root.optJSONArray("reminder_rules")?.let { arr ->
            val existing = db.rules().all().map { it.type to it.triggerValue + it.message }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = ReminderRule(type = o.getString("type"), triggerValue = o.getString("trigger_value"), message = o.getString("message"), enabled = o.optBoolean("enabled", true))
                if ((r.type to r.triggerValue + r.message) !in existing) { db.rules().upsert(r); n++ }
            }
        }
        return n
    }

    suspend fun clearAll(ctx: Context) {
        val db = AppDb.get(ctx)
        db.batches().clear(); db.metrics().clear(); db.diary().clear()
    }
}
