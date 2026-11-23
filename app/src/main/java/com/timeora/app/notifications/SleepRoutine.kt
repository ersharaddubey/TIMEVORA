package com.timevora.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

object SleepRoutine {
    private const val ACTION_BEDTIME = "com.timevora.app.SLEEP_BEDTIME"
    private const val ACTION_WAKE = "com.timevora.app.SLEEP_WAKE"
    private const val ACTION_WINDDOWN = "com.timevora.app.SLEEP_WINDDOWN"

    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences("timevora", Context.MODE_PRIVATE)
        cancel(context, ACTION_BEDTIME, 7101)
        cancel(context, ACTION_WAKE, 7102)
        cancel(context, ACTION_WINDDOWN, 7103)
        if (!prefs.getBoolean("sleep_enabled", false)) return
        val bedtime = prefs.getInt("sleep_bedtime", 22 * 60)
        val wake = prefs.getInt("sleep_wake", 7 * 60)
        val lead = prefs.getInt("sleep_winddown_minutes", 30).coerceIn(0, 180)
        val winddownAt = (bedtime - lead + 1440) % 1440
        setNext(context, ACTION_BEDTIME, 7101, bedtime)
        setNext(context, ACTION_WAKE, 7102, wake)
        if (lead > 0) setNext(context, ACTION_WINDDOWN, 7103, winddownAt)
    }

    private fun setNext(context: Context, action: String, request: Int, minute: Int) {
        val now = Calendar.getInstance()
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(context, request, Intent(context, SleepRoutineReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        AlarmHelper.set(context, at.timeInMillis, pi)
    }

    private fun cancel(context: Context, action: String, request: Int) {
        val pi = PendingIntent.getBroadcast(context, request, Intent(context, SleepRoutineReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).cancel(pi)
    }

    fun onAlarm(context: Context, action: String?) {
        val prefs = context.getSharedPreferences("timevora", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sleep_enabled", false)) return
        when (action) {
            ACTION_BEDTIME -> Channels.post(context, Channels.SLEEP, 7101, "Time to wind down 🌙",
                "Your night routine starts now. Put your phone away, dim the lights and get ready for bed.")
            ACTION_WAKE -> Channels.post(context, Channels.SLEEP, 7102, "Good morning ☀️",
                "Your wake-up reminder is here. Start the day calmly and keep your routine consistent.")
            ACTION_WINDDOWN -> Channels.post(context, Channels.SLEEP, 7103, "Wind-down time 🌙",
                "Your bedtime is approaching. Start your night routine, dim the lights and put away distracting apps.")
        }
        schedule(context)
    }
}

class SleepRoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SleepRoutine.onAlarm(context, intent?.action)
    }
}
