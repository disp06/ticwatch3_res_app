package com.disp06.rc.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

enum class StreamType(val code: Int) {
    PPG(1),          // raw PPG, 1..4 channels (vendor-defined units)
    HEART_RATE(2),   // BPM from Sensor.TYPE_HEART_RATE (fallback)
    ACCEL(3),        // m/s^2, x y z
    GYRO(4),         // rad/s, x y z
    STEPS(5),        // cumulative step counter
    MOTION(6);       // adaptive-sampling state changes: 0 = still, 1 = motion

    companion object {
        fun of(code: Int) = entries.first { it.code == code }
    }
}

/**
 * One sensor stream: [timestampsMs] are absolute epoch milliseconds, [values] is
 * row-major (sample i, channel c) => values[i * channels + c].
 */
class SensorStream(
    val type: StreamType,
    val channels: Int,
    val timestampsMs: LongArray,
    val values: FloatArray,
) {
    val size get() = timestampsMs.size
    fun value(i: Int, c: Int = 0) = values[i * channels + c]

    init {
        require(values.size == timestampsMs.size * channels) { "values/timestamps mismatch for $type" }
    }
}

/** A batch of sensor data covering [startMs, endMs). */
class SensorBatch(
    /** Unique key: "{deviceId}-{localId}". Used for dedupe and ack. */
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val mode: SamplingMode,
    val ppgSource: PpgSource,
    val streams: List<SensorStream>,
) {
    fun stream(type: StreamType) = streams.firstOrNull { it.type == type }
    val sampleCount get() = streams.sumOf { it.size }

    companion object {
        /**
         * Merges several (possibly overlapping or out-of-order) batches into one continuous
         * batch so analysis windows that straddle batch boundaries see all their samples.
         */
        fun merge(batches: List<SensorBatch>): SensorBatch? {
            if (batches.isEmpty()) return null
            if (batches.size == 1) return batches[0]
            val sorted = batches.sortedBy { it.startMs }
            val streams = StreamType.entries.mapNotNull { type ->
                val parts = sorted.mapNotNull { it.stream(type) }.filter { it.size > 0 }
                if (parts.isEmpty()) return@mapNotNull null
                val ch = parts.maxOf { it.channels }
                val n = parts.sumOf { it.size }
                val order = ArrayList<Pair<Long, Pair<SensorStream, Int>>>(n)
                for (p in parts) for (i in 0 until p.size) order += p.timestampsMs[i] to (p to i)
                order.sortBy { it.first }
                val ts = LongArray(n) { order[it].first }
                val vals = FloatArray(n * ch)
                order.forEachIndexed { k, (_, src) ->
                    val (st, i) = src
                    for (c in 0 until minOf(ch, st.channels)) vals[k * ch + c] = st.value(i, c)
                }
                SensorStream(type, ch, ts, vals)
            }
            val source = sorted.groupingBy { it.ppgSource }.eachCount().maxBy { it.value }.key
            return SensorBatch(sorted.first().key, sorted.minOf { it.startMs }, sorted.maxOf { it.endMs }, sorted.last().mode, source, streams)
        }
    }
}

/**
 * Compact binary codec: delta-encoded varint timestamps + float32 values, then DEFLATE.
 * Format version 1:
 *   magic "RCB1", key (UTF), start, end, mode ordinal, ppgSource code, nStreams,
 *   per stream: type, channels, n, n varint deltas (first relative to start), scale,
 *   then n*channels float32 (scale == 0) or zigzag-varint deltas of round(value*scale)
 */
object BatchCodec {
    private const val MAGIC = 0x52434231 // "RCB1"

