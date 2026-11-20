package com.disp06.rc.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.disp06.rc.mobile.data.MetricEntity
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Downsample long ranges to hourly means so a month stays readable and fast. */
private fun series(list: List<MetricEntity>, range: TrendRange, pick: (MetricEntity) -> Double?): List<TimePoint> {
    val raw = list.mapNotNull { m -> pick(m)?.let { TimePoint(m.timestamp, it) } }
    val bucket = when (range) { TrendRange.DAY -> return raw; TrendRange.WEEK -> 3_600_000L; TrendRange.MONTH -> 6 * 3_600_000L }
    return raw.groupBy { it.t / bucket }.map { (k, v) -> TimePoint(k * bucket + bucket / 2, v.map { it.v }.average()) }.sortedBy { it.t }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(vm: MainViewModel) {
    val range by vm.trendRange.collectAsStateWithLifecycle()
    val data by vm.trend.collectAsStateWithLifecycle()
    val pattern by vm.pattern.collectAsStateWithLifecycle()
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { u -> vm.export("csv", u) } }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> vm.export("json", u) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Trends")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TrendRange.entries.forEachIndexed { i, r ->
                SegmentedButton(range == r, { vm.trendRange.value = r }, SegmentedButtonDefaults.itemShape(i, TrendRange.entries.size)) { Text(r.label) }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Heart rate") {
                TimeLineChart(series(data, range) { it.heartRate }, "bpm", timePattern = range.pattern)
            }
            SectionCard(title = "HRV (RMSSD)") {
                TimeLineChart(series(data, range) { it.rmssd }, "ms", timePattern = range.pattern)
            }
            SectionCard(title = "Stress by hour · last 30 days") {
                pattern?.let { StressHeatmap(it.heatmap) }
            }
            SectionCard(title = "Day analyzer") {
                val p = pattern
                if (p?.overallMean == null) {
                    Text("Collect a few days of data to see weekday patterns.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    WeekdayBars(p.byWeekday, p.mostStressful?.let { it.value - 1 }, Modifier.fillMaxWidth())
                    val name = { d: java.time.DayOfWeek -> d.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
                    Text(buildString {
                        append("Average stress ${p.overallMean?.roundToInt()}")
                        p.overallStd?.let { append(" ± ${it.roundToInt()}") }
                        p.mostStressful?.let { append(". Highest on ${name(it)}") }
                        p.calmest?.let { append(", calmest on ${name(it)}") }
                        append(".")
                        if (p.hotspots.isNotEmpty()) {
                            append("\nRecurring peaks: ")
                            append(p.hotspots.take(4).joinToString { (d, h) -> "${d.getDisplayName(TextStyle.SHORT, Locale.getDefault())} %02d:00".format(h) })
                        }
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { csv.launch("research-companion.csv") }, Modifier.weight(1f)) { Text("Export CSV") }
                OutlinedButton(onClick = { json.launch("research-companion.json") }, Modifier.weight(1f)) { Text("Export JSON") }
            }
        }
    }
}
