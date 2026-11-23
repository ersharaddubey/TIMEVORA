package com.timevora.app

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.ExamEvent
import com.timevora.app.data.ExamSubject
import com.timevora.app.data.RewardEngine
import com.timevora.app.data.StudyLog
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.todayKey
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val examDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val examMonthFormat = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
private val subjectColors: List<Color> get() = listOf(AccentPink, AccentOrange, Color(0xFF7E57C2), Color(0xFF26A69A), Color(0xFF42A5F5), Color(0xFFFFB300))
private val DangerRed = Color(0xFFDC2626)

private fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
private fun daysUntil(date: String): Int = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(date)).toInt() }.getOrDefault(0)
private fun countdownLabel(days: Int): String = if (days <= 0) "TODAY" else if (days == 1) "1 DAY" else "$days DAYS"
private fun urgencyColor(days: Int): Color = if (days <= 3) DangerRed else if (days <= 7) AccentOrange else AccentPink

/** The Study tab: same content as [ExamModeSection], but scrollable and with proper screen padding. */
@Composable
internal fun ExamModeScreen(context: Context, refresh: Int, onChanged: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
        ExamModeSection(context, refresh, onChanged)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.styled(Color.White.copy(alpha = 0.10f), styleShape(14), lite = true).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Color(0xFFB9C8E8), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        action()
    }
}

