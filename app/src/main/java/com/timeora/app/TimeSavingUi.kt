package com.timevora.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.PlannerItem
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.loadPlan
import kotlinx.coroutines.launch
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * timevora v5.6.9 — 25 Time-Saving Features hub.
 * Groups A–E with interactive tools that create / organise planner tasks
 * and store lightweight preferences without requiring a DB schema change.
 * Dump supports Undo snackbar after save.
 */
@Composable
internal fun TimeSavingScreen(c: Context, refresh: Int, onBack: () -> Unit, onChanged: () -> Unit, initialTool: String? = null) {
    val scope = rememberCoroutineScope()
    val prefs = remember { c.getSharedPreferences("timevora_timesaving", Context.MODE_PRIVATE) }
    // When opened from More/Home as a specific tool, first back returns to More — not the internal 25 list.
    val fromExternal = initialTool != null
    var tool by remember { mutableStateOf(initialTool) }
    LaunchedEffect(initialTool) { if (initialTool != null) tool = initialTool }
    var planTitles by remember { mutableStateOf(emptyList<String>()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh, reload) {
        val dao = timevoraDatabase.get(c).usageDao()
        planTitles = loadPlan(dao, LocalDate.now()).map { it.item.title }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SurfaceBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(CardBg)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (tool != null && !fromExternal) tool = null
                else onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Navy)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (tool == null) "25 Time-Saving Features" else toolTitle(tool!!),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Navy
                )
                Text(
                    if (tool == null) "Save time every day · timevora v5.6.9"
                    else toolOneLiner(tool!!),
                    fontSize = 11.sp,
                    color = Navy.copy(alpha = 0.55f)
                )
            }
        }

        if (tool == null) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionHeader("A. Smart tools for everyday time")
                FeatureCard(1, "Smart Task Dump", "Empty your mind", "Write everything once — we split it into Planner tasks.") { tool = "dump" }
                FeatureCard(2, "Auto Priority Sort", "Priority without the debate", "Arrange by deadline, importance and effort.") { tool = "priority" }
                FeatureCard(3, "Top 3 Today", "Only three must-dos", "Pick today’s top 3; keep the rest secondary.") { tool = "top3" }
                FeatureCard(4, "Quick Task Matcher", "Fit work to free time", "Suggest tasks for 5 / 10 / 15 / 30 minute slots.") { tool = "matcher" }
                FeatureCard(5, "Task Batching", "Same kind of work together", "Emails, calls, bills — less context switching.") { tool = "batch" }

                SectionHeader("B. Stop time leaks")
                FeatureCard(6, "Time Leak Detector", "Where minutes go", "Spot phone-checking and app-switching leaks.") { tool = "leaks" }
                FeatureCard(7, "Return-to-Task Memory", "Pick up where you left off", "Save last step + next step before you leave.") { tool = "return" }
                FeatureCard(8, "Scroll Stopper", "End the endless scroll", "Set intention or time limit when opening social apps.") { tool = "scroll" }
                FeatureCard(9, "Notification Batch Planner", "Check on a schedule", "Fixed windows for messages instead of constant peeks.") { tool = "notif" }
                FeatureCard(10, "Decision Saver", "Next action, now", "Suggest what to do so you don’t waste 10–15 minutes choosing.") { tool = "decision" }

                SectionHeader("C. Faster planning")
                FeatureCard(11, "Auto Day Planner", "Full-day outline", "Schedule from tasks, duration and free time.") { tool = "dayplan" }
                FeatureCard(12, "Deadline Radar", "Don’t miss due dates", "Upcoming deadlines with early warnings.") { tool = "deadline" }
                FeatureCard(13, "Smart Buffer Time", "Breathing room", "Buffers between meetings so one delay doesn’t ruin the day.") { tool = "buffer" }
                FeatureCard(14, "Energy-Based Planner", "Work matches energy", "High / medium / low energy tasks by time of day.") { tool = "energy" }
                FeatureCard(15, "Routine Templates", "Don’t replan routines", "Morning, office start, weekly review — one-tap checklists.") { tool = "templates" }

                SectionHeader("D. Finish small work fast")
                FeatureCard(16, "2-Minute Action", "Do it now", "Tiny tasks finished immediately instead of left pending.") { tool = "2min" }
                FeatureCard(17, "Smart Checklist Maker", "Ready-made lists", "Travel / meeting / release checklists from a name.") { tool = "checklist" }
                FeatureCard(18, "Meeting Prep & Wrap", "Save meeting time", "Agenda and questions before; decisions after.") { tool = "meeting" }
                FeatureCard(19, "One-Tap Start", "Start without setup", "Favorite focus block or routine in one tap.") { tool = "onetap" }
                FeatureCard(20, "Waiting Time Mode", "Use short waits", "5–15 minute gaps for quick wins.") { tool = "waiting" }

                SectionHeader("E. Daily routines")
                FeatureCard(21, "Daily Shutdown", "Close the day cleanly", "Review pending, notes, and where to start tomorrow.") { tool = "shutdown" }
                FeatureCard(22, "Tomorrow Ready", "Prep tonight", "Priorities and gear ready for the morning.") { tool = "tomorrow" }
                FeatureCard(23, "Weekly Reset", "Weekly clear-out", "Clean the list and set next week’s top priorities.") { tool = "weekly" }
                FeatureCard(24, "Time Calculator", "Sum minutes fast", "Total time across tasks without mental math.") { tool = "calc" }
                FeatureCard(25, "Time Estimate Trainer", "Get better at estimates", "Log estimate vs actual and improve over time.") { tool = "estimate" }

                Spacer(Modifier.height(24.dp))
            }
        } else {
            when (tool) {
                "dump" -> SmartTaskDumpTool(c, prefs, onDone = { reload++; onChanged() }, onGoTop3 = { tool = "top3"; reload++; onChanged() }, onClose = { tool = null; reload++; onChanged() })
                "top3" -> Top3TodayTool(c, prefs, planTitles, onDone = { reload++; onChanged() }, onOpenDump = { tool = "dump" })
                "matcher" -> QuickMatcherTool(c, prefs, planTitles, onOpenDump = { tool = "dump" })
                "priority" -> PrioritySortTool(c, planTitles, onOpenDump = { tool = "dump" })
                "batch" -> BatchTool(c, prefs, planTitles)
                "decision" -> DecisionSaverTool(c, prefs, planTitles)
                "2min" -> TwoMinuteTool(c, prefs, planTitles)
                "calc" -> TimeCalculatorTool()
                "estimate" -> EstimateTrainerTool(c, prefs)
                "return" -> ReturnMemoryTool(c, prefs)
                "shutdown" -> ShutdownTool(c, prefs, planTitles, onDone = { onChanged() })
                "tomorrow" -> TomorrowReadyTool(c, prefs, onDone = { onChanged() })
                "templates" -> TemplatesTool(c, onDone = { reload++; onChanged(); tool = null })
                "checklist" -> ChecklistMakerTool(c, onDone = { reload++; onChanged() })
                "dayplan" -> DayPlanTool(c, prefs, planTitles)
                "leaks" -> LeaksInfoTool(c)
                "scroll" -> ScrollStopperInfo()
                "notif" -> NotifBatchTool(c, prefs)
                "deadline" -> DeadlineRadarTool(c)
                "buffer" -> BufferTool(c, prefs)
                "energy" -> EnergyTool(c, prefs, planTitles)
                "meeting" -> MeetingPrepTool(c, prefs)
                "onetap" -> OneTapTool(c, prefs)
                "waiting" -> WaitingModeTool(c, prefs, planTitles)
                "weekly" -> WeeklyResetTool(c, prefs, planTitles)
                else -> Box(Modifier.padding(16.dp)) { Text("Coming soon in a follow-up build.", color = Navy) }
            }
        }
    }
}

