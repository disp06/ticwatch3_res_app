package com.disp06.rc.shared

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reminder trigger grammar (stored in reminder_rule.trigger_value):
 *  - time:      "09:00" or "09:00,21:30"
 *  - interval:  "2h", "90m", optionally with active hours "2h@08-22" (default 08-22)
 *  - threshold: "stress>70 for 10m", "hr>110 for 5m", "rmssd<20 for 15m"
 */
sealed interface Trigger {
    data class Time(val times: List<LocalTime>) : Trigger
    data class Interval(val minutes: Int, val fromHour: Int, val toHour: Int) : Trigger
    data class Threshold(val metric: String, val greater: Boolean, val value: Double, val minutes: Int) : Trigger

    companion object {
        private val TIME = Regex("""^(\d{1,2}):(\d{2})$""")
        private val INTERVAL = Regex("""^(\d+)\s*([hm])(?:@(\d{1,2})-(\d{1,2}))?$""", RegexOption.IGNORE_CASE)
        private val THRESHOLD = Regex("""^(stress|hr|rmssd)\s*([<>])\s*(\d+(?:\.\d+)?)(?:\s*for\s*(\d+)\s*m(?:in)?)?$""", RegexOption.IGNORE_CASE)

        fun parse(type: String, value: String): Trigger? = runCatching {
            when (type) {
                "time" -> {
                    val times = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                        val m = TIME.matchEntire(it) ?: error("bad time")
                        LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                    }
                    if (times.isEmpty()) null else Time(times)
                }
                "interval" -> {
                    val m = INTERVAL.matchEntire(value.trim()) ?: return null
                    val n = m.groupValues[1].toInt()
                    val minutes = if (m.groupValues[2].equals("h", true)) n * 60 else n
                    if (minutes < 15) return null
                    val from = m.groupValues[3].ifEmpty { "8" }.toInt()
                    val to = m.groupValues[4].ifEmpty { "22" }.toInt()
                    if (from !in 0..23 || to !in 1..24 || from >= to) return null
                    Interval(minutes, from, to)
                }
                "threshold" -> {
                    val m = THRESHOLD.matchEntire(value.trim()) ?: return null
                    Threshold(
                        metric = m.groupValues[1].lowercase(),
                        greater = m.groupValues[2] == ">",
                        value = m.groupValues[3].toDouble(),
                        minutes = m.groupValues[4].ifEmpty { "10" }.toInt().coerceAtLeast(5),
                    )
                }
                else -> null
            }
        }.getOrNull()

        /** Next fire time strictly after [nowMs] for time/interval triggers; null for thresholds. */
        fun nextFire(trigger: Trigger, nowMs: Long, zone: ZoneId): Long? {
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            return when (trigger) {
                is Time -> trigger.times.map { t ->
                    var z = now.with(t).withSecond(0).withNano(0)
                    if (!z.isAfter(now)) z = z.plusDays(1)
                    z
                }.minOf { it }.toInstant().toEpochMilli()
                is Interval -> nextInterval(trigger, now).toInstant().toEpochMilli()
                is Threshold -> null
            }
        }

        private fun nextInterval(t: Interval, now: ZonedDateTime): ZonedDateTime {
            var day = now.toLocalDate()
            repeat(3) {
                val start = day.atTime(t.fromHour, 0).atZone(now.zone)
                val end = if (t.toHour == 24) day.plusDays(1).atStartOfDay(now.zone) else day.atTime(t.toHour, 0).atZone(now.zone)
                var c = start.plusMinutes(t.minutes.toLong()) // first reminder one interval after the window opens
                while (!c.isAfter(now) && c.isBefore(end)) c = c.plusMinutes(t.minutes.toLong())
                if (c.isAfter(now) && !c.isAfter(end)) return c
                day = day.plusDays(1)
            }
            return now.plusMinutes(t.minutes.toLong())
        }
    }
}
