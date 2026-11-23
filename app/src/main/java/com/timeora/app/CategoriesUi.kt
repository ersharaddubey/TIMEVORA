package com.timevora.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.AppCategorizer
import com.timevora.app.data.AppCategory
import com.timevora.app.data.AppCategoryOverride
import com.timevora.app.data.AppUsageTotal
import com.timevora.app.data.CategoryLimit
import com.timevora.app.data.KnownApp
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.util.Fmt
import kotlinx.coroutines.launch

/** 60 -> "1h", 90 -> "1h 30m", 45 -> "45m". */
internal fun minutesLabel(m: Int): String = when {
    m >= 60 && m % 60 == 0 -> "${m / 60}h"
    m > 60 -> "${m / 60}h ${m % 60}m"
    else -> "${m}m"
}

// ---------------------------------------------------------------------------------------------
// Settings: category limits and the manual app-category editor
// ---------------------------------------------------------------------------------------------

@Composable
internal fun CategoryLimitsSection(c: Context, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var limits by remember { mutableStateOf(emptyList<CategoryLimit>()) }
    var apps by remember { mutableStateOf(emptyList<KnownApp>()) }
    var ready by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var pickCat by remember { mutableStateOf(AppCategory.SOCIAL) }
    var minutes by remember { mutableStateOf("60") }

    LaunchedEffect(Unit) {
        val dao = timevoraDatabase.get(c).usageDao()
        AppCategorizer.ensureLoaded(c)
        limits = dao.categoryLimits()
        apps = dao.knownApps()
        ready = true
    }

    fun save(limit: CategoryLimit) {
        scope.launch {
            val dao = timevoraDatabase.get(c).usageDao()
            dao.saveCategoryLimit(limit)
            limits = dao.categoryLimits()
            onChanged()
        }
    }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Category limits", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
        TextButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null); Text("Add category") }
    }
    Text(
        "Limit the combined daily time of every app in a category, for example Social total 1 hour. You get alerts at 50%, 80% and 100%. timevora notifies you; it does not block apps.",
        fontSize = 12.sp, color = Color.Gray
    )
    if (limits.isEmpty()) Text("No category limits yet.", fontSize = 12.sp, color = Color.Gray)
    limits.forEach { limit ->
        val cat = AppCategory.fromName(limit.category) ?: AppCategory.OTHER
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${cat.emoji} ${cat.label}", fontWeight = FontWeight.SemiBold, color = Navy)
                        Text("${minutesLabel(limit.limitMinutes)} per day, all ${cat.label} apps together", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(checked = limit.enabled, onCheckedChange = { v -> save(limit.copy(enabled = v)) })
                    IconButton(onClick = {
                        scope.launch {
                            val dao = timevoraDatabase.get(c).usageDao()
                            dao.deleteCategoryLimit(limit.category)
                            limits = dao.categoryLimits()
                            onChanged()
                        }
                    }) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = limit.alert50, onClick = { save(limit.copy(alert50 = !limit.alert50)) }, label = { Text("50%") })
                    FilterChip(selected = limit.alert80, onClick = { save(limit.copy(alert80 = !limit.alert80)) }, label = { Text("80%") })
                    FilterChip(selected = limit.alert100, onClick = { save(limit.copy(alert100 = !limit.alert100)) }, label = { Text("100%") })
                }
            }
        }
    }
    OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Review app categories") }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Set category limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Category")
                    ChipRow(AppCategory.entries.map { it to (it.emoji + " " + it.label) }, pickCat) { pickCat = it }
                    val members = if (ready) apps.filter { AppCategorizer.of(c, it.packageName, it.appLabel) == pickCat }.take(6).joinToString(", ") { it.appLabel } else ""
                    Text(
                        if (members.isNotEmpty()) "Includes: $members" else "No recorded apps in this category yet.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Text("Daily limit for the whole category")
                    ChipRow(listOf(30 to "30 min", 60 to "1 hour", 90 to "1.5 h", 120 to "2 hours"), minutes.toIntOrNull() ?: -1) { minutes = it.toString() }
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Minutes per day") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = minutes.toIntOrNull() ?: 0
                    if (m > 0) save(CategoryLimit(pickCat.name, m))
                    showAdd = false
                }) { Text("Save limit") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }

    if (showApps) {
        AppCategoriesDialog(c, apps, onClose = { showApps = false; onChanged() })
    }
}

