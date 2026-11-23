package com.timevora.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.timevora.app.MainActivity
import com.timevora.app.Palette
import com.timevora.app.QuickCaptureActivity
import com.timevora.app.R
import com.timevora.app.ThemeStore
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.todayKey
import com.timevora.app.notifications.FocusManager
import com.timevora.app.notifications.Pomodoro
import com.timevora.app.notifications.WeeklyWrapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Today's numbers, read once per refresh and shared by every widget size. */
internal data class Snapshot(
    val screen: Long,
    val focusSeconds: Long,
    val xp: Int,
    val habitsDone: Int,
    val habitsActive: Int,
    val lifeScore: Int
)

/**
 * Draws all timevora widgets (Small, Medium, Large and the Focus timer) with the current theme.
 * Local-only: nothing here touches the network.
 */
object WidgetUpdater {
    fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        fun idsOf(cls: Class<*>): IntArray = manager.getAppWidgetIds(ComponentName(app, cls))
        val large = idsOf(timevoraWidgetProvider::class.java)
        val small = idsOf(SmallWidgetProvider::class.java)
        val medium = idsOf(MediumWidgetProvider::class.java)
        val focus = idsOf(FocusTimerWidgetProvider::class.java)
        if (large.isEmpty() && small.isEmpty() && medium.isEmpty() && focus.isEmpty()) return