private fun toolTitle(id: String): String = when (id) {
    "dump" -> "Smart Task Dump"
    "priority" -> "Auto Priority Sort"
    "top3" -> "Top 3 Today"
    "matcher" -> "Quick Task Matcher"
    "batch" -> "Task Batching"
    "leaks" -> "Time Leak Detector"
    "return" -> "Return-to-Task Memory"
    "scroll" -> "Scroll Stopper"
    "notif" -> "Notification Batch Planner"
    "decision" -> "Decision Saver"
    "dayplan" -> "Auto Day Planner"
    "deadline" -> "Deadline Radar"
    "buffer" -> "Smart Buffer Time"
    "energy" -> "Energy-Based Planner"
    "templates" -> "Routine Templates"
    "2min" -> "2-Minute Action"
    "checklist" -> "Smart Checklist Maker"
    "meeting" -> "Meeting Prep & Wrap"
    "onetap" -> "One-Tap Start"
    "waiting" -> "Waiting Time Mode"
    "shutdown" -> "Daily Shutdown"
    "tomorrow" -> "Tomorrow Ready"
    "weekly" -> "Weekly Reset"
    "calc" -> "Time Calculator"
    "estimate" -> "Time Estimate Trainer"
    else -> "Time-Saving Tool"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = Navy.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/** Shared empty state for tools that need Planner tasks — one clear next step. */
@Composable
private fun EmptyTasksHint(onOpenDump: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SoftPink, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("No tasks in Planner yet", color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Empty your mind first — Smart Task Dump turns a quick list into Planner items.", color = Navy.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(
            "Open Smart Task Dump →",
            color = AccentPink,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onOpenDump)
        )
    }
}