@Composable
private fun AddPill(text: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    if (filled) {
        Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), contentPadding = pad, colors = ButtonDefaults.buttonColors(containerColor = AccentPink)) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), contentPadding = pad, colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPink)) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun ExamModeSection(context: Context, refresh: Int, onChanged: () -> Unit) {
    val dao = remember { timevoraDatabase.get(context).usageDao() }
    val scope = rememberCoroutineScope()
    var subjects by remember { mutableStateOf(emptyList<ExamSubject>()) }
    var exams by remember { mutableStateOf(emptyList<ExamEvent>()) }
    var studyTotals by remember { mutableStateOf(emptyMap<Long, Int>()) }
    var showSubjectDialog by remember { mutableStateOf(false) }
    var showExamDialog by remember { mutableStateOf(false) }
    var editingSubject by remember { mutableStateOf<ExamSubject?>(null) }
    var subjectToDelete by remember { mutableStateOf<ExamSubject?>(null) }
    var examToDelete by remember { mutableStateOf<ExamEvent?>(null) }
    var recentLogs by remember { mutableStateOf(emptyList<StudyLog>()) }
    var subjectName by remember { mutableStateOf("") }
    var targetHours by remember { mutableStateOf("10") }
    var examTitle by remember { mutableStateOf("") }
    var examDate by remember { mutableStateOf(LocalDate.now().plusDays(7).toString()) }
    var examNotes by remember { mutableStateOf("") }
    var selectedSubject by remember { mutableStateOf<Long?>(null) }
    var showSubjectMenu by remember { mutableStateOf(false) }
    var menuSubject by remember { mutableStateOf<Long?>(null) }
    var studySubject by remember { mutableStateOf<ExamSubject?>(null) }
    var studyMinutes by remember { mutableStateOf("25") }
    var studyNote by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface

    LaunchedEffect(refresh, reload) {
        subjects = dao.examSubjects()
        exams = dao.examEvents()
            .filter { runCatching { !LocalDate.parse(it.examDate).isBefore(LocalDate.now()) }.getOrDefault(false) }
            .sortedBy { it.examDate }
        studyTotals = subjects.associate { it.id to dao.subjectStudyMinutes(it.id) }
        recentLogs = dao.recentStudyLogs(8)
        if (selectedSubject == null && subjects.isNotEmpty()) selectedSubject = subjects.first().id
    }

    val totalMin = studyTotals.values.sum()
    val nextExam = exams.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---------- Hero ----------
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(24), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GOALS & DEADLINES", color = Color(0xFFFFB4C2), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                        Text("Projects & exams", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                        Text("Subjects, projects, deadlines & time logged — for study or work", color = Color(0xFFD9E4FF), fontSize = 12.sp)
                    }
                    Box(Modifier.size(46.dp).background(AccentPink, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.School, contentDescription = null, tint = Color.White)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroStat("TOTAL STUDY", fmtMin(totalMin), Modifier.weight(1f))
                    HeroStat("SUBJECTS", "${subjects.size}", Modifier.weight(1f))
                    HeroStat("UPCOMING", "${exams.size}", Modifier.weight(1f))
                }
                if (nextExam != null) {
                    val nd = daysUntil(nextExam.examDate)
                    Row(Modifier.fillMaxWidth().styled(Color.White.copy(alpha = 0.10f), styleShape(14), lite = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, contentDescription = null, tint = Color(0xFFFFB4C2))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NEXT DEADLINE", color = Color(0xFFB9C8E8), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            Text(nextExam.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(countdownLabel(nd), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                            modifier = Modifier.background(urgencyColor(nd), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
            }
        }

        // ---------- Subjects ----------
        SectionHeader("My subjects", "Set targets and track your preparation") {
            AddPill("Add", filled = true) { editingSubject = null; subjectName = ""; targetHours = "10"; showSubjectDialog = true }
        }
        if (subjects.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📚", fontSize = 34.sp)
                    Text("Start your exam preparation", color = onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Add your first subject, choose a study target and log each session here.", color = Color.Gray, fontSize = 12.sp, lineHeight = 17.sp)
                    Button(onClick = { editingSubject = null; subjectName = ""; targetHours = "10"; showSubjectDialog = true },
                        shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = AccentPink)) { Text("Create first subject", fontWeight = FontWeight.Bold) }
                }
            }
        }
        subjects.forEachIndexed { idx, subject ->
            val minutes = studyTotals[subject.id] ?: 0
            val progress = (minutes.toFloat() / subject.targetMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
            val tint = subjectColors[idx % subjectColors.size]
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).styled(tint.copy(alpha = 0.16f), styleShape(14), lite = true), contentAlignment = Alignment.Center) {
                            Text(subject.name.trim().take(1).uppercase(), color = tint, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(subject.name, color = onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${fmtMin(minutes)} of ${fmtMin(subject.targetMinutes)}", color = Color.Gray, fontSize = 12.sp)
                        }
                        Box {
                            IconButton(onClick = { menuSubject = subject.id }) { Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.Gray) }
                            DropdownMenu(expanded = menuSubject == subject.id, onDismissRequest = { menuSubject = null }) {
                                DropdownMenuItem(
                                    text = { Text("Edit subject") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        menuSubject = null
                                        editingSubject = subject; subjectName = subject.name
                                        targetHours = (subject.targetMinutes / 60).coerceAtLeast(1).toString(); showSubjectDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete subject", color = DangerRed) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = DangerRed) },
                                    onClick = { menuSubject = null; subjectToDelete = subject }
                                )
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                        color = tint, trackColor = tint.copy(alpha = 0.16f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${(progress * 100).toInt()}% complete", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(if (minutes >= subject.targetMinutes) "Target reached 🎉" else "${fmtMin(subject.targetMinutes - minutes)} left", color = Color.Gray, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { studySubject = subject; studyMinutes = "25"; studyNote = "" },
                            shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tint)
                        ) { Icon(Icons.Default.Timer, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Log study", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
            }
        }

        // ---------- Exam countdown ----------
        SectionHeader("Exam countdown", "Keep important dates in sight") {
            AddPill("Add exam", filled = false, enabled = subjects.isNotEmpty()) {
                examTitle = ""; examNotes = ""; selectedSubject = subjects.firstOrNull()?.id; examDate = LocalDate.now().plusDays(7).toString(); showExamDialog = true
            }
        }
        if (exams.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (subjects.isEmpty()) "Add a subject first, then add its exam date to start the countdown." else "No upcoming exams. Add your next exam date to start the countdown.",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(16.dp)
                )
            }
        }
        exams.forEach { exam ->
            val subject = subjects.firstOrNull { it.id == exam.subjectId }
            val date = runCatching { LocalDate.parse(exam.examDate) }.getOrNull()
            val days = daysUntil(exam.examDate)
            val tint = urgencyColor(days)
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.size(56.dp).styled(tint.copy(alpha = 0.14f), styleShape(16), lite = true),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(date?.dayOfMonth?.toString() ?: "–", color = tint, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text(date?.format(examMonthFormat)?.uppercase() ?: "", color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(exam.title, color = onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${subject?.name ?: "Subject"}  ·  ${date?.format(examDateFormat) ?: exam.examDate}", color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (exam.notes.isNotBlank()) Text(exam.notes, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(countdownLabel(days), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
                            modifier = Modifier.background(tint, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
                        IconButton(onClick = { examToDelete = exam }) { Icon(Icons.Default.Delete, contentDescription = "Delete exam", tint = Color(0xFFDC6262)) }
                    }
                }
            }
        }

        // ---------- Recent sessions ----------
        SectionHeader("Recent study sessions", "Your latest logged time")
        if (recentLogs.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Text("Your saved study sessions will appear here.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    recentLogs.forEachIndexed { i, log ->
                        val subject = subjects.firstOrNull { it.id == log.subjectId }
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Color.Gray.copy(alpha = 0.2f))
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(subject?.name ?: "Study session", color = onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${log.day}${if (log.note.isNotBlank()) "  ·  ${log.note}" else ""}", color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(fmtMin(log.minutes), color = AccentPink, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                                modifier = Modifier.background(AccentPink.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
                            IconButton(onClick = { scope.launch { dao.deleteStudyLog(log.id); reload++; onChanged() } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete study log", tint = Color(0xFFDC6262))
                            }
                        }
                    }
                }
            }
        }
        Text("Study data stays on this device. Sessions of 15 minutes or more also earn 10 XP and count toward your activity streak.", color = Color.Gray, fontSize = 11.sp, lineHeight = 15.sp)
    }

    // ---------- Dialogs ----------
    if (subjectToDelete != null) AlertDialog(
        onDismissRequest = { subjectToDelete = null },
        shape = styleShape(24),
        title = { Text("Delete ${subjectToDelete?.name}?", fontWeight = FontWeight.Bold) },
        text = { Text("This also deletes its exam dates and study logs. This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            val target = subjectToDelete!!
            scope.launch { dao.deleteExamsForSubject(target.id); dao.deleteStudyLogsForSubject(target.id); dao.deleteExamSubject(target.id); subjectToDelete = null; reload++; onChanged() }
        }) { Text("Delete", color = DangerRed, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { subjectToDelete = null }) { Text("Cancel") } }
    )
    if (examToDelete != null) AlertDialog(
        onDismissRequest = { examToDelete = null },
        shape = styleShape(24),
        title = { Text("Delete exam?", fontWeight = FontWeight.Bold) },
        text = { Text("${examToDelete?.title} will be removed.") },
        confirmButton = { TextButton(onClick = { val target = examToDelete!!; scope.launch { dao.deleteExamEvent(target.id); examToDelete = null; reload++; onChanged() } }) { Text("Delete", color = DangerRed, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { examToDelete = null }) { Text("Cancel") } }
    )

    if (showSubjectDialog) AlertDialog(
        onDismissRequest = { showSubjectDialog = false },
        shape = styleShape(24),
        title = { Text(if (editingSubject == null) "Add study subject" else "Edit study subject", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(subjectName, { subjectName = it }, label = { Text("Subject name") }, singleLine = true, shape = styleShape(14), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetHours, { targetHours = it.filter(Char::isDigit).take(3) }, label = { Text("Study target (hours)") }, singleLine = true, shape = styleShape(14), modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = subjectName.isNotBlank() && (targetHours.toIntOrNull() ?: 0) > 0, onClick = {
            scope.launch {
                val hours = (targetHours.toIntOrNull() ?: 10).coerceIn(1, 999)
                val current = editingSubject
                if (current == null) dao.saveExamSubject(ExamSubject(name = subjectName.trim(), targetMinutes = hours * 60))
                else dao.updateExamSubject(current.id, subjectName.trim(), hours * 60)
                subjectName = ""; editingSubject = null; showSubjectDialog = false; reload++; onChanged()
            }
        }) { Text("Save subject", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { showSubjectDialog = false; editingSubject = null }) { Text("Cancel") } }
    )

    if (showExamDialog) AlertDialog(
        onDismissRequest = { showExamDialog = false },
        shape = styleShape(24),
        title = { Text("Add exam", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(examTitle, { examTitle = it }, label = { Text("Exam name") }, singleLine = true, shape = styleShape(14), modifier = Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showSubjectMenu = true }, modifier = Modifier.fillMaxWidth(), shape = styleShape(14)) {
                    Text(subjects.firstOrNull { it.id == selectedSubject }?.name ?: "Choose subject", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = showSubjectMenu, onDismissRequest = { showSubjectMenu = false }) {
                    subjects.forEach { subject -> DropdownMenuItem(text = { Text(subject.name) }, onClick = { selectedSubject = subject.id; showSubjectMenu = false }) }
                }
            }
            OutlinedButton(onClick = {
                val initialDate = runCatching { LocalDate.parse(examDate) }.getOrDefault(LocalDate.now().plusDays(7))
                DatePickerDialog(context, { _, year, month, dayOfMonth ->
                    examDate = LocalDate.of(year, month + 1, dayOfMonth).toString()
                }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth(), shape = styleShape(14)) {
                Text("Exam date: ${runCatching { LocalDate.parse(examDate).format(examDateFormat) }.getOrDefault(examDate)}")
            }
            OutlinedTextField(examNotes, { examNotes = it }, label = { Text("Notes (optional)") }, minLines = 2, shape = styleShape(14), modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = examTitle.isNotBlank() && selectedSubject != null && runCatching { LocalDate.parse(examDate); true }.getOrDefault(false), onClick = {
            scope.launch { dao.saveExamEvent(ExamEvent(title = examTitle.trim(), subjectId = selectedSubject!!, examDate = examDate, notes = examNotes.trim())); showExamDialog = false; reload++; onChanged() }
        }) { Text("Save exam", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { showExamDialog = false }) { Text("Cancel") } }
    )

    studySubject?.let { subject -> AlertDialog(
        onDismissRequest = { studySubject = null },
        shape = styleShape(24),
        title = { Text("Log ${subject.name} study", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(studyMinutes, { studyMinutes = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes studied") }, singleLine = true, shape = styleShape(14), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(studyNote, { studyNote = it }, label = { Text("What did you study? (optional)") }, minLines = 2, shape = styleShape(14), modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = (studyMinutes.toIntOrNull() ?: 0) in 1..1440, onClick = {
            scope.launch {
                val minutes = studyMinutes.toInt()
                val logId = dao.addStudyLog(StudyLog(subjectId = subject.id, day = todayKey(), minutes = minutes, note = studyNote.trim()))
                if (minutes >= 15) RewardEngine.award(context, "exam-study-$logId", todayKey(), 10, "Study session: ${subject.name}")
                studySubject = null; reload++; onChanged()
            }
        }) { Text("Save study log", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { studySubject = null }) { Text("Cancel") } }
    ) }
}
