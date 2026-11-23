package com.timevora.app.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Streak Freeze: a missed day does not break a streak of 3+ days while a freeze is available.
 *
 * - Everyone starts with [STARTER] freeze; you can hold at most [MAX].
 * - One more freeze is earned for every [DAYS_PER_FREEZE] days of an unbroken streak.
 * - Freezes are used automatically, the next time the app evaluates the streak, for the days just missed
 *   (only if the missed days are no more than the freezes you hold, so a freeze is never wasted on a streak that
 *   would break anyway). A frozen day keeps the streak alive but does not add to its length.
 * - Applies to the "stay active" streak (habits, planner, study, focus). The Forest streak is separate.
 * Everything is stored on the device in SharedPreferences.
 */
object StreakFreeze {
    const val MAX = 2
    const val STARTER = 1
    const val MIN_STREAK_TO_PROTECT = 3
    const val DAYS_PER_FREEZE = 7

    private val lock = Mutex()
    private fun prefs(c: Context) = c.getSharedPreferences("timevora", Context.MODE_PRIVATE)

    fun count(c: Context): Int = prefs(c).getInt("freeze_count", STARTER).coerceIn(0, MAX)

    fun usedDays(c: Context): Set<String> = HashSet(prefs(c).getStringSet("freeze_used_days", emptySet()) ?: emptySet())

    /** A short message when a freeze protected a day in the last few days, otherwise empty. */
    fun recentNote(c: Context): String {
        val last = parseDays(usedDays(c)).lastOrNull() ?: return ""
        val age = ChronoUnit.DAYS.between(last, LocalDate.now())
        return if (age in 0..3) "🧊 A freeze protected ${last.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, so your streak is safe." else ""
    }

    suspend fun update(c: Context) = lock.withLock {
        val p = prefs(c)
        val dao = timevoraDatabase.get(c).usageDao()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val activeList = dao.activityDays(15 * 60)
        val active = parseDays(activeList)
        val used = parseDays(usedDays(c)).toMutableSet()
        var freezes = count(c)
        var changed = false

        // 1. Days missed up to yesterday (a run longer than MAX can never be covered, so stop counting there).
        var missed = 0
        var d = yesterday
        while (d !in active && d !in used && missed <= MAX) { missed++; d = d.minusDays(1) }
        if (missed in 1..minOf(freezes, MAX)) {
            // d is now the last protected/active day before the gap: how long was the streak that the gap interrupts?
            var run = 0
            var x = d
            while (x in active || x in used) { if (x in active) run++; x = x.minusDays(1) }
            if (run >= MIN_STREAK_TO_PROTECT) {
                for (i in 0 until missed) used.add(yesterday.minusDays(i.toLong()))
                freezes -= missed
                changed = true
            }
        }

        // 2. Earn a freeze for each completed block of 7 streak days (once per streak and block).
        val streak = RewardEngine.streakStats(activeList, today, used.map { it.toString() })
        val start = streak.start
        val earned = HashSet(p.getStringSet("freeze_earned", emptySet()) ?: emptySet())
        if (start != null) {
            for (m in 1..(streak.current / DAYS_PER_FREEZE)) {
                if (earned.add("$start#$m")) {
                    freezes = minOf(MAX, freezes + 1)
                    changed = true
                }
            }
        }

        if (changed) {
            p.edit()
                .putInt("freeze_count", freezes.coerceIn(0, MAX))
                .putStringSet("freeze_used_days", used.map { it.toString() }.toHashSet())
                .putStringSet("freeze_earned", earned)
                .apply()
        }
    }
}