/** Lists every recorded app with its category. Tap the category to move the app somewhere else. */
@Composable
private fun AppCategoriesDialog(c: Context, apps: List<KnownApp>, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("App categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("timevora groups your apps automatically. If one is in the wrong group, tap its category to change it.", fontSize = 12.sp, color = Color.Gray)
                Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Column {
                        if (apps.isEmpty()) Text("Start tracking and use some apps first. They will appear here.", fontSize = 12.sp, color = Color.Gray)
                        apps.forEach { app ->
                            val cat = run { version; AppCategorizer.of(c, app.packageName, app.appLabel) }
                            val custom = run { version; AppCategorizer.isCustom(app.packageName) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Navy)
                                    if (custom) Text("set by you", fontSize = 10.sp, color = Color.Gray)
                                }
                                Box {
                                    TextButton(onClick = { menuFor = app.packageName }) { Text(cat.emoji + " " + cat.label) }
                                    DropdownMenu(expanded = menuFor == app.packageName, onDismissRequest = { menuFor = null }) {
                                        AppCategory.entries.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.emoji + " " + option.label) },
                                                onClick = {
                                                    menuFor = null
                                                    scope.launch {
                                                        timevoraDatabase.get(c).usageDao().saveAppCategory(AppCategoryOverride(app.packageName, option.name))
                                                        AppCategorizer.reload(c)
                                                        version++
                                                    }
                                                }
                                            )
                                        }
                                        if (custom) {
                                            DropdownMenuItem(
                                                text = { Text("Back to automatic") },
                                                onClick = {
                                                    menuFor = null
                                                    scope.launch {
                                                        timevoraDatabase.get(c).usageDao().deleteAppCategory(app.packageName)
                                                        AppCategorizer.reload(c)
                                                        version++
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

// ---------------------------------------------------------------------------------------------
// Insights: pie chart of time by category, with limit progress
// ---------------------------------------------------------------------------------------------

private data class CategoryRow(val cat: AppCategory, val millis: Long, val apps: List<AppUsageTotal>)

/** Donut/pie chart of the period's usage grouped by category. [showLimits] adds today's category limit progress. */
@Composable
internal fun CategoryPieSection(c: Context, rows: List<AppUsageTotal>, showLimits: Boolean, refresh: Int) {
    var limits by remember { mutableStateOf(emptyList<CategoryLimit>()) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        AppCategorizer.ensureLoaded(c)
        limits = timevoraDatabase.get(c).usageDao().categoryLimits()
        generation++
    }

    val gen = generation
    val used = rows.groupBy { row -> gen; AppCategorizer.of(c, row.packageName, row.appLabel) }
        .map { (cat, list) -> CategoryRow(cat, list.sumOf { it.durationMillis }, list.sortedByDescending { it.durationMillis }) }
        .filter { it.millis > 0L }
        .sortedByDescending { it.millis }
    val sum = used.sumOf { it.millis }
    val activeLimits = if (showLimits) limits.filter { it.enabled && it.limitMinutes > 0 } else emptyList()
    val limitOnly = activeLimits
        .filter { l -> used.none { it.cat.name == l.category } }
        .mapNotNull { l -> AppCategory.fromName(l.category)?.let { CategoryRow(it, 0L, emptyList()) } }
    val legend = used + limitOnly

    Text("Time by category", fontWeight = FontWeight.Bold, color = Navy)
    Column(
        Modifier.fillMaxWidth().styled(CardBg, styleShape(18), lite = true).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (sum <= 0L) {
            Text("No usage recorded yet.", color = Color.Gray)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CategoryDonut(used.map { Color(it.cat.color) to it.millis.toFloat() }, Fmt.hms(sum))
            }
        }
        legend.forEach { row ->
            val limit = activeLimits.firstOrNull { it.category == row.cat.name }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Color(row.cat.color), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${row.cat.emoji} ${row.cat.label}", fontWeight = FontWeight.SemiBold, color = Navy)
                        if (row.apps.isNotEmpty()) {
                            Text(row.apps.take(3).joinToString(", ") { it.appLabel }, fontSize = 10.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.hms(row.millis), fontWeight = FontWeight.Bold, color = Navy)
                        if (sum > 0L) Text("${(row.millis * 100f / sum).toInt()}%", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                if (limit != null) {
                    val limitMs = limit.limitMinutes * 60_000L
                    val fraction = row.millis.toFloat() / limitMs
                    val over = fraction >= 1f
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        color = if (over) Color(0xFFD32F2F) else Color(row.cat.color),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (over) "Limit reached: ${Fmt.hms(row.millis)} of ${minutesLabel(limit.limitMinutes)} per day"
                        else "${Fmt.hms(limitMs - row.millis)} left of your ${minutesLabel(limit.limitMinutes)} limit",
                        fontSize = 10.sp, color = if (over) Color(0xFFD32F2F) else Color.Gray
                    )
                }
            }
        }
        Text(
            "Apps are grouped automatically on your device. Fix a wrong group or set a daily category limit in Settings.",
            fontSize = 10.sp, color = Color.Gray
        )
    }
}

@Composable
private fun CategoryDonut(slices: List<Pair<Color, Float>>, centerText: String) {
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.22f
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val total = slices.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1f)
            var angle = -90f
            slices.forEach { (color, value) ->
                val sweep = value / total * 360f
                val gap = if (slices.size > 1) 1.5f else 0f
                drawArc(
                    color = color,
                    startAngle = angle,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke)
                )
                angle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, fontWeight = FontWeight.Bold, color = Navy, fontSize = 13.sp)
            Text("total", fontSize = 11.sp, color = Color.Gray)
        }
    }
}
