package com.disp06.rc.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.disp06.rc.mobile.data.DiaryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val FILTER_METRICS = listOf("" to "All") + METRICS + listOf("reminder" to "Reminders", "alert" to "Alerts")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryScreen(vm: MainViewModel) {
    val entries by vm.diary.collectAsStateWithLifecycle()
    val range by vm.diaryRange.collectAsStateWithLifecycle()
    val metric by vm.diaryMetric.collectAsStateWithLifecycle()
    val query by vm.diaryQuery.collectAsStateWithLifecycle()
    var showEntry by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<DiaryEntry?>(null) }
    val zone = ZoneId.systemDefault()
    val time = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }

    Box(Modifier.fillMaxSize()) {
        Column {
            ScreenTitle("Diary")
            OutlinedTextField(
                query, { vm.diaryQuery.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search notes and tags") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiaryRange.entries.forEach { r -> FilterChip(range == r, { vm.diaryRange.value = r }, { Text(r.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTER_METRICS.forEach { (k, l) -> FilterChip(metric == k, { vm.diaryMetric.value = k }, { Text(l) }) }
            }
            if (entries.isEmpty()) {
                Text("No entries for this filter.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val grouped = entries.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                grouped.forEach { (day, list) ->
                    item(key = "h$day") {
                        Text(
                            if (day == LocalDate.now()) "Today" else day.format(dayFmt),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = {}, onLongClick = { toDelete = e })
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(time.format(Instant.ofEpochMilli(e.timestamp)), Modifier.width(52.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(metricLabel(e.metric), style = MaterialTheme.typography.bodyLarge)
                                    if (e.type == "auto") Text("  auto", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (e.tags.isNotBlank()) Text(e.tags.split(',').filter { it.isNotBlank() }.joinToString("  ") { "#" + it.trim() },
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            e.value?.let {
                                Text("${it.roundToInt()} ${metricUnit(e.metric)}".trim(), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showEntry = true }, Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Add entry")
        }
    }
    if (showEntry) EntryDialog({ showEntry = false }, vm::addEntry)
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete entry?") },
            text = { Text("${metricLabel(e.metric)} · ${time.format(Instant.ofEpochMilli(e.timestamp))}" + if (e.type == "auto") "\nAuto entries are recreated if that hour is processed again." else "") },
            confirmButton = { TextButton(onClick = { vm.deleteEntry(e.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
