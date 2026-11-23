package com.timevora.app.util

import java.util.Locale

/** Small formatting helpers shared by the UI, service and notifications. */
object Fmt {
    /** 3725000 ms -> "1h 2m 5s". Always shows seconds so short sessions never look like zero. */
    fun hms(ms: Long): String {
        val t = (ms / 1000L).coerceAtLeast(0L)
        val h = t / 3600L
        val m = (t % 3600L) / 60L
        val s = t % 60L
        return when {
            h > 0L -> "${h}h ${m}m ${s}s"
            m > 0L -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Stopwatch style: 02:05 or 1:02:05. */
    fun clock(ms: Long): String {
        val t = (ms / 1000L).coerceAtLeast(0L)
        val h = t / 3600L
        val m = (t % 3600L) / 60L
        val s = t % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** minute-of-day (0..1439) -> "9:05 AM". Negative means no time was set. */
    fun timeLabel(minuteOfDay: Int): String {
        if (minuteOfDay < 0) return "Any time"
        val h24 = minuteOfDay / 60
        val m = minuteOfDay % 60
        val ap = if (h24 < 12) "AM" else "PM"
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return String.format(Locale.US, "%d:%02d %s", h12, m, ap)
    }
}
