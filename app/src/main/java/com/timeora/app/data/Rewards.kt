package com.timevora.app.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** A deterministic achievement definition; IDs are persisted, labels remain local to the app. */
data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val threshold: Int,
    val icon: String
)

/** [start] is the first active day of the current streak (null when there is no streak). */
data class StreakStats(val current: Int, val best: Int, val activeDays: Int, val start: LocalDate? = null)
data class LevelProgress(val level: Int, val currentXp: Int, val nextLevelXp: Int, val progress: Float)

object RewardCatalog {
    val achievements = listOf(
        AchievementDefinition("first_focus", "First Focus", "Complete your first focus session", 1, "🎯"),
        AchievementDefinition("focus_10", "Focus Builder", "Complete 10 focus sessions", 10, "⏱️"),
        AchievementDefinition("focus_50", "Deep Work Pro", "Complete 50 focus sessions", 50, "🧠"),
        AchievementDefinition("first_habit", "Habit Starter", "Complete your first habit", 1, "🌱"),
        AchievementDefinition("habit_25", "Habit Builder", "Complete 25 habits", 25, "🌿"),
        AchievementDefinition("habit_100", "Consistency Champion", "Complete 100 habits", 100, "🏆"),
        AchievementDefinition("tasks_10", "Getting Things Done", "Complete 10 planner items", 10, "✅"),
        AchievementDefinition("tasks_50", "Execution Expert", "Complete 50 planner items", 50, "📋"),
        AchievementDefinition("streak_3", "Three-Day Streak", "Stay active for 3 consecutive days", 3, "🔥"),
        AchievementDefinition("streak_7", "Week of Wins", "Stay active for 7 consecutive days", 7, "🔥"),
        AchievementDefinition("streak_30", "Unstoppable", "Stay active for 30 consecutive days", 30, "💎"),
        AchievementDefinition("focus_hours_10", "Focus Hours I", "Spend 10 hours in focus sessions", 10, "⏳"),
        AchievementDefinition("focus_hours_100", "100 Focus Hours", "Spend 100 hours in focus sessions", 100, "🕰️"),
        AchievementDefinition("night_owl_fixed", "Night Owl Fixed", "5 nights in a row with no phone use between 12 AM and 5 AM, after a late-night habit", 5, "🦉"),
        AchievementDefinition("freeze_saved", "Saved by the Freeze", "A Streak Freeze protected your streak", 1, "🧊"),
        AchievementDefinition("xp_100", "Rising Star", "Earn 100 total XP", 100, "⭐"),
        AchievementDefinition("xp_500", "Time Investor", "Earn 500 total XP", 500, "🌟"),
        AchievementDefinition("xp_1000", "timevora Elite", "Earn 1,000 total XP", 1000, "👑")
    )
}

object RewardEngine {
    /** The unique event key is the idempotency key: repeated callbacks never grant duplicate XP. */
    suspend fun award(
        context: Context,
        eventKey: String,
        day: String = todayKey(),
        points: Int,
        reason: String
    ): Boolean {
        if (points <= 0 || eventKey.isBlank()) return false
        val dao = timevoraDatabase.get(context).usageDao()
        val inserted = dao.addXp(XpTransaction(eventKey, day, points, reason, System.currentTimeMillis()))
        evaluateAchievements(context)
        return inserted != -1L
    }

    suspend fun evaluateAchievements(context: Context) {
        // Streak Freeze first: it may bridge a missed day and hand out a freeze earned by a long streak.
        StreakFreeze.update(context)
        val dao = timevoraDatabase.get(context).usageDao()
        val unlocked = dao.unlockedAchievements().map { it.achievementId }.toSet()
        val values = progress(context)
        RewardCatalog.achievements.forEach { achievement ->
            val progress = values[achievement.id] ?: 0
            if (achievement.id !in unlocked && progress >= achievement.threshold) {
                val now = System.currentTimeMillis()
                val inserted = dao.unlockAchievement(AchievementUnlock(achievement.id, now, progress))
                if (inserted != -1L) {
                    dao.addXp(XpTransaction("achievement-${achievement.id}", todayKey(), 25, "Achievement unlocked: ${achievement.title}", now))
                }
            }
        }
    }