        val palette = ThemeStore.resolve(app)
        val snap = loadSnapshot(app)
        large.forEach { manager.updateAppWidget(it, buildLarge(app, it, snap, palette)) }
        small.forEach { manager.updateAppWidget(it, buildSmall(app, it, snap, palette)) }
        medium.forEach { manager.updateAppWidget(it, buildMedium(app, it, snap, palette)) }
        focus.forEach { manager.updateAppWidget(it, buildFocus(app, it, snap, palette)) }
    }

    private fun loadSnapshot(context: Context): Snapshot {
        val day = todayKey()
        return runBlocking(Dispatchers.IO) {
            val dao = timevoraDatabase.get(context).usageDao()
            Snapshot(
                screen = dao.total(day),
                focusSeconds = dao.focusSeconds(day),
                xp = dao.xpToday(day),
                habitsDone = dao.completedHabits(day),
                habitsActive = dao.activeHabitCount(),
                lifeScore = dao.recentScores(1).firstOrNull()?.score ?: 0
            )
        }
    }

    // ---------- small helpers ----------

    private fun durationLabel(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis.coerceAtLeast(0))
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (hours > 0) "${hours}h ${remainder}m" else "${remainder}m"
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openApp(context: Context, code: Int, tab: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab != null) i.putExtra(WeeklyWrapped.EXTRA_TAB, tab)
        return PendingIntent.getActivity(context, code, i, flags())
    }

    private fun capture(context: Context, code: Int, mode: String, voice: Boolean): PendingIntent =
        PendingIntent.getActivity(context, code, QuickCaptureActivity.intent(context, mode, voice), flags())

    private fun RemoteViews.paint(bgId: Int, color: Int) = setInt(bgId, "setColorFilter", color)

    private fun RemoteViews.text(id: Int, value: CharSequence, color: Int) {
        setTextViewText(id, value)
        setTextColor(id, color)
    }

    private fun RemoteViews.pill(id: Int, bgId: Int, txId: Int, label: String, p: Palette, click: PendingIntent, filled: Boolean = true) {
        // Filled pills use the accent colour; the quieter ones use a soft tint of it.
        paint(bgId, if (filled) p.accent else p.soft)
        setTextViewText(txId, label)
        setTextColor(txId, if (filled) p.onAccent else p.accent)
        setOnClickPendingIntent(id, click)
    }

    // ---------- Large (4x4) ----------

    private fun buildLarge(c: Context, id: Int, s: Snapshot, p: Palette): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_timeora)
        v.paint(R.id.widget_bg, p.card)
        v.text(R.id.widget_title, "timevora", p.accent)
        v.text(R.id.widget_refresh, "↻ Refresh", p.muted)
        v.text(R.id.widget_date, "TODAY • ${todayKey()}", p.muted)
        listOf(R.id.widget_lbl_screen, R.id.widget_lbl_focus, R.id.widget_lbl_xp, R.id.widget_lbl_habits, R.id.widget_lbl_life)
            .forEach { v.setTextColor(it, p.muted) }
        v.text(R.id.widget_screen_time, durationLabel(s.screen), p.ink)
        v.text(R.id.widget_focus, durationLabel(s.focusSeconds * 1000L), p.ink)
        v.text(R.id.widget_xp, "+${s.xp} XP", p.accent)
        v.text(R.id.widget_habits, "${s.habitsDone}/${s.habitsActive} done", p.ink)
        v.text(R.id.widget_life_score, if (s.lifeScore > 0) "${s.lifeScore}/100" else "—", p.accent)
        v.setOnClickPendingIntent(R.id.widget_root, openApp(c, 100_000 + id))
        v.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(c, 200_000 + id, Intent(c, timevoraWidgetProvider::class.java).setAction(timevoraWidgetProvider.ACTION_REFRESH), flags())
        )
        v.pill(R.id.widget_add_task, R.id.widget_add_task_bg, R.id.widget_add_task_tx, "＋ Task", p, capture(c, 3001, "task", false))
        v.pill(R.id.widget_add_note, R.id.widget_add_note_bg, R.id.widget_add_note_tx, "📝 Note", p, capture(c, 3002, "note", false), filled = false)
        v.pill(R.id.widget_add_voice, R.id.widget_add_voice_bg, R.id.widget_add_voice_tx, "🎤 Voice", p, capture(c, 3003, "task", true), filled = false)
        return v
    }

    // ---------- Small (2x2) ----------

    private fun buildSmall(c: Context, id: Int, s: Snapshot, p: Palette): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_small)
        v.paint(R.id.sm_bg, p.card)
        v.text(R.id.sm_title, "timevora", p.accent)
        v.text(R.id.sm_lbl_focus, "FOCUS TODAY", p.muted)
        v.text(R.id.sm_focus, durationLabel(s.focusSeconds * 1000L), p.ink)
        v.text(R.id.sm_xp, "+${s.xp} XP", p.accent)
        v.text(R.id.sm_habits, "${s.habitsDone}/${s.habitsActive} habits", p.muted)
        v.setOnClickPendingIntent(R.id.sm_root, openApp(c, 300_000 + id))
        return v
    }

    // ---------- Medium (4x2) ----------

    private fun buildMedium(c: Context, id: Int, s: Snapshot, p: Palette): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_medium)
        v.paint(R.id.md_bg, p.card)
        listOf(R.id.md_lbl_screen, R.id.md_lbl_focus, R.id.md_lbl_xp, R.id.md_lbl_habits).forEach { v.setTextColor(it, p.muted) }
        v.text(R.id.md_screen, durationLabel(s.screen), p.ink)
        v.text(R.id.md_focus, durationLabel(s.focusSeconds * 1000L), p.ink)
        v.text(R.id.md_xp, "+${s.xp}", p.accent)
        v.text(R.id.md_habits, "${s.habitsDone}/${s.habitsActive}", p.ink)
        v.setOnClickPendingIntent(R.id.md_root, openApp(c, 400_000 + id))
        v.pill(R.id.md_task, R.id.md_task_bg, R.id.md_task_tx, "＋ Task", p, capture(c, 3011, "task", false))
        v.pill(R.id.md_note, R.id.md_note_bg, R.id.md_note_tx, "📝 Note", p, capture(c, 3012, "note", false), filled = false)
        v.pill(R.id.md_voice, R.id.md_voice_bg, R.id.md_voice_tx, "🎤 Voice", p, capture(c, 3013, "task", true), filled = false)
        return v
    }

    // ---------- Focus timer (4x2) ----------

    private fun buildFocus(c: Context, id: Int, s: Snapshot, p: Palette): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_focus)
        v.paint(R.id.fw_bg, p.card)
        val now = System.currentTimeMillis()
        val inBreak = Pomodoro.inBreak(c)
        val active = FocusManager.isActive(c)
        val focusPrefs = FocusManager.prefs(c)
        val end = if (inBreak) Pomodoro.breakEnd(c) else focusPrefs.getLong("end", 0L)

        if ((inBreak || active) && end > now) {
            // Running: live countdown (the system ticks it, no updates needed). Tapping opens the Focus screen,
            // where stopping early asks for confirmation (a tree withers, Strict Mode needs its phrase).
            v.setViewVisibility(R.id.fw_idle, View.GONE)
            v.setViewVisibility(R.id.fw_run, View.VISIBLE)
            val label = when {
                inBreak -> "☕ BREAK  •  round ${Pomodoro.round(c)} of ${Pomodoro.rounds(c)} done"
                Pomodoro.isOn(c) -> "🍅 ROUND ${Pomodoro.round(c)} OF ${Pomodoro.rounds(c)}" + (focusPrefs.getString("label", "")?.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: "")
                else -> (focusPrefs.getString("label", "") ?: "").ifBlank { "FOCUS SESSION" }
            }
            v.text(R.id.fw_run_label, label, p.accent)
            v.setChronometerCountDown(R.id.fw_timer, true)
            v.setChronometer(R.id.fw_timer, SystemClock.elapsedRealtime() + (end - now), null, true)
            v.setTextColor(R.id.fw_timer, p.ink)
            v.text(R.id.fw_run_hint, if (inBreak) "Next round starts by itself  •  tap to open" else "Keep the phone down  •  tap to open", p.muted)
            v.setOnClickPendingIntent(R.id.fw_root, openApp(c, 500_000 + id, "Focus"))
        } else {
            v.setViewVisibility(R.id.fw_run, View.GONE)
            v.setViewVisibility(R.id.fw_idle, View.VISIBLE)
            v.text(R.id.fw_title, "🍅 Start a focus session", p.ink)
            v.text(R.id.fw_today, "Today " + durationLabel(s.focusSeconds * 1000L), p.muted)
            fun start(minutes: Int) = PendingIntent.getBroadcast(
                c, 7000 + minutes,
                Intent(c, FocusWidgetReceiver::class.java).setAction(FocusWidgetReceiver.ACTION_START).putExtra(FocusWidgetReceiver.EXTRA_MINUTES, minutes),
                flags()
            )
            v.pill(R.id.fw_p15, R.id.fw_p15_bg, R.id.fw_p15_tx, "15m", p, start(15), filled = false)
            v.pill(R.id.fw_p25, R.id.fw_p25_bg, R.id.fw_p25_tx, "25m", p, start(25))
            v.pill(R.id.fw_p45, R.id.fw_p45_bg, R.id.fw_p45_tx, "45m", p, start(45), filled = false)
            v.pill(R.id.fw_p60, R.id.fw_p60_bg, R.id.fw_p60_tx, "60m", p, start(60), filled = false)
            v.pill(
                R.id.fw_pomo, R.id.fw_pomo_bg, R.id.fw_pomo_tx, "🍅", p,
                PendingIntent.getBroadcast(c, 7999, Intent(c, FocusWidgetReceiver::class.java).setAction(FocusWidgetReceiver.ACTION_POMODORO), flags()),
                filled = false
            )
            v.setOnClickPendingIntent(R.id.fw_root, openApp(c, 500_000 + id))
        }
        return v
    }
}
