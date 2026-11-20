package com.disp06.rc.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.disp06.rc.mobile.Settings
import com.disp06.rc.mobile.data.AppDb
import com.disp06.rc.mobile.data.DiaryEntry
import com.disp06.rc.mobile.data.Exporter
import com.disp06.rc.mobile.data.MetricEntity
import com.disp06.rc.mobile.data.ReminderRule
import com.disp06.rc.mobile.sync.ReminderEngine
import com.disp06.rc.mobile.sync.WatchLink
import com.disp06.rc.mobile.sync.toPoint
import com.disp06.rc.shared.dsp.DayAnalyzer
import com.disp06.rc.shared.dsp.DayPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

enum class DiaryRange(val label: String, val days: Int) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30), ALL("All", 0) }
enum class TrendRange(val label: String, val ms: Long, val pattern: String) {
    DAY("Day", 86_400_000L, "HH:mm"), WEEK("Week", 7 * 86_400_000L, "EEE HH:mm"), MONTH("Month", 30 * 86_400_000L, "d MMM")
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val db = AppDb.get(app)
    private val settingsStore = Settings.get(app)
    private val start = System.currentTimeMillis()

    val settings = settingsStore.values
    val status = WatchLink.status
    val sensors = WatchLink.sensors

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val latest = db.metrics().latestWithHr().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lastSync = db.batches().lastReceived().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val batchCount = db.batches().count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Last 30 days of 5-minute windows (≤ 8 640 rows) — feeds dashboard, trends and the day analyzer. */
    private val month = db.metrics().since(start - 30 * 86_400_000L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val last24h: StateFlow<List<MetricEntity>> = month.map { l -> l.filter { it.timestamp >= System.currentTimeMillis() - 86_400_000L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trendRange = MutableStateFlow(TrendRange.DAY)
    val trend = combine(month, trendRange) { l, r -> l.filter { it.timestamp >= System.currentTimeMillis() - r.ms } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pattern: StateFlow<DayPattern?> = month.map { l -> DayAnalyzer.analyze(l.map { it.toPoint() }, ZoneId.systemDefault()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Diary filters
    val diaryRange = MutableStateFlow(DiaryRange.WEEK)
    val diaryMetric = MutableStateFlow("")
    val diaryQuery = MutableStateFlow("")
    val diary = combine(diaryRange, diaryMetric, diaryQuery) { r, m, q -> Triple(r, m, q) }
        .flatMapLatest { (r, m, q) ->
            val from = if (r.days == 0) 0L else startOfToday() - (r.days - 1) * 86_400_000L
            db.diary().search(from, m, q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules = db.rules().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        WatchLink.init(app)
        refreshStatus()
    }

    private fun startOfToday() = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun say(s: String) { _messages.tryEmit(s) }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { block() }.onFailure { say("Error: ${it.message}") }
    }

    fun refreshStatus(withSensors: Boolean = false) = io {
        if (!WatchLink.requestStatus(ctx, withSensors) && withSensors) say("Watch not reachable")
    }

    fun toggleCollection() = io {
        val collecting = status.value?.collecting == true
        val ok = if (collecting) WatchLink.stop(ctx) else WatchLink.start(ctx)
        say(
            when {
                !ok -> "Watch not reachable — command queued"
                collecting -> "Stopping collection"
                else -> "Starting collection"
            }
        )
    }

    fun syncNow() = io { say(if (WatchLink.syncNow(ctx)) "Asked watch to sync" else "Watch not reachable") }

    fun sendReminder(text: String) = io {
        say(if (WatchLink.notify(ctx, text, "Reminder")) "Sent to watch" else "Watch not reachable — will retry")
    }

    fun addEntry(metric: String, value: Double?, note: String, tags: String) = io {
        db.diary().insert(DiaryEntry(timestamp = System.currentTimeMillis(), type = "manual", metric = metric, value = value, note = note.trim(), tags = tags.trim()))
        say("Entry saved")
    }

    fun deleteEntry(id: Long) = io { db.diary().delete(id) }

    fun saveRule(rule: ReminderRule) = io {
        db.rules().upsert(rule)
        ReminderEngine.syncSchedule(ctx)
    }

    fun deleteRule(id: Long) = io {
        db.rules().delete(id)
        ReminderEngine.syncSchedule(ctx)
    }

    fun updateSettings(pushToWatch: Boolean = true, block: (Settings.Values) -> Settings.Values) {
        settingsStore.update(block)
        if (pushToWatch) io { WatchLink.pushConfig(ctx) }
    }

    fun export(kind: String, uri: Uri) = io {
        val n = when (kind) {
            "csv" -> Exporter.exportCsv(ctx, uri)
            "raw" -> Exporter.exportRawCsv(ctx, uri)
            else -> Exporter.exportJson(ctx, uri)
        }
        say("Exported $n rows")
    }

    fun import(uri: Uri) = io { say("Imported ${Exporter.importJson(ctx, uri)} records") }

    fun clearData() = io { Exporter.clearAll(ctx); say("Local data cleared") }
}
