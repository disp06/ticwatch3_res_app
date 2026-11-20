package com.disp06.rc.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class TimePoint(val t: Long, val v: Double)

private const val GAP_MS = 20 * 60_000L

/** Tiny 24-h sparkline (no axes): one series, 2 px line, gaps where data is missing. */
@Composable
fun Sparkline(points: List<TimePoint>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val t0 = points.first().t; val t1 = points.last().t
        val lo = points.minOf { it.v }; val hi = points.maxOf { it.v }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        drawPath(linePath(points, t0, t1, lo, span, size), color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(color, 4.dp.toPx(), Offset(size.width, (size.height * (1 - (last.v - lo) / span)).toFloat()))
    }
}

private fun linePath(points: List<TimePoint>, t0: Long, t1: Long, lo: Double, span: Double, size: Size): Path {
    val path = Path()
    val tSpan = (t1 - t0).coerceAtLeast(1).toFloat()
    var prev: TimePoint? = null
    for (p in points) {
        val x = (p.t - t0) / tSpan * size.width
        val y = (size.height * (1 - (p.v - lo) / span)).toFloat()
        if (prev == null || p.t - prev.t > GAP_MS) path.moveTo(x, y) else path.lineTo(x, y)
        prev = p
    }
    return path
}

/**
 * Time-series line chart with recessive grid, min/max y labels, start/end time labels and a
 * touch-and-drag crosshair readout (mobile equivalent of a hover tooltip).
 */
@Composable
fun TimeLineChart(
    points: List<TimePoint>,
    unit: String,
    modifier: Modifier = Modifier,
    timePattern: String = "HH:mm",
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val cs = MaterialTheme.colorScheme
    var selected by remember(points) { mutableStateOf<TimePoint?>(null) }
    val fmt = remember(timePattern) { DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.systemDefault()) }
    Column(modifier) {
        Text(
            selected?.let { "${it.v.roundToInt()} $unit · ${fmt.format(Instant.ofEpochMilli(it.t))}" } ?: " ",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurface,
        )
        if (points.size < 2) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                Text("Not enough data yet", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            return@Column
        }
        val t0 = points.first().t; val t1 = points.last().t
        val lo = points.minOf { it.v }; val hi = points.maxOf { it.v }
        val pad = ((hi - lo) * 0.1).coerceAtLeast(1.0)
        val yLo = lo - pad; val yHi = hi + pad
        Row {
            Column(Modifier.height(140.dp).width(34.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${yHi.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("${yLo.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Canvas(
                Modifier.weight(1f).height(140.dp)
                    .pointerInput(points) {
                        fun pick(x: Float) {
                            val t = t0 + (x / size.width * (t1 - t0)).toLong()
                            selected = points.minByOrNull { abs(it.t - t) }
                        }
                        detectDragGestures(onDragEnd = { }, onDragStart = { pick(it.x) }) { c, _ -> pick(c.position.x) }
                    }
                    .pointerInput(points) {
                        detectTapGestures { o ->
                            val t = t0 + (o.x / size.width * (t1 - t0)).toLong()
                            selected = points.minByOrNull { abs(it.t - t) }
                        }
                    },
            ) {
                val grid = cs.outlineVariant
                for (i in 0..2) {
                    val y = size.height * i / 2f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                drawPath(
                    linePath(points, t0, t1, yLo, yHi - yLo, size), color,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                selected?.let { s ->
                    val x = (s.t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * size.width
                    val y = (size.height * (1 - (s.v - yLo) / (yHi - yLo))).toFloat()
                    drawLine(cs.onSurfaceVariant, Offset(x, 0f), Offset(x, size.height), 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    drawCircle(cs.surface, 6.dp.toPx(), Offset(x, y))
                    drawCircle(color, 4.dp.toPx(), Offset(x, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt.format(Instant.ofEpochMilli(t0)), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(fmt.format(Instant.ofEpochMilli(t1)), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

private fun dayName(i: Int) = DayOfWeek.of(i + 1).getDisplayName(JTextStyle.SHORT, Locale.getDefault())

/**
 * Stress heatmap, weekday × hour. Sequential single-hue encoding (primary, low → high opacity);
 * empty cells are an outline. Tap a cell to read its value.
 */
@Composable
fun StressHeatmap(grid: List<List<Double?>>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var sel by remember(grid) { mutableStateOf<Triple<Int, Int, Double?>?>(null) }
    Column(modifier) {
        Text(
            sel?.let { (d, h, v) -> "${dayName(d)} %02d:00 · ".format(h) + (v?.let { "stress ${it.roundToInt()}" } ?: "no data") } ?: "Tap a cell",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Column(Modifier.width(34.dp).height((18 * 7).dp), verticalArrangement = Arrangement.SpaceAround) {
                for (d in 0 until 7) Text(dayName(d), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Canvas(
                Modifier.weight(1f).height((18 * 7).dp).pointerInput(grid) {
                    detectTapGestures { o ->
                        val h = (o.x / size.width * 24).toInt().coerceIn(0, 23)
                        val d = (o.y / size.height * 7).toInt().coerceIn(0, 6)
                        sel = Triple(d, h, grid[d][h])
                    }
                },
            ) {
                val cw = size.width / 24f; val ch = size.height / 7f
                val gap = 2.dp.toPx()
                for (d in 0 until 7) for (h in 0 until 24) {
                    val v = grid[d][h]
                    val tl = Offset(h * cw + gap / 2, d * ch + gap / 2)
                    val sz = Size(cw - gap, ch - gap)
                    if (v == null) {
                        drawRoundRect(cs.outlineVariant, tl, sz, CornerRadius(3f), style = Stroke(1f))
                    } else {
                        drawRoundRect(cs.primary.copy(alpha = (0.12f + 0.88f * (v / 100.0).toFloat()).coerceIn(0.12f, 1f)), tl, sz, CornerRadius(3f))
                    }
                    if (sel?.first == d && sel?.second == h) drawRoundRect(cs.onSurface, tl, sz, CornerRadius(3f), style = Stroke(2f))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 34.dp, top = 4.dp)) {
            for (h in listOf(0, 6, 12, 18, 24)) {
                Text("$h", Modifier.align(BiasAlignment(-1f + 2f * h / 24f, 0f)),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Mean stress per weekday; the most stressful day is drawn in full colour, the rest muted. */
@Composable
fun WeekdayBars(values: List<Double?>, highlight: Int?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val max = (values.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(10.0)
    Row(modifier.height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.labelSmall,
                    color = if (i == highlight) cs.onSurface else cs.onSurfaceVariant)
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    val h = ((v ?: 0.0) / max * size.height).toFloat().coerceAtLeast(if (v == null) 0f else 2f)
                    val w = size.width * 0.6f
                    drawRoundRect(
                        if (i == highlight) cs.primary else cs.primary.copy(alpha = 0.35f),
                        Offset((size.width - w) / 2, size.height - h), Size(w, h), CornerRadius(4.dp.toPx()),
                    )
                    drawLine(cs.outlineVariant, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }
                Text(dayName(i), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
