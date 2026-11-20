package com.disp06.rc.wear

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.disp06.rc.shared.BatchCodec
import com.disp06.rc.shared.PpgSource
import com.disp06.rc.shared.SamplingMode
import com.disp06.rc.shared.SensorBatch
import com.disp06.rc.shared.SensorStream
import com.disp06.rc.shared.StreamType
import com.disp06.rc.wear.data.BatchEntity
import com.disp06.rc.wear.data.Uploader
import com.disp06.rc.wear.data.WatchDb
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** Live collector state for the watch UI and status messages (written on the sensor thread). */
object CollectorState {
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var inMotion = false
    @Volatile var source = PpgSource.NONE
    @Volatile var sourceName = "-"
    @Volatile var ppgHz = 0
    @Volatile var bufferedSamples = 0
    @Volatile var lastFlushMs = 0L
    @Volatile var gapCount = 0
    @Volatile var maxGapS = 0
    @Volatile var wakeLockMode = "-"
    @Volatile var lastHr = 0f
}

/**
 * Foreground service that samples PPG (or HR), accelerometer, gyroscope and step counter,
 * keeps samples in RAM and writes one compressed batch to Room every flush interval
 * (5 min standard, 15 min low-power/eco). It never does analytics — that is the phone's job.
 */
class SensorCollectorService : Service(), SensorEventListener {
    private lateinit var sm: SensorManager
    private lateinit var prefs: Prefs
    private lateinit var handler: Handler
    private val thread = HandlerThread("rc-sensors")
    private val io = Executors.newSingleThreadExecutor()

    private var mode = SamplingMode.STANDARD
    private var heartSensor: Sensor? = null
    /** Device HR sensor recorded next to raw PPG as a reference for beat detection on the phone. */
    private var refHrSensor: Sensor? = null
    /** Per-sensor clock offsets for vendor sensors whose timestamps aren't elapsedRealtimeNanos. */
    private val vendorOffsets = HashMap<Sensor, Long>()
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var steps: Sensor? = null
    private var registered = false

    private val buffers = HashMap<StreamType, StreamBuffer>()
    private var batchStart = 0L
    private var tsOffsetMs = 0L
    private var lastHeartTs = 0L

    // Motion detection over 5 s blocks of acceleration magnitude (Welford).
    private var blockStart = 0L
    private var bn = 0
    private var bMean = 0.0
    private var bM2 = 0.0
    private var lastMotionMs = 0L