/** One short line under the tool title — what this screen does. */
private fun toolOneLiner(id: String): String = when (id) {
    "dump" -> "Write everything once → Planner tasks"
    "priority" -> "See today’s tasks in useful order"
    "top3" -> "Lock in only three must-dos"
    "matcher" -> "Match work to free minutes"
    "batch" -> "Group similar work together"
    "leaks" -> "Spot where minutes slip away"
    "return" -> "Resume exactly where you left"
    "scroll" -> "Intention before social apps"
    "notif" -> "Check messages on a schedule"
    "decision" -> "Next action without overthinking"
    "dayplan" -> "Outline a full day from tasks"
    "deadline" -> "See due dates before they hit"
    "buffer" -> "Add breathing room between blocks"
    "energy" -> "Match tasks to your energy"
    "templates" -> "One-tap routine checklists"
    "2min" -> "Finish tiny tasks right now"
    "checklist" -> "Ready-made lists on demand"
    "meeting" -> "Agenda before, actions after"
    "onetap" -> "Start a focus block in one tap"
    "waiting" -> "Use short waits for quick wins"
    "shutdown" -> "Close the day cleanly"
    "tomorrow" -> "Prep priorities the night before"
    "weekly" -> "Clear the list for next week"
    "calc" -> "Sum minutes without mental math"
    "estimate" -> "Compare estimate vs actual"
    else -> "Tool · Time-Saving"
}

/** Clear success feedback + optional light haptic. */
private fun successToast(c: Context, message: String) {
    try {
        val view = (c as? android.app.Activity)?.window?.decorView
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    } catch (_: Exception) { }
    Toast.makeText(c, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun FeatureCard(num: Int, title: String, hi: String, body: String, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(AccentPink.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("•", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AccentPink)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Navy)
                Text(hi, fontSize = 12.sp, color = AccentPink)
            }
            Text("Open →", fontSize = 12.sp, color = AccentPink, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 12.sp, color = Navy.copy(alpha = 0.72f), lineHeight = 16.sp)
    }
}

// ─── Tools ───────────────────────────────────────────────────────────────────