    /** Current progress value for every achievement id (the same numbers drive unlocking and the progress text on screen). */
    suspend fun progress(context: Context): Map<String, Int> {
        val dao = timevoraDatabase.get(context).usageDao()
        val focus = dao.focusSessionCount()
        val habits = dao.completedHabitLogCount()
        val tasks = dao.completedPlannerCount()
        val xp = dao.totalXp()
        val streak = currentStreak(context)
        val focusHours = (dao.focusSecondsTotal() / 3600L).toInt()
        val owl = if (dao.unlockedAchievements().any { it.achievementId == "night_owl_fixed" }) 5 else nightOwlProgress(context)
        return mapOf(
            "first_focus" to focus, "focus_10" to focus, "focus_50" to focus,
            "first_habit" to habits, "habit_25" to habits, "habit_100" to habits,
            "tasks_10" to tasks, "tasks_50" to tasks,
            "streak_3" to streak.current, "streak_7" to streak.current, "streak_30" to streak.current,
            "focus_hours_10" to focusHours, "focus_hours_100" to focusHours,
            "night_owl_fixed" to owl,
            "freeze_saved" to StreakFreeze.usedDays(context).size,
            "xp_100" to xp, "xp_500" to xp, "xp_1000" to xp
        )
    }

    /** The "stay active" streak, with days protected by a Streak Freeze bridged. */
    suspend fun currentStreak(context: Context): StreakStats {
        val dao = timevoraDatabase.get(context).usageDao()
        return streakStats(dao.activityDays(15 * 60), frozenDays = StreakFreeze.usedDays(context))
    }

    /**
     * Night Owl Fixed: number of nights in a row (up to 5, counting back from yesterday) with no phone use between
     * 12 AM and 5 AM. Only counts when tracking clearly ran that day, and only reaches 5 if the person really had a
     * late-night habit before (2+ nights with 10+ minutes of use in the 14 days before the clean run).
     * timevora's own screen time is ignored.
     */
    suspend fun nightOwlProgress(context: Context): Int {
        val dao = timevoraDatabase.get(context).usageDao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        suspend fun late(d: LocalDate): Long = dao.usageBetweenExcluding(
            d.atStartOfDay(zone).toInstant().toEpochMilli(),
            d.atTime(5, 0).atZone(zone).toInstant().toEpochMilli(),
            context.packageName
        )
        var clean = 0
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            if (dao.total(d.toString()) > 0L && late(d) < 60_000L) clean++ else break
        }
        if (clean < 5) return clean
        val owlNights = (6..19).count { late(today.minusDays(it.toLong())) >= 10 * 60_000L }
        return if (owlNights >= 2) 5 else 0
    }

    /**
     * [frozenDays] are days protected by a Streak Freeze: they keep the streak alive but do not add to its length.
     */
    fun streakStats(activityDays: List<String>, today: LocalDate = LocalDate.now(), frozenDays: Collection<String> = emptyList()): StreakStats {
        val dates = parseDays(activityDays)
        if (dates.isEmpty()) return StreakStats(0, 0, 0)
        val frozen = parseDays(frozenDays)
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        dates.forEach { date ->
            run = if (previous != null && bridged(previous!!, date, frozen)) run + 1 else 1
            if (run > best) best = run
            previous = date
        }
        // Today may still be in progress, so an inactive today does not break the streak yet.
        var cursor: LocalDate = if (today in dates) today else today.minusDays(1)
        var current = 0
        var start: LocalDate? = null
        while (true) {
            if (cursor in dates) { current++; start = cursor }
            else if (cursor !in frozen) break
            cursor = cursor.minusDays(1)
        }
        return StreakStats(current, best, dates.size, start)
    }

    /** True when every day strictly between [from] and [to] is a frozen day (so there is no real gap). */
    private fun bridged(from: LocalDate, to: LocalDate, frozen: Set<LocalDate>): Boolean {
        var d = from.plusDays(1)
        while (d.isBefore(to)) {
            if (d !in frozen) return false
            d = d.plusDays(1)
        }
        return true
    }

    fun levelProgress(totalXp: Int): LevelProgress {
        val safe = totalXp.coerceAtLeast(0)
        val level = safe / 250 + 1
        val current = safe % 250
        return LevelProgress(level, current, 250, current / 250f)
    }
}

internal fun parseDays(days: Collection<String>): java.util.SortedSet<LocalDate> =
    days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSortedSet()
