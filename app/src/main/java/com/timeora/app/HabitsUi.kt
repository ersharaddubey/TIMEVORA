package com.timevora.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.Habit
import com.timevora.app.data.HabitLog
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.RewardEngine
import com.timevora.app.data.StreakFreeze
import com.timevora.app.data.RewardCatalog
import com.timevora.app.data.todayKey
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun HabitsScreen(context: Context, refresh: Int, onChanged: () -> Unit) {
    val dao = remember { timevoraDatabase.get(context).usageDao() }
    val scope = rememberCoroutineScope()
    val day = LocalDate.now().toString()
    var habits by remember { mutableStateOf(emptyList<Habit>()) }
    var completed by remember { mutableStateOf(emptySet<Long>()) }
    var totalXp by remember { mutableStateOf(0) }
    var todayXp by remember { mutableStateOf(0) }
    var xpTarget by remember { mutableIntStateOf(context.getSharedPreferences("timevora", Context.MODE_PRIVATE).getInt("daily_xp_target", 50)) }
    var streak by remember { mutableStateOf(com.timevora.app.data.StreakStats(0, 0, 0)) }
    var unlocked by remember { mutableStateOf(emptySet<String>()) }
    var achievementProgress by remember { mutableStateOf(emptyMap<String, Int>()) }
    var freezes by remember { mutableIntStateOf(StreakFreeze.count(context)) }
    var freezeNote by remember { mutableStateOf("") }
    var focusCount by remember { mutableIntStateOf(0) }
    var habitLogCount by remember { mutableIntStateOf(0) }
    var taskCount by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal") }
    var schedule by remember { mutableStateOf("daily") }
    var showArchived by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<Map<Long, Set<String>>>(emptyMap()) }
    val weekStart = LocalDate.now().minusDays(6).toString()

    LaunchedEffect(refresh) {
        habits = if (showArchived) dao.allHabits().filter { !it.active } else dao.activeHabits()
        history = habits.associate { h -> h.id to dao.habitLogDays(h.id, weekStart, day).toSet() }
        completed = habits.filter { dao.habitDone(it.id, day) > 0 }.map { it.id }.toSet()
        totalXp = dao.totalXp()
        todayXp = dao.xpToday(day)
        streak = RewardEngine.currentStreak(context)
        unlocked = dao.unlockedAchievements().map { it.achievementId }.toSet()
        focusCount = dao.focusSessionCount()
        habitLogCount = dao.completedHabitLogCount()
        taskCount = dao.completedPlannerCount()
        RewardEngine.evaluateAchievements(context)
        // Evaluation may have used or earned a Streak Freeze, so read the streak and freezes again.
        streak = RewardEngine.currentStreak(context)
        freezes = StreakFreeze.count(context)
        freezeNote = StreakFreeze.recentNote(context)
        achievementProgress = RewardEngine.progress(context)
        unlocked = dao.unlockedAchievements().map { it.achievementId }.toSet()
        totalXp = dao.totalXp()
        todayXp = dao.xpToday(day)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your habits", color = Navy, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Small actions. Consistent progress.", color = Color.Gray, fontSize = 13.sp)
                TextButton(onClick = { showArchived = !showArchived; onChanged() }) { Text(if (showArchived) "Show active habits" else "View archived habits") }
            }
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, contentDescription = "Add habit", tint = Blue) }
        }
        val dueToday = habits.count { it.active && isHabitDue(it, LocalDate.now()) }
        val dueCompleted = habits.count { it.active && isHabitDue(it, LocalDate.now()) && it.id in completed }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Due today", "$dueCompleted/$dueToday", Modifier.weight(1f))
            MiniStat("XP today", "+$todayXp", Modifier.weight(1f))
            MiniStat("Total XP", "$totalXp", Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = PanelBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DAILY XP TARGET", color = Navy, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("$todayXp / $xpTarget XP", color = Blue, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.LinearProgressIndicator(progress = { (todayXp.toFloat() / xpTarget.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(25, 50, 100).forEach { target ->
                        TextButton(onClick = {
                            xpTarget = target
                            context.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit().putInt("daily_xp_target", target).apply()
                        }) { Text(if (xpTarget == target) "✓ $target" else "$target") }
                    }
                }
                Text("Target is a personal motivation setting; it does not change XP rewards.", color = Color.Gray, fontSize = 10.sp)
            }
        }
        val level = RewardEngine.levelProgress(totalXp)
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LEVEL ${level.level}", color = Color(0xFFB9C8E8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${level.currentXp}/${level.nextLevelXp} XP to next level", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Text("🔥 ${streak.current} day streak", color = Color.White, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.LinearProgressIndicator(progress = { level.progress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF55D6A5), trackColor = Color(0xFF344563))
                Text("Best streak: ${streak.best} days  •  ${streak.activeDays} active days", color = Color(0xFFB9C8E8), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth().styled(Color(0xFF344563), styleShape(12), lite = true).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🧊", fontSize = 22.sp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Streak freezes: $freezes/${StreakFreeze.MAX}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("A missed day will not break a ${StreakFreeze.MIN_STREAK_TO_PROTECT}+ day streak. Earn one for every ${StreakFreeze.DAYS_PER_FREEZE} days in a row.", color = Color(0xFFB9C8E8), fontSize = 10.sp)
                    }
                }
                if (freezeNote.isNotEmpty()) Text(freezeNote, color = Color(0xFF55D6A5), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("DAILY CONSISTENCY", color = Color(0xFFB9C8E8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(if (dueToday == 0) "No habits scheduled today" else "${(dueCompleted * 100 / dueToday)}% completed", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("$dueCompleted of $dueToday habits due today completed", color = Color(0xFFB9C8E8), fontSize = 11.sp)
                Text("${LocalDate.now().dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} • $day", color = Color(0xFFB9C8E8), fontSize = 12.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("7-DAY HABIT ANALYTICS", color = Navy, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                val possible = habits.sumOf { h -> (0..6).count { offset -> isHabitDue(h, LocalDate.now().minusDays(offset.toLong())) } }
                val doneWeek = history.values.sumOf { it.size }
                val pct = if (possible == 0) 0 else (doneWeek * 100 / possible).coerceAtMost(100)
                Text("$doneWeek completions • $pct% consistency", color = Blue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (6 downTo 0).forEach { offset ->
                        val date = LocalDate.now().minusDays(offset.toLong())
                        val count = history.values.count { date.toString() in it }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (count > 0) "●" else "○", color = if (count > 0) Color(0xFF15966A) else Color.LightGray, fontSize = 18.sp)
                            Text(date.dayOfWeek.name.take(1), color = Color.Gray, fontSize = 9.sp)
                        }
                    }
                }
                Text("Green dots mark days with at least one completed habit.", color = Color.Gray, fontSize = 10.sp)
            }
        }
        Text(if (showArchived) "ARCHIVED HABITS" else "TODAY'S HABITS", color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (habits.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Build your first routine", color = Navy, fontWeight = FontWeight.Bold)
                    Text("Add a habit such as reading, exercise, studying or taking a mindful break. Your habit history stays on this device.", color = Color.Gray, fontSize = 13.sp)
                    Button(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add your first habit") }
                }
            }
        }
        habits.forEach { habit ->
            val isDone = habit.id in completed
            Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(16), lite = true).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = habit.active, onClick = {
                    scope.launch {
                        if (isDone) {
                            dao.unlogHabit(habit.id, day)
                        } else {
                            dao.logHabit(HabitLog(habit.id, day, System.currentTimeMillis()))
                            RewardEngine.award(context, "habit-${habit.id}-$day", day, 10, "Completed: ${habit.title}")
                        }
                        onChanged()
                    }
                }) {
                    Icon(if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = if (isDone) "Mark incomplete" else "Complete habit", tint = if (isDone) Color(0xFF15966A) else Blue)
                }
                Column(Modifier.weight(1f)) {
                    Text(habit.title, color = if (isDone) Color.Gray else Navy, fontWeight = FontWeight.SemiBold)
                    Text("${habit.category} • ${habit.scheduleLabel()} • +10 XP", color = Color.Gray, fontSize = 10.sp)
                    val days = history[habit.id].orEmpty()
                    val weekCount = days.size
                    Text("Last 7 days: $weekCount • Current streak: ${habitStreak(days)} days", color = Blue, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (6 downTo 0).forEach { offset ->
                            val date = LocalDate.now().minusDays(offset.toLong())
                            Text(if (date.toString() in days) "●" else "○", color = if (date.toString() in days) Color(0xFF15966A) else Color.LightGray, fontSize = 13.sp)
                        }
                    }
                }
                if (isDone) Text("DONE", color = Color(0xFF15966A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { scope.launch { if (habit.active) dao.archiveHabit(habit.id) else dao.saveHabit(habit.copy(active = true)); onChanged() } }) { Text(if (habit.active) "Archive" else "Restore", fontSize = 10.sp) }
            }
        }
        Text("BADGES & ACHIEVEMENTS (${unlocked.size}/${RewardCatalog.achievements.size})", color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        RewardCatalog.achievements.forEach { achievement ->
            val isUnlocked = achievement.id in unlocked
            val progress = (achievementProgress[achievement.id] ?: 0).coerceAtMost(achievement.threshold)
            Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isUnlocked) achievement.icon else "🔒", fontSize = 24.sp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(achievement.title, color = Navy, fontWeight = FontWeight.SemiBold)
                    Text(achievement.description, color = Color.Gray, fontSize = 11.sp)
                    if (!isUnlocked) Text("$progress/${achievement.threshold}", color = Blue, fontSize = 10.sp)
                }
                Text(if (isUnlocked) "UNLOCKED" else "LOCKED", color = if (isUnlocked) Color(0xFF15966A) else Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("XP rewards are saved in a permanent on-device ledger. Repeated events cannot award the same reward twice.", color = Color.Gray, fontSize = 11.sp)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Create a habit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Habit name") }, singleLine = true)
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true)
                    Text("Repeat schedule", color = Navy, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("daily", "weekdays", "weekly").forEach { option ->
                            TextButton(onClick = { schedule = option }) { Text(if (schedule == option) "✓ ${option.replaceFirstChar { it.uppercase() }}" else option.replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    Text("Weekly repeats on the weekday you create the habit.", color = Color.Gray, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch {
                        dao.saveHabit(Habit(title = title.trim(), category = category.trim().ifBlank { "Personal" }, schedule = schedule))
                        title = ""; category = "Personal"; schedule = "daily"; showAdd = false; onChanged()
                    }
                }) { Text("Save habit") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}


private fun Habit.scheduleLabel(): String = when (schedule) {
    "weekdays" -> "Weekdays"
    "weekly" -> "Weekly"
    else -> "Daily"
}

private fun isHabitDue(habit: Habit, date: LocalDate): Boolean = when (habit.schedule) {
    "weekdays" -> date.dayOfWeek.value in 1..5
    "weekly" -> date.dayOfWeek == java.time.Instant.ofEpochMilli(habit.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().dayOfWeek
    else -> true
}

private fun habitStreak(days: Set<String>): Int {
    var cursor = LocalDate.now()
    if (cursor.toString() !in days) cursor = cursor.minusDays(1)
    var count = 0
    while (cursor.toString() in days) { count++; cursor = cursor.minusDays(1) }
    return count
}
