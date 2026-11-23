package com.timevora.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.notifications.FocusSettings
import com.timevora.app.notifications.Pomodoro
import com.timevora.app.util.Fmt

/** The sentence a person must type to quit a Strict Mode session. It is friction on purpose: nothing gets blocked. */
internal const val STRICT_PHRASE = "I am giving up on my focus"

/** A label with a switch, used for the Pomodoro and Strict Mode toggles. */
@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Navy)
            Text(subtitle, fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Asks the person to type [STRICT_PHRASE] before [onConfirm] runs. */
@Composable
internal fun StrictQuitDialog(title: String, consequence: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ok = typed.trim().replace(Regex("\\s+"), " ").equals(STRICT_PHRASE, ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔒 $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(consequence, fontSize = 13.sp)
                Text("To quit, type this sentence exactly:", fontSize = 13.sp, color = Color.Gray)
                Text("\"$STRICT_PHRASE\"", fontWeight = FontWeight.Bold, color = Navy)
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it.take(60) },
                    singleLine = true, label = { Text("Type here") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = ok) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep focusing") } }
    )
}

/** Shown between Pomodoro rounds. The next round starts on its own when the countdown ends. */
@Composable
internal fun PomodoroBreakCard(c: Context, now: Long, onSkip: () -> Unit, onEnd: () -> Unit) {
    val end = Pomodoro.breakEnd(c)
    val total = Pomodoro.breakMin(c) * 60_000L
    val left = (end - now).coerceAtLeast(0L)
    val next = Pomodoro.round(c) + 1
    var confirmEnd by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("☕ BREAK  •  round ${Pomodoro.round(c)} of ${Pomodoro.rounds(c)} done", color = Color(0xFFB9C8E8), fontSize = 12.sp)
            Text(Fmt.clock(left), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { if (total > 0L) 1f - left.toFloat() / total else 1f }, modifier = Modifier.fillMaxWidth())
            Text("Stretch, drink water, rest your eyes. Round $next of ${Pomodoro.rounds(c)} starts automatically.", color = Color(0xFFD9E4FF), fontSize = 12.sp)
            if (Pomodoro.strict(c)) Text("🔒 Strict Mode is on for this Pomodoro", color = Color(0xFFD9E4FF), fontSize = 12.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip break") }
        OutlinedButton(onClick = { if (Pomodoro.strict(c)) { confirmEnd = true } else { onEnd() } }, modifier = Modifier.weight(1f)) { Text("End Pomodoro") }
    }
    if (confirmEnd) {
        StrictQuitDialog(
            title = "Strict Mode is on",
            consequence = "Ending now cancels the remaining rounds. Your finished rounds and trees are kept.",
            confirmLabel = "End Pomodoro",
            onConfirm = { confirmEnd = false; onEnd() },
            onDismiss = { confirmEnd = false }
        )
    }
}

/** Setup controls (Pomodoro switch, preset, rounds) shown on the Focus screen before a session starts. */
@Composable
internal fun PomodoroSetup(c: Context, pomo: Boolean, onPomo: (Boolean) -> Unit, work: Int, onWork: (Int) -> Unit, rounds: Int, onRounds: (Int) -> Unit) {
    ToggleRow("🍅 Pomodoro auto-cycle", "Work and break rounds run one after another by themselves", pomo) {
        onPomo(it); FocusSettings.setPomodoro(c, it)
    }
    if (pomo) {
        Text("Work / break", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 13.sp)
        ChipRow(Pomodoro.PRESETS.map { it.first to "${it.first} / ${it.second} min" }, work) { onWork(it); FocusSettings.setWorkMinutes(c, it) }
        Text("Rounds", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 13.sp)
        ChipRow(listOf(2, 3, 4, 6, 8).map { it to "$it rounds" }, rounds) { onRounds(it); FocusSettings.setRounds(c, it) }
        val brk = Pomodoro.breakFor(work)
        Text(
            "🍅 $rounds rounds of $work min with $brk min breaks = about ${Fmt.hms(Pomodoro.totalMinutes(work, brk, rounds) * 60_000L)}. Every round plants its own tree.",
            fontSize = 12.sp, color = Color.Gray
        )
    }
}
