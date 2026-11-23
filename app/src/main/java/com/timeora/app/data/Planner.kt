package com.timevora.app.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Does this diary item (task / meeting / routine) fall on the given date? */
fun PlannerItem.occursOn(date: LocalDate): Boolean {
    val start = LocalDate.parse(startDay)
    if (date.isBefore(start)) return false
    return when (repeatMode) {
        "daily" -> true
        "weekdays" -> date.dayOfWeek.value in 1..5
        "weekly" -> date.dayOfWeek == start.dayOfWeek
        else -> date == start
    }
}

/** The moment the reminder for this item should fire on the given date. */
fun PlannerItem.triggerFor(date: LocalDate): ZonedDateTime =
    date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(ZoneId.systemDefault()).minusMinutes(remindBefore.coerceAtLeast(0).toLong())

data class PlanRow(val item: PlannerItem, val done: Boolean)

suspend fun loadPlan(dao: UsageDao, date: LocalDate): List<PlanRow> {
    val done = dao.doneIds(date.toString()).toSet()
    return dao.plannerAll()
        .filter { it.occursOn(date) }
        .sortedWith(compareBy<PlannerItem>({ it.minuteOfDay < 0 }, { it.minuteOfDay }, { it.id }))
        .map { PlanRow(it, it.id in done) }
}
