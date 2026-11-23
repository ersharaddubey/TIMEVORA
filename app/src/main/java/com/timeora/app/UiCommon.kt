package com.timevora.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.AppUsageTotal
import com.timevora.app.util.Fmt
import kotlinx.coroutines.delay

// timevora has one colour pattern (see Theme.kt: AppColors, light and dark), so the whole app and the home-screen widgets match.
// Light: primary #2563EB, secondary #0891B2, ink #1E293B, canvas #F3F5F8.
internal val Navy: Color get() = Color(ThemeState.palette.ink)            // main text colour
internal val HeroBg: Color get() = Color(ThemeState.palette.hero)         // dark hero cards that carry light text
internal val CardBg: Color get() = Color(ThemeState.palette.card)         // normal cards / sheets
internal val PanelBg: Color get() = Color(ThemeState.palette.panel)       // pastel info panels
internal val Blue: Color get() = Color(ThemeState.palette.accent)
internal val Purple: Color get() = Color(ThemeState.palette.accent2)
internal val SurfaceBg: Color get() = Color(ThemeState.palette.canvas)
internal val AccentPink: Color get() = Color(ThemeState.palette.accent)
internal val AccentOrange: Color get() = Color(ThemeState.palette.accent2)
internal val SoftPink: Color get() = Color(ThemeState.palette.soft)
internal val SoftOrange: Color get() = Color(ThemeState.palette.soft2)

/** Pastel feature colour from the server. In a dark theme it is mixed into the card colour so light text stays readable. */
internal fun tintOf(argb: Long): Color {
    val base = Color(argb)
    return if (ThemeState.palette.dark) androidx.compose.ui.graphics.lerp(CardBg, base, 0.18f) else base
}

/** Current time in millis, refreshed every second. Used to make timers and totals tick live. */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
internal fun StatCard(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Color.Gray)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
        }
    }
}

@Composable
internal fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Color.Gray, fontSize = 11.sp)
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Navy)
        }
    }
}

@Composable
internal fun UsageRow(row: AppUsageTotal) {
    Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.appLabel, fontWeight = FontWeight.SemiBold, color = Navy)
            Text("${row.sessions} recorded session(s)", fontSize = 11.sp, color = Color.Gray)
        }
        Text(Fmt.hms(row.durationMillis), fontWeight = FontWeight.Bold, color = Blue)
    }
}

@Composable
internal fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