    private var continuousLock: PowerManager.WakeLock? = null
    private var windowLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(SensorManager::class.java)
        prefs = Prefs(this)
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.collecting = false
                handler.post { stopCollecting(flush = true) }
                handler.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> handler.post { CollectorState.paused = true; stopCollecting(flush = true); updateNotification() }
            ACTION_RESUME -> handler.post { CollectorState.paused = false; startCollecting() ; updateNotification() }
            ACTION_ECO_WINDOW -> handler.post { if (!CollectorState.paused) startCollecting() }
            ACTION_FLUSH -> handler.post { flush(force = true) }
            else -> { // START or RECONFIGURE or sticky restart (null intent)
                prefs.collecting = true
                handler.post {
                    stopCollecting(flush = true)
                    if (!CollectorState.paused) startCollecting()
                }
            }
        }
        CollectorState.running = true
        return START_STICKY
    }

    // region collection lifecycle (sensor thread)

    private fun startCollecting() {
        if (registered) return
        mode = prefs.mode
        val choice = SensorProbe.choose(sm, prefs.ppgSensorType)
        heartSensor = choice.sensor
        CollectorState.source = when {
            choice.sensor == null -> PpgSource.NONE
            choice.isRawPpg -> PpgSource.RAW_PPG
            else -> PpgSource.HR_BPM
        }
        CollectorState.sourceName = choice.sensor?.name ?: "none"
        refHrSensor = if (CollectorState.source == PpgSource.RAW_PPG) SensorProbe.preferWakeUp(sm, Sensor.TYPE_HEART_RATE) else null
        accel = SensorProbe.preferWakeUp(sm, Sensor.TYPE_ACCELEROMETER)
        gyro = if (mode.gyro && prefs.gyroEnabled) SensorProbe.preferWakeUp(sm, Sensor.TYPE_GYROSCOPE) else null
        steps = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        tsOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1_000_000
        if (batchStart == 0L) batchStart = System.currentTimeMillis()
        if (CollectorState.lastFlushMs == 0L) CollectorState.lastFlushMs = System.currentTimeMillis()
        lastHeartTs = 0

        val ppgHz = if (CollectorState.inMotion) mode.ppgMotionHz else mode.ppgStillHz
        register(heartSensor, ppgHz)
        refHrSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000, handler) }
        register(accel, mode.imuHz)
        register(gyro, mode.imuHz)
        steps?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000, handler) }
        CollectorState.ppgHz = if (CollectorState.source == PpgSource.HR_BPM) 1 else ppgHz
        registered = true

        // Non-wake-up sensors stop delivering (FIFO overflows) when the CPU suspends with the
        // screen off. If the core sensors have no wake-up variant we must hold the CPU awake.
        val needLock = listOfNotNull(heartSensor, accel).any { !it.isWakeUpSensor }
        if (mode.windowSeconds > 0) {
            windowLock = wakeLock("rc:eco-window").apply { acquire((mode.windowSeconds + 15) * 1000L) }
            handler.postDelayed(endEcoWindow, mode.windowSeconds * 1000L)
            CollectorState.wakeLockMode = "eco window"
        } else if (needLock) {
            continuousLock = wakeLock("rc:collect").apply { acquire() }
            CollectorState.wakeLockMode = "held (no wake-up sensor)"
        } else {
            CollectorState.wakeLockMode = "not needed (wake-up FIFO)"
        }
        Log.i(TAG, "Collecting: mode=$mode source=${CollectorState.source} (${CollectorState.sourceName}) lock=${CollectorState.wakeLockMode}")
    }

    private fun register(s: Sensor?, hz: Int) {
        if (s == null || hz <= 0) return
        val periodUs = 1_000_000 / hz
        // Batch in the hardware FIFO for up to 10 s (or what fits) to cut CPU wake-ups.
        val fifo = s.fifoReservedEventCount
        val latencyUs = if (fifo > 0) minOf(10_000_000L, fifo.toLong() * periodUs * 8 / 10).toInt() else 0
        sm.registerListener(this, s, periodUs, latencyUs, handler)
    }

    private val endEcoWindow = Runnable {
        stopCollecting(flush = false)
        if (System.currentTimeMillis() - CollectorState.lastFlushMs >= mode.flushMinutes * 60_000L) flush(force = false)
        scheduleEcoWindow()
        windowLock?.let { if (it.isHeld) it.release() }
    }

    private fun scheduleEcoWindow() {
        val am = getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + (mode.cycleMinutes * 60L - mode.windowSeconds) * 1000L
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, AlarmReceiver.ecoIntent(this))
    }

    private fun stopCollecting(flush: Boolean) {
        handler.removeCallbacks(endEcoWindow)
        if (registered) {
            sm.flush(this)
            sm.unregisterListener(this)
            registered = false
        }
        if (flush) flush(force = true)
        continuousLock?.let { if (it.isHeld) it.release() }
        continuousLock = null
    }

    // endregion

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        val raw = e.timestamp / 1_000_000
        var ts = tsOffsetMs + raw
        if (abs(ts - now) > 60_000) {
            // Vendor sensor on a different clock base. Anchor it once and keep its own spacing
            // (using arrival time would squash FIFO-batched samples together).
            val off = vendorOffsets[e.sensor]?.takeIf { abs(raw + it - now) <= 60_000 } ?: (now - raw).also { vendorOffsets[e.sensor] = it }
            ts = raw + off
        }
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                buf(StreamType.ACCEL, 3).add(ts, e.values, 3)
                trackMotion(ts, e.values)
            }
            Sensor.TYPE_GYROSCOPE -> buf(StreamType.GYRO, 3).add(ts, e.values, 3)
            Sensor.TYPE_STEP_COUNTER -> buf(StreamType.STEPS, 1).add(ts, e.values, 1)
            Sensor.TYPE_HEART_RATE -> if (e.sensor != heartSensor || CollectorState.source == PpgSource.HR_BPM) {
                if (e.accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT && e.values[0] > 0f) {
                    buf(StreamType.HEART_RATE, 1).add(ts, e.values, 1)
                    CollectorState.lastHr = e.values[0]
                }
                if (CollectorState.source == PpgSource.HR_BPM) checkGap(ts)
            }
            else -> if (e.sensor == heartSensor) {
                val ch = e.values.size.coerceIn(1, 4)
                buf(StreamType.PPG, ch).add(ts, e.values, ch)
                checkGap(ts)
            }
        }
        // Flush on wall-clock boundaries (e.g. :00, :05, :10) so batches line up with the
        // phone's 5-minute analysis windows.
        val period = mode.flushMinutes * 60_000L
        if (now / period != CollectorState.lastFlushMs / period) flush(force = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buf(t: StreamType, ch: Int) = buffers.getOrPut(t) { StreamBuffer(ch) }

    private fun checkGap(ts: Long) {
        if (lastHeartTs != 0L && mode.windowSeconds == 0) {
            val gap = ts - lastHeartTs
            if (gap > 30_000) {
                CollectorState.gapCount++
                CollectorState.maxGapS = maxOf(CollectorState.maxGapS, (gap / 1000).toInt())
            }
        }
        lastHeartTs = ts
    }

    private fun trackMotion(ts: Long, v: FloatArray) {
        val m = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
        if (blockStart == 0L) blockStart = ts
        bn++
        val d = m - bMean
        bMean += d / bn
        bM2 += d * (m - bMean)
        if (ts - blockStart < 5_000) return
        val std = if (bn > 1) sqrt(bM2 / (bn - 1)) else 0.0
        blockStart = ts; bn = 0; bMean = 0.0; bM2 = 0.0
        val moving = std > MOTION_STD
        if (moving) lastMotionMs = ts
        // Switch to motion immediately; back to still only after 30 s of calm (hysteresis).
        val newState = moving || (CollectorState.inMotion && ts - lastMotionMs < 30_000)
        if (newState != CollectorState.inMotion) {
            CollectorState.inMotion = newState
            buf(StreamType.MOTION, 1).add(ts, floatArrayOf(if (newState) 1f else 0f), 1)
            if (CollectorState.source == PpgSource.RAW_PPG && mode.ppgMotionHz != mode.ppgStillHz && registered) {
                sm.unregisterListener(this, heartSensor)
                val hz = if (newState) mode.ppgMotionHz else mode.ppgStillHz
                register(heartSensor, hz)
                CollectorState.ppgHz = hz
            }
        }
    }

    /** Snapshot RAM buffers into one encoded batch and persist it (sensor thread). */
    private fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        CollectorState.lastFlushMs = now
        val streams = buffers.map { (t, b) -> b.drain(t) }.filter { it.size > 0 }
        CollectorState.bufferedSamples = 0
        if (streams.isEmpty()) { batchStart = now; return }
        val start = batchStart
        batchStart = now
        val wl = wakeLock("rc:flush").apply { acquire(30_000) }
        val batch = SensorBatch(
            key = "${prefs.deviceId}-$start",
            startMs = start,
            endMs = now,
            mode = mode,
            ppgSource = CollectorState.source,
            streams = streams,
        )
        io.execute {
            try {
                val payload = BatchCodec.encode(batch)
                WatchDb.get(this).batches().insert(
                    BatchEntity(
                        key = batch.key, startMs = start, endMs = now,
                        samplingRate = CollectorState.ppgHz, sampleCount = batch.sampleCount, payload = payload,
                    )
                )
                Log.i(TAG, "Flushed ${batch.sampleCount} samples, ${payload.size / 1024} KB")
                // Upload directly from the foreground service: WorkManager jobs get deferred by Doze
                // on the watch for long periods, so batches piled up until the app was opened.
                if (force || now - prefs.lastUploadMs >= prefs.syncMinutes * 60_000L - 60_000L) {
                    appScope.launch { runCatching { Uploader.uploadPending(this@SensorCollectorService) } }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Flush failed", t)
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
        // Refresh the clock offset (wall clock may have been adjusted by network time).
        tsOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1_000_000
    }

    private fun wakeLock(tag: String) =
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)

    // region notification

    private fun buildNotification(): Notification {
        val paused = CollectorState.paused
        val toggle = Intent(this, SensorCollectorService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, WatchApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Research Companion")
            .setContentText(if (paused) "Paused" else "Collecting sensor data")
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (paused) "Resume" else "Pause",
                PendingIntent.getService(this, 1, toggle, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    private fun updateNotification() {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // endregion

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not end collection: schedule a restart.
        if (prefs.collecting) {
            getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000, AlarmReceiver.restartIntent(this)
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.post { stopCollecting(flush = true) }
        handler.post { thread.quitSafely() }
        io.shutdown()
        CollectorState.running = false
        windowLock?.let { if (it.isHeld) it.release() }
        if (prefs.collecting) {
            getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5_000, AlarmReceiver.restartIntent(this)
            )
        }
        super.onDestroy()
    }

    /** Growable sample buffer for one stream (sensor thread only). */
    private class StreamBuffer(val channels: Int) {
        var ts = LongArray(4096)
        var vals = FloatArray(4096 * channels)
        var n = 0

        fun add(t: Long, v: FloatArray, ch: Int) {
            if (n == ts.size) {
                ts = ts.copyOf(n * 2)
                vals = vals.copyOf(n * 2 * channels)
            }
            ts[n] = t
            for (c in 0 until channels) vals[n * channels + c] = if (c < ch && c < v.size) v[c] else 0f
            n++
            CollectorState.bufferedSamples++
        }

        fun drain(type: StreamType): SensorStream {
            // Sensor batching can deliver slightly out-of-order timestamps across sensors; sort per stream.
            val order = (0 until n).sortedBy { ts[it] }
            val t = LongArray(n) { ts[order[it]] }
            val v = FloatArray(n * channels) { vals[order[it / channels] * channels + it % channels] }
            n = 0
            if (ts.size > 65536) { ts = LongArray(4096); vals = FloatArray(4096 * channels) }
            return SensorStream(type, channels, t, v)
        }
    }

    companion object {
        private const val TAG = "RC.Collector"
        private const val NOTIF_ID = 1
        private const val MOTION_STD = 0.6
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_RECONFIGURE = "reconfigure"
        const val ACTION_ECO_WINDOW = "eco"
        const val ACTION_FLUSH = "flush"

        fun send(ctx: Context, action: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SensorCollectorService::class.java).setAction(action))
        }
    }
}
