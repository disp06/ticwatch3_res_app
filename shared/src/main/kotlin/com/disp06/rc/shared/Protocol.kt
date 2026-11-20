package com.disp06.rc.shared

/**
 * Data Layer paths, capability names and message keys shared by watch and phone.
 * Both APKs must use the same applicationId and the same signing key, otherwise the
 * Wearable Data Layer will silently refuse to deliver anything between them.
 */
object Protocol {
    const val CAPABILITY_WATCH = "research_companion_watch"
    const val CAPABILITY_PHONE = "research_companion_phone"

    // DataItems (watch -> phone)
    /** `/batch/{batchKey}/{seq}` — one chunk of an encoded sensor batch. */
    const val PATH_BATCH_PREFIX = "/batch/"

    // DataItems (phone -> watch)
    /** Collection config (sampling mode etc.). Persistent so the watch picks it up after reconnect. */
    const val PATH_CONFIG = "/config"
    /** Time- and interval-based reminders that the watch schedules locally (work offline). */
    const val PATH_SCHEDULE = "/reminders/schedule"

    // Messages (phone -> watch)
    const val MSG_START = "/cmd/start"
    const val MSG_STOP = "/cmd/stop"
    const val MSG_STATUS_REQUEST = "/cmd/status"
    const val MSG_FLUSH = "/cmd/flush"
    const val MSG_NOTIFY = "/notify"
    const val MSG_ACK = "/ack"

    // Messages (watch -> phone)
    const val MSG_STATUS = "/status"
    const val MSG_REMINDER_ACTION = "/reminder/action"

    // DataMap keys for batch chunks
    const val KEY_BATCH_KEY = "batchKey"
    const val KEY_SEQ = "seq"
    const val KEY_TOTAL = "total"
    const val KEY_CRC = "crc"
    const val KEY_BYTES = "bytes"
    const val KEY_JSON = "json"

    /** Maximum size of a single chunk (the Data Layer limit is 100 KB; brief asks for <= 50 KB). */
    const val MAX_CHUNK_BYTES = 50 * 1024
}

/** Sampling profiles. Rates are in Hz; 0 means sensor disabled. */
enum class SamplingMode(
    val ppgStillHz: Int,
    val ppgMotionHz: Int,
    val imuHz: Int,
    val gyro: Boolean,
    val flushMinutes: Int,
    /** For duty-cycled modes: seconds of collection per cycle (0 = continuous). */
    val windowSeconds: Int,
    val cycleMinutes: Int,
) {
    STANDARD(25, 50, 50, true, 5, 0, 0),
    LOW_POWER(10, 10, 25, false, 15, 0, 0),
    /** Collects a 60 s window every 5 minutes. HRV is still computed per window. */
    ECO(25, 25, 25, false, 15, 60, 5);
}

/** Where heart data came from. Raw PPG enables real HRV; HR_BPM only gives heart rate. */
enum class PpgSource(val code: Int) {
    NONE(0), RAW_PPG(1), HR_BPM(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }
}
