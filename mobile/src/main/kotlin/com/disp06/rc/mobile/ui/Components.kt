package com.disp06.rc.mobile.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ScreenTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
    if (onClick != null) Card(onClick, modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = colors, content = body)
    else Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = colors, content = body)
}

fun relative(ms: Long?): String =
    if (ms == null || ms == 0L) "never" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

val METRICS = listOf("note" to "Note", "hr" to "Heart rate", "hrv" to "HRV", "stress" to "Stress", "activity" to "Activity")
fun metricLabel(m: String) = when (m) {
    "reminder" -> "Reminder"; "alert" -> "Alert"
    else -> METRICS.firstOrNull { it.first == m }?.second ?: m
}
fun metricUnit(m: String) = when (m) { "hr" -> "bpm"; "hrv" -> "ms"; "activity" -> "steps"; else -> "" }

/** Manual diary entry: metric, optional value, note, tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryDialog(onDismiss: () -> Unit, onSave: (metric: String, value: Double?, note: String, tags: String) -> Unit) {
    var metric by remember { mutableStateOf("note") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    val needsValue = metric != "note"
    val parsed = value.replace(',', '.').toDoubleOrNull()
    val valid = if (needsValue) parsed != null else note.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    METRICS.forEach { (k, l) -> FilterChip(metric == k, { metric = k }, { Text(l) }) }
                }
                if (needsValue) OutlinedTextField(
                    value, { value = it }, label = { Text("Value ${metricUnit(metric).let { if (it.isEmpty()) "(0–100)" else "($it)" }}") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(note, { note = it }, label = { Text(if (needsValue) "Note (optional)" else "Note") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(metric, if (needsValue) parsed else null, note, tags); onDismiss() }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SendReminderDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to watch") },
        text = { OutlinedTextField(text, { text = it }, label = { Text("Message") }, placeholder = { Text("Drink water") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSend(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
