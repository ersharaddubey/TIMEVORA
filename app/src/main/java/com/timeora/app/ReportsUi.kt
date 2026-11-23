package com.timevora.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.AppUsageTotal
import com.timevora.app.data.DailyUsageTotal
import com.timevora.app.data.DeviceStats
import com.timevora.app.data.DeviceStatsReader
import com.timevora.app.data.Live
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.UsageSession
import com.timevora.app.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DayFmtYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val DayLong = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val ClockFmt = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH)

private val ShortFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private fun shortTimeOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(ShortFmt)

private fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(ClockFmt)

/** Adds the interval [startMs, endMs) to 24 hourly buckets of today. */
private fun addInterval(out: LongArray, startMs: Long, endMs: Long) {
    val dayStart = Live.startOfDay()
    var s = maxOf(startMs, dayStart)
    while (s < endMs) {
        val hour = ((s - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
        val hourEnd = dayStart + (hour + 1) * 3_600_000L
        val seg = minOf(endMs, hourEnd)
        if (seg <= s) break
        out[hour] += seg - s
        s = seg
    }
}

@Composable
internal fun ReportsScreen(c: Context, onExport: () -> Unit, refresh: Int) {
    val now = rememberNow()
    val live = Live.current(c)
    var period by remember { mutableStateOf("Today") }
    var data by remember { mutableStateOf(emptyList<DailyUsageTotal>()) }
    var appRows by remember { mutableStateOf(emptyList<AppUsageTotal>()) }
    var previousTotal by remember { mutableStateOf(0L) }
    var todaySessions by remember { mutableStateOf(emptyList<UsageSession>()) }
    var stats by remember { mutableStateOf(DeviceStats()) }
    var weeklyFocusSeconds by remember { mutableStateOf(0L) }
    var weeklyTasksDone by remember { mutableStateOf(0) }
    var weeklyHabitsDone by remember { mutableStateOf(0) }
    var weeklyStudyMinutes by remember { mutableStateOf(0) }
    var weeklyScoreAverage by remember { mutableStateOf<Int?>(null) }
    var weeklyScoreDelta by remember { mutableStateOf<Int?>(null) }
    var previousWeekUsage by remember { mutableStateOf(0L) }
    var weeklyRecordedUsage by remember { mutableStateOf(0L) }
    var weeklyLoaded by remember { mutableStateOf(false) }

    val days = when (period) { "Today" -> 1; "Week" -> 7; else -> 30 }
    val end = LocalDate.now()
    val start = end.minusDays((days - 1).toLong())

    LaunchedEffect(period, refresh) {
        val dao = timevoraDatabase.get(c).usageDao()
        data = dao.dailyTotals(start.toString(), end.toString())
        appRows = dao.rangeApps(start.toString(), end.toString())
        val prevEnd = start.minusDays(1)
        val prevStart = prevEnd.minusDays((days - 1).toLong())
        previousTotal = dao.dailyTotals(prevStart.toString(), prevEnd.toString()).sumOf { it.durationMillis }
        todaySessions = dao.sessions(end.toString())
    }
    LaunchedEffect(refresh / 10) { stats = DeviceStatsReader.today(c) }
    LaunchedEffect(refresh / 10) {
        val dao = timevoraDatabase.get(c).usageDao()
        val weekEnd = LocalDate.now()
        val weekStart = weekEnd.minusDays(6)
        val previousEnd = weekStart.minusDays(1)
        val previousStart = previousEnd.minusDays(6)
        weeklyFocusSeconds = dao.focusSecondsBetween(weekStart.toString(), weekEnd.toString())
        weeklyRecordedUsage = dao.dailyTotals(weekStart.toString(), weekEnd.toString()).sumOf { it.durationMillis }
        weeklyTasksDone = dao.completedPlannerBetween(weekStart.toString(), weekEnd.toString())
        weeklyHabitsDone = dao.completedHabitsBetween(weekStart.toString(), weekEnd.toString())
        weeklyStudyMinutes = dao.studyMinutesBetween(weekStart.toString(), weekEnd.toString())
        val scoreRows = dao.recentScores(14)
        val currentScores = scoreRows.filter { it.day >= weekStart.toString() && it.day <= weekEnd.toString() }
        val priorScores = scoreRows.filter { it.day >= previousStart.toString() && it.day <= previousEnd.toString() }
        weeklyScoreAverage = currentScores.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.toInt()
        weeklyScoreDelta = if (currentScores.isNotEmpty() && priorScores.isNotEmpty())
            currentScores.map { it.score }.average().toInt() - priorScores.map { it.score }.average().toInt()
        else null
        previousWeekUsage = dao.dailyTotals(previousStart.toString(), previousEnd.toString()).sumOf { it.durationMillis }
        weeklyLoaded = true
    }

    // ---- numbers, always including the app that is open right now ----
    val liveExtra = Live.todayMillis(live, now)
    val byDay = data.associate { it.day to it.durationMillis }
    val values = (0 until days).map { i ->
        val d = start.plusDays(i.toLong())
        (byDay[d.toString()] ?: 0L) + (if (d == end) liveExtra else 0L)
    }
    val total = values.sum()
    val average = total / days
    val peakIndex = values.indices.maxByOrNull { values[it] } ?: 0
    val appRowsNow = Live.withLive(appRows, live, now)
    val todayTotal = values.lastOrNull() ?: 0L

    val hourlyStored = remember(todaySessions) {
        val out = LongArray(24)
        todaySessions.forEach { addInterval(out, it.startMillis, it.endMillis) }
        out
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your reports", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)

        // Live banner
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("LIVE  •  updates every second", color = Color(0xFFB9C8E8), fontSize = 11.sp)
                Text(Fmt.hms(todayTotal), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("used today so far", color = Color(0xFFD9E4FF), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (live != null) "Now using ${live.label}  ${Fmt.clock(now - live.startMillis)}" else "No other app is in use at this moment",
                    color = Color(0xFFD9E4FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Today", "Week", "Month").forEachIndexed { i, v ->
                SegmentedButton(selected = period == v, onClick = { period = v }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(v) }
            }
        }
        Text(
            if (days == 1) end.format(DayFmtYear) else "${start.format(DayFmt)} – ${end.format(DayFmtYear)}",
            color = Color.Gray
        )

        if (weeklyLoaded) {
            val weekUsage = weeklyRecordedUsage + liveExtra
            val usageChange = if (previousWeekUsage > 0L) ((weekUsage - previousWeekUsage).toDouble() / previousWeekUsage * 100).toInt() else null
            val tips = buildList {
                if (usageChange != null && usageChange >= 15) add("Screen time is up $usageChange% vs last week. Try one 30-minute phone-free block each day.")
                else if (usageChange != null && usageChange <= -10) add("Great progress: screen time is down ${-usageChange}% vs last week. Keep the routine that helped.")
                if (weeklyFocusSeconds < 3_600L) add("You logged under 1 hour of focus this week. Schedule one 25-minute focus session tomorrow.")
                else add("You logged ${Fmt.hms(weeklyFocusSeconds * 1000)} of focus this week. Protect your best focus time.")
                if (weeklyHabitsDone == 0) add("No habit completions were logged this week. Start with one small daily habit.")
                else add("You completed $weeklyHabitsDone habit check-ins this week. Aim to repeat your most consistent habit.")
                if (weeklyStudyMinutes > 0) add("Study time: ${weeklyStudyMinutes / 60}h ${weeklyStudyMinutes % 60}m this week. Split the next study goal into short sessions.")
                if (weeklyTasksDone > 0) add("You checked off $weeklyTasksDone planner items this week. Choose your top 3 priorities for the coming week.")
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SMART WEEKLY INTELLIGENCE", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Your week at a glance", color = Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WeeklyMiniStat("Focus", Fmt.hms(weeklyFocusSeconds * 1000), Modifier.weight(1f))
                        WeeklyMiniStat("Habits", "$weeklyHabitsDone", Modifier.weight(1f))
                        WeeklyMiniStat("Tasks", "$weeklyTasksDone", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WeeklyMiniStat("Life score avg", weeklyScoreAverage?.let { "$it/100" } ?: "—", Modifier.weight(1f))
                        WeeklyMiniStat("Score change", weeklyScoreDelta?.let { (if (it > 0) "+" else "") + it } ?: "No baseline", Modifier.weight(1f))
                    }
                    WeeklyMiniStat("Screen time · 7 days", Fmt.hms(weekUsage), Modifier.fillMaxWidth())
                    WeeklyMiniStat("Study logged", "${weeklyStudyMinutes / 60}h ${weeklyStudyMinutes % 60}m", Modifier.fillMaxWidth())
                    Text("Personal suggestions", color = Navy, fontWeight = FontWeight.Bold)
                    tips.forEach { tip ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = Blue, fontWeight = FontWeight.Bold)
                            Text(tip, color = Navy.copy(alpha = .85f), fontSize = 13.sp)
                        }
                    }
                    Text("Based on your last 7 days of on-device records. Suggestions are rule-based; missing history is not estimated.", color = Color.Gray, fontSize = 10.sp)
                }
            }
        }

        if (weeklyLoaded) {
            val weekUsage = weeklyRecordedUsage + liveExtra
            val usageChange = if (previousWeekUsage > 0L) ((weekUsage - previousWeekUsage).toDouble() / previousWeekUsage * 100).toInt() else null
            val coachHeadline = when {
                weeklyScoreAverage != null && weeklyScoreAverage!! < 50 -> "Reset gently: rebuild your daily foundations"
                weeklyFocusSeconds < 1800L && weeklyStudyMinutes < 30 -> "Start small: build one focused win today"
                usageChange != null && usageChange >= 20 -> "Take back a little screen time today"
                weeklyHabitsDone == 0 -> "Restart with one easy habit"
                weeklyTasksDone >= 5 && weeklyFocusSeconds >= 3600L -> "Your consistency is building momentum"
                else -> "Protect your focus and keep your rhythm"
            }
            val coachActions = buildList {
                if (weeklyScoreAverage != null && weeklyScoreAverage!! < 50) add("Life Score: choose one small win in focus, planning or habits. A steady routine matters more than a perfect day.")
                if (weeklyFocusSeconds < 3600L) add("Focus: do one distraction-free 25-minute session, then take a 5-minute break.")
                else add("Focus: schedule a 50-minute deep-work block during the part of day that suits you best.")
                if (usageChange != null && usageChange >= 15) add("Digital balance: set your phone aside for one 30-minute block and avoid opening non-essential apps.")
                else add("Digital balance: keep your next break phone-free and check whether your screen time stays intentional.")
                if (weeklyHabitsDone == 0) add("Habits: choose one active habit and complete its smallest possible version today.")
                else add("Habits: repeat one habit you completed recently to maintain consistency.")
                if (weeklyStudyMinutes > 0) add("Study: split your next study target into a short session and log the minutes in Exam Mode.")
                else if (weeklyTasksDone == 0) add("Planning: write down your single most important task and finish that before adding more.")
                else add("Planning: pick your top three tasks and finish the most important one first.")
            }
            Card(colors = CardDefaults.cardColors(containerColor = PanelBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI PRODUCTIVITY COACH", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(coachHeadline, color = Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Your suggested plan for today", color = Navy.copy(alpha = .8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    coachActions.forEachIndexed { index, action ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("${index + 1}.", color = Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(action, color = Navy.copy(alpha = .85f), fontSize = 13.sp)
                        }
                    }
                    Text("Coach runs entirely on your device using recorded habits, focus, study, planner, Life Score and screen-time trends. It is a rules-based assistant, not a cloud AI service.", color = Color.Gray, fontSize = 10.sp)
                }
            }
        }

        StatCard("Total tracked screen time", Fmt.hms(total))
        if (days > 1) StatCard("Daily average", Fmt.hms(average))
        if (days > 1) {
            StatCard("Highest usage day", if (total > 0L) "${start.plusDays(peakIndex.toLong()).format(DayLong)} · ${Fmt.hms(values[peakIndex])}" else "No data yet")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Pickups", "${stats.pickups}", Modifier.weight(1f))
                MiniStat("Unlocks", "${stats.unlocks}", Modifier.weight(1f))
                MiniStat("First pickup", if (stats.firstPickupMillis > 0L) shortTimeOf(stats.firstPickupMillis) else "–", Modifier.weight(1f))
            }
        }

        val label = when (period) { "Today" -> "yesterday"; "Week" -> "previous week"; else -> "previous month" }
        val delta = if (previousTotal > 0L) ((total - previousTotal).toDouble() / previousTotal * 100).toInt() else null
        StatCard(
            "Compared with $label",
            delta?.let { (if (it > 0) "+" else "") + "$it% · ${if (it > 0) "more" else if (it < 0) "less" else "same"} usage" } ?: "No previous-period baseline"
        )
        if (previousTotal > 0L && total < previousTotal) {
            StatCard("Time saved vs $label", Fmt.hms(previousTotal - total))
        }

        // Chart
        if (days == 1) {
            val hourly = hourlyStored.copyOf()
            if (live != null) addInterval(hourly, live.startMillis, now)
            Text("Usage by hour", fontWeight = FontWeight.Bold, color = Navy)
            UsageBarChart(hourly.toList(), "12 AM", "12 PM", "11 PM", 3f)
        } else {
            Text("Daily usage trend", fontWeight = FontWeight.Bold, color = Navy)
            UsageBarChart(values, start.format(DayFmt), start.plusDays((days / 2).toLong()).format(DayFmt), end.format(DayFmt), if (days == 7) 12f else 3f)
        }

        CategoryPieSection(c, appRowsNow, showLimits = days == 1, refresh = refresh)

        Text(if (days == 1) "Apps today" else "Top apps this ${period.lowercase()}", fontWeight = FontWeight.Bold, color = Navy)
        if (appRowsNow.isEmpty()) Text("No usage data recorded yet.", color = Color.Gray)
        val appsTotal = appRowsNow.sumOf { it.durationMillis }
        appRowsNow.take(10).forEach { row ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.appLabel)
                    Text(Fmt.hms(row.durationMillis), fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { if (appsTotal > 0L) (row.durationMillis.toFloat() / appsTotal).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                )
            }
        }

        if (days == 1) {
            Text("Today's timeline", fontWeight = FontWeight.Bold, color = Navy)
            if (live == null && todaySessions.isEmpty()) Text("Sessions will appear here with exact start and end times.", color = Color.Gray)
            if (live != null) TimelineRow(live.label, "${clockOf(live.startMillis)} – now", Fmt.hms(now - live.startMillis), true)
            todaySessions.take(15).forEach { s ->
                TimelineRow(s.appLabel, "${clockOf(s.startMillis)} – ${clockOf(s.endMillis)}", Fmt.hms(s.durationMillis), false)
            }
        }

        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export last 90 days as CSV")
        }
        Text("Reports use timevora's locally recorded sessions plus the app you are using right now. Missing days are shown as zero; historical usage from before tracking was enabled is not fabricated.", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun WeeklyMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.styled(Color.White.copy(alpha = 0.8f), styleShape(12), lite = true).padding(10.dp)) {
        Text(label, color = Color.Gray, fontSize = 10.sp)
        Text(value, color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun TimelineRow(app: String, range: String, duration: String, isLive: Boolean) {
    Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (isLive) "● " else "") + app, fontWeight = FontWeight.SemiBold, color = Navy)
            Text(range, fontSize = 11.sp, color = Color.Gray)
        }
        Text(duration, fontWeight = FontWeight.Bold, color = Blue)
    }
}

@Composable
private fun UsageBarChart(values: List<Long>, left: String, mid: String, right: String, gap: Float) {
    val count = values.size.coerceAtLeast(1)
    val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().styled(CardBg, styleShape(18), lite = true).padding(14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width
            val h = size.height
            val barW = (w - gap * (count - 1)) / count
            drawLine(Color(0xFFE6EAF2), Offset(0f, h - 1), Offset(w, h - 1), 2f)
            values.forEachIndexed { i, v ->
                val bh = (v.toFloat() / max) * h * 0.88f
                val x = i * (barW + gap)
                drawRoundRect(color = Blue, topLeft = Offset(x, h - bh), size = Size(barW, bh), cornerRadius = CornerRadius(5f, 5f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(left, fontSize = 10.sp, color = Color.Gray)
            Text(mid, fontSize = 10.sp, color = Color.Gray)
            Text(right, fontSize = 10.sp, color = Color.Gray)
        }
        Text("Peak: ${Fmt.hms(max)} · Bars show recorded totals including live time", fontSize = 11.sp, color = Color.Gray)
    }
}
