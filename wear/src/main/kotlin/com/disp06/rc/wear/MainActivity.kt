package com.disp06.rc.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.disp06.rc.shared.PpgSource
import com.disp06.rc.shared.SamplingMode
import com.disp06.rc.wear.data.Uploader
import com.disp06.rc.wear.data.WatchDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal watch UI (plain Views — no Compose — to keep the watch APK and its memory use small).
 * Status, start/stop, sampling mode, sensor probe and battery-optimisation shortcut.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var modeBtn: Button
    private lateinit var details: TextView

    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (resources.displayMetrics.widthPixels * 0.12).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad * 2)
        }
        title = text(18f, Color.WHITE, bold = true).also { it.text = "Research Companion"; col.addView(it) }
        status = text(14f, ACCENT).also { col.addView(it) }
        toggle = button("Start") { toggleCollection() }.also { col.addView(it) }
        modeBtn = button("Mode") { cycleMode() }.also { col.addView(it) }
        col.addView(button("Sync now") {
            if (CollectorState.running) SensorCollectorService.send(this, SensorCollectorService.ACTION_FLUSH)
            Uploader.enqueueUpload(this, force = true)
            toast("Syncing…")
        })
        col.addView(button("Sensors") { showSensors() })
        col.addView(button("Battery settings") { openBatterySettings() })
        details = text(12f, Color.LTGRAY).also { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val running = CollectorState.running && prefs.collecting
        status.text = when {
            !running -> "Stopped"
            CollectorState.paused -> "Paused"
            CollectorState.source == PpgSource.RAW_PPG -> "● Raw PPG ${CollectorState.ppgHz} Hz" + if (CollectorState.inMotion) " · motion" else ""
            CollectorState.source == PpgSource.HR_BPM -> "● Heart rate ${CollectorState.lastHr.toInt()} bpm"
            else -> "● No heart sensor"
        }
        toggle.text = if (running) "Stop" else "Start"
        modeBtn.text = when (prefs.mode) {
            SamplingMode.STANDARD -> "Mode: Standard"
            SamplingMode.LOW_POWER -> "Mode: Low power"
            SamplingMode.ECO -> "Mode: Eco"
        }
        appScope.launch {
            val stats = WatchDb.get(this@MainActivity).batches().stats()
            val last = prefs.lastUploadMs
            val txt = buildString {
                append("Buffered: ${CollectorState.bufferedSamples} samples\n")
                append("Waiting for phone: ${stats.pending} batches (${(stats.pendingBytes ?: 0) / 1024} KB)\n")
                append("Last sync: ").append(if (last == 0L) "never" else DateUtils.getRelativeTimeSpanString(last)).append('\n')
                append("Gaps >30 s: ${CollectorState.gapCount}")
                if (CollectorState.maxGapS > 0) append(" (max ${CollectorState.maxGapS} s)")
                append("\nCPU lock: ${CollectorState.wakeLockMode}\nSource: ${CollectorState.sourceName}")
            }
            withContext(Dispatchers.Main) { if (sensorList == null) details.text = txt }
        }
    }

    private var sensorList: String? = null

    private fun showSensors() {
        val sm = getSystemService(SensorManager::class.java)
        val arr = SensorProbe.describeAll(sm)
        sensorList = buildString {
            append("All sensors (tap Sensors again to hide):\n")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                append("• ${o.getString("name")}\n  type ${o.getInt("type")}${if (o.getBoolean("wakeUp")) " · wake-up" else ""}\n")
            }
        }.takeIf { sensorList == null }
        details.text = sensorList ?: ""
        refresh()
    }

    private fun toggleCollection() {
        if (CollectorState.running && prefs.collecting) {
            SensorCollectorService.send(this, SensorCollectorService.ACTION_STOP)
            return
        }
        val needed = buildList {
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
            return
        }
        SensorCollectorService.send(this, SensorCollectorService.ACTION_START)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            SensorCollectorService.send(this, SensorCollectorService.ACTION_START)
        } else {
            toast("Body sensors permission is required")
        }
    }

    private fun cycleMode() {
        prefs.mode = SamplingMode.entries[(prefs.mode.ordinal + 1) % SamplingMode.entries.size]
        if (CollectorState.running) SensorCollectorService.send(this, SensorCollectorService.ACTION_RECONFIGURE)
        refresh()
    }

    private fun openBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { startActivity(i) }.isSuccess) {
                toast("Exclude Research Companion from battery optimisation")
                return
            }
        }
        toast("Settings not available on this watch")
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 6, 0, 6)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 100f; setColor(0xFF2A2F36.toInt()) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 8 }
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 7
        private val ACCENT = 0xFF8AB4F8.toInt()
    }
}
