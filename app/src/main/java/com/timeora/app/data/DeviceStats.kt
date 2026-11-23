package com.timevora.app.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Phone pickups (screen turned on) and unlocks today, read from Android's usage events. */
data class DeviceStats(val pickups: Int = 0, val unlocks: Int = 0, val firstPickupMillis: Long = 0L)

object DeviceStatsReader {
    private const val SCREEN_INTERACTIVE = 15
    private const val KEYGUARD_HIDDEN = 18

    suspend fun today(c: Context): DeviceStats = withContext(Dispatchers.IO) {
        try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val events = usm.queryEvents(start, System.currentTimeMillis())
            val e = UsageEvents.Event()
            var pickups = 0
            var unlocks = 0
            var first = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == SCREEN_INTERACTIVE) {
                    pickups++
                    if (first == 0L) first = e.timeStamp
                } else if (e.eventType == KEYGUARD_HIDDEN) {
                    unlocks++
                }
            }
            DeviceStats(pickups, unlocks, first)
        } catch (_: Exception) {
            DeviceStats()
        }
    }
}
