package com.timevora.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject
import com.timevora.app.data.timevoraDatabase
import java.time.LocalDate
import java.util.UUID

private data class LocalChallenge(
    val id: String, val title: String, val friend: String, val goalMinutes: Int,
    val durationDays: Int, val startDay: String, val myMinutes: Int, val friendMinutes: Int
)

private fun readChallenges(c: Context): List<LocalChallenge> {
    val raw = c.getSharedPreferences("timevora_challenges", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    return runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i -> val o = a.getJSONObject(i)
            LocalChallenge(o.getString("id"), o.getString("title"), o.getString("friend"), o.getInt("goal"), o.getInt("days"), o.getString("start"), o.getInt("mine"), o.getInt("theirs"))
        }
    }.getOrDefault(emptyList())
}

private fun writeChallenges(c: Context, items: List<LocalChallenge>) {
    val a = JSONArray()
    items.forEach { x -> a.put(JSONObject().put("id", x.id).put("title", x.title).put("friend", x.friend).put("goal", x.goalMinutes).put("days", x.durationDays).put("start", x.startDay).put("mine", x.myMinutes).put("theirs", x.friendMinutes)) }
    c.getSharedPreferences("timevora_challenges", Context.MODE_PRIVATE).edit().putString("items", a.toString()).apply()
}

@Composable
internal fun OfflineScorecards(c: Context, refresh: Int) {
    var items by remember { mutableStateOf(readChallenges(c)) }
    var showAdd by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var trackedFocusMinutes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(refresh) { items = readChallenges(c) }
    LaunchedEffect(refresh, items) {
        val dao = timevoraDatabase.get(c).usageDao()
        trackedFocusMinutes = items.associate { challenge ->
            val start = runCatching { LocalDate.parse(challenge.startDay) }.getOrDefault(LocalDate.now())
            val plannedEnd = start.plusDays((challenge.durationDays - 1).coerceAtLeast(0).toLong())
            val end = minOf(LocalDate.now(), plannedEnd)
            val seconds = if (end.isBefore(start)) 0L else dao.focusSecondsBetween(start.toString(), end.toString())
            challenge.id to (seconds / 60L).toInt()
        }
    }
    fun save(next: List<LocalChallenge>) { items = next; writeChallenges(c, next) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scorecard challenges", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Set a goal with a friend and compare totals", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { showAdd = true }) { Text("New") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Offline scorecard · scores are entered by hand", fontWeight = FontWeight.SemiBold)
                Text("Your focus minutes are recorded on this device. Enter your friend's reported score manually and share an invite using your preferred app. For real-time progress with friends, use the Live rooms tab instead.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (items.isEmpty()) {
            Card { Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🏆", style = MaterialTheme.typography.headlineLarge)
                Text("No challenges yet", fontWeight = FontWeight.SemiBold)
                Text("Start a focus challenge with a friend.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { showAdd = true }) { Text("Create challenge") }
            } }
        }
        items.forEach { x ->
            val elapsed = (LocalDate.now().toEpochDay() - LocalDate.parse(x.startDay).toEpochDay()).toInt().coerceAtLeast(0)
            val left = (x.durationDays - elapsed).coerceAtLeast(0)
            val myRecordedMinutes = x.myMinutes + (trackedFocusMinutes[x.id] ?: 0)
            val minePct = (myRecordedMinutes.toFloat() / (x.goalMinutes * x.durationDays).coerceAtLeast(1)).coerceIn(0f, 1f)
            val theirPct = (x.friendMinutes.toFloat() / (x.goalMinutes * x.durationDays).coerceAtLeast(1)).coerceIn(0f, 1f)
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(x.title, fontWeight = FontWeight.Bold)
                            Text("You vs ${x.friend} • ${x.goalMinutes} focus min/day • ${x.durationDays} days", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (left == 0) "Ended" else "$left days left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("You · $myRecordedMinutes min total", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { minePct }, modifier = Modifier.fillMaxWidth())
                    Text("Your total combines manual entries and locally recorded timevora focus sessions. Friend scores are entered manually; they are not fetched or synced automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${x.friend} · ${x.friendMinutes} min", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { theirPct }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { save(items.map { if (it.id == x.id) it.copy(myMinutes = it.myMinutes + 5) else it }) }) { Text("Manual +5 min") }
                        OutlinedButton(onClick = { editId = x.id }) { Text("Friend score") }
                        OutlinedButton(onClick = {
                            val msg = "timevora challenge: ${x.title}\nGoal: ${x.goalMinutes} focus minutes/day for ${x.durationDays} days.\nLet's compete! Install timevora and track your progress."
                            c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Share challenge"))
                        }) { Text("Invite") }
                    }
                    TextButton(onClick = { save(items.filterNot { it.id == x.id }) }) { Text("Delete challenge") }
                }
            }
        }
    }

    if (showAdd) AddChallengeDialog(onDismiss = { showAdd = false }, onCreate = { title, friend, goal, days ->
        save(items + LocalChallenge(UUID.randomUUID().toString(), title, friend, goal, days, LocalDate.now().toString(), 0, 0)); showAdd = false
    })
    editId?.let { id ->
        val x = items.firstOrNull { it.id == id }
        if (x != null) ScoreDialog(friend = x.friend, initial = x.friendMinutes, onDismiss = { editId = null }, onSave = { score -> save(items.map { if (it.id == id) it.copy(friendMinutes = score) else it }); editId = null })
    }
}

@Composable
private fun AddChallengeDialog(onDismiss: () -> Unit, onCreate: (String, String, Int, Int) -> Unit) {
    var title by remember { mutableStateOf("Daily Focus Sprint") }
    var friend by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("60") }
    var days by remember { mutableStateOf("7") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create challenge") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Challenge name") }, singleLine = true)
            OutlinedTextField(friend, { friend = it }, label = { Text("Friend's name") }, singleLine = true)
            OutlinedTextField(goal, { goal = it.filter(Char::isDigit).take(4) }, label = { Text("Focus goal (minutes/day)") }, singleLine = true)
            OutlinedTextField(days, { days = it.filter(Char::isDigit).take(2) }, label = { Text("Duration (days)") }, singleLine = true)
        }
    }, confirmButton = { Button(enabled = title.isNotBlank() && friend.isNotBlank() && (goal.toIntOrNull() ?: 0) in 1..1440 && (days.toIntOrNull() ?: 0) in 1..90, onClick = { onCreate(title.trim(), friend.trim(), goal.toInt(), days.toInt()) }) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ScoreDialog(friend: String, initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableStateOf(initial.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Update $friend's score") }, text = {
        OutlinedTextField(value, { value = it.filter(Char::isDigit).take(7) }, label = { Text("Total focus minutes reported") }, singleLine = true)
    }, confirmButton = { Button(enabled = value.toIntOrNull() != null, onClick = { onSave(value.toInt()) }) { Text("Save score") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
