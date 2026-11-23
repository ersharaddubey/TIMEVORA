package com.timevora.app

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.notifications.Channels
import com.timevora.app.notifications.ReportScheduler

@Composable
internal fun NotificationCenterScreen(c: Context, refresh: Int, onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    val prefs = remember { c.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    var planner by remember { mutableStateOf(prefs.getBoolean("planner_notifications", true)) }
    var report by remember { mutableStateOf(prefs.getBoolean("morning_report", true)) }
    var focus by remember { mutableStateOf(prefs.getBoolean("focus_nudge", true)) }
    var sleep by remember { mutableStateOf(prefs.getBoolean("sleep_enabled", false)) }
    var quick by remember { mutableStateOf(com.timevora.app.notifications.QuickCapture.isNotificationOn(c)) }
    var breakMin by remember { mutableIntStateOf(prefs.getInt("break_minutes", 30)) }
    var goal by remember { mutableIntStateOf(prefs.getInt("goal_minutes", 0)) }
    val allowed = remember(c, refresh) { NotificationManagerCompat.from(c).areNotificationsEnabled() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = AccentPink), shape = styleShape(24), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("✦  YOUR PERSONAL ALERTS", color = Color.White.copy(alpha = .86f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                Text("Stay on track.\nWithout checking your phone.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 30.sp)
                Text("Smart nudges for your plans, focus, screen-time goals and rest.", color = Color.White.copy(alpha = .92f), fontSize = 13.sp)
                Text(if (allowed) "● Notifications are allowed on this device" else "○ Notifications are currently blocked by Android", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (!allowed) {
                    Button(onClick = onRequestPermission) { Text("Enable notifications") }
                    Button(onClick = onOpenSettings) { Text("Open Android notification settings") }
                }
            }
        }
        Text("CHOOSE WHAT MATTERS", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
        AlertPreference("⚡", "Quick capture", "A pinned notification to add a task, note or voice note in one tap, without opening the Planner", quick) { quick = it; com.timevora.app.notifications.QuickCapture.setNotificationOn(c, it) }
        AlertPreference("📅", "Plan & task alerts", "Get a timely nudge before diary tasks and meetings", planner) { planner = it; prefs.edit().putBoolean("planner_notifications", it).apply() }
        AlertPreference("☀️", "Morning progress report", "A quick recap of yesterday, delivered in the morning", report) { report = it; prefs.edit().putBoolean("morning_report", it).apply(); ReportScheduler.schedule(c) }
        AlertPreference("⏱️", "Focus protection", "A gentle heads-up when you leave an active focus session", focus) { focus = it; prefs.edit().putBoolean("focus_nudge", it).apply() }
        AlertPreference("🌙", "Sleep routine", "Wind-down and wake-up reminders based on your schedule", sleep) { sleep = it; prefs.edit().putBoolean("sleep_enabled", it).apply(); com.timevora.app.notifications.SleepRoutine.schedule(c) }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🧘  BREAK REMINDERS", color = Navy, fontWeight = FontWeight.Bold)
                Text("A small pause can reset a long scrolling session.", color = Color.Gray, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Off", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m").forEach { (v, label) -> FilterChip(selected = breakMin == v, onClick = { breakMin = v; prefs.edit().putInt("break_minutes", v).apply() }, label = { Text(label) }) } }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🎯  DAILY SCREEN-TIME GOAL", color = Navy, fontWeight = FontWeight.Bold)
                Text("Choose a limit and timevora will alert you as you approach it.", color = Color.Gray, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Off", 60 to "1h", 120 to "2h", 180 to "3h", 240 to "4h", 360 to "6h").forEach { (v, label) -> FilterChip(selected = goal == v, onClick = { goal = v; prefs.edit().putInt("goal_minutes", v).apply() }, label = { Text(label) }) } }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = SoftPink), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("A NOTE ABOUT DELIVERY", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                Text("Android controls notification permission, battery optimisation and delivery timing. Keep timevora notifications enabled in system settings for reliable reminders.", color = Navy, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AlertPreference(emoji: String, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 25.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(detail, color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked, onChange)
        }
    }
}
