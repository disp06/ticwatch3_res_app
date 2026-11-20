package com.disp06.rc.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.disp06.rc.mobile.BuildConfig
import com.disp06.rc.mobile.data.ReminderRule
import com.disp06.rc.shared.SamplingMode
import com.disp06.rc.shared.Trigger

private val MODE_LABEL = mapOf(SamplingMode.STANDARD to "Standard", SamplingMode.LOW_POWER to "Low power", SamplingMode.ECO to "Eco")
private val MODE_HELP = mapOf(
    SamplingMode.STANDARD to "Adaptive 25/50 Hz PPG, 50 Hz motion. Best data, ~1.5 days battery.",
    SamplingMode.LOW_POWER to "10 Hz PPG, 25 Hz motion, no gyroscope, 15-min batches.",
    SamplingMode.ECO to "One 60-second recording every 5 minutes. Longest battery life.",
)

@Composable
private fun SettingRow(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val sensors by vm.sensors.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ReminderRule?>(null) }
    var pickSensor by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { u -> vm.export("csv", u) } }
    val raw = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { u -> vm.export("raw", u) } }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> vm.export("json", u) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.import(u) } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            SectionCard(title = "Collection") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SamplingMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.mode == m, { vm.updateSettings { it.copy(mode = m) } }, SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(MODE_LABEL.getValue(m))
                        }
                    }
                }
                Text(MODE_HELP.getValue(s.mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingRow("Gyroscope", "Extra motion data for artifact removal (Standard mode only)") {
                    Switch(s.gyro, { v -> vm.updateSettings { it.copy(gyro = v) } }, enabled = s.mode == SamplingMode.STANDARD)
                }
                Text("Sync interval", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        FilterChip(s.syncMinutes == m, { vm.updateSettings { it.copy(syncMinutes = m) } }, { Text("$m min") })
                    }
                }
                SettingRow(
                    "Heart signal source",
                    when (s.ppgSensor) {
                        0 -> "Auto (raw PPG if the watch exposes it)"
                        -1 -> "Heart-rate sensor only (no HRV)"
                        else -> "Sensor type ${s.ppgSensor}" + (sensors?.firstOrNull { it.type == s.ppgSensor }?.let { " · ${it.name}" } ?: "")
                    },
                    onClick = { vm.refreshStatus(withSensors = true); pickSensor = true },
                )
            }

            SectionCard(title = "Reminders") {
                if (rules.isEmpty()) Text("No reminders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                rules.forEach { r ->
                    SettingRow(r.message, "${r.type} · ${r.triggerValue}", onClick = { editing = r }) {
                        Switch(r.enabled, { vm.saveRule(r.copy(enabled = it)) })
                    }
                }
                TextButton(onClick = { editing = ReminderRule(type = "time", triggerValue = "09:00", message = "") }) {
                    Icon(Icons.Filled.Add, null); Text(" Add reminder")
                }
            }

            SectionCard(title = "Data") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ csv.launch("research-companion.csv") }) { Text("Export CSV") }
                    OutlinedButton({ json.launch("research-companion.json") }) { Text("Export JSON") }
                    OutlinedButton({ raw.launch("research-companion-raw.csv") }) { Text("Export raw samples") }
                    OutlinedButton({ import.launch(arrayOf("application/json", "*/*")) }) { Text("Import JSON") }
                }
                Row {
                    TextButton(onClick = vm::reprocessAll) { Text("Reprocess all data") }
                    TextButton(onClick = { confirmClear = true }) { Text("Clear local data", color = MaterialTheme.colorScheme.error) }
                }
            }

            SectionCard(title = "Appearance & alerts") {
                Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
                AccentPicker(Accent.of(s.accent)) { a -> vm.updateSettings(pushToWatch = false) { it.copy(accent = a.name) } }
                SettingRow("Follow system theme", "Off = always dark") {
                    Switch(s.followSystemTheme, { v -> vm.updateSettings(pushToWatch = false) { it.copy(followSystemTheme = v) } })
                }
                SettingRow("Also notify on phone", "Stress alerts and reminder mirrors") {
                    Switch(s.phoneNotifications, { v -> vm.updateSettings(pushToWatch = false) { it.copy(phoneNotifications = v) } })
                }
            }

            SectionCard(title = "About & permissions") {
                val notifOk = Build.VERSION.SDK_INT < 33 ||
                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                SettingRow("Phone notifications", if (notifOk) "Allowed" else "Not allowed — tap to grant",
                    onClick = if (!notifOk && Build.VERSION.SDK_INT >= 33) ({ notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }) else null)
                val st = status
                SettingRow(
                    "Watch",
                    if (st == null) "Not seen yet — install the watch app and open it once"
                    else "Seen ${relative(st.receivedAt)} · ${st.sourceName}\nCPU lock: ${st.wakeLock}\nGaps >30 s: ${st.gaps}" +
                        (if (st.maxGapS > 0) " (max ${st.maxGapS} s)" else "") + " · buffered ${st.pendingKb} KB",
                    onClick = { vm.refreshStatus() },
                )
                SettingRow("Version", BuildConfig.VERSION_NAME)
            }
            Text(
                "Research tool, not a medical device. Stress scores are a heuristic based on your own HRV baseline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp, bottom = 24.dp),
            )
        }
    }

    editing?.let { r -> RuleDialog(r, onDismiss = { editing = null }, onSave = { vm.saveRule(it); editing = null }, onDelete = { vm.deleteRule(r.id); editing = null }) }

    if (pickSensor) AlertDialog(
        onDismissRequest = { pickSensor = false },
        title = { Text("Heart signal source") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { SettingRow("Auto", "Use a vendor raw-PPG sensor if found", onClick = { vm.updateSettings { it.copy(ppgSensor = 0) }; pickSensor = false }) }
                item { SettingRow("Heart rate only", "Sensor.TYPE_HEART_RATE · 1 Hz, no HRV", onClick = { vm.updateSettings { it.copy(ppgSensor = -1) }; pickSensor = false }) }
                item { HorizontalDivider() }
                val list = sensors
                if (list == null) item { Text("Asking the watch for its sensor list…", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else items(list.filter { it.type >= 65536 || it.type == 21 }) { sn ->
                    SettingRow(sn.name, "type ${sn.type} · ${sn.vendor}${if (sn.wakeUp) " · wake-up" else ""}",
                        onClick = { vm.updateSettings { it.copy(ppgSensor = sn.type) }; pickSensor = false })
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickSensor = false }) { Text("Close") } },
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear local data?") },
        text = { Text("Deletes all raw batches, metrics and diary entries on this phone. Reminders are kept. This cannot be undone — export first if needed.") },
        confirmButton = { TextButton(onClick = { vm.clearData(); confirmClear = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun RuleDialog(rule: ReminderRule, onDismiss: () -> Unit, onSave: (ReminderRule) -> Unit, onDelete: () -> Unit) {
    var type by remember { mutableStateOf(rule.type) }
    var value by remember { mutableStateOf(rule.triggerValue) }
    var message by remember { mutableStateOf(rule.message) }
    val examples = mapOf("time" to "09:00 or 09:00,21:00", "interval" to "2h or 90m@08-22", "threshold" to "stress>70 for 10m · hr>110 for 5m")
    val defaults = mapOf("time" to "09:00", "interval" to "2h@08-22", "threshold" to "stress>70 for 10m")
    val valid = Trigger.parse(type, value) != null && message.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "New reminder" else "Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("time" to "Time", "interval" to "Interval", "threshold" to "Threshold").forEach { (k, l) ->
                        FilterChip(type == k, { type = k; if (Trigger.parse(k, value) == null) value = defaults.getValue(k) }, { Text(l) })
                    }
                }
                OutlinedTextField(message, { message = it }, label = { Text("Message") },
                    placeholder = { Text(if (type == "threshold") "Try a breathing exercise" else "Drink water") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value, { value = it }, label = { Text("Trigger") }, singleLine = true,
                    isError = Trigger.parse(type, value) == null, supportingText = { Text(examples.getValue(type)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rule.copy(type = type, triggerValue = value.trim(), message = message.trim())) }, enabled = valid) { Text("Save") } },
        dismissButton = {
            Row {
                if (rule.id != 0L) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Row of colour swatches; the wallpaper option is a sweep of hues. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: Accent, onPick: (Accent) -> Unit) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= 31
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Accent.entries.filter { it != Accent.DYNAMIC || dynamicAvailable }.forEach { a ->
            val isSel = a == selected || (!dynamicAvailable && selected == Accent.DYNAMIC && a == Accent.BLUE)
            val fill = if (a.seed == null) Brush.sweepGradient(listOf(Color(0xFFE5484D), Color(0xFFE8B23A), Color(0xFF3DBE7A), Color(0xFF4C8DF6), Color(0xFF8E6CEF), Color(0xFFE5484D)))
                else SolidColor(Color(a.seed))
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .border(if (isSel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .padding(if (isSel) 5.dp else 0.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .semantics { contentDescription = a.label + if (isSel) ", selected" else "" }
                    .clickable { onPick(a) },
            )
        }
    }
}
