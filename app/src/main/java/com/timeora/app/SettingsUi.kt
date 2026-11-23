package com.timevora.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.timevora.app.data.AppLimit
import com.timevora.app.data.KnownApp
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.notifications.AlarmHelper
import com.timevora.app.notifications.ReportScheduler
import com.timevora.app.tracking.UsageTrackingService
import com.timevora.app.util.Fmt
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent

@Composable
internal fun SettingsScreen(
    c: Context,
    refresh: Int,
    scrollTo: String? = null,
    floating: Boolean,
    setFloating: (Boolean) -> Unit,
    overlay: Boolean,
    grantOverlay: () -> Unit,
    onClear: () -> Unit,
    onOpenNotifications: () -> Unit,
    onExport: () -> Unit,
    onChanged: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var sleepY by remember { mutableIntStateOf(-1) }
    var pauseY by remember { mutableIntStateOf(-1) }
    LaunchedEffect(scrollTo, sleepY, pauseY) {
        val y = when (scrollTo) { "sleep" -> sleepY; "pause" -> pauseY; else -> -1 }
        if (y >= 0) scroll.scrollTo(y)
    }
    val prefs = remember { c.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    var hour by remember { mutableIntStateOf(prefs.getInt("report_hour", 8)) }
    var minuteOfReport by remember { mutableIntStateOf(prefs.getInt("report_minute", 0)) }
    var alpha by remember { mutableFloatStateOf(prefs.getFloat("bubble_alpha", 0.86f)) }
    var scale by remember { mutableFloatStateOf(prefs.getFloat("bubble_scale", 1f)) }
    var side by remember { mutableStateOf(prefs.getString("bubble_side", "right") ?: "right") }
    var countSelf by remember { mutableStateOf(prefs.getBoolean("count_self", true)) }
    var bedtime by remember { mutableIntStateOf(prefs.getInt("sleep_bedtime", 22 * 60)) }
    var wakeTime by remember { mutableIntStateOf(prefs.getInt("sleep_wake", 7 * 60)) }
    var winddownMinutes by remember { mutableIntStateOf(prefs.getInt("sleep_winddown_minutes", 30)) }
    var openingPause by remember { mutableStateOf(prefs.getBoolean("opening_pause", false)) }
    var pauseSeconds by remember { mutableIntStateOf(prefs.getInt("opening_pause_seconds", 5)) }
    var pauseCooldown by remember { mutableIntStateOf(prefs.getInt("opening_pause_cooldown_minutes", 1)) }
    var limits by remember { mutableStateOf(emptyList<AppLimit>()) }
    var knownApps by remember { mutableStateOf(emptyList<KnownApp>()) }
    var showAdd by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<KnownApp?>(null) }
    var minutes by remember { mutableStateOf("30") }
    val exactOk = remember(refresh) { AlarmHelper.canExact(c) }

    LaunchedEffect(Unit) {
        val dao = timevoraDatabase.get(c).usageDao()
        limits = dao.limits()
        knownApps = dao.knownApps()
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Permissions setup")
        }

        SettingSwitch("Floating mini timer", "Show the stopwatch over other apps", floating, setFloating)
        if (floating && !overlay) OutlinedButton(onClick = grantOverlay) { Text("Grant overlay permission") }
        Text("Bubble size · ${(scale * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
        Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.75f..1.5f, onValueChangeFinished = {
            prefs.edit().putFloat("bubble_scale", scale).apply()
            c.startService(Intent(c, UsageTrackingService::class.java).setAction("REFRESH_BUBBLE"))
        })
        Text("Bubble transparency · ${(alpha * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
        Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.35f..1f, onValueChangeFinished = {
            prefs.edit().putFloat("bubble_alpha", alpha).apply()
            c.startService(Intent(c, UsageTrackingService::class.java).setAction("REFRESH_BUBBLE"))
        })
        Text("Default bubble side", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side == "left", onClick = { side = "left"; prefs.edit().putString("bubble_side", side).apply() }, label = { Text("Left") })
            FilterChip(selected = side == "right", onClick = { side = "right"; prefs.edit().putString("bubble_side", side).apply() }, label = { Text("Right") })
        }

        HorizontalDivider()
        SettingSwitch("Count time inside timevora", "Include the time you spend in this app in your totals, so live numbers tick while you look at them", countSelf) { countSelf = it; prefs.edit().putBoolean("count_self", it).apply() }

        Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NOTIFICATIONS & REMINDERS", fontWeight = FontWeight.ExtraBold, color = Navy)
                Text("Manage task alerts, focus nudges, break reminders, screen-time goals, daily reports and sleep reminders together.", fontSize = 12.sp, color = Color.Gray)
                Button(onClick = onOpenNotifications, modifier = Modifier.fillMaxWidth()) { Text("Open Notification Centre") }
            }
        }

        ServerCard(c, onChanged)

        HorizontalDivider()
        Text("Sleep schedule", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.onGloballyPositioned { sleepY = it.positionInParent().y.toInt() })
        Text("Turn the sleep routine on or off in Notification Centre. Set its times here.", fontSize = 11.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bedtime reminder", fontWeight = FontWeight.SemiBold)
                Text(Fmt.timeLabel(bedtime), color = Color.Gray, fontSize = 13.sp)
            }
            OutlinedButton(onClick = {
                TimePickerDialog(c, { _, h, m ->
                    bedtime = h * 60 + m
                    prefs.edit().putInt("sleep_bedtime", bedtime).apply()
                    com.timevora.app.notifications.SleepRoutine.schedule(c)
                }, bedtime / 60, bedtime % 60, false).show()
            }) { Text("Set") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wake-up reminder", fontWeight = FontWeight.SemiBold)
                Text(Fmt.timeLabel(wakeTime), color = Color.Gray, fontSize = 13.sp)
            }
            OutlinedButton(onClick = {
                TimePickerDialog(c, { _, h, m ->
                    wakeTime = h * 60 + m
                    prefs.edit().putInt("sleep_wake", wakeTime).apply()
                    com.timevora.app.notifications.SleepRoutine.schedule(c)
                }, wakeTime / 60, wakeTime % 60, false).show()
            }) { Text("Set") }
        }
        Text("Wind-down lead time", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "60 min"), winddownMinutes) {
            winddownMinutes = it
            prefs.edit().putInt("sleep_winddown_minutes", it).apply()
            com.timevora.app.notifications.SleepRoutine.schedule(c)
        }
        Text("A separate reminder will arrive before bedtime when the sleep routine is enabled.", color = Color.Gray, fontSize = 10.sp)

        HorizontalDivider()
        Text("App opening pause", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.onGloballyPositioned { pauseY = it.positionInParent().y.toInt() })
        SettingSwitch("Pause before using an app", "Show a short mindful overlay after another app comes to the foreground (requires overlay permission). Android does not allow timevora to hard-block app launches.", openingPause) {
            openingPause = it
            prefs.edit().putBoolean("opening_pause", it).apply()
            c.startService(Intent(c, UsageTrackingService::class.java).setAction("REFRESH_BUBBLE"))
        }
        Text("This is an interruption, not an app blocker: the other app may already have opened before the overlay appears.", color = Color.Gray, fontSize = 10.sp)
        Text("Pause duration", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(3 to "3 sec", 5 to "5 sec", 10 to "10 sec", 15 to "15 sec"), pauseSeconds) {
            pauseSeconds = it
            prefs.edit().putInt("opening_pause_seconds", it).apply()
        }
        Text("Repeat pause cooldown for each app", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(0 to "Every time", 1 to "1 min", 5 to "5 min", 15 to "15 min"), pauseCooldown) {
            pauseCooldown = it
            prefs.edit().putInt("opening_pause_cooldown_minutes", it).apply()
        }
        if (openingPause && !overlay) OutlinedButton(onClick = grantOverlay) { Text("Grant overlay permission") }

        if (Build.VERSION.SDK_INT >= 31) {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("On-time reminders", fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(
                            if (exactOk) "Allowed. Reminders arrive exactly on time." else "Not allowed. Reminders can arrive a few minutes late. Allow it for exact meeting alerts.",
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }
                    if (exactOk) Text("✓ On", color = Color(0xFF176B42), fontWeight = FontWeight.Bold)
                    else OutlinedButton(onClick = {
                        try { c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.packageName))) } catch (_: Exception) { }
                    }) { Text("Allow") }
                }
            }
        }

        HorizontalDivider()
        Text("Morning report delivery time", fontWeight = FontWeight.Bold)
        Text("Enable or disable the morning report in Notification Centre.", color = Color.Gray, fontSize = 11.sp)
        Text("Report time", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.timeLabel(hour * 60 + minuteOfReport), fontSize = 22.sp, color = Navy)
            Spacer(Modifier.width(16.dp))
            Button(onClick = {
                TimePickerDialog(c, { _, h, m ->
                    hour = h; minuteOfReport = m
                    prefs.edit().putInt("report_hour", h).putInt("report_minute", m).apply()
                    ReportScheduler.schedule(c)
                }, hour, minuteOfReport, false).show()
            }) { Text("Change time") }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("App time limits", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null); Text("Add app") }
        }
        if (limits.isEmpty()) Text("No app limits configured. Add an app to receive 50%, 80% and 100% usage alerts.", fontSize = 12.sp, color = Color.Gray)
        limits.forEach { limit ->
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(limit.appLabel, fontWeight = FontWeight.SemiBold)
                            Text("${limit.limitMinutes} min daily limit", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = limit.enabled, onCheckedChange = { v ->
                            scope.launch {
                                val dao = timevoraDatabase.get(c).usageDao()
                                dao.saveLimit(limit.copy(enabled = v)); limits = dao.limits()
                            }
                        })
                        IconButton(onClick = {
                            scope.launch {
                                val dao = timevoraDatabase.get(c).usageDao()
                                dao.deleteLimit(limit.packageName); limits = dao.limits()
                            }
                        }) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = limit.alert50, onClick = { scope.launch { val dao = timevoraDatabase.get(c).usageDao(); dao.saveLimit(limit.copy(alert50 = !limit.alert50)); limits = dao.limits() } }, label = { Text("50%") })
                        FilterChip(selected = limit.alert80, onClick = { scope.launch { val dao = timevoraDatabase.get(c).usageDao(); dao.saveLimit(limit.copy(alert80 = !limit.alert80)); limits = dao.limits() } }, label = { Text("80%") })
                        FilterChip(selected = limit.alert100, onClick = { scope.launch { val dao = timevoraDatabase.get(c).usageDao(); dao.saveLimit(limit.copy(alert100 = !limit.alert100)); limits = dao.limits() } }, label = { Text("100%") })
                    }
                }
            }
        }

        CategoryLimitsSection(c, onChanged)

        HorizontalDivider()
        Text("Data & privacy", fontWeight = FontWeight.Bold, color = Navy)
        Text("Usage history, diary, focus sessions and app limits are stored locally on this device. timevora does not read messages, passwords or other apps' notification content.", color = Color.Gray, fontSize = 13.sp)
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileDownload, null); Text("Export usage history (CSV)") }
        OutlinedButton(onClick = { showClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all usage history") }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Clear usage history?") },
            text = { Text("All recorded app usage will be deleted from this phone. Your diary, plans and focus sessions are kept.") },
            confirmButton = { TextButton(onClick = { showClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancel") } }
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; selectedApp = null },
            title = { Text("Set app time limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose an app already recorded by timevora")
                    Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        Column {
                            knownApps.filter { candidate -> limits.none { it.packageName == candidate.packageName } }.forEach { app ->
                                TextButton(onClick = { selectedApp = app }) { Text((if (selectedApp?.packageName == app.packageName) "✓ " else "") + app.appLabel) }
                            }
                        }
                    }
                    if (knownApps.isEmpty()) Text("Start tracking and use an app first. It will appear here.", fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Daily limit (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val app = selectedApp
                    val m = minutes.toIntOrNull() ?: 0
                    if (app != null && m > 0) {
                        scope.launch {
                            val dao = timevoraDatabase.get(c).usageDao()
                            dao.saveLimit(AppLimit(app.packageName, app.appLabel, m)); limits = dao.limits(); onChanged()
                        }
                    }
                    showAdd = false; selectedApp = null
                }) { Text("Save limit") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; selectedApp = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Navy)
            Text(desc, fontSize = 11.sp, color = Color.Gray)
        }
        Switch(checked, onChange)
    }
}
