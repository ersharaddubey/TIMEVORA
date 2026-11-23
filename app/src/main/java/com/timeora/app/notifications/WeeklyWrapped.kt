package com.timevora.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.timevora.app.MainActivity
import java.util.Calendar

/** Sunday 8 PM nudge that opens the Weekly Wrapped story cards. Switchable on the Wrapped screen. */
object WeeklyWrapped {
    const val CHANNEL = "weekly_wrapped"
    const val EXTRA_TAB = "open_tab"
    private const val ACTION = "com.timevora.app.WEEKLY_WRAPPED"
    private const val REQUEST = 7201
    private const val NOTIFICATION_ID = 7201
    private const val HOUR = 20

    fun enabled(c: Context): Boolean = c.getSharedPreferences("timevora", Context.MODE_PRIVATE).getBoolean("wrapped_notify", true)

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQUEST, Intent(c, WeeklyWrappedReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c))
        if (!enabled(c)) return
        val now = Calendar.getInstance()
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var guard = 0
        while ((at.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || !at.after(now)) && guard < 9) {
            at.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        AlarmHelper.set(c, at.timeInMillis, pending(c))
    }

    fun onAlarm(c: Context) {
        if (enabled(c)) post(c)
        schedule(c)
    }

    private fun post(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weekly Wrapped", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 4, Intent(c, MainActivity::class.java).putExtra(EXTRA_TAB, "Wrapped").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "Your top app, your best day and how much time you saved. Swipe through your week."
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Your Weekly Wrapped is ready 🎬")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}

class WeeklyWrappedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WeeklyWrapped.onAlarm(context)
    }
}
