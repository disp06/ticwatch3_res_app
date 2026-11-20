package com.disp06.rc.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/** Renders the chart components with synthetic data (run: ./gradlew :mobile:recordPaparazziDebug). */
class ChartsScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2800), maxPercentDifference = 0.1)

    private val t0 = 1_763_600_000_000L
    private val hrv = (0 until 288).map { TimePoint(t0 + it * 300_000L, 42 + 12 * sin(it / 30.0) + (it % 7)) }
    private val hr = (0 until 288).map { TimePoint(t0 + it * 300_000L, 64 + 10 * sin(it / 25.0 + 1) + (it % 5)) }
    private val heat = (0 until 7).map { d -> (0 until 24).map { h -> if (h < 7) null else (20 + 8 * d + 30 * sin(h / 5.0)).coerceIn(0.0, 100.0) } }

    @Test fun charts() = paparazzi.snapshot {
        AppTheme(followSystem = false) {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle("Today", "Watch connected · 82% · raw PPG 25 Hz")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard(Modifier.weight(1f), title = "Heart rate") {
                        Row(verticalAlignment = Alignment.Bottom) { Text("68", style = MaterialTheme.typography.displayMedium); Text(" bpm") }
                    }
                    SectionCard(Modifier.weight(1f), title = "Stress") {
                        Row(verticalAlignment = Alignment.Bottom) { Text("34", style = MaterialTheme.typography.displayMedium); Text(" /100") }
                    }
                }
                SectionCard(title = "HRV · last 24 h") { Sparkline(hrv, Modifier.fillMaxWidth().height(56.dp)) }
                SectionCard(title = "Heart rate") { TimeLineChart(hr, "bpm") }
                SectionCard(title = "Stress by hour · last 30 days") { StressHeatmap(heat) }
                SectionCard(title = "Day analyzer") { WeekdayBars(listOf(31.0, 38.0, 52.0, 35.0, 29.0, null, 18.0), 2, Modifier.fillMaxWidth()) }
            }
        }
    }
}
