package com.disp06.rc.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.disp06.rc.mobile.sync.ReminderWorker
import com.disp06.rc.shared.SamplingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Alerts & reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    companion object {
        const val CH_ALERTS = "alerts"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** User settings (SharedPreferences + StateFlow so Compose recomposes on change). */
class Settings private constructor(ctx: Context) {
    private val p = ctx.getSharedPreferences("rc_phone", Context.MODE_PRIVATE)

    data class Values(
        val mode: SamplingMode,
        val gyro: Boolean,
        /** 0 auto, -1 HR only, >0 specific sensor type id. */
        val ppgSensor: Int,
        val syncMinutes: Int,
        val phoneNotifications: Boolean,
        val followSystemTheme: Boolean,
        val onboarded: Boolean,
        /** Accent enum name (see ui.Accent); "DYNAMIC" = wallpaper colours. */
        val accent: String,
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values
    val current get() = _values.value

    private fun read() = Values(
        mode = runCatching { SamplingMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(SamplingMode.STANDARD),
        gyro = p.getBoolean("gyro", true),
        ppgSensor = p.getInt("ppgSensor", 0),
        syncMinutes = p.getInt("syncMinutes", 15),
        phoneNotifications = p.getBoolean("phoneNotif", true),
        followSystemTheme = p.getBoolean("systemTheme", false),
        onboarded = p.getBoolean("onboarded", false),
        accent = p.getString("accent", "DYNAMIC")!!,
    )

    fun update(block: (Values) -> Values) {
        val v = block(current)
        p.edit()
            .putString("mode", v.mode.name).putBoolean("gyro", v.gyro).putInt("ppgSensor", v.ppgSensor)
            .putInt("syncMinutes", v.syncMinutes).putBoolean("phoneNotif", v.phoneNotifications)
            .putBoolean("systemTheme", v.followSystemTheme).putBoolean("onboarded", v.onboarded)
            .putString("accent", v.accent)
            .apply()
        _values.value = v
    }

    /** Last stress baseline used by processing: "kind|rmssd". */
    var baselineInfo: String
        get() = p.getString("baseline", "default|40")!!
        set(v) = p.edit().putString("baseline", v).apply()

    var watchStatusJson: String
        get() = p.getString("watchStatus", "{}")!!
        set(v) = p.edit().putString("watchStatus", v).apply()

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: Settings(ctx.applicationContext).also { instance = it } }
    }
}
