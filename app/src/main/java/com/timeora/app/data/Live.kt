package com.timevora.app.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** The app that is open right now (not saved to the database until the session ends). */
data class LiveSession(val pkg: String, val label: String, val startMillis: Long)

object Live {
    /** Reads the running session that the tracking service publishes every second. */
    fun current(c: Context): LiveSession? {
        val p = c.getSharedPreferences("timevora_status", Context.MODE_PRIVATE)
        val beat = p.getLong("beat", 0L)
        val start = p.getLong("live_start", 0L)
        val pkg = p.getString("live_pkg", "") ?: ""
        if (pkg.isEmpty() || start <= 0L) return null
        if (System.currentTimeMillis() - beat > 8000L) return null
        return LiveSession(pkg, p.getString("live_label", pkg) ?: pkg, start)
    }

    /** True while the tracking service is running (it writes a heartbeat every second). */
    fun trackingAlive(c: Context): Boolean =
        System.currentTimeMillis() - c.getSharedPreferences("timevora_status", Context.MODE_PRIVATE).getLong("beat", 0L) <= 8000L

    fun startOfDay(day: LocalDate = LocalDate.now()): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Milliseconds of the running session that fall on today (handles midnight crossing). */
    fun todayMillis(live: LiveSession?, now: Long): Long =
        if (live == null) 0L else (now - maxOf(live.startMillis, startOfDay())).coerceAtLeast(0L)

    /** Adds the running session to a list of per-app totals so the numbers are current to the second. */
    fun withLive(rows: List<AppUsageTotal>, live: LiveSession?, now: Long): List<AppUsageTotal> {
        val extra = todayMillis(live, now)
        if (live == null || extra <= 0L) return rows
        val found = rows.any { it.packageName == live.pkg }
        val merged = if (found) {
            rows.map { if (it.packageName == live.pkg) it.copy(durationMillis = it.durationMillis + extra, sessions = it.sessions + 1) else it }
        } else {
            rows + AppUsageTotal(live.pkg, live.label, extra, 1)
        }
        return merged.sortedByDescending { it.durationMillis }
    }
}
