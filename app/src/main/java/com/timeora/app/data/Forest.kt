package com.timevora.app.data

import android.content.Context

/** Focus Forest: every focus session plants a tree. Phone use during the session makes it wither. */
enum class TreeKind(val label: String) {
    SAPLING("Sapling"), OAK("Oak"), PINE("Pine"), BANYAN("Giant banyan")
}

/** Longer sessions grow bigger trees. */
fun treeKindFor(plannedSeconds: Int): TreeKind = when {
    plannedSeconds >= 3600 -> TreeKind.BANYAN
    plannedSeconds >= 1800 -> TreeKind.PINE
    plannedSeconds >= 900 -> TreeKind.OAK
    else -> TreeKind.SAPLING
}

/** A tree is healthy only if the session ran to the end and the phone stayed (almost) untouched. */
fun FocusSession.isHealthyTree(): Boolean = !withered && actualSeconds >= plannedSeconds

data class ForestTree(val kind: TreeKind, val withered: Boolean, val day: String)

data class ForestTier(val level: Int, val name: String, val minStreak: Int, val blurb: String)

object ForestTiers {
    val all = listOf(
        ForestTier(0, "Empty land", 0, "Finish a focus session without touching other apps to plant your first tree."),
        ForestTier(1, "Seedling field", 1, "Your first trees are growing. Come back tomorrow to keep the streak."),
        ForestTier(2, "Garden", 3, "Flowers have started to bloom around your trees."),
        ForestTier(3, "Forest", 7, "A real forest: sunshine, shade and a full week of focus."),
        ForestTier(4, "Woodland", 14, "Birds have moved in. Two weeks of steady focus."),
        ForestTier(5, "Jungle", 30, "A thick, glowing jungle. 30 days of focus, well done.")
    )

    fun forStreak(streak: Int): ForestTier = all.last { streak >= it.minStreak }
    fun next(streak: Int): ForestTier? = all.firstOrNull { it.minStreak > streak }
}

data class ForestSnapshot(
    val trees: List<ForestTree>,   // oldest first, at most Forest.MAX_SHOWN of the newest
    val healthyTotal: Int,
    val witheredTotal: Int,
    val streak: Int,
    val bestStreak: Int,
    val healthyToday: Int
)

object Forest {
    /** Sessions shorter than this do not plant a tree. */
    const val MIN_TREE_SECONDS = 300
    /** Total time in other apps that is forgiven (a quick glance does not kill the tree). */
    const val GRACE_LEAK_MS = 15_000L
    const val MAX_SHOWN = 48

    /**
     * Milliseconds spent in other apps between [startMillis] and [untilMillis].
     * Saved sessions plus the app that is open right now; timevora itself never counts.
     */
    suspend fun leakedMillis(c: Context, startMillis: Long, untilMillis: Long): Long {
        val stored = timevoraDatabase.get(c).usageDao().usageBetweenExcluding(startMillis, untilMillis, c.packageName)
        val live = Live.current(c)
        val liveMs = if (live != null && live.pkg != c.packageName)
            (untilMillis - maxOf(live.startMillis, startMillis)).coerceAtLeast(0L) else 0L
        return stored + liveMs
    }

    suspend fun load(c: Context): ForestSnapshot {
        val dao = timevoraDatabase.get(c).usageDao()
        val sessions = dao.forestSessions(MAX_SHOWN).reversed()
        val trees = sessions.map { ForestTree(treeKindFor(it.plannedSeconds), !it.isHealthyTree(), it.day) }
        val streak = RewardEngine.streakStats(dao.treeDays())
        val today = todayKey()
        return ForestSnapshot(
            trees = trees,
            healthyTotal = dao.healthyTreeCount(),
            witheredTotal = dao.witheredTreeCount(),
            streak = streak.current,
            bestStreak = streak.best,
            healthyToday = trees.count { it.day == today && !it.withered }
        )
    }
}