@Composable
private fun SmartTaskDumpTool(
    c: Context,
    prefs: android.content.SharedPreferences,
    onDone: () -> Unit,
    onGoTop3: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastSavedCount by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Write all tasks at once (comma or new line). Each becomes a separate Planner task.",
                fontSize = 13.sp,
                color = Navy.copy(alpha = 0.75f)
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = { Text("Pay bills, client call, deep work block, grocery…") },
                label = { Text("Task dump") }
            )
            PrimaryBtn(if (busy) "Saving…" else "Create tasks", enabled = text.isNotBlank() && !busy) {
                busy = true
                scope.launch {
                    val parts = text
                        .split(Regex("[,\\n]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    val dao = timevoraDatabase.get(c).usageDao()
                    val day = LocalDate.now().toString()
                    val now = System.currentTimeMillis()
                    val insertedIds = mutableListOf<Long>()
                    parts.forEach { title ->
                        val id = dao.savePlanner(
                            PlannerItem(
                                title = title.take(80),
                                notes = "From Smart Task Dump",
                                kind = "task",
                                startDay = day,
                                minuteOfDay = -1,
                                repeatMode = "none",
                                remindBefore = -1,
                                createdAt = now
                            )
                        )
                        if (id > 0) insertedIds.add(id)
                    }
                    busy = false
                    text = ""
                    lastSavedCount = parts.size
                    successToast(c, "Saved · ${parts.size} tasks in Planner")
                    onDone()
                    // Undo snackbar ~8s
                    val result = snackbarHostState.showSnackbar(
                        message = "Added ${parts.size} tasks",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long,
                        withDismissAction = true
                    )
                    if (result == SnackbarResult.ActionPerformed && insertedIds.isNotEmpty()) {
                        insertedIds.forEach { dao.deletePlanner(it) }
                        lastSavedCount = 0
                        successToast(c, "Undone · ${insertedIds.size} tasks removed")
                        onDone()
                    }
                }
            }
            if (lastSavedCount > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SoftPink),
                    shape = styleShape(16),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What’s next?", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("$lastSavedCount tasks in Planner. Narrow to Top 3, or open Planner anytime.", color = Navy.copy(alpha = 0.7f), fontSize = 13.sp)
                        PrimaryBtn("Pick Top 3 Today") { onGoTop3() }
                        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                            Text("Done for now", color = AccentPink)
                        }
                    }
                }
            }
            Text("Tip: set priority and time later in the Planner tab. Undo available for a few seconds after save.", fontSize = 11.sp, color = Navy.copy(alpha = 0.5f))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun Top3TodayTool(
    c: Context,
    prefs: android.content.SharedPreferences,
    planTitles: List<String>,
    onDone: () -> Unit,
    onOpenDump: () -> Unit = {}
) {
    val key = "top3_${LocalDate.now()}"
    var selected by remember {
        mutableStateOf<MutableList<String>>(
            prefs.getString(key, "")?.split("|")?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Pick only today’s 3 most important tasks. Everything else stays secondary so you don’t feel overwhelmed.",
            fontSize = 13.sp,
            color = Navy.copy(alpha = 0.75f)
        )
        if (planTitles.isEmpty()) {
            EmptyTasksHint(onOpenDump)
        } else {
            planTitles.forEach { title ->
                val on = title in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) AccentPink.copy(alpha = 0.12f) else CardBg, RoundedCornerShape(12.dp))
                        .clickable {
                            if (on) selected = selected.filter { it != title }.toMutableList()
                            else if (selected.size < 3) selected = (selected + title).toMutableList()
                            else Toast.makeText(c, "You can only pick 3", Toast.LENGTH_SHORT).show()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (on) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (on) AccentPink else Navy.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(title, color = Navy, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        PrimaryBtn("Save Top 3 (${selected.size}/3)", enabled = selected.isNotEmpty()) {
            prefs.edit().putString(key, selected.joinToString("|")).apply()
            successToast(c, "Saved · Top 3 for today")
            onDone()
        }
        if (selected.isNotEmpty()) {
            Text("Today’s Top 3:", fontWeight = FontWeight.SemiBold, color = Navy)
            selected.forEachIndexed { i, t -> Text("${i + 1}. $t", color = AccentPink, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun QuickMatcherTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>, onOpenDump: () -> Unit = {}) {
    var minutes by remember { mutableIntStateOf(10) }
    val suggestions = remember(minutes, planTitles) {
        // Heuristic: short titles / keywords for short slots
        planTitles.filter { t ->
            val lower = t.lowercase()
            when {
                minutes <= 5 -> lower.contains("email") || lower.contains("reply") || lower.contains("call") || lower.length < 18
                minutes <= 15 -> lower.length < 40 || lower.contains("fix") || lower.contains("review") || lower.contains("mail")
                else -> true
            }
        }.ifEmpty { planTitles.take(3) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("How much free time do you have?", fontSize = 14.sp, color = Navy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { m ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
            }
        }
        Text("Suggested work ($minutes min):", fontWeight = FontWeight.SemiBold, color = Navy)
        if (suggestions.isEmpty()) {
            EmptyTasksHint(onOpenDump)
        } else {
            suggestions.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = AccentPink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t, color = Navy, fontSize = 14.sp)
                }
            }
        }
        Text("Heuristic suggestion — refine by setting your own durations.", fontSize = 11.sp, color = Navy.copy(alpha = 0.5f))
    }
}

@Composable
private fun PrioritySortTool(c: Context, planTitles: List<String>, onOpenDump: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Today's tasks (Planner order). Put high-priority / deadline work higher — edit time/notes in Planner.",
            fontSize = 13.sp,
            color = Navy.copy(alpha = 0.75f)
        )
        if (planTitles.isEmpty()) EmptyTasksHint(onOpenDump)
        else planTitles.forEachIndexed { i, t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(CardBg, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, color = AccentPink, modifier = Modifier.width(28.dp))
                Text(t, color = Navy)
            }
        }
    }
}

@Composable
private fun BatchTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    val batches = listOf("Emails", "Calls", "Bills / Admin", "Coding", "Errands")
    var active by remember { mutableStateOf(prefs.getString("active_batch", "") ?: "") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Pick a batch and do similar work together — fewer context switches.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        batches.forEach { b ->
            val on = active == b
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) AccentPink.copy(alpha = 0.12f) else CardBg, RoundedCornerShape(12.dp))
                    .clickable {
                        active = if (on) "" else b
                        prefs.edit().putString("active_batch", active).apply()
                    }
                    .padding(14.dp)
            ) {
                Text(if (on) "● $b (active)" else "○ $b", color = if (on) AccentPink else Navy, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        if (active.isNotEmpty()) {
            Text("Active batch: $active — group similar tasks in Planner and do them together.", color = AccentPink, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DecisionSaverTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    val suggestion = planTitles.firstOrNull() ?: "No pending tasks — start with Smart Task Dump."
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("End decision paralysis. Best next step:", fontSize = 14.sp, color = Navy)
        Box(
            Modifier
                .fillMaxWidth()
                .background(AccentPink.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Text("Start now", fontSize = 12.sp, color = AccentPink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(suggestion, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Navy)
            }
        }
        Text("Works well with Top 3 and Quick Matcher.", fontSize = 12.sp, color = Navy.copy(alpha = 0.55f))
    }
}

@Composable
private fun TwoMinuteTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    val quick = planTitles.filter {
        val l = it.lowercase()
        l.contains("email") || l.contains("reply") || l.contains("message") || l.contains("rename") ||
            l.contains("confirm") || l.length <= 16
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Tasks you can finish in 2 minutes or less — do them now, don’t leave them pending.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        if (quick.isEmpty()) {
            Text("No 2-minute tasks found. Use short titles like “reply” or “email” for small work.", color = Navy.copy(alpha = 0.6f), fontSize = 13.sp)
        } else {
            quick.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("⚡ $t", color = Navy, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TimeCalculatorTool() {
    var input by remember { mutableStateOf("") }
    val totalMin = remember(input) {
        input.split(Regex("[+\\s,]+"))
            .mapNotNull { it.trim().toIntOrNull() }
            .sum()
    }
    val h = totalMin / 60
    val m = totalMin % 60
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Enter minutes (e.g. 35 + 45 + 20 + 60)", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Minutes") },
            placeholder = { Text("35 45 20 60") }
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text(
                if (totalMin == 0) "Total: —" else "Total: $totalMin min  →  ${h}h ${m}m",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Navy
            )
        }
    }
}

@Composable
private fun EstimateTrainerTool(c: Context, prefs: android.content.SharedPreferences) {
    var task by remember { mutableStateOf("") }
    var estimate by remember { mutableStateOf("") }
    var actual by remember { mutableStateOf("") }
    val historyKey = "estimate_log"
    var log by remember {
        mutableStateOf(prefs.getString(historyKey, "[]") ?: "[]")
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Write an estimate before you start, actual when done. Accuracy improves over time.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = task, onValueChange = { task = it.take(60) }, label = { Text("Task") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = estimate, onValueChange = { estimate = it.filter { ch -> ch.isDigit() }.take(4) }, label = { Text("Estimate min") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = actual, onValueChange = { actual = it.filter { ch -> ch.isDigit() }.take(4) }, label = { Text("Actual min") }, modifier = Modifier.weight(1f))
        }
        PrimaryBtn("Log comparison", enabled = task.isNotBlank() && estimate.isNotBlank() && actual.isNotBlank()) {
            try {
                val arr = JSONArray(log)
                arr.put(JSONObject().put("t", task).put("e", estimate.toInt()).put("a", actual.toInt()).put("d", LocalDate.now().toString()))
                log = arr.toString()
                prefs.edit().putString(historyKey, log).apply()
                task = ""; estimate = ""; actual = ""
                successToast(c, "Saved · estimate logged")
            } catch (_: Exception) {
                Toast.makeText(c, "Could not save", Toast.LENGTH_SHORT).show()
            }
        }
        Text("Recent:", fontWeight = FontWeight.SemiBold, color = Navy)
        val recentEntries = remember(log) {
              runCatching {
                  val arr = JSONArray(log)
                  (arr.length() - 1 downTo maxOf(0, arr.length() - 8)).map { i ->
                      val o = arr.getJSONObject(i)
                      val e = o.getInt("e")
                      val a = o.getInt("a")
                      val diff = a - e
                      "${o.getString("t")}: est ${e}m → actual ${a}m (${if (diff >= 0) "+" else ""}$diff)"
                  }
              }.getOrDefault(emptyList())
          }
          recentEntries.forEach { entry ->
              Text(entry, fontSize = 12.sp, color = Navy.copy(alpha = 0.8f))
          }
    }
}

@Composable
private fun ReturnMemoryTool(c: Context, prefs: android.content.SharedPreferences) {
    var last by remember { mutableStateOf(prefs.getString("return_last", "") ?: "") }
    var next by remember { mutableStateOf(prefs.getString("return_next", "") ?: "") }
    var note by remember { mutableStateOf(prefs.getString("return_note", "") ?: "") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Before you leave a task, note the last step and next step. Resume instantly when you return.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = last, onValueChange = { last = it.take(120) }, label = { Text("Last step") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = next, onValueChange = { next = it.take(120) }, label = { Text("Next step") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        PrimaryBtn("Save memory") {
            prefs.edit()
                .putString("return_last", last)
                .putString("return_next", next)
                .putString("return_note", note)
                .apply()
            successToast(c, "Saved · return point ready")
        }
        if (last.isNotBlank() || next.isNotBlank()) {
            Box(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Column {
                    if (last.isNotBlank()) Text("Last: $last", color = Navy, fontSize = 13.sp)
                    if (next.isNotBlank()) Text("Next: $next", color = AccentPink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (note.isNotBlank()) Text("Note: $note", color = Navy.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ShutdownTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>, onDone: () -> Unit) {
    var note by remember { mutableStateOf(prefs.getString("shutdown_note_${LocalDate.now()}", "") ?: "") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("End of day: review pending work, write a note, and decide where to start tomorrow — so mornings need less thinking.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        Text("Today’s tasks:", fontWeight = FontWeight.SemiBold, color = Navy)
        planTitles.take(12).forEach { Text("• $it", color = Navy.copy(alpha = 0.8f), fontSize = 13.sp) }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(300) },
            label = { Text("Where to start tomorrow / notes") },
            modifier = Modifier.fillMaxWidth().height(100.dp)
        )
        PrimaryBtn("Shutdown complete") {
            prefs.edit().putString("shutdown_note_${LocalDate.now()}", note).apply()
            successToast(c, "Saved · day closed")
            onDone()
        }
    }
}

@Composable
private fun TomorrowReadyTool(c: Context, prefs: android.content.SharedPreferences, onDone: () -> Unit) {
    var plan by remember { mutableStateOf(prefs.getString("tomorrow_plan", "") ?: "") }
    var stuff by remember { mutableStateOf(prefs.getString("tomorrow_stuff", "") ?: "") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Tonight, set tomorrow’s must-do work and what you’ll need.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = plan, onValueChange = { plan = it.take(250) }, label = { Text("Tomorrow’s top tasks") }, modifier = Modifier.fillMaxWidth().height(90.dp), placeholder = { Text("Gym, 9am client call, coding…") })
        OutlinedTextField(value = stuff, onValueChange = { stuff = it.take(200) }, label = { Text("Gear / documents") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Laptop, notes, charger…") })
        PrimaryBtn("Save for tomorrow") {
            prefs.edit().putString("tomorrow_plan", plan).putString("tomorrow_stuff", stuff).apply()
            successToast(c, "Saved · tomorrow ready")
            onDone()
        }
    }
}

@Composable
private fun TemplatesTool(c: Context, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val templates = listOf(
        "Morning routine" to listOf("Water", "Stretch 5 min", "Review Top 3", "Deep work block"),
        "Office startup" to listOf("Check calendar", "Clear urgent mail", "Standup notes", "First deep task"),
        "Weekly review" to listOf("Clear done tasks", "Pick next week Top 3", "Review estimates", "Archive clutter"),
        "Project release" to listOf("Changelog", "Smoke test", "Deploy checklist", "Announce", "Monitor")
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Pick a template — every item becomes a Planner task for today.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        templates.forEach { (name, items) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CardBg, RoundedCornerShape(12.dp))
                    .clickable {
                        scope.launch {
                            val dao = timevoraDatabase.get(c).usageDao()
                            val day = LocalDate.now().toString()
                            val now = System.currentTimeMillis()
                            items.forEach { title ->
                                dao.savePlanner(
                                    PlannerItem(
                                        title = title,
                                        notes = "Template: $name",
                                        kind = "routine",
                                        startDay = day,
                                        minuteOfDay = -1,
                                        repeatMode = "none",
                                        remindBefore = -1,
                                        createdAt = now
                                    )
                                )
                            }
                            successToast(c, "Saved · $name (${items.size} items in Planner)")
                            onDone()
                        }
                    }
                    .padding(14.dp)
            ) {
                Text(name, fontWeight = FontWeight.SemiBold, color = Navy)
                Text(items.joinToString(" · "), fontSize = 12.sp, color = Navy.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ChecklistMakerTool(c: Context, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val presets = mapOf(
        "travel" to listOf("Tickets", "ID / Passport", "Charger", "Medicines", "Booking confirmation", "Clothes"),
        "meeting" to listOf("Agenda", "Documents", "Questions (3)", "Notes app open", "Follow-up tasks"),
        "exam" to listOf("Syllabus review", "Past papers", "Notes summary", "Water / snacks", "Admit card"),
        "release" to listOf("Changelog", "Tests green", "Backup", "Deploy", "Monitor 30 min")
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Type a job name (travel / meeting / exam / release) or custom.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Checklist for…") }, modifier = Modifier.fillMaxWidth())
        PrimaryBtn("Build checklist", enabled = name.isNotBlank()) {
            val key = name.trim().lowercase()
            val items = presets.entries.firstOrNull { key.contains(it.key) }?.value
                ?: listOf("$name — step 1", "$name — step 2", "$name — step 3")
            scope.launch {
                val dao = timevoraDatabase.get(c).usageDao()
                val day = LocalDate.now().toString()
                val now = System.currentTimeMillis()
                items.forEach { title ->
                    dao.savePlanner(
                        PlannerItem(
                            title = title,
                            notes = "Checklist: $name",
                            kind = "task",
                            startDay = day,
                            minuteOfDay = -1,
                            repeatMode = "none",
                            remindBefore = -1,
                            createdAt = now
                        )
                    )
                }
                successToast(c, "Saved · ${items.size} checklist items in Planner")
                onDone()
            }
        }
        Text("Presets: travel, meeting, exam, release", fontSize = 11.sp, color = Navy.copy(alpha = 0.5f))
    }
}

@Composable
private fun DayPlanTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Simple day outline from today’s tasks (set time blocks later in Planner).", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        val blocks = listOf("09:00 Deep work", "10:30 Email / admin", "11:30 Meeting / calls", "14:00 Deep work 2", "16:00 Wrap & review")
        blocks.forEach { Text("• $it", color = Navy, fontSize = 14.sp) }
        Spacer(Modifier.height(8.dp))
        Text("Pending tasks to place:", fontWeight = FontWeight.SemiBold, color = Navy)
        planTitles.forEach { Text("– $it", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f)) }
    }
}

@Composable
private fun LeaksInfoTool(c: Context) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Time Leak Detector relies on usage tracking and Focus Shield.", fontSize = 13.sp, color = Navy.copy(alpha = 0.8f))
        Text("• See screen time and app breakdown on Home / Insights.", fontSize = 13.sp, color = Navy)
        Text("• Set Distraction Shield target apps in a Focus session.", fontSize = 13.sp, color = Navy)
        Text("• Small unlocks / pickups show up in Device stats.", fontSize = 13.sp, color = Navy)
        Text("A dedicated leak timeline can be added in a later build.", fontSize = 12.sp, color = Navy.copy(alpha = 0.5f))
    }
}

@Composable
private fun ScrollStopperInfo() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Scroll Stopper = intention + time limit when you open social / video apps.", fontSize = 13.sp, color = Navy.copy(alpha = 0.8f))
        Text("For now: use Focus timer + Distraction Shield. Add YouTube / Instagram to target packages.", fontSize = 13.sp, color = Navy.copy(alpha = 0.8f))
    }
}

@Composable
private fun NotifBatchTool(c: Context, prefs: android.content.SharedPreferences) {
    var windows by remember { mutableStateOf(prefs.getString("notif_windows", "10:00, 14:00, 18:00") ?: "10:00, 14:00, 18:00") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set fixed windows to check notifications. Avoid opening the phone outside those times.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = windows, onValueChange = { windows = it }, label = { Text("Windows (comma separated)") }, modifier = Modifier.fillMaxWidth())
        PrimaryBtn("Save windows") {
            prefs.edit().putString("notif_windows", windows).apply()
            successToast(c, "Saved · check windows $windows")
        }
    }
}

@Composable
private fun DeadlineRadarTool(c: Context) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dated Planner tasks and meetings act as your deadline radar.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        Text("• Open the Planner tab to see upcoming items.", fontSize = 13.sp, color = Navy)
        Text("• Set reminders (remind before).", fontSize = 13.sp, color = Navy)
        Text("• For big work, estimate duration and set a reminder to start today.", fontSize = 13.sp, color = Navy)
    }
}

@Composable
private fun BufferTool(c: Context, prefs: android.content.SharedPreferences) {
    var buf by remember { mutableStateOf(prefs.getInt("buffer_min", 10).toString()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Default buffer (minutes) after each meeting or deep work block.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = buf, onValueChange = { buf = it.filter { ch -> ch.isDigit() }.take(2) }, label = { Text("Buffer minutes") }, modifier = Modifier.fillMaxWidth())
        PrimaryBtn("Save buffer") {
            prefs.edit().putInt("buffer_min", buf.toIntOrNull() ?: 10).apply()
            successToast(c, "Saved · ${buf}m buffer")
        }
    }
}

@Composable
private fun EnergyTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    var slot by remember { mutableStateOf(prefs.getString("energy_slot", "morning") ?: "morning") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pick your high-energy window — put hard tasks there.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("morning" to "Morning", "afternoon" to "Afternoon", "evening" to "Evening").forEach { (id, label) ->
                FilterChip(selected = slot == id, onClick = {
                    slot = id
                    prefs.edit().putString("energy_slot", id).apply()
                }, label = { Text(label) })
            }
        }
        Text(
            when (slot) {
                "morning" -> "Morning: coding, problem solving, deep work"
                "afternoon" -> "Afternoon: meetings, emails, collaboration"
                else -> "Evening: routine, admin, light review"
            },
            color = AccentPink,
            fontSize = 13.sp
        )
        Text("Tasks to place:", fontWeight = FontWeight.SemiBold, color = Navy)
        planTitles.forEach { Text("• $it", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f)) }
    }
}

