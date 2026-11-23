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
import com.timevora.app.data.FocusSession
import com.timevora.app.data.Forest
import com.timevora.app.data.treeKindFor
import com.timevora.app.data.RewardEngine
import com.timevora.app.data.PlannerDone
import com.timevora.app.data.PlannerItem
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.occursOn
import com.timevora.app.data.triggerFor
import com.timevora.app.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

object Channels {
    const val PLANNER = "planner_reminders"
    const val FOCUS = "focus_sessions"
    const val BREAKS = "break_alerts"
    const val GOAL = "goal_alerts"
    const val SLEEP = "sleep_routine"
    const val AMBIENT = "ambient_sound"
    const val QUICK = "quick_capture"

    fun ensure(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(PLANNER, "Diary, task and meeting reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(FOCUS, "Focus sessions", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(BREAKS, "Break reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(GOAL, "Daily goal alerts", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(SLEEP, "Sleep and night routine", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(AMBIENT, "Ambient sounds", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(QUICK, "Quick capture", NotificationManager.IMPORTANCE_LOW))
    }

    fun post(c: Context, channel: String, id: Int, title: String, text: String) {
        ensure(c)
        val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }
}

/** Sets one alarm, exact when Android allows it, otherwise as close as the system permits. */
object AlarmHelper {
    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun set(c: Context, atMillis: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }
}

object PlannerAlarms {
    private const val ACTION_DONE = "com.timevora.app.PLANNER_DONE"

    private fun pending(c: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            c, (id and 0x7fffffffL).toInt(),
            Intent(c, PlannerAlarmReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Next reminder time for an item, or null when there is nothing left to remind. */
    fun nextTrigger(item: PlannerItem, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (item.minuteOfDay < 0 || item.remindBefore < 0) return null
        val limit = from.plusSeconds(1)
        val today = from.toLocalDate()
        for (d in 0..9) {
            val date = today.plusDays(d.toLong())
            if (item.occursOn(date)) {
                val t = item.triggerFor(date)
                if (t.isAfter(limit)) return t
            }
        }
        return null
    }

    fun schedule(c: Context, item: PlannerItem) {
        cancel(c, item.id)
        val t = nextTrigger(item) ?: return
        AlarmHelper.set(c, t.toInstant().toEpochMilli(), pending(c, item.id))
    }

    fun cancel(c: Context, id: Long) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, id))
    }

    suspend fun rescheduleAll(c: Context) {
        val items = timevoraDatabase.get(c).usageDao().plannerAll()
        items.forEach { schedule(c, it) }
    }

    internal fun notificationId(itemId: Long, date: LocalDate): Int = (itemId.toInt() * 31) + date.toEpochDay().toInt()

    internal fun doneIntent(c: Context, itemId: Long, date: LocalDate, notifId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, notifId,
            Intent(c, PlannerAlarmReceiver::class.java).setAction(ACTION_DONE).putExtra("id", itemId).putExtra("day", date.toString()).putExtra("nid", notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal fun isDoneAction(i: Intent) = i.action == ACTION_DONE
}

class PlannerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0L) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = timevoraDatabase.get(context).usageDao()
                if (PlannerAlarms.isDoneAction(intent)) {
                    val day = intent.getStringExtra("day") ?: LocalDate.now().toString()
                    dao.markDone(PlannerDone(id, day))
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(intent.getIntExtra("nid", 0))
                    return@launch
                }
                val item = dao.plannerById(id) ?: return@launch
                // Always line up the next reminder first, so a repeating item never stops.
                PlannerAlarms.schedule(context, item)
                val prefs = context.getSharedPreferences("timevora", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("planner_notifications", true)) return@launch
                val now = ZonedDateTime.now()
                val today = now.toLocalDate()
                val occ = listOf(today, today.plusDays(1))
                    .filter { item.occursOn(it) }
                    .minByOrNull { abs(Duration.between(item.triggerFor(it), now).toMinutes()) } ?: return@launch
                if (dao.doneCount(id, occ.toString()) > 0) return@launch
                val kind = when (item.kind) { "meeting" -> "Meeting"; "routine" -> "Routine"; else -> "Task" }
                val whenText = if (item.remindBefore > 0) "starts in ${item.remindBefore} min (${Fmt.timeLabel(item.minuteOfDay)})" else "is now (${Fmt.timeLabel(item.minuteOfDay)})"
                val body = item.title + " " + whenText + (if (item.notes.isNotBlank()) "\n" + item.notes else "")
                Channels.ensure(context)
                val nid = PlannerAlarms.notificationId(item.id, occ)
                val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(context, Channels.PLANNER)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle("$kind: ${item.title}")
                    .setContentText(whenText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .addAction(0, "Mark done", PlannerAlarms.doneIntent(context, item.id, occ, nid))
                    .build()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(nid, n)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Focus timer state lives in SharedPreferences so the service, alarm and screen all agree. */
object FocusManager {
    private const val NAME = "timevora_focus"
    private val lock = Any()

    fun prefs(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isActive(c: Context): Boolean = prefs(c).getLong("end", 0L) > 0L

    private fun pending(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 9001, Intent(c, FocusEndReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Strict Mode (strict = true): stopping this session early needs a typed confirmation phrase. Nothing is ever blocked. */
    fun isStrict(c: Context): Boolean = prefs(c).getBoolean("strict", false)

    /** Pause the active focus timer and preserve the remaining duration for a recovery reset. */
    fun pauseForRecovery(c: Context): Long {
        val p = prefs(c)
        val end = p.getLong("end", 0L)
        if (end <= 0L) return 0L
        val remaining = (end - System.currentTimeMillis()).coerceAtLeast(1L)
        p.edit().putLong("recovery_remaining", remaining).putLong("recovery_paused_at", System.currentTimeMillis()).putLong("end", -1L).apply()
        val stats = c.getSharedPreferences("timevora_focus_stats", Context.MODE_PRIVATE)
        stats.edit().putInt("recovery_count", stats.getInt("recovery_count", 0) + 1).apply()
        c.getSystemService(AlarmManager::class.java).cancel(pending(c))
        return remaining
    }

    /** Resume a recovery-paused session, shifting its start by the pause duration so recovery time is excluded. */
    fun resumeAfterRecovery(c: Context) {
        val p = prefs(c)
        val remaining = p.getLong("recovery_remaining", 0L)
        if (remaining <= 0L || p.getLong("end", 0L) != -1L) return
        val now = System.currentTimeMillis()
        val pausedAt = p.getLong("recovery_paused_at", now)
        val start = p.getLong("start", now) + (now - pausedAt).coerceAtLeast(0L)
        val newEnd = now + remaining
        p.edit().putLong("start", start).putLong("end", newEnd).remove("recovery_remaining").remove("recovery_paused_at").apply()
        AlarmHelper.set(c, newEnd, pending(c))
        try { com.timevora.app.widget.timevoraWidgetProvider.requestRefresh(c) } catch (_: Exception) {}
    }

    fun isRecoveryPaused(c: Context): Boolean = prefs(c).getLong("end", 0L) == -1L && prefs(c).getLong("recovery_remaining", 0L) > 0L
    fun recoveryRemaining(c: Context): Long = prefs(c).getLong("recovery_remaining", 0L)

    fun start(c: Context, minutes: Int, label: String, strict: Boolean = false) {
        val now = System.currentTimeMillis()
        val end = now + minutes * 60_000L
        prefs(c).edit().clear().putLong("start", now).putLong("end", end).putInt("planned", minutes * 60).putString("label", label).putBoolean("strict", strict).commit()
        AlarmHelper.set(c, end, pending(c))
        try { com.timevora.app.widget.timevoraWidgetProvider.requestRefresh(c) } catch (_: Exception) {}
    }

    /** Re-arms the end alarm (after reboot or update). Finishes the session if it already ran out. */
    suspend fun restore(c: Context) {
        val end = prefs(c).getLong("end", 0L)
        if (end > 0L) {
            if (end <= System.currentTimeMillis()) finish(c, natural = true, notify = true)
            else AlarmHelper.set(c, end, pending(c))
        }
        // Pomodoro: a finished round may have just started a break; re-arm it or start the next round.
        try { Pomodoro.restore(c) } catch (_: Exception) {}
    }

    private data class Ended(val start: Long, val planned: Int, val actual: Int, val label: String, val day: String)

    /**
     * Ends the running session once. natural = the timer ran out, otherwise the user stopped it early.
     * Focus Forest: the tree withers if the session was stopped early or more than the grace time was spent in other apps.
     */
    suspend fun finish(c: Context, natural: Boolean, notify: Boolean) {
        val ended: Ended? = synchronized(lock) {
            val p = prefs(c)
            val end = p.getLong("end", 0L)
            if (end <= 0L) {
                null
            } else {
                val start = p.getLong("start", 0L)
                val planned = p.getInt("planned", 0)
                val label = p.getString("label", "") ?: ""
                p.edit().clear().commit()
                val actual = if (natural) planned else ((minOf(System.currentTimeMillis(), end) - start) / 1000L).toInt().coerceIn(0, planned)
                val day = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                Ended(start, planned, actual, label, day)
            }
        }
        c.getSystemService(AlarmManager::class.java).cancel(pending(c))
        if (ended == null) return
        if (natural) prefs(c).edit().putBoolean("mission_review_pending", true).putString("mission_review_label", ended.label).apply()
        // Ambient sound belongs to the session: it ends when the session ends.
        try { com.timevora.app.audio.AmbientPlayer.stopNow(c) } catch (_: Exception) {}
        val until = minOf(System.currentTimeMillis(), ended.start + ended.planned * 1000L)
        val leakedMs = try { Forest.leakedMillis(c, ended.start, until) } catch (_: Exception) { 0L }
        val withered = !natural || leakedMs > Forest.GRACE_LEAK_MS
        val session = FocusSession(
            day = ended.day, startMillis = ended.start, plannedSeconds = ended.planned, actualSeconds = ended.actual,
            label = ended.label, leakedSeconds = (leakedMs / 1000L).toInt(), withered = withered
        )
        if (natural || session.actualSeconds >= 10) {
            timevoraDatabase.get(c).usageDao().insertFocus(session)
            if (session.actualSeconds >= 15 * 60) {
                RewardEngine.award(
                    c,
                    eventKey = "focus-${session.startMillis}",
                    day = session.day,
                    points = if (natural && !withered) 20 else 10,
                    reason = if (natural && !withered) "Completed focus session" else "Focus session (15+ minutes)"
                )
            }
        }
        if (natural && notify) {
            val growsTree = session.plannedSeconds >= Forest.MIN_TREE_SECONDS
            if (growsTree && withered) {
                Channels.post(c, Channels.FOCUS, 42, "Timer done, but your tree withered",
                    "You spent ${Fmt.hms(leakedMs)} in other apps during this session. Plant a new tree and keep the phone down this time.")
            } else if (growsTree) {
                val streak = try { RewardEngine.streakStats(timevoraDatabase.get(c).usageDao().treeDays()).current } catch (_: Exception) { 0 }
                Channels.post(c, Channels.FOCUS, 42, "Your ${treeKindFor(session.plannedSeconds).label.lowercase()} is fully grown",
                    "You stayed focused for ${Fmt.hms(session.actualSeconds * 1000L)}. Forest streak: $streak day(s). Open timevora to see it.")
            } else {
                Channels.post(c, Channels.FOCUS, 42, "Focus session complete",
                    "You stayed focused for ${Fmt.hms(session.actualSeconds * 1000L)}. Take a short break, you earned it.")
            }
        }
        // Pomodoro auto-cycle: start the break, finish the cycle, or drop it if the person quit.
        val treeNote = when {
            session.plannedSeconds < Forest.MIN_TREE_SECONDS -> ""
            withered -> "Tree withered."
            else -> "Tree grown 🌳."
        }
        try { Pomodoro.onWorkEnded(c, natural, notify, treeNote) } catch (_: Exception) {}
        // The Focus-timer widget goes back to its start buttons (or shows the Pomodoro break).
        try { com.timevora.app.widget.timevoraWidgetProvider.requestRefresh(c) } catch (_: Exception) {}
    }
}

class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { FocusManager.finish(context, natural = true, notify = true) } finally { pendingResult.finish() }
        }
    }
}
