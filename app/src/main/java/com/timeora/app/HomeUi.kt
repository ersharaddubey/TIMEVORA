package com.timevora.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.AppUsageTotal
import com.timevora.app.data.Catalog
import com.timevora.app.data.CatalogStore
import com.timevora.app.data.FeatureInfo
import com.timevora.app.data.DeviceStats
import com.timevora.app.data.DeviceStatsReader
import com.timevora.app.data.DailyScore
import com.timevora.app.data.Live
import com.timevora.app.data.PlanRow
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.loadPlan
import com.timevora.app.data.todayKey
import com.timevora.app.util.Fmt
import android.widget.Toast
import java.time.LocalDate

@Composable
internal fun HomeScreen(
    c: Context,
    granted: Boolean,
    overlay: Boolean,
    total: Long,
    rows: List<AppUsageTotal>,
    status: String,
    refresh: Int,
    catalog: Catalog,
    onGrantUsage: () -> Unit,
    onGrantOverlay: () -> Unit,
    onStart: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenFeature: (FeatureInfo) -> Unit,
    onShowWhy: (FeatureInfo) -> Unit,
    onStartFocusWithLabel: (String) -> Unit = {}
) {
    val now = rememberNow()
    val live = Live.current(c)
    val totalNow = total + Live.todayMillis(live, now)
    val rowsNow = Live.withLive(rows, live, now)
    val prefs = remember { c.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    val goalMin = prefs.getInt("goal_minutes", 0)
    var stats by remember { mutableStateOf(DeviceStats()) }
    var focusSec by remember { mutableLongStateOf(0L) }
    var plan by remember { mutableStateOf(emptyList<PlanRow>()) }
    var lifeScore by remember { mutableStateOf(0) }
    var habitCount by remember { mutableStateOf(0) }
    var habitsDone by remember { mutableStateOf(0) }
    var scoreHistory by remember { mutableStateOf(emptyList<DailyScore>()) }
    var query by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var showAllFeatures by remember { mutableStateOf(false) }
    var homeExpanded by remember { mutableStateOf(prefs.getBoolean("home_expanded_v1", false)) }
    val hour = remember { java.time.LocalTime.now().hour }
    val hasTop3 = prefs.getString("top3_${LocalDate.now()}", "").orEmpty().isNotBlank()
        || c.getSharedPreferences("timevora_timesaving", Context.MODE_PRIVATE)
            .getString("top3_${LocalDate.now()}", "").orEmpty().isNotBlank()

    LaunchedEffect(refresh / 10, granted) { if (granted) stats = DeviceStatsReader.today(c) }
    LaunchedEffect(refresh) {
        val dao = timevoraDatabase.get(c).usageDao()
        focusSec = dao.focusSeconds(todayKey())
        plan = loadPlan(dao, LocalDate.now())
        habitCount = dao.activeHabitCount()
        habitsDone = dao.completedHabits(todayKey())
        scoreHistory = dao.recentScores(7).reversed()
        val focusScore = (focusSec / 7200f * 100f).toInt().coerceIn(0, 100)
        val planScore = if (plan.isEmpty()) 60 else (plan.count { it.done } * 100 / plan.size)
        val habitScore = if (habitCount == 0) 60 else (habitsDone * 100 / habitCount).coerceIn(0, 100)
        val balanceScore = if (goalMin > 0) {
            val ratio = totalNow / (goalMin * 60_000L).toFloat()
            (100f - (ratio - 1f).coerceAtLeast(0f) * 100f).toInt().coerceIn(0, 100)
        } else 70
        val routineScore = if (plan.isEmpty() && habitCount == 0) 60 else ((planScore + habitScore) / 2)
        lifeScore = (focusScore * .30f + planScore * .25f + habitScore * .20f + balanceScore * .15f + routineScore * .10f).toInt().coerceIn(0, 100)
        dao.saveDailyScore(DailyScore(todayKey(), lifeScore, focusScore, planScore, habitScore, balanceScore, System.currentTimeMillis()))
        // First win: any real use unlocks fuller home by default next time
        if ((focusSec > 0 || plan.isNotEmpty()) && !prefs.getBoolean("first_win_v1", false)) {
            prefs.edit().putBoolean("first_win_v1", true).apply()
        }
    }
    val firstWin = prefs.getBoolean("first_win_v1", false) || focusSec > 0 || plan.isNotEmpty()
    val quietHome = !homeExpanded && !firstWin && query.isBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeGreeting()
        if (!quietHome) FeatureSearchBar(query) { query = it }
        if (query.isBlank()) {
            if (quietHome) {
                FirstSessionCoachCard(onOpenFeature = onOpenFeature, onStartFocusWithLabel = onStartFocusWithLabel)
            } else {
                StartHereTodayCard(
                    planEmpty = plan.isEmpty(),
                    hasTop3 = hasTop3,
                    onOpenFeature = onOpenFeature,
                    onStartFocusWithLabel = onStartFocusWithLabel
                )
                ContinueWhereLeftCard(
                    plan = plan,
                    lastFocusLabel = prefs.getString("last_focus_label", "").orEmpty(),
                    onStartFocusWithLabel = onStartFocusWithLabel,
                    onOpenFeature = onOpenFeature
                )
                DoneForTodayCard(
                    plan = plan,
                    hour = hour,
                    onOpenFeature = onOpenFeature
                )
                TodaysFlowStrip(
                    planEmpty = plan.isEmpty(),
                    hasTop3 = hasTop3,
                    onOpenFeature = onOpenFeature
                )
                TimeSavingShortcutCard(onOpenFeature)
                PinnedToolCard(c, onOpenFeature)
                AnnouncementBar(catalog.announcement)
                BannerCarousel(catalog.homeBanners()) { b -> catalog.features.firstOrNull { it.id == b.target }?.let(onOpenFeature) }
                if (!granted) SetupNudge(onGrantUsage)
                QuickIconsRow(FeatureRank.sort(c, allAppFeatures(catalog, false)) { it.id }.take(12), onOpenFeature)
            }
            if (quietHome || !homeExpanded) {
                TextButton(
                    onClick = {
                        homeExpanded = true
                        prefs.edit().putBoolean("home_expanded_v1", true).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("More on Home", color = AccentPink, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!quietHome) {
        val allFeats = remember(catalog) { allAppFeatures(catalog, includeSettings = false) }
        val ranked = remember(catalog, refresh) { FeatureRank.sort(c, allFeats) { it.id } }
        val shown = ranked.filter { f ->
            (goal.isEmpty() || f.goal == goal) &&
                (query.isBlank() || (listOf(f.title, f.tagline, f.why, f.useWhen) + f.points).any { it.contains(query.trim(), ignoreCase = true) })
        }
        // Easy layer: collapse long feature grid when not searching
        val gridItems = if (query.isBlank() && goal.isEmpty() && !showAllFeatures) shown.take(4) else shown
        SectionTitle(
            if (query.isBlank() && goal.isEmpty()) {
                if (showAllFeatures) "ALL ${ranked.size} FEATURES" else "TOP FEATURES"
            } else "${shown.size} MATCHING FEATURES",
            if (query.isBlank() && !showAllFeatures) "Start here — expand when you need more."
            else "Tap a card to open it. Most-used features rise to the top."
        )
        GoalFilter(goal) { goal = it }
        if (shown.isEmpty()) Text("No feature matches. Try another word or choose All.", color = Color.Gray, fontSize = 13.sp)
        else FeatureGrid(gridItems, onOpenFeature, onShowWhy)
        if (query.isBlank() && goal.isEmpty() && shown.size > 4) {
            TextButton(
                onClick = { showAllFeatures = !showAllFeatures },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (showAllFeatures) "Show less" else "Show all features (${shown.size})",
                    color = AccentPink,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (query.isBlank()) {
            LiveRoomsPromo(catalog.features.firstOrNull { it.id == "friends" }, onOpenFeature)
            DailyTipCard(catalog.tips)
        }

        Text("YOUR DAY AT A GLANCE", color = Navy, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp)) {
                Text("TODAY'S SCREEN TIME", color = Color(0xFFB9C8E8), fontSize = 12.sp)
                Text(Fmt.hms(totalNow), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("${rowsNow.size} apps used  •  ${rowsNow.firstOrNull()?.appLabel ?: "No activity recorded yet"} most used", color = Color(0xFFD9E4FF))
                if (live != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("● LIVE  ${live.label}  ${Fmt.clock(now - live.startMillis)}", color = Color(0xFFB9C8E8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Pickups", "${stats.pickups}", Modifier.weight(1f))
            MiniStat("Unlocks", "${stats.unlocks}", Modifier.weight(1f))
            MiniStat("Focus today", Fmt.hms(focusSec * 1000L), Modifier.weight(1f))
        }

        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TODAY'S LIFE SCORE", color = Color(0xFFB9C8E8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${lifeScore}/100", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(progress = { lifeScore / 100f }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF55D6A5), trackColor = Color(0xFF354765))
                Text("Focus 30% • Plan 25% • Habits 20% • Balance 15% • Routine 10%", color = Color(0xFFD9E4FF), fontSize = 10.sp)
                Text("${habitsDone}/${habitCount} habits completed today", color = Color(0xFFB9C8E8), fontSize = 11.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("7-DAY LIFE SCORE TREND", color = Navy, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (scoreHistory.isEmpty()) {
                    Text("Your score history will appear as you use timevora each day.", color = Color.Gray, fontSize = 12.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        scoreHistory.forEach { score ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${score.score}", color = Navy, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(progress = { score.score / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                                Text(runCatching { LocalDate.parse(score.day).dayOfWeek.name.take(1) }.getOrDefault("•"), color = Color.Gray, fontSize = 9.sp)
                            }
                        }
                    }
                    val delta = scoreHistory.last().score - scoreHistory.first().score
                    Text(if (scoreHistory.size < 2) "Keep using timevora to build your trend." else "Change across recorded days: ${if (delta > 0) "+" else ""}$delta points", color = Color.Gray, fontSize = 11.sp)
                }
                Text("Scores use recorded focus, plan, habit, screen-time balance and routine data. Missing days are not estimated.", color = Color.Gray, fontSize = 10.sp)
            }
        }

        if (goalMin > 0) {
            val goalMs = goalMin * 60_000L
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Daily goal", fontWeight = FontWeight.Bold, color = Navy)
                    LinearProgressIndicator(progress = { (totalNow.toFloat() / goalMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (totalNow <= goalMs) "${Fmt.hms(totalNow)} of ${Fmt.hms(goalMs)} used  •  ${Fmt.hms(goalMs - totalNow)} left"
                        else "Goal ${Fmt.hms(goalMs)} crossed by ${Fmt.hms(totalNow - goalMs)}",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            }
        }

        if (!granted) PermissionCard("Usage access required", "timevora needs Usage Access to identify foreground apps and calculate screen time.", "Grant usage access", onGrantUsage)
        else Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F7EF))) { Text("✓ Usage access is enabled", Modifier.padding(14.dp), color = Color(0xFF176B42)) }
        if (!overlay) PermissionCard("Floating mini timer", "Allow display over other apps to show the small stopwatch bubble.", "Enable floating bubble", onGrantOverlay)
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) { Text(status, Modifier.padding(14.dp), color = Navy, fontSize = 13.sp) }
        Button(onClick = onStart, enabled = granted, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start timevora tracking")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY'S PLAN", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onOpenDiary) { Text("Open planner") }
        }
        if (plan.isEmpty()) {
            // Better empty state with one clear action
            Card(
                colors = CardDefaults.cardColors(containerColor = SoftPink),
                shape = styleShape(16),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No tasks yet for today", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Dump everything on your mind in one go — we’ll turn it into Planner tasks.", color = Navy.copy(alpha = 0.7f), fontSize = 13.sp)
                    Button(
                        onClick = {
                            timeSavingFeatureList().firstOrNull { it.id == "ts_dump" }?.let(onOpenFeature)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add with Smart Task Dump")
                    }
                }
            }
        } else {
            val pending = plan.count { !it.done }
            Text("$pending pending  •  ${plan.size - pending} done", color = Color.Gray, fontSize = 12.sp)
            plan.take(4).forEach { r ->
                Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.done) "✓" else "○", color = if (r.done) Color(0xFF176B42) else Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(r.item.title, Modifier.weight(1f), color = if (r.done) Color.Gray else Navy, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Fmt.timeLabel(r.item.minuteOfDay), color = Blue, fontSize = 12.sp)
                    if (!r.done) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(AccentPink.copy(alpha = 0.15f), RoundedCornerShape(50))
                                .clickable { onStartFocusWithLabel(r.item.title) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start Focus", tint = AccentPink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        Text("APP USAGE TODAY", fontWeight = FontWeight.Bold, color = Navy)
        if (rowsNow.isEmpty()) Text("Your app-wise usage will appear here after tracking starts.", color = Color.Gray)
        rowsNow.take(8).forEach { UsageRow(it) }
        } // end if (!quietHome) full home body
    }
}

@Composable
internal fun FeatureShortcut(
    emoji: String,
    title: String,
    subtitle: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = tint),
        shape = styleShape(16)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(emoji, fontSize = 23.sp)
            Text(title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Navy.copy(alpha = .68f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 25 time-saving tools — English, unique emoji, no numbering. */
internal fun timeSavingFeatureList(): List<FeatureInfo> {
    fun ts(id: String, emoji: String, title: String, tagline: String, order: Int) = FeatureInfo(
        id = "ts_$id", route = "TimeSaving", emoji = emoji, title = title, tagline = tagline,
        why = tagline, whyHi = "", useWhen = "", goal = "productive",
        points = emptyList(), steps = emptyList(), promise = "",
        color = 0xFFE3F2FD, order = order, live = false
    )
    return listOf(
        ts("dump", "🧠", "Smart Task Dump", "Empty your mind — capture everything at once", 1),
        ts("priority", "📶", "Auto Priority Sort", "Sort by deadline, importance and effort", 2),
        ts("top3", "🎯", "Top 3 Today", "Only three must-do tasks for the day", 3),
        ts("matcher", "⌛", "Quick Task Matcher", "Match work to 5 / 10 / 15 / 30 free minutes", 4),
        ts("batch", "🗂️", "Task Batching", "Group emails, calls and bills together", 5),
        ts("leaks", "🕳️", "Time Leak Detector", "Find where minutes slip away", 6),
        ts("return", "🔖", "Return-to-Task Memory", "Resume exactly where you left off", 7),
        ts("scroll", "🧱", "Scroll Stopper", "Set an intention or limit before social apps", 8),
        ts("notif", "📬", "Notification Batch Planner", "Check messages only in set windows", 9),
        ts("decision", "🎲", "Decision Saver", "Suggest the next action so you don’t stall", 10),
        ts("dayplan", "🗓️", "Auto Day Planner", "Build a day schedule from tasks and time", 11),
        ts("deadline", "🚨", "Deadline Radar", "See upcoming deadlines and early warnings", 12),
        ts("buffer", "🛡️", "Smart Buffer Time", "Add cushion between meetings and tasks", 13),
        ts("energy", "🔋", "Energy-Based Planner", "Match tasks to high / medium / low energy", 14),
        ts("templates", "📑", "Routine Templates", "One-tap morning, office and weekly checklists", 15),
        ts("2min", "⚡", "2-Minute Action", "Finish tiny tasks now instead of parking them", 16),
        ts("checklist", "☑️", "Smart Checklist Maker", "Travel, meeting or release lists on demand", 17),
        ts("meeting", "🎤", "Meeting Prep & Wrap", "Agenda before, decisions and actions after", 18),
        ts("onetap", "▶️", "One-Tap Start", "Start a favorite focus block in one tap", 19),
        ts("waiting", "🪑", "Waiting Time Mode", "Use 5–15 minute waits for quick wins", 20),
        ts("shutdown", "🌇", "Daily Shutdown", "Close the day and note where to start tomorrow", 21),
        ts("tomorrow", "🌄", "Tomorrow Ready", "Prep priorities and gear the night before", 22),
        ts("weekly", "🔄", "Weekly Reset", "Clear the list and set next week’s priorities", 23),
        ts("calc", "🧮", "Time Calculator", "Sum task minutes without mental math", 24),
        ts("estimate", "📉", "Time Estimate Trainer", "Compare estimate vs actual and improve", 25),
    )
}

/**
 * Continue where you left — last Focus label or next pending plan item.
 */
@Composable
internal fun ContinueWhereLeftCard(
    plan: List<PlanRow>,
    lastFocusLabel: String,
    onStartFocusWithLabel: (String) -> Unit,
    onOpenFeature: (FeatureInfo) -> Unit
) {
    val nextPending = plan.firstOrNull { !it.done }?.item?.title.orEmpty()
    val label = when {
        lastFocusLabel.isNotBlank() -> lastFocusLabel
        nextPending.isNotBlank() -> nextPending
        else -> return
    }
    val subtitle = when {
        lastFocusLabel.isNotBlank() -> "Last Focus · resume or restart"
        else -> "Next on your plan"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onStartFocusWithLabel(label) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE)),
        shape = styleShape(16)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▶️", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE WHERE YOU LEFT", color = AccentPink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                Text(label, color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Navy.copy(alpha = 0.65f), fontSize = 12.sp)
            }
            Text("Focus ›", color = AccentPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/** One-tap Time-Saving tools entry on Home (no dig into More). */
@Composable
internal fun TimeSavingShortcutCard(onOpenFeature: (FeatureInfo) -> Unit) {
    val dump = timeSavingFeatureList().firstOrNull { it.id == "ts_dump" }
    val hub = FeatureInfo(
        id = "timesaving", route = "TimeSaving", emoji = "⚡", title = "Time-Saving tools",
        tagline = "Dump, Top 3, checklists & 20 more", why = "", whyHi = "", useWhen = "", goal = "productive",
        points = emptyList(), steps = emptyList(), promise = "", color = 0xFFE3F2FD, order = 0, live = false
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Prefer opening the hub list; fall back to Dump as the main tool
                onOpenFeature(hub)
            },
        colors = CardDefaults.cardColors(containerColor = SoftOrange),
        shape = styleShape(14)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("TOOLS", color = AccentPink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text("Time-Saving tools", color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Dump · Top 3 · Checklists · more", color = Navy.copy(alpha = 0.65f), fontSize = 12.sp)
            }
            Text("Open ›", color = AccentPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/**
 * Home flow: Dump → Top 3 → Focus with simple progress dots.
 */
@Composable
internal fun TodaysFlowStrip(
    planEmpty: Boolean,
    hasTop3: Boolean,
    onOpenFeature: (FeatureInfo) -> Unit
) {
    val dump = timeSavingFeatureList().firstOrNull { it.id == "ts_dump" }
    val top3 = timeSavingFeatureList().firstOrNull { it.id == "ts_top3" }
    val focus = FeatureInfo(
        id = "focus", route = "Focus", emoji = "🎯", title = "Start Focus",
        tagline = "Deep work block", why = "", whyHi = "", useWhen = "", goal = "productive",
        points = emptyList(), steps = emptyList(), promise = "", color = 0xFFE3F2FD, order = 0, live = false
    )
    val steps = listOf(
        Triple("1", "Dump", !planEmpty) to dump,
        Triple("2", "Top 3", hasTop3) to top3,
        Triple("3", "Focus", false) to focus
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = styleShape(16),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TODAY’S FLOW", color = Navy.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { (meta, feature) ->
                    val (num, label, done) = meta
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { feature?.let(onOpenFeature) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (done) Color(0xFFE8F7EF) else SoftPink
                        ),
                        shape = styleShape(12)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(if (done) "✓" else num, color = if (done) Color(0xFF176B42) else AccentPink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(label, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text("Dump → prioritise → focus. Tap any step.", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

/** One-tap pinned tool from Home (set from More long-press or pin chip). */
@Composable
internal fun PinnedToolCard(c: Context, onOpenFeature: (FeatureInfo) -> Unit) {
    val prefs = remember { c.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    val pinnedId = prefs.getString("pinned_tool_id", "") ?: ""
    if (pinnedId.isBlank()) return
    val feature = remember(pinnedId) {
        allAppFeatures(CatalogStore.current(c), includeSettings = false).firstOrNull { it.id == pinnedId }
            ?: timeSavingFeatureList().firstOrNull { it.id == pinnedId }
    } ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFeature(feature) },
        colors = CardDefaults.cardColors(containerColor = SoftOrange),
        shape = styleShape(14)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(feature.emoji, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PINNED", color = AccentPink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(feature.title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(feature.tagline, color = Navy.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1)
            }
            Text("Open ›", color = AccentPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/**
 * First-session coach: only Dump · Focus · Top 3 until the user has a first win.
 */
@Composable
internal fun FirstSessionCoachCard(
    onOpenFeature: (FeatureInfo) -> Unit,
    onStartFocusWithLabel: (String) -> Unit
) {
    val dump = timeSavingFeatureList().firstOrNull { it.id == "ts_dump" }
    val top3 = timeSavingFeatureList().firstOrNull { it.id == "ts_top3" }
    Card(
        colors = CardDefaults.cardColors(containerColor = HeroBg),
        shape = styleShape(20),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("START HERE", color = Color(0xFFB9C8E8), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text("Three steps. That’s the whole app.", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Dump what’s on your mind → pick Top 3 → Focus on one.", color = Color(0xFFD9E4FF), fontSize = 13.sp)
            listOfNotNull(
                dump?.let { Triple("🧠", "1. Dump tasks", it) },
                top3?.let { Triple("📌", "2. Top 3 Today", it) }
            ).forEach { (emoji, label, feature) ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenFeature(feature) },
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.14f)),
                    shape = styleShape(14)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("›", color = Color.White, fontSize = 20.sp)
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onStartFocusWithLabel("Deep work") },
                colors = CardDefaults.cardColors(containerColor = AccentPink),
                shape = styleShape(14)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🎯", fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("3. Start Focus (25 min)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("›", color = Color.White, fontSize = 20.sp)
                }
            }
        }
    }
}

/** Evening / plan-complete: close the day cleanly. */
@Composable
internal fun DoneForTodayCard(
    plan: List<PlanRow>,
    hour: Int,
    onOpenFeature: (FeatureInfo) -> Unit
) {
    val shutdown = timeSavingFeatureList().firstOrNull { it.id == "ts_shutdown" } ?: return
    val allDone = plan.isNotEmpty() && plan.all { it.done }
    val evening = hour >= 18
    if (!allDone && !evening) return
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenFeature(shutdown) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D3748)),
        shape = styleShape(16)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🌇", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("DONE FOR TODAY?", color = Color(0xFFB9C8E8), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                Text(
                    if (allDone) "Plan complete — close the day & set tomorrow"
                    else "Wind down · note what’s left for tomorrow",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
            Text("Shutdown ›", color = AccentPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/**
 * Home “Start here today” — smart defaults:
 * empty plan → Dump first; has plan → Focus; has Top 3 → Focus.
 */
@Composable
internal fun StartHereTodayCard(
    planEmpty: Boolean,
    hasTop3: Boolean = false,
    onOpenFeature: (FeatureInfo) -> Unit,
    onStartFocusWithLabel: (String) -> Unit = {}
) {
    val dump = timeSavingFeatureList().firstOrNull { it.id == "ts_dump" }
    val top3 = timeSavingFeatureList().firstOrNull { it.id == "ts_top3" }
    val focus = FeatureInfo(
        id = "focus", route = "Focus", emoji = "🎯", title = "Start Focus",
        tagline = "Deep work block", why = "", whyHi = "", useWhen = "", goal = "productive",
        points = emptyList(), steps = emptyList(), promise = "", color = 0xFFE3F2FD, order = 0, live = false
    )
    data class Action(val emoji: String, val label: String, val onClick: () -> Unit)
    val actions = when {
        planEmpty -> listOfNotNull(
            dump?.let { Action("🧠", "Dump tasks") { onOpenFeature(it) } },
            Action("🎯", "Start Focus") { onStartFocusWithLabel("Deep work") },
            top3?.let { Action("📌", "Top 3") { onOpenFeature(it) } }
        )
        !hasTop3 -> listOfNotNull(
            top3?.let { Action("📌", "Top 3 Today") { onOpenFeature(it) } },
            Action("🎯", "Start Focus") { onStartFocusWithLabel("Deep work") },
            dump?.let { Action("🧠", "Dump more") { onOpenFeature(it) } }
        )
        else -> listOfNotNull(
            Action("🎯", "Start Focus") { onStartFocusWithLabel("Deep work") },
            top3?.let { Action("📌", "Top 3") { onOpenFeature(it) } },
            dump?.let { Action("🧠", "Dump more") { onOpenFeature(it) } }
        )
    }
    val headline = when {
        planEmpty -> "Empty mind → clear plan in under a minute"
        !hasTop3 -> "Narrow to Top 3, then focus"
        else -> "You’re set — start a focus block"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = HeroBg),
        shape = styleShape(20),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("START HERE TODAY", color = Color(0xFFB9C8E8), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text(headline, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.take(3).forEach { a ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = a.onClick),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.14f)),
                        shape = styleShape(14)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(a.emoji, fontSize = 22.sp)
                            Text(a.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** Home + More: 25 tools + catalog (skip nested timesaving) + optional settings. */
internal fun allAppFeatures(catalog: Catalog, includeSettings: Boolean = false): List<FeatureInfo> {
    val settings = FeatureInfo(
        "settings", "Profile", "⚙️", "Profile & Settings", "Permissions, theme, online features and data",
        "Control tracking, the floating timer, app limits, theme and your data in one place.", "",
        "When you want to change how timevora works.", "productive",
        listOf("Control tracking and floating timer", "Set app limits and screen-time goals", "Connect to the timevora server for live rooms"),
        listOf("Open Settings", "Change what you need", "Changes save automatically"),
        "A digital routine that fits your life.", 0xFFECEFF1, 99, false
    )
    val other = catalog.features.filter { it.id != "timesaving" && !it.id.startsWith("ts_") }
    return timeSavingFeatureList() + other + if (includeSettings) listOf(settings) else emptyList()
}

/** Category chips for More tab — keeps the long list manageable. */
private val HUB_CATEGORIES = listOf(
    "all" to "All",
    "planning" to "Planning",
    "leaks" to "Leaks",
    "quick" to "Quick",
    "routine" to "Routine",
    "core" to "Core app"
)

private fun hubCategoryOf(f: FeatureInfo): String {
    if (!f.id.startsWith("ts_")) return "core"
    val id = f.id.removePrefix("ts_")
    return when (id) {
        "dump", "priority", "top3", "matcher", "batch", "dayplan", "deadline", "buffer", "energy", "templates" -> "planning"
        "leaks", "return", "scroll", "notif", "decision" -> "leaks"
        "2min", "checklist", "meeting", "onetap", "waiting", "calc", "estimate" -> "quick"
        "shutdown", "tomorrow", "weekly" -> "routine"
        else -> "planning"
    }
}

/** Core tools shown first in More (Basics). Rest behind “Show advanced”. */
private val HUB_BASIC_IDS = setOf("ts_dump", "ts_top3", "ts_priority", "ts_2min", "ts_checklist")

@Composable
internal fun FeatureHubScreen(catalog: Catalog, onOpenFeature: (FeatureInfo) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var customize by remember { mutableStateOf(false) }
    var hubQuery by remember { mutableStateOf("") }
    var hubCat by remember { mutableStateOf("all") }
    var showAdvanced by remember { mutableStateOf(false) }
    val baseList = remember(catalog, tick) { allAppFeatures(catalog, includeSettings = true) }
    var hubList by remember(catalog, tick) {
        mutableStateOf(FeatureRank.sort(ctx, baseList) { it.id })
    }
    LaunchedEffect(customize, tick) {
        if (!customize) hubList = FeatureRank.sort(ctx, allAppFeatures(catalog, true)) { it.id }
    }

    val filtered = remember(hubList, hubQuery, hubCat) {
        hubList.filter { f ->
            val catOk = hubCat == "all" || hubCategoryOf(f) == hubCat
            val q = hubQuery.trim()
            val qOk = q.isEmpty() || listOf(f.title, f.tagline, f.why, f.useWhen).any { it.contains(q, ignoreCase = true) }
            catOk && qOk
        }
    }
    // Basics-first: when not searching / not customizing / All category, collapse advanced
    val displayList = remember(filtered, showAdvanced, hubQuery, hubCat, customize) {
        val searching = hubQuery.isNotBlank() || hubCat != "all" || customize
        if (searching || showAdvanced) filtered
        else {
            val basics = filtered.filter { it.id in HUB_BASIC_IDS }
            if (basics.isNotEmpty()) basics else filtered.take(5)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("All features", color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (customize) "Use ▲ ▼ to reorder — saved as your order"
                    else if (!showAdvanced && hubQuery.isBlank() && hubCat == "all") "Basics first · ${displayList.size} shown"
                    else "Search or pick a category · ${filtered.size} shown",
                    color = Color.Gray, fontSize = 12.sp
                )
            }
            TextButton(onClick = {
                customize = !customize
                if (!customize) tick++
            }) {
                Text(if (customize) "Done" else "Customize", color = AccentPink, fontWeight = FontWeight.Bold)
            }
        }

        // Search + category chips (user-friendly More)
        if (!customize) {
            FeatureSearchBar(hubQuery) { hubQuery = it }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HUB_CATEGORIES.forEach { (id, label) ->
                    val selected = hubCat == id
                    Text(
                        label,
                        color = if (selected) Color.White else Navy,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier
                            .background(if (selected) AccentPink else CardBg, RoundedCornerShape(50))
                            .clickable { hubCat = id }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (customize) {
            TextButton(onClick = {
                FeatureRank.clearCustom(ctx)
                tick++
                customize = false
            }) { Text("Reset to auto (by usage)", fontSize = 12.sp) }
        }

        if (displayList.isEmpty()) {
            Text("No matches. Try another word or category.", color = Color.Gray, fontSize = 13.sp)
        }

        displayList.forEachIndexed { index, f ->
            // index in filtered list; for customize we operate on full hubList
            val fullIndex = hubList.indexOfFirst { it.id == f.id }
            Row(
                Modifier.fillMaxWidth().styled(tintOf(f.color), styleShape(16), lite = true)
                    .then(if (!customize) Modifier.clickable { onOpenFeature(f) } else Modifier)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(46.dp).background(CardBg, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) { Text(f.emoji, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.title, color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        if (f.live) Text("  ● LIVE", color = Color(0xFFE53935), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(f.tagline, color = Navy.copy(alpha = .8f), fontSize = 12.sp)
                    val uses = FeatureRank.useCount(ctx, f.id)
                    if (uses > 0 && !customize) Text("Used $uses×", color = Navy.copy(alpha = .45f), fontSize = 10.sp)
                }
                if (customize && fullIndex >= 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▲", Modifier.clickable {
                            if (fullIndex > 0) {
                                val ids = hubList.map { it.id }
                                FeatureRank.move(ctx, ids, fullIndex, fullIndex - 1)
                                hubList = FeatureRank.sort(ctx, allAppFeatures(catalog, true)) { it.id }
                            }
                        }.padding(4.dp), color = AccentPink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("▼", Modifier.clickable {
                            if (fullIndex < hubList.lastIndex) {
                                val ids = hubList.map { it.id }
                                FeatureRank.move(ctx, ids, fullIndex, fullIndex + 1)
                                hubList = FeatureRank.sort(ctx, allAppFeatures(catalog, true)) { it.id }
                            }
                        }.padding(4.dp), color = AccentPink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    val pinned = ctx.getSharedPreferences("timevora", Context.MODE_PRIVATE)
                        .getString("pinned_tool_id", "") == f.id
                    Text(
                        if (pinned) "📌" else "📍",
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable {
                                val p = ctx.getSharedPreferences("timevora", Context.MODE_PRIVATE)
                                if (pinned) {
                                    p.edit().remove("pinned_tool_id").apply()
                                    Toast.makeText(ctx, "Unpinned from Home", Toast.LENGTH_SHORT).show()
                                } else {
                                    p.edit().putString("pinned_tool_id", f.id).apply()
                                    Toast.makeText(ctx, "Pinned on Home · ${f.title}", Toast.LENGTH_SHORT).show()
                                }
                                tick++
                            }
                            .padding(end = 8.dp)
                    )
                    Text("›", color = AccentPink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // Basics vs advanced toggle (only when collapsed view is active)
        if (!customize && hubQuery.isBlank() && hubCat == "all" && filtered.size > displayList.size) {
            TextButton(
                onClick = { showAdvanced = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show advanced tools (${filtered.size - displayList.size} more)", color = AccentPink, fontWeight = FontWeight.Bold)
            }
        } else if (!customize && showAdvanced && hubQuery.isBlank() && hubCat == "all") {
            TextButton(
                onClick = { showAdvanced = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show basics only", color = AccentPink, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun PermissionCard(title: String, body: String, action: String, click: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Navy)
            Text(body, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = click) { Text(action) }
        }
    }
}