    fun encode(batch: SensorBatch): ByteArray {
        val bos = ByteArrayOutputStream(64 * 1024)
        val def = Deflater(Deflater.BEST_SPEED)
        DataOutputStream(DeflaterOutputStream(bos, def, 8192)).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(batch.key)
            out.writeLong(batch.startMs)
            out.writeLong(batch.endMs)
            out.writeByte(batch.mode.ordinal)
            out.writeByte(batch.ppgSource.code)
            out.writeShort(batch.streams.size)
            for (s in batch.streams) {
                out.writeByte(s.type.code)
                out.writeByte(s.channels)
                out.writeInt(s.size)
                var prev = batch.startMs
                for (t in s.timestampsMs) {
                    writeVarLong(out, zigzag(t - prev))
                    prev = t
                }
                val scale = quantScale(s.type)
                out.writeFloat(scale)
                if (scale == 0f) {
                    for (v in s.values) out.writeFloat(v)
                } else {
                    // Delta-coded int16 quantisation per channel; deltas compress far better under DEFLATE.
                    val prevQ = IntArray(s.channels)
                    for (i in s.values.indices) {
                        val c = i % s.channels
                        val q = Math.round(s.values[i] * scale).coerceIn(-32768, 32767)
                        writeVarLong(out, zigzag((q - prevQ[c]).toLong()))
                        prevQ[c] = q
                    }
                }
            }
        }
        def.end()
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): SensorBatch {
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { inp ->
            require(inp.readInt() == MAGIC) { "Bad batch magic" }
            val key = inp.readUTF()
            val start = inp.readLong()
            val end = inp.readLong()
            val mode = SamplingMode.entries[inp.readUnsignedByte()]
            val src = PpgSource.of(inp.readUnsignedByte())
            val n = inp.readUnsignedShort()
            val streams = ArrayList<SensorStream>(n)
            repeat(n) {
                val type = StreamType.of(inp.readUnsignedByte())
                val ch = inp.readUnsignedByte()
                val count = inp.readInt()
                val ts = LongArray(count)
                var prev = start
                for (i in 0 until count) {
                    prev += unzigzag(readVarLong(inp))
                    ts[i] = prev
                }
                val vals = FloatArray(count * ch)
                val scale = inp.readFloat()
                if (scale == 0f) {
                    for (i in vals.indices) vals[i] = inp.readFloat()
                } else {
                    val prevQ = IntArray(ch)
                    for (i in vals.indices) {
                        val c = i % ch
                        prevQ[c] += unzigzag(readVarLong(inp)).toInt()
                        vals[i] = prevQ[c] / scale
                    }
                }
                streams += SensorStream(type, ch, ts, vals)
            }
            return SensorBatch(key, start, end, mode, src, streams)
        }
    }

    /** 0 = lossless float32. ACCEL: 0.01 m/s^2 steps; GYRO: 0.001 rad/s steps. */
    fun quantScale(type: StreamType): Float = when (type) {
        StreamType.ACCEL -> 100f
        StreamType.GYRO -> 1000f
        else -> 0f
    }

    fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun zigzag(v: Long) = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long) = (v ushr 1) xor -(v and 1)

    private fun writeVarLong(out: DataOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.writeByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.writeByte(v.toInt())
    }

    private fun readVarLong(inp: DataInputStream): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = inp.readUnsignedByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "Varint too long" }
        }
    }
}

/** Splits an encoded batch into Data Layer sized chunks and reassembles them. */
object Chunker {
    fun split(bytes: ByteArray, maxChunk: Int = Protocol.MAX_CHUNK_BYTES): List<ByteArray> {
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        return (bytes.indices step maxChunk).map { from ->
            bytes.copyOfRange(from, minOf(bytes.size, from + maxChunk))
        }
    }

    /**
     * Reassembles chunks given as (seq -> bytes). Returns null if any sequence number
     * in 0 until [total] is missing.
     */
    fun join(chunks: Map<Int, ByteArray>, total: Int): ByteArray? {
        if ((0 until total).any { it !in chunks }) return null
        val out = ByteArrayOutputStream(chunks.values.sumOf { it.size })
        for (i in 0 until total) out.write(chunks.getValue(i))
        return out.toByteArray()
    }
}
