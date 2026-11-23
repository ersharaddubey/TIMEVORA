package com.timevora.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timevora.app.audio.AmbientPlayer
import com.timevora.app.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The choices on the Focus screen that are remembered between sessions. */
object FocusSettings {
    private fun p(c: Context) = c.getSharedPreferences("timevora_focus_ui", Context.MODE_PRIVATE)
    fun pomodoro(c: Context) = p(c).getBoolean("pomodoro", false)
    fun setPomodoro(c: Context, v: Boolean) { p(c).edit().putBoolean("pomodoro", v).apply() }
    /** Work minutes of the chosen preset (25, 50, 15 or 90). */
    fun workMinutes(c: Context) = p(c).getInt("work", 25)
    fun setWorkMinutes(c: Context, v: Int) { p(c).edit().putInt("work", v).apply() }
    fun rounds(c: Context) = p(c).getInt("rounds", 4)
    fun setRounds(c: Context, v: Int) { p(c).edit().putInt("rounds", v).apply() }
    fun strict(c: Context) = p(c).getBoolean("strict", false)
    fun setStrict(c: Context, v: Boolean) { p(c).edit().putBoolean("strict", v).apply() }
}

/**
 * Pomodoro auto-cycle: work, break, work, break ... for a set number of rounds, with no taps in between.
 * Every work phase is a normal focus session (so it grows a tree and counts as focus time); the break is tracked here.
 * No other app is ever blocked: Strict Mode only adds friction to quitting (see FocusManager.isStrict and the quit dialog).
 */
object Pomodoro {
    private const val NAME = "timevora_pomodoro"
    private val lock = Any()

    /** work minutes to break minutes */
    val PRESETS = listOf(25 to 5, 50 to 10, 15 to 3, 90 to 20)
    fun breakFor(workMin: Int): Int = PRESETS.firstOrNull { it.first == workMin }?.second ?: 5

    fun prefs(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isOn(c: Context): Boolean = prefs(c).getBoolean("on", false)
    fun breakEnd(c: Context): Long = prefs(c).getLong("breakEnd", 0L)
    fun inBreak(c: Context): Boolean = isOn(c) && breakEnd(c) > 0L
    fun round(c: Context): Int = prefs(c).getInt("round", 1)
    fun rounds(c: Context): Int = prefs(c).getInt("rounds", 1)
    fun workMin(c: Context): Int = prefs(c).getInt("workMin", 25)
    fun breakMin(c: Context): Int = prefs(c).getInt("breakMin", 5)
    fun strict(c: Context): Boolean = isOn(c) && prefs(c).getBoolean("strict", false)

    private fun pendingBreak(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 9002, Intent(c, PomodoroBreakReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Starts round 1 of a new cycle. */
    fun begin(c: Context, workMin: Int, breakMin: Int, rounds: Int, label: String, strict: Boolean) {
        synchronized(lock) {
            prefs(c).edit().clear()
                .putBoolean("on", true).putInt("round", 1).putInt("rounds", rounds.coerceIn(1, 12))
                .putInt("workMin", workMin).putInt("breakMin", breakMin).putString("label", label)
                .putBoolean("strict", strict).putLong("breakEnd", 0L).commit()
        }
        FocusManager.start(c, workMin, label, strict)
    }

    /** Forgets the cycle (and any break alarm). Safe to call when no cycle exists. */
    fun endCycle(c: Context) {
        synchronized(lock) { prefs(c).edit().clear().commit() }
        c.getSystemService(AlarmManager::class.java).cancel(pendingBreak(c))
        try { com.timevora.app.widget.timevoraWidgetProvider.requestRefresh(c) } catch (_: Exception) {}
    }

    /** Called by FocusManager when a work phase has ended. Decides: break, finished, or the person quit. */
    fun onWorkEnded(c: Context, natural: Boolean, notify: Boolean, treeNote: String) {
        var result = ""
        var round = 0
        var rounds = 0
        var breakMin = 0
        var at = 0L
        synchronized(lock) {
            val p = prefs(c)
            if (p.getBoolean("on", false) && p.getLong("breakEnd", 0L) == 0L) {
                round = p.getInt("round", 1)
                rounds = p.getInt("rounds", 1)
                breakMin = p.getInt("breakMin", 5)
                when {
                    !natural -> { p.edit().clear().commit(); result = "quit" }
                    round >= rounds -> { p.edit().clear().commit(); result = "done" }
                    else -> { at = System.currentTimeMillis() + breakMin * 60_000L; p.edit().putLong("breakEnd", at).commit(); result = "break" }
                }
            }
        }
        when (result) {
            "break" -> {
                AlarmHelper.set(c, at, pendingBreak(c))
                if (notify) Channels.post(c, Channels.FOCUS, 42, "Round $round of $rounds done: break time ☕", "$treeNote Rest for $breakMin min. The next round starts by itself.")
            }
            "done" -> {
                c.getSystemService(AlarmManager::class.java).cancel(pendingBreak(c))
                if (notify) Channels.post(c, Channels.FOCUS, 42, "Pomodoro complete 🎉", "All $rounds rounds are done. $treeNote Great work, you can relax now.")
            }
            "quit" -> c.getSystemService(AlarmManager::class.java).cancel(pendingBreak(c))
        }
    }

    /** The break is over (alarm, or the screen noticed): starts the next work round. Returns false if there was nothing to start. */
    fun nextWork(c: Context, notify: Boolean): Boolean {
        val p = prefs(c)
        var round = 0
        val go = synchronized(lock) {
            if (!p.getBoolean("on", false) || p.getLong("breakEnd", 0L) <= 0L) {
                false
            } else {
                round = p.getInt("round", 1) + 1
                p.edit().putInt("round", round).putLong("breakEnd", 0L).commit()
                true
            }
        }
        if (!go) return false
        c.getSystemService(AlarmManager::class.java).cancel(pendingBreak(c))
        val rounds = p.getInt("rounds", 1)
        val work = p.getInt("workMin", 25)
        FocusManager.start(c, work, p.getString("label", "") ?: "", p.getBoolean("strict", false))
        // The sound stopped with the last round; bring it back. Android may refuse this from the background, which is fine.
        try { AmbientPlayer.savedSound(c)?.let { AmbientPlayer.play(c, it) } } catch (_: Exception) {}
        if (notify) Channels.post(c, Channels.FOCUS, 43, "Back to focus: round $round of $rounds", "$work min starts now. Keep the phone down.")
        return true
    }

    /** After a reboot or app update: re-arm the break alarm, or start the next round if the break already ran out. */
    fun restore(c: Context) {
        if (!inBreak(c)) return
        val end = breakEnd(c)
        if (end <= System.currentTimeMillis()) nextWork(c, notify = true) else AlarmHelper.set(c, end, pendingBreak(c))
    }

    fun summary(c: Context): String = "${workMin(c)}/${breakMin(c)} min  •  round ${round(c)} of ${rounds(c)}"

    fun totalMinutes(work: Int, brk: Int, rounds: Int): Int = rounds * work + (rounds - 1) * brk

    @Suppress("unused")
    fun totalLabel(work: Int, brk: Int, rounds: Int): String = Fmt.hms(totalMinutes(work, brk, rounds) * 60_000L)
}

class PomodoroBreakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Pomodoro.nextWork(context, notify = true) } finally { pendingResult.finish() }
        }
    }
}