@Composable
private fun MeetingPrepTool(c: Context, prefs: android.content.SharedPreferences) {
    var agenda by remember { mutableStateOf(prefs.getString("meet_agenda", "") ?: "") }
    var qs by remember { mutableStateOf(prefs.getString("meet_qs", "") ?: "") }
    var actions by remember { mutableStateOf(prefs.getString("meet_actions", "") ?: "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Agenda + 3 questions before the meeting; decisions / action items after.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = agenda, onValueChange = { agenda = it.take(300) }, label = { Text("Agenda") }, modifier = Modifier.fillMaxWidth().height(80.dp))
        OutlinedTextField(value = qs, onValueChange = { qs = it.take(300) }, label = { Text("Questions") }, modifier = Modifier.fillMaxWidth().height(80.dp))
        OutlinedTextField(value = actions, onValueChange = { actions = it.take(300) }, label = { Text("Action items (after)") }, modifier = Modifier.fillMaxWidth().height(80.dp))
        PrimaryBtn("Save meeting notes") {
            prefs.edit().putString("meet_agenda", agenda).putString("meet_qs", qs).putString("meet_actions", actions).apply()
            successToast(c, "Saved")
        }
    }
}

@Composable
private fun OneTapTool(c: Context, prefs: android.content.SharedPreferences) {
    var label by remember { mutableStateOf(prefs.getString("onetap_label", "Morning Coding – 50 min") ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Favorite preset — one tap to remember what to start. Run the timer from the Focus tab.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        OutlinedTextField(value = label, onValueChange = { label = it.take(60) }, label = { Text("Preset name") }, modifier = Modifier.fillMaxWidth())
        PrimaryBtn("Save One-Tap preset") {
            prefs.edit().putString("onetap_label", label).apply()
            successToast(c, "Saved · preset $label")
        }
        if (label.isNotBlank()) {
            Box(Modifier.fillMaxWidth().background(AccentPink.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("▶ $label", fontWeight = FontWeight.Bold, color = Navy)
            }
        }
    }
}

@Composable
private fun WaitingModeTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    QuickMatcherTool(c, prefs, planTitles)
}

@Composable
private fun WeeklyResetTool(c: Context, prefs: android.content.SharedPreferences, planTitles: List<String>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("15 minutes weekly: clean the list, drop junk tasks, write next week’s top priorities.", fontSize = 13.sp, color = Navy.copy(alpha = 0.75f))
        Text("Current open tasks (${planTitles.size}):", fontWeight = FontWeight.SemiBold, color = Navy)
        planTitles.forEach { Text("• $it", fontSize = 13.sp, color = Navy.copy(alpha = 0.8f)) }
        var priorities by remember { mutableStateOf(prefs.getString("week_priorities", "") ?: "") }
        OutlinedTextField(value = priorities, onValueChange = { priorities = it.take(300) }, label = { Text("Next week top priorities") }, modifier = Modifier.fillMaxWidth().height(100.dp))
        PrimaryBtn("Save weekly priorities") {
            prefs.edit().putString("week_priorities", priorities).putString("week_reset_day", LocalDate.now().toString()).apply()
            successToast(c, "Saved · weekly reset")
        }
    }
}

@Composable
private fun PrimaryBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (enabled) AccentPink else AccentPink.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
