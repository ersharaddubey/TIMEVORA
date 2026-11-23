package com.timevora.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.AppUsageTotal
import com.timevora.app.data.Live
import com.timevora.app.data.PlanRow
import com.timevora.app.data.PlannerDone
import com.timevora.app.data.PlannerItem
import com.timevora.app.data.RewardEngine
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.loadPlan
import com.timevora.app.notifications.PlannerAlarms
import com.timevora.app.util.Fmt
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DiaryDayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

private fun kindLabel(kind: String) = when (kind) { "meeting" -> "Meeting"; "routine" -> "Routine"; else -> "Task" }
private fun repeatLabel(mode: String) = when (mode) { "daily" -> "Every day"; "weekdays" -> "Mon–Fri"; "weekly" -> "Every week"; else -> "" }
private fun remindLabel(min: Int) = when { min < 0 -> ""; min == 0 -> "Reminder at time"; min == 60 -> "Reminder 1 hour before"; else -> "Reminder $min min before" }

/** Diary: your own plan for each day (tasks, meetings, routines with reminders) plus a personal note. */
@Composable
internal fun DiaryScreen(c: Context, refresh: Int, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val live = Live.current(c)
    var day by remember { mutableStateOf(LocalDate.now()) }
    var plan by remember { mutableStateOf(emptyList<PlanRow>()) }
    var rows by remember { mutableStateOf(emptyList<AppUsageTotal>()) }
    var total by remember { mutableLongStateOf(0L) }
    var focusSec by remember { mutableLongStateOf(0L) }
    var reload by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlannerItem?>(null) }
    val notes = remember { c.getSharedPreferences("timevora_notes", Context.MODE_PRIVATE) }
    var note by remember(day) { mutableStateOf(notes.getString(day.toString(), "") ?: "") }
    var savedNote by remember(day) { mutableStateOf(notes.getString(day.toString(), "") ?: "") }
    var showDeleteNoteConfirm by remember { mutableStateOf(false) }
    val isToday = day == LocalDate.now()

    LaunchedEffect(day, refresh, reload) {
        val dao = timevoraDatabase.get(c).usageDao()
        plan = loadPlan(dao, day)
        rows = dao.daily(day.toString())
        total = dao.total(day.toString())
        focusSec = dao.focusSeconds(day.toString())
    }

    val totalNow = total + (if (isToday) Live.todayMillis(live, now) else 0L)
    val rowsNow = if (isToday) Live.withLive(rows, live, now) else rows

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Planner", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Work, life, projects — your day plan for anyone.", color = Color.Gray, fontSize = 13.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { day = day.minusDays(1) }) { Text("‹") }
            Text(
                (if (isToday) "Today · " else if (day == LocalDate.now().plusDays(1)) "Tomorrow · " else "") + day.format(DiaryDayFmt),
                Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.Gray
            )
            OutlinedButton(onClick = { day = day.plusDays(1) }) { Text("›") }
        }
        if (!isToday) TextButton(onClick = { day = LocalDate.now() }) { Text("Back to today") }

        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SCREEN TIME", color = Color(0xFFB9C8E8), fontSize = 11.sp)
                Text(Fmt.hms(totalNow), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val doneCount = plan.count { it.done }
                Text("Focus ${Fmt.hms(focusSec * 1000L)}  •  Plan done $doneCount/${plan.size}", color = Color(0xFFD9E4FF), fontSize = 12.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY’S PLAN", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Default.Add, null); Text("Add item") }
        }
        if (plan.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SoftPink),
                shape = styleShape(16),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nothing planned for this day", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Add work tasks, meetings, errands or habits. Or capture everything at once with Smart Task Dump from Home.", color = Navy.copy(alpha = 0.7f), fontSize = 13.sp)
                    Button(onClick = { editing = null; showEditor = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add task or meeting")
                    }
                }
            }
        }
        plan.forEach { row ->
            PlanRowView(
                row,
                onToggle = { checked ->
                    scope.launch {
                        val dao = timevoraDatabase.get(c).usageDao()
                        if (checked) {
                            dao.markDone(PlannerDone(row.item.id, day.toString()))
                            RewardEngine.award(c, "planner-${row.item.id}-${day}", day.toString(), 10, "Completed: ${row.item.title}")
                        } else {
                            dao.unmarkDone(row.item.id, day.toString())
                        }
                        reload++
                        onChanged()
                    }
                },
                onEdit = { editing = row.item; showEditor = true }
            )
        }
        if (plan.any { it.item.remindBefore >= 0 } && !timevoraPerms.notifications(c)) {
            Text("Notifications are off, so reminders cannot appear. Turn them on from Settings > Permissions setup.", color = Color(0xFF9A3412), fontSize = 12.sp)
        }

        Text("DIARY NOTE", fontWeight = FontWeight.Bold, color = Navy)
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Write about your day") },
            placeholder = { Text("Your thoughts, memories or daily reflection…") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Button(
                onClick = {
                    notes.edit().putString(day.toString(), note.trim()).apply()
                    note = note.trim()
                    savedNote = note
                },
                enabled = note != savedNote,
                modifier = Modifier.weight(1f)
            ) { Text("Save note") }
            OutlinedButton(
                onClick = { showDeleteNoteConfirm = true },
                enabled = savedNote.isNotBlank() || note.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Delete note") }
        }
        Text(
            when { note != savedNote -> "Unsaved changes · Save to keep this note on your phone"; savedNote.isNotBlank() -> "Saved on this phone"; else -> "This note is stored only on this device" },
            fontSize = 11.sp, color = if (note != savedNote) Color(0xFFB45309) else Color.Gray
        )

        Text("APP USAGE THIS DAY", fontWeight = FontWeight.Bold, color = Navy)
        if (rowsNow.isEmpty()) Text("No app usage recorded for this day.", color = Color.Gray, fontSize = 13.sp)
        rowsNow.forEach { UsageRow(it) }
    }

    if (showDeleteNoteConfirm) AlertDialog(
        onDismissRequest = { showDeleteNoteConfirm = false },
        title = { Text("Delete diary note?") },
        text = { Text("This will permanently clear the note for ${day.format(DiaryDayFmt)}.") },
        confirmButton = { TextButton(onClick = {
            notes.edit().remove(day.toString()).apply()
            note = ""
            savedNote = ""
            showDeleteNoteConfirm = false
        }) { Text("Delete", color = Color(0xFFDC2626)) } },
        dismissButton = { TextButton(onClick = { showDeleteNoteConfirm = false }) { Text("Cancel") } }
    )

    if (showEditor) {
        PlannerEditor(
            c = c,
            initial = editing,
            day = day,
            onDismiss = { showEditor = false },
            onSave = { item ->
                showEditor = false
                scope.launch {
                    val dao = timevoraDatabase.get(c).usageDao()
                    val newId = dao.savePlanner(item)
                    val saved = if (item.id == 0L) item.copy(id = newId) else item
                    PlannerAlarms.schedule(c, saved)
                    reload++
                    onChanged()
                }
            },
            onDelete = { item ->
                showEditor = false
                scope.launch {
                    val dao = timevoraDatabase.get(c).usageDao()
                    dao.deletePlanner(item.id)
                    dao.deleteDoneFor(item.id)
                    PlannerAlarms.cancel(c, item.id)
                    reload++
                    onChanged()
                }
            }
        )
    }
}

