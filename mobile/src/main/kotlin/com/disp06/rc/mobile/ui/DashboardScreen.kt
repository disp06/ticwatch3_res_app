package com.disp06.rc.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.disp06.rc.mobile.sync.WatchStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun stressLabel(v: Double) = when {
    v < 30 -> "Calm"
    v < 60 -> "Moderate"
    else -> "High"
}

private fun statusLine(s: WatchStatus?): String {
    if (s == null) return "Waiting for watch…"
    val age = System.currentTimeMillis() - s.receivedAt
    val parts = mutableListOf<String>()
    parts += if (age > 30 * 60_000L) "Last seen ${relative(s.receivedAt)}" else "Watch connected"
    if (s.battery >= 0) parts += "${s.battery}%" + if (s.charging) " ⚡" else ""
    parts += when {
        !s.collecting -> "not collecting"
        s.paused -> "paused"
        s.source == "RAW_PPG" -> "raw PPG ${s.ppgHz} Hz"
        s.source == "HR_BPM" -> "heart rate only"
        else -> "no heart sensor"
    }
    return parts.joinToString(" · ")
}

@Composable
fun DashboardScreen(vm: MainViewModel, onOpenTrends: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val day by vm.last24h.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    var showEntry by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("Today", statusLine(status)) }
            IconButton(onClick = { vm.syncNow(); vm.refreshStatus() }, Modifier.padding(end = 8.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(Modifier.weight(1f), title = "Heart rate") {
                    val hr = latest?.heartRate
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(hr?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.displayMedium)
                        Text(" bpm", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Text(latest?.let { "5-min avg · ${time.format(Instant.ofEpochMilli(it.timestamp))}" } ?: "No data yet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionCard(Modifier.weight(1f), title = "Stress") {
                    val st = day.lastOrNull { it.stressScore != null }?.stressScore
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(st?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.displayMedium)
                        Text(" /100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Text(st?.let { stressLabel(it) } ?: "Needs a few calm minutes", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(title = "HRV · last 24 h", onClick = onOpenTrends) {
                val pts = day.mapNotNull { m -> m.rmssd?.let { TimePoint(m.timestamp, it) } }
                if (pts.size >= 2) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${pts.last().v.roundToInt()}", style = MaterialTheme.typography.headlineMedium)
                        Text(" ms RMSSD", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Sparkline(pts, Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp))
                } else {
                    Text(
                        if (status?.source == "HR_BPM") "HRV needs raw PPG. Your watch is sending heart rate only — see Settings › Heart signal source."
                        else "HRV appears after a few low-motion 5-minute windows.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val collecting = status?.collecting == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::toggleCollection, Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Text(if (collecting) "  Stop" else "  Start")
                }
                FilledTonalButton(onClick = { showEntry = true }, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Log")
                }
                FilledTonalButton(onClick = { showSend = true }, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Notifications, null, Modifier.size(18.dp)); Text("  Remind")
                }
            }
            Text(
                "Last sync ${relative(lastSync)}" + (status?.takeIf { it.pendingBatches > 0 }?.let { " · ${it.pendingBatches} batches waiting on watch" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
            )
        }
    }
    if (showEntry) EntryDialog({ showEntry = false }, vm::addEntry)
    if (showSend) SendReminderDialog({ showSend = false }, vm::sendReminder)
}
