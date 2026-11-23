package com.timevora.app

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.audio.AmbientPlayer
import com.timevora.app.data.FocusSession
import com.timevora.app.data.Forest
import com.timevora.app.data.ForestSnapshot
import com.timevora.app.data.ForestTiers
import com.timevora.app.data.treeKindFor
import com.timevora.app.data.Live
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.todayKey
import com.timevora.app.notifications.FocusManager
import com.timevora.app.notifications.FocusSettings
import com.timevora.app.notifications.Pomodoro
import com.timevora.app.util.Fmt
import kotlinx.coroutines.launch

/** Focus timer: pick a length, work, and see how much phone time leaked into the session. */
@Composable
internal fun FocusScreen(c: Context, refresh: Int, onChanged: () -> Unit) {
    val now = rememberNow()
    val live = Live.current(c)
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(25) }
    var customText by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var expectedOutcome by remember { mutableStateOf("") }
    var ritualSeconds by remember { mutableIntStateOf(0) }
    var ritualReady by remember { mutableStateOf(false) }
    var reviewMission by remember { mutableStateOf<String?>(null) }
    var todaySec by remember { mutableLongStateOf(0L) }
    var recent by remember { mutableStateOf(emptyList<FocusSession>()) }
    var usedBefore by remember { mutableLongStateOf(0L) }
    var forest by remember { mutableStateOf<ForestSnapshot?>(null) }
    var pomo by remember { mutableStateOf(FocusSettings.pomodoro(c)) }
    var pomoWork by remember { mutableIntStateOf(FocusSettings.workMinutes(c)) }
    var pomoRounds by remember { mutableIntStateOf(FocusSettings.rounds(c)) }
    var strict by remember { mutableStateOf(FocusSettings.strict(c)) }
    var confirmStop by remember { mutableStateOf(false) }
    var recoverCountdown by remember { mutableIntStateOf(0) }
    var recoveryReason by remember { mutableStateOf("Unplanned scrolling") }
    var shieldEnabled by remember { mutableStateOf(c.getSharedPreferences("timevora", Context.MODE_PRIVATE).getBoolean("focus_shield_enabled", false)) }
    var shieldTargets by remember { mutableStateOf(c.getSharedPreferences("timevora", Context.MODE_PRIVATE).getString("focus_shield_packages", "") ?: "") }
    var shieldWhitelist by remember { mutableStateOf(c.getSharedPreferences("timevora", Context.MODE_PRIVATE).getString("focus_shield_whitelist", "") ?: "") }
    var distractionCount by remember { mutableIntStateOf(0) }
    var dnaSessions by remember { mutableStateOf(emptyList<FocusSession>()) }
    val recoveryPaused = FocusManager.isRecoveryPaused(c)

    // One-tap Focus from Home plan row / Continue card — prefill + allow skip ritual
    var fromQuickStart by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        val p = c.getSharedPreferences("timevora", Context.MODE_PRIVATE)
        val pending = p.getString("pending_focus_label", "").orEmpty()
        if (pending.isNotBlank()) {
            label = pending
            minutes = 25
            fromQuickStart = true
            p.edit().remove("pending_focus_label").apply()
        }
    }

    val fp = FocusManager.prefs(c)
    val end = run { version; fp.getLong("end", 0L) }
    val start = fp.getLong("start", 0L)
    val planned = fp.getInt("planned", 0)
    val active = end > 0L
    val remaining = end - now
    val inBreak = run { version; Pomodoro.inBreak(c) }
    val breakLeft = Pomodoro.breakEnd(c) - now

    LaunchedEffect(recoverCountdown) {
        if (recoverCountdown > 0) { kotlinx.coroutines.delay(1000); recoverCountdown = (recoverCountdown - 1).coerceAtLeast(0) }
    }
    LaunchedEffect(refresh, version) {
        val log = c.getSharedPreferences("timevora_distraction_log", Context.MODE_PRIVATE).getString("events", "") ?: ""
        distractionCount = log.lines().count { it.isNotBlank() }
        dnaSessions = timevoraDatabase.get(c).usageDao().recentFocus(60)
    }
    LaunchedEffect(ritualSeconds) {
        if (ritualSeconds > 0) { kotlinx.coroutines.delay(1000); ritualSeconds = (ritualSeconds - 1).coerceAtLeast(0) }
    }

    LaunchedEffect(refresh, version) {
        val dao = timevoraDatabase.get(c).usageDao()
        todaySec = dao.focusSeconds(todayKey())
        recent = dao.recentFocus(5)
        usedBefore = if (start > 0L && end > 0L) dao.usageBetweenExcluding(start, Long.MAX_VALUE, c.packageName) else 0L
        forest = Forest.load(c)
        if (fp.getBoolean("mission_review_pending", false)) reviewMission = fp.getString("mission_review_label", "") ?: ""
    }
    // While a session runs, re-read the saved phone use every 5 seconds so the tree can wither live.
    LaunchedEffect(active, now / 5000L) {
        if (active && start > 0L) {
            usedBefore = timevoraDatabase.get(c).usageDao().usageBetweenExcluding(start, Long.MAX_VALUE, c.packageName)
        }
    }
    // The timer ran out while this screen is open: finish it here too (the alarm may arrive a moment later).
    LaunchedEffect(active && remaining <= 0L) {
        if (active && remaining <= 0L) {
            FocusManager.finish(c, natural = true, notify = false)
            version++
            onChanged()
        }
    }

    // Pomodoro: the break is over while this screen is open, so start the next round right here (the alarm is only a backup).
    LaunchedEffect(inBreak && breakLeft <= 0L) {
        if (inBreak && breakLeft <= 0L && !active) {
            Pomodoro.nextWork(c, notify = false)
            version++
            onChanged()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Focus timer", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)

        if (inBreak && !active) {
            PomodoroBreakCard(
                c, now,
                onSkip = { Pomodoro.nextWork(c, notify = false); version++; onChanged() },
                onEnd = { Pomodoro.endCycle(c); version++; onChanged() }
            )
        } else if (recoveryPaused) {
            val left = FocusManager.recoveryRemaining(c)
            Card(colors = CardDefaults.cardColors(containerColor = PanelBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pause and recover", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Text("It's okay. Let's get back to focus.", color = Navy)
                    Text("Paused with ${Fmt.clock(left)} remaining", color = Blue, fontWeight = FontWeight.SemiBold)
                    Text("Recovery reason: $recoveryReason", color = Color.Gray, fontSize = 12.sp)
                    if (recoverCountdown > 0) {
                        Text("Reset · $recoverCountdown", color = Blue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { (30 - recoverCountdown).coerceIn(0, 30) / 30f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Button(onClick = { recoverCountdown = 30 }, modifier = Modifier.fillMaxWidth()) { Text("Start 30-second reset") }
                    }
                    if (recoverCountdown == 0) Button(onClick = { FocusManager.resumeAfterRecovery(c); version++; onChanged() }, modifier = Modifier.fillMaxWidth()) { Text("Resume session") }
                    TextButton(onClick = { scope.launch { FocusManager.resumeAfterRecovery(c); FocusManager.finish(c, natural = false, notify = false); version++; onChanged() } }) { Text("End session") }
                }
            }
        } else if (active && remaining > 0L) {
            val strictOn = fp.getBoolean("strict", false)
            val plannedMs = planned * 1000L
            val elapsed = (plannedMs - remaining).coerceIn(0L, plannedMs)
            // Only other apps count: time spent inside timevora itself is not a leak.
            val leaked = usedBefore + (if (live != null && live.pkg != c.packageName) (now - maxOf(live.startMillis, start)).coerceAtLeast(0L) else 0L)
            val withered = leaked > Forest.GRACE_LEAK_MS
            val growsTree = planned >= Forest.MIN_TREE_SECONDS
            LiveTree(
                kind = treeKindFor(planned),
                growth = if (plannedMs > 0L) elapsed.toFloat() / plannedMs else 0f,
                withered = withered,
                tier = ForestTiers.forStreak(forest?.streak ?: 0).level
            )
            Text(
                when {
                    !growsTree -> "Sessions under 5 minutes do not grow a tree."
                    withered -> "🥀 Your tree has withered. Stay off other apps next time to grow a healthy one."
                    else -> "🌱 Growing. Allowance left in other apps: ${Fmt.hms((Forest.GRACE_LEAK_MS - leaked).coerceAtLeast(0L))}"
                },
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Navy
            )
            if (!Live.trackingAlive(c)) {
                Text("Tracking is off, so timevora cannot see other apps. Start tracking from Home or Setup so the tree reacts to phone use.", fontSize = 12.sp, color = Blue)
            }
            if (Pomodoro.isOn(c) || strictOn) {
                Text(
                    listOfNotNull(
                        if (Pomodoro.isOn(c)) "🍅 Pomodoro ${Pomodoro.summary(c)}" else null,
                        if (strictOn) "🔒 Strict Mode" else null
                    ).joinToString("   "),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Navy
                )
            }
            Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fp.getString("label", "")?.ifBlank { "FOCUS SESSION" } ?: "FOCUS SESSION", color = Color(0xFFB9C8E8), fontSize = 12.sp)
                    Text(Fmt.clock(remaining), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { if (plannedMs > 0L) elapsed.toFloat() / plannedMs else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Elapsed ${Fmt.hms(elapsed)} of ${Fmt.hms(plannedMs)}", color = Color(0xFFD9E4FF), fontSize = 12.sp)
                    Text("Phone used in other apps during this session: ${Fmt.hms(leaked)}", color = Color(0xFFD9E4FF), fontSize = 12.sp)
                }
            }
            val stopNow = { scope.launch { FocusManager.finish(c, natural = false, notify = false); version++; onChanged() }; Unit }
            OutlinedButton(
                onClick = { if (strictOn) { confirmStop = true } else { stopNow() } },
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text(if (Pomodoro.isOn(c)) "Stop Pomodoro" else "Stop session") }
            Button(onClick = { recoveryReason = "Unplanned scrolling"; FocusManager.pauseForRecovery(c); recoverCountdown = 30; version++; onChanged() }, modifier = Modifier.fillMaxWidth()) { Text("Pause & Recover · 30-sec reset") }
            if (strictOn) Text("Strict Mode: stopping needs a typed confirmation. Other apps are not blocked.", fontSize = 11.sp, color = Color.Gray)
            if (confirmStop) {
                StrictQuitDialog(
                    title = "Strict Mode is on",
                    consequence = "Quitting now withers this tree" + if (Pomodoro.isOn(c)) " and cancels the remaining rounds." else ".",
                    confirmLabel = "Quit session",
                    onConfirm = { confirmStop = false; stopNow() },
                    onDismiss = { confirmStop = false }
                )
            }
        } else {
            Text("Pick a length and start. When time is up you get a notification. If you open other apps during the session, timevora gently reminds you and counts how much phone time leaked in.", color = Color.Gray, fontSize = 13.sp)
            PomodoroSetup(c, pomo, { pomo = it }, pomoWork, { pomoWork = it }, pomoRounds, { pomoRounds = it })
            if (!pomo) {
                ChipRow(listOf(15 to "15 min", 25 to "25 min", 45 to "45 min", 60 to "60 min"), minutes) { minutes = it; customText = "" }
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter(Char::isDigit).take(3); customText.toIntOrNull()?.let { m -> if (m > 0) minutes = m } },
                    label = { Text("Custom minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ToggleRow("🔒 Strict Mode", "To stop early you must type a confirmation sentence. Apps are never blocked, it only adds friction.", strict) {
                strict = it; FocusSettings.setStrict(c, it)
            }
            Card(colors = CardDefaults.cardColors(containerColor = PanelBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🛡️ DISTRACTION SHIELD", fontWeight = FontWeight.Bold, color = Navy)
                    ToggleRow("Shield during focus", "Mindful warning for selected distracting apps (not a hard block)", shieldEnabled) {
                        shieldEnabled = it; c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit().putBoolean("focus_shield_enabled", it).apply()
                    }
                    OutlinedTextField(value = shieldTargets, onValueChange = { shieldTargets = it; c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit().putString("focus_shield_packages", it).apply() }, label = { Text("Target app package names (comma-separated)") }, placeholder = { Text("com.instagram.android, com.google.android.youtube") }, modifier = Modifier.fillMaxWidth(), singleLine = false)
                    OutlinedTextField(value = shieldWhitelist, onValueChange = { shieldWhitelist = it; c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit().putString("focus_shield_whitelist", it).apply() }, label = { Text("Whitelist package names (comma-separated)") }, placeholder = { Text("e.g. com.whatsapp") }, modifier = Modifier.fillMaxWidth(), singleLine = false)
                    Text("Only chosen target apps trigger the Focus Shield. Whitelist entries override targets. Android overlays allow warnings, not guaranteed app blocking.", color = Color.Gray, fontSize = 11.sp)
                    Text("Distraction events logged on this device: $distractionCount", color = Blue, fontSize = 12.sp)
                }
            }
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(100) },
                label = { Text("Today's mission — what must you finish?") },
                placeholder = { Text("e.g. Complete the React login page") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = expectedOutcome, onValueChange = { expectedOutcome = it.take(160) },
                label = { Text("What result should you have at the end?") },
                placeholder = { Text("e.g. Login form validated and ready to test") },
                singleLine = false, modifier = Modifier.fillMaxWidth()
            )
            if (ritualSeconds > 0) {
                Card(colors = CardDefaults.cardColors(containerColor = PanelBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("60-SECOND PRE-FOCUS RITUAL", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Take 3 slow breaths • silence your phone • clear your desk • visualize today's result", color = Navy, fontSize = 13.sp)
                        Text("${ritualSeconds}s", fontSize = 32.sp, color = Navy, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { (60 - ritualSeconds).coerceIn(0, 60) / 60f }, modifier = Modifier.fillMaxWidth())
                        Text("When the ritual finishes, your focus session starts automatically.", color = Color.Gray, fontSize = 11.sp)
                        TextButton(onClick = { ritualReady = false; ritualSeconds = 0 }) { Text("Cancel ritual") }
                    }
                }
            } else {
                Button(
                    onClick = {
                        // Quick-start path (from Home plan / Continue): skip ritual, start now
                        if (fromQuickStart && !pomo) {
                            Pomodoro.endCycle(c)
                            FocusManager.start(c, minutes.coerceIn(1, 600), label.trim(), strict)
                            c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit()
                                .putString("last_focus_label", label.trim()).apply()
                            c.getSharedPreferences("timevora_focus_mission", Context.MODE_PRIVATE).edit()
                                .putString("mission", label.trim()).putString("expected", expectedOutcome.trim())
                                .putLong("created", System.currentTimeMillis()).apply()
                            AmbientPlayer.savedSound(c)?.let { if (AmbientPlayer.current != it) AmbientPlayer.play(c, it) }
                            fromQuickStart = false
                            version++; onChanged()
                        } else {
                            ritualReady = true; ritualSeconds = 60
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = label.isNotBlank()
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            fromQuickStart && !pomo -> "Start Focus now · $minutes min"
                            pomo -> "Start 60-sec ritual · Pomodoro $pomoWork/${Pomodoro.breakFor(pomoWork)} x $pomoRounds"
                            else -> "Start 60-sec ritual · $minutes min focus"
                        }
                    )
                }
                if (fromQuickStart && !pomo) {
                    TextButton(onClick = { fromQuickStart = false; ritualReady = true; ritualSeconds = 60 }) {
                        Text("Use 60-sec ritual instead", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            LaunchedEffect(ritualSeconds) {
                if (ritualSeconds == 0 && ritualReady) {
                    ritualReady = false
                    if (pomo) {
                        Pomodoro.begin(c, pomoWork, Pomodoro.breakFor(pomoWork), pomoRounds, label.trim(), strict)
                    } else {
                        Pomodoro.endCycle(c)
                        FocusManager.start(c, minutes.coerceIn(1, 600), label.trim(), strict)
                    }
                    c.getSharedPreferences("timevora_focus_mission", Context.MODE_PRIVATE).edit()
                        .putString("mission", label.trim()).putString("expected", expectedOutcome.trim()).putLong("created", System.currentTimeMillis()).apply()
                    // For Home “Continue where you left”
                    c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit()
                        .putString("last_focus_label", label.trim()).apply()
                    AmbientPlayer.savedSound(c)?.let { if (AmbientPlayer.current != it) AmbientPlayer.play(c, it) }
                    version++; onChanged()
                }
            }
            if (ritualSeconds == 1) ritualReady = true
            val plantSeconds = (if (pomo) pomoWork else minutes.coerceIn(1, 600)) * 60
            Text(
                if (plantSeconds >= Forest.MIN_TREE_SECONDS) "🌳 This session plants a ${treeKindFor(plantSeconds).label.lowercase()}. Leave other apps alone and it grows; use them and it withers."
                else "Sessions under 5 minutes do not grow a tree.",
                color = Color.Gray, fontSize = 12.sp
            )
        }

        AmbientSection(c, now, active && remaining > 0L)

        MiniStat("Focus time today", Fmt.hms(todaySec * 1000L), Modifier.fillMaxWidth())

        Card(colors = CardDefaults.cardColors(containerColor = PanelBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🧬 YOUR FOCUS DNA", fontWeight = FontWeight.Bold, color = Navy)
                val completed = dnaSessions.filter { it.actualSeconds >= it.plannedSeconds && it.plannedSeconds > 0 }
                val avgFocus = if (dnaSessions.isNotEmpty()) dnaSessions.sumOf { it.actualSeconds }.toLong() / dnaSessions.size else 0L
                val consistency = (dnaSessions.count { it.actualSeconds >= 15 * 60 }.toFloat() / maxOf(1, dnaSessions.size) * 100).toInt()
                val resistance = (dnaSessions.count { !it.withered }.toFloat() / maxOf(1, dnaSessions.size) * 100).toInt()
                Text("Deep Work Score", color = Blue, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { (consistency.coerceIn(0,100))/100f }, modifier = Modifier.fillMaxWidth())
                Text("Consistency: $consistency%   •   Distraction resistance: $resistance%", color = Navy, fontSize = 12.sp)
                Text("Average session: ${Fmt.hms(avgFocus * 1000L)}   •   Sessions: ${dnaSessions.size}", color = Navy, fontSize = 12.sp)
                Text("Completed sessions: ${completed.size}   •   Recovery resets: ${c.getSharedPreferences("timevora_focus_stats", Context.MODE_PRIVATE).getInt("recovery_count", 0)}", color = Color.Gray, fontSize = 12.sp)
                Text("Your Focus DNA updates as you complete more sessions. Metrics are based on recent local session records.", color = Color.Gray, fontSize = 11.sp)
            }
        }

        forest?.let { ForestSection(c, it, recent, now) }

        Text("RECENT SESSIONS", fontWeight = FontWeight.Bold, color = Navy)
        if (recent.isEmpty()) Text("Your finished focus sessions will appear here.", color = Color.Gray, fontSize = 13.sp)
        recent.forEach { s ->
            Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.label.ifBlank { "Focus session" }, fontWeight = FontWeight.SemiBold, color = Navy)
                    Text(
                        s.day + when {
                            s.actualSeconds < s.plannedSeconds -> "  •  stopped early 🥀"
                            s.withered -> "  •  withered, phone use 🥀"
                            s.plannedSeconds >= Forest.MIN_TREE_SECONDS -> "  •  tree grown 🌳"
                            else -> "  •  completed"
                        },
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
                Text(Fmt.hms(s.actualSeconds * 1000L), fontWeight = FontWeight.Bold, color = Blue)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    reviewMission?.let { mission ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Mission check-in") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Timer finished. Did you complete your mission?")
                if (mission.isNotBlank()) Text("Mission: $mission", fontWeight = FontWeight.SemiBold)
            } },
            confirmButton = { TextButton(onClick = {
                fp.edit().putString("mission_last_result", "completed").putLong("mission_last_reviewed", System.currentTimeMillis()).putBoolean("mission_review_pending", false).apply()
                reviewMission = null
            }) { Text("Completed ✓") } },
            dismissButton = { Row {
                TextButton(onClick = {
                    fp.edit().putString("mission_last_result", "partial").putLong("mission_last_reviewed", System.currentTimeMillis()).putBoolean("mission_review_pending", false).apply()
                    reviewMission = null
                }) { Text("Partly") }
                TextButton(onClick = {
                    fp.edit().putString("mission_last_result", "not_done").putLong("mission_last_reviewed", System.currentTimeMillis()).putBoolean("mission_review_pending", false).apply()
                    reviewMission = null
                }) { Text("Not yet") }
            } }
        )
    }
}