@Composable
private fun PlanRowView(row: PlanRow, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val item = row.item
    Row(
        Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).clickable { onEdit() }.padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = row.done, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                item.title, fontWeight = FontWeight.SemiBold,
                color = if (row.done) Color.Gray else Navy,
                textDecoration = if (row.done) TextDecoration.LineThrough else null
            )
            val meta = listOf(kindLabel(item.kind), Fmt.timeLabel(item.minuteOfDay), repeatLabel(item.repeatMode), remindLabel(item.remindBefore))
                .filter { it.isNotEmpty() }.joinToString("  •  ")
            Text(meta, fontSize = 11.sp, color = Color.Gray)
            if (item.notes.isNotBlank()) Text(item.notes, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
        }
    }
}

@Composable
private fun PlannerEditor(
    c: Context,
    initial: PlannerItem?,
    day: LocalDate,
    onDismiss: () -> Unit,
    onSave: (PlannerItem) -> Unit,
    onDelete: (PlannerItem) -> Unit
) {
    var kind by remember { mutableStateOf(initial?.kind ?: "task") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var details by remember { mutableStateOf(initial?.notes ?: "") }
    var date by remember { mutableStateOf(initial?.let { LocalDate.parse(it.startDay) } ?: day) }
    var minute by remember { mutableIntStateOf(initial?.minuteOfDay ?: -1) }
    var repeat by remember { mutableStateOf(initial?.repeatMode ?: "none") }
    var remind by remember { mutableIntStateOf(initial?.remindBefore ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add to your plan" else "Edit plan item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipRow(listOf("task" to "Task", "meeting" to "Meeting", "routine" to "Routine"), kind) { kind = it }
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = details, onValueChange = { details = it.take(300) }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(c, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Date: " + date.format(DiaryDayFmt)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val h = if (minute >= 0) minute / 60 else 9
                            val m = if (minute >= 0) minute % 60 else 0
                            TimePickerDialog(c, { _, hh, mm -> if (minute < 0) remind = 0; minute = hh * 60 + mm }, h, m, false).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Time: " + Fmt.timeLabel(minute)) }
                    if (minute >= 0) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { minute = -1 }) { Text("Clear") }
                    }
                }
                if (minute >= 0) {
                    Text("Remind me", fontWeight = FontWeight.SemiBold)
                    ChipRow(listOf(-1 to "No reminder", 0 to "At time", 5 to "5 min before", 10 to "10 min before", 30 to "30 min before", 60 to "1 hour before"), remind) { remind = it }
                }
                Text("Repeat", fontWeight = FontWeight.SemiBold)
                ChipRow(listOf("none" to "Once", "daily" to "Every day", "weekdays" to "Mon–Fri", "weekly" to "Every week"), repeat) { repeat = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    onSave(
                        PlannerItem(
                            id = initial?.id ?: 0L,
                            title = title.trim(),
                            notes = details.trim(),
                            kind = kind,
                            startDay = date.toString(),
                            minuteOfDay = minute,
                            repeatMode = repeat,
                            remindBefore = if (minute >= 0) remind else -1,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
