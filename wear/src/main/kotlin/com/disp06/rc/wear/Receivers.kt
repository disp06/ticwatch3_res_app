package com.disp06.rc.wear

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.disp06.rc.shared.Protocol
import com.disp06.rc.shared.Trigger
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block] off the main thread while keeping the broadcast alive. */
internal fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    appScope.launch {
        try { block() } finally { pending.finish() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).collecting) SensorCollectorService.send(context, SensorCollectorService.ACTION_START)
        Reminders.rescheduleAll(context)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESTART -> if (Prefs(context).collecting) SensorCollectorService.send(context, SensorCollectorService.ACTION_START)
            ACTION_ECO -> if (Prefs(context).collecting) SensorCollectorService.send(context, SensorCollectorService.ACTION_ECO_WINDOW)
            ACTION_REMINDER -> {
                val id = intent.getLongExtra("id", -1)
                val rule = Reminders.load(context).firstOrNull { it.id == id }
                val msg = intent.getStringExtra("message") ?: rule?.message ?: return
                Reminders.show(context, id, "Reminder", msg, WatchApp.CH_REMINDERS)
                if (rule != null && !intent.getBooleanExtra("snooze", false)) Reminders.schedule(context, rule)
            }
        }
    }

    companion object {
        const val ACTION_RESTART = "com.disp06.rc.RESTART"
        const val ACTION_ECO = "com.disp06.rc.ECO"
        const val ACTION_REMINDER = "com.disp06.rc.REMINDER"

        private fun pi(ctx: Context, code: Int, i: Intent) =
            PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun restartIntent(ctx: Context) = pi(ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RESTART))
        fun ecoIntent(ctx: Context) = pi(ctx, 2, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ECO))
        fun reminderIntent(ctx: Context, id: Long, snooze: Boolean = false, message: String? = null) = pi(
            ctx, (if (snooze) 200_000 else 100_000) + id.toInt(),
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_REMINDER)
                .putExtra("id", id).putExtra("snooze", snooze).putExtra("message", message),
        )
    }
}

/** Snooze / Done buttons on reminder notifications; reports the action back to the phone. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val notifId = intent.getIntExtra("notifId", 0)
        val action = intent.getStringExtra("action") ?: return
        val message = intent.getStringExtra("message")
        context.getSystemService(NotificationManager::class.java).cancel(notifId)
        if (action == "snooze") {
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60_000L,
                AlarmReceiver.reminderIntent(context, id, snooze = true, message = message),
            )
        }
        async {
            val payload = JSONObject().put("ruleId", id).put("action", action).put("at", System.currentTimeMillis())
            runCatching {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                for (n in nodes) {
                    Wearable.getMessageClient(context).sendMessage(n.id, Protocol.MSG_REMINDER_ACTION, payload.toString().toByteArray()).await()
                }
            }
        }
    }
}

/** Time/interval reminders pushed by the phone and scheduled locally, so they fire offline too. */
object Reminders {
    data class Rule(val id: Long, val type: String, val value: String, val message: String)

    fun load(ctx: Context): List<Rule> = runCatching {
        val arr = JSONArray(Prefs(ctx).scheduleJson)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Rule(o.getLong("id"), o.getString("type"), o.getString("value"), o.getString("message"))
        }
    }.getOrDefault(emptyList())

    fun replaceAll(ctx: Context, rules: List<Rule>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        load(ctx).forEach { am.cancel(AlarmReceiver.reminderIntent(ctx, it.id)) }
        Prefs(ctx).scheduleJson = JSONArray().apply {
            rules.forEach { put(JSONObject().put("id", it.id).put("type", it.type).put("value", it.value).put("message", it.message)) }
        }.toString()
        rules.forEach { schedule(ctx, it) }
    }

    fun rescheduleAll(ctx: Context) = load(ctx).forEach { schedule(ctx, it) }

    fun schedule(ctx: Context, rule: Rule) {
        val trigger = Trigger.parse(rule.type, rule.value) ?: return
        val at = Trigger.nextFire(trigger, System.currentTimeMillis(), ZoneId.systemDefault()) ?: return
        ctx.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, AlarmReceiver.reminderIntent(ctx, rule.id))
    }

    fun show(ctx: Context, ruleId: Long, title: String, text: String, channel: String) {
        val notifId = (1000 + (ruleId and 0xFFFF)).toInt() + if (channel == WatchApp.CH_ALERTS) 70_000 else 0
        fun action(label: String, a: String, code: Int) = NotificationCompat.Action(
            0, label,
            PendingIntent.getBroadcast(
                ctx, notifId * 4 + code,
                Intent(ctx, ReminderActionReceiver::class.java)
                    .putExtra("id", ruleId).putExtra("notifId", notifId).putExtra("action", a).putExtra("message", text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(action("Done", "done", 1))
            .addAction(action("Snooze 15 min", "snooze", 2))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notifId, n)
    }
}
