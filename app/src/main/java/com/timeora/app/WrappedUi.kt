package com.timevora.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.timevora.app.data.AppCategorizer
import com.timevora.app.data.Forest
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.isHealthyTree
import com.timevora.app.notifications.WeeklyWrapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Weekly Wrapped: Spotify-style story cards built from the last 7 days of local data.
// Everything is computed on the device. Nothing is uploaded; sharing is the user's own choice.
// ---------------------------------------------------------------------------------------------

/** One story card. [top] / [bottom] are the ARGB gradient colours. */
data class WrappedCard(
    val emoji: String,
    val kicker: String,
    val big: String,
    val sub: String,
    val lines: List<String> = emptyList(),
    val top: Long,
    val bottom: Long
)

data class WrappedWeek(val start: LocalDate, val end: LocalDate, val cards: List<WrappedCard>, val hasData: Boolean)

private const val PINK_TOP = 0xFFFF3F6CL
private const val PINK_BOTTOM = 0xFFFF905AL
private const val NAVY_TOP = 0xFF282C3FL
private const val NAVY_BOTTOM = 0xFF5A3E85L
private const val PURPLE_TOP = 0xFF7E57C2L
private const val PURPLE_BOTTOM = 0xFFFF3F6CL
private const val TEAL_TOP = 0xFF00897BL
private const val TEAL_BOTTOM = 0xFF26A69AL
private const val GOLD_TOP = 0xFFFF905AL
private const val GOLD_BOTTOM = 0xFFE53935L
private const val GREEN_TOP = 0xFF2E7D32L
private const val GREEN_BOTTOM = 0xFF81C784L

/** 15_120_000 ms -> "4h 12m"; under an hour "12m"; under a minute "<1m". */
internal fun wrappedHm(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L -> "${h}h ${m}m"
        m > 0L -> "${m}m"
        else -> "<1m"
    }
}

object WrappedBuilder {
    /** The week is the 7 days ending on [end] (Monday to Sunday when opened on a Sunday). */
    suspend fun build(c: Context, end: LocalDate = LocalDate.now()): WrappedWeek = withContext(Dispatchers.IO) {
        val dao = timevoraDatabase.get(c).usageDao()
        val start = end.minusDays(6)
        val prevEnd = start.minusDays(1)
        val prevStart = prevEnd.minusDays(6)
        val s = start.toString()
        val e = end.toString()

        val total = dao.dailyTotals(s, e).sumOf { it.durationMillis }
        val prevTotal = dao.dailyTotals(prevStart.toString(), prevEnd.toString()).sumOf { it.durationMillis }
        // timevora itself is left out of the ranking so the top app is something the person actually chose to open.
        val apps = dao.rangeApps(s, e).filter { it.packageName != c.packageName && it.durationMillis > 0L }
        val focusSeconds = dao.focusSecondsBetween(s, e)
        val trees = dao.recentFocus(300).count { it.day >= s && it.day <= e && it.plannedSeconds >= Forest.MIN_TREE_SECONDS && it.isHealthyTree() }

        val range = start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " – " + end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        val hasData = total > 0L || focusSeconds > 0L
        val cards = mutableListOf<WrappedCard>()

        cards += WrappedCard("🎬", "timevora WRAPPED", "Your week,\nwrapped", range, listOf("Swipe to see your week"), PINK_TOP, PINK_BOTTOM)
        if (!hasData) {
            cards += WrappedCard("🌱", "NOT ENOUGH DATA YET", "Nothing to\nwrap yet", "Keep tracking for a few days and your Wrapped will appear here.", emptyList(), NAVY_TOP, NAVY_BOTTOM)
            return@withContext WrappedWeek(start, end, cards, false)
        }

        // 1. Total screen time
        if (total > 0L) {
            cards += WrappedCard("📱", "YOUR SCREEN TIME", wrappedHm(total), "That is about ${wrappedHm(total / 7L)} a day", emptyList(), NAVY_TOP, NAVY_BOTTOM)
        }

        // 2. Top app (+ runners-up)
        apps.firstOrNull()?.let { top ->
            val pct = if (total > 0L) (top.durationMillis * 100L / total).toInt() else 0
            val others = apps.drop(1).take(2).mapIndexed { i, a -> "${i + 2}. ${a.appLabel}  ·  ${wrappedHm(a.durationMillis)}" }
            cards += WrappedCard("🏆", "YOUR TOP APP", top.appLabel, "${wrappedHm(top.durationMillis)} · $pct% of your week", others, PINK_TOP, PINK_BOTTOM)
        }

        // 3. Top category
        AppCategorizer.ensureLoaded(c)
        val byCategory = apps.groupBy { AppCategorizer.of(c, it.packageName, it.appLabel) }
            .mapValues { (_, list) -> list.sumOf { it.durationMillis } }
            .entries.sortedByDescending { it.value }
        byCategory.firstOrNull()?.let { (cat, ms) ->
            val pct = if (total > 0L) (ms * 100L / total).toInt() else 0
            cards += WrappedCard(cat.emoji, "YOUR TOP CATEGORY", cat.label, "${wrappedHm(ms)} · $pct% of your week", emptyList(), PURPLE_TOP, PURPLE_BOTTOM)
        }

        // 4. Most productive day: focus minutes + study minutes + 5 points per habit and per task done.
        data class DayScore(val day: LocalDate, val score: Long, val focusMin: Long, val habits: Int, val tasks: Int, val studyMin: Int)
        val scores = (0..6).map { i ->
            val d = start.plusDays(i.toLong())
            val ds = d.toString()
            val focusMin = dao.focusSeconds(ds) / 60L
            val habits = dao.completedHabits(ds)
            val tasks = dao.completedPlannerBetween(ds, ds)
            val study = dao.studyMinutesBetween(ds, ds)
            DayScore(d, focusMin + study + 5L * habits + 5L * tasks, focusMin, habits, tasks, study)
        }
        val best = scores.maxByOrNull { it.score }
        if (best != null && best.score > 0L) {
            val parts = mutableListOf<String>()
            if (best.focusMin > 0) parts += "${best.focusMin} min focus"
            if (best.studyMin > 0) parts += "${best.studyMin} min study"
            if (best.habits > 0) parts += "${best.habits} habit${if (best.habits == 1) "" else "s"}"
            if (best.tasks > 0) parts += "${best.tasks} task${if (best.tasks == 1) "" else "s"}"
            cards += WrappedCard("⚡", "YOUR MOST PRODUCTIVE DAY", best.day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH), parts.joinToString(" · "), emptyList(), GOLD_TOP, GOLD_BOTTOM)
        }

        // 5. Time saved vs the week before (only claimed when it is really true)
        val diff = prevTotal - total
        if (prevTotal > 0L && total > 0L) {
            if (diff >= 60_000L) {
                cards += WrappedCard("🌱", "TIME WON BACK", "${wrappedHm(diff)} saved", "vs. the week before (${wrappedHm(prevTotal)} → ${wrappedHm(total)})", listOf("That is time for something that matters."), TEAL_TOP, TEAL_BOTTOM)
            } else if (diff <= -60_000L) {
                cards += WrappedCard("📈", "VS LAST WEEK", "+${wrappedHm(abs(diff))}", "more screen time than the week before", listOf("New week, new start. Try one phone-free hour."), NAVY_TOP, NAVY_BOTTOM)
            } else {
                cards += WrappedCard("⚖️", "VS LAST WEEK", "About the same", "Your screen time barely moved from last week.", emptyList(), NAVY_TOP, NAVY_BOTTOM)
            }
        } else if (total > 0L) {
            cards += WrappedCard("🗓️", "FIRST WEEK", "Baseline set", "Next Sunday you will see how this week compares.", emptyList(), NAVY_TOP, NAVY_BOTTOM)
        }

        // 6. Focus and forest
        if (focusSeconds > 0L) {
            cards += WrappedCard("🌳", "YOUR FOCUS", wrappedHm(focusSeconds * 1000L), if (trees > 0) "$trees tree${if (trees == 1) "" else "s"} planted in your forest" else "of focused time", emptyList(), GREEN_TOP, GREEN_BOTTOM)
        }

        // 7. Summary card, made for sharing
        val summary = mutableListOf<String>()
        apps.firstOrNull()?.let { summary += "Top app: ${it.appLabel}" }
        if (best != null && best.score > 0L) summary += "Best day: ${best.day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"
        if (prevTotal > 0L && diff >= 60_000L) summary += "Saved ${wrappedHm(diff)} vs last week"
        if (focusSeconds > 0L) summary += "Focus: ${wrappedHm(focusSeconds * 1000L)}" + if (trees > 0) " · $trees 🌳" else ""
        cards += WrappedCard("✨", "MY WEEK ON timevora", if (total > 0L) wrappedHm(total) else "Focused week", if (total > 0L) "screen time · $range" else range, summary, PINK_TOP, PURPLE_TOP)

        WrappedWeek(start, end, cards, true)
    }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable
internal fun WrappedScreen(c: Context, refresh: Int) {
    var week by remember { mutableStateOf<WrappedWeek?>(null) }
    LaunchedEffect(refresh) { week = WrappedBuilder.build(c) }
    val w = week
    if (w == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentPink) }
    } else {
        WrappedPager(c, w)
    }
}

@Composable
private fun WrappedPager(c: Context, week: WrappedWeek) {
    val cards = week.cards
    val pager = rememberPagerState(pageCount = { cards.size })
    val scope = rememberCoroutineScope()
    val prefs = remember { c.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    var notify by remember { mutableStateOf(WeeklyWrapped.enabled(c)) }
    val last = pager.currentPage == cards.lastIndex

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cards.indices.forEach { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(styleShape(2))
                        .background(if (i <= pager.currentPage) AccentPink else Color(0x33000000))
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp) { i ->
            StoryCard(cards[i]) {
                if (pager.currentPage < cards.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { shareWrapped(c, listOf(cards[pager.currentPage])) } }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.padding(start = 6.dp))
                Text("Share this card")
            }
            if (last && cards.size > 2) {
                OutlinedButton(onClick = { scope.launch { shareWrapped(c, cards) } }) { Text("Share all") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sunday evening reminder", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 14.sp)
                Text("Get your Wrapped every Sunday at 8 PM", color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = notify, onCheckedChange = {
                notify = it
                prefs.edit().putBoolean("wrapped_notify", it).apply()
                WeeklyWrapped.schedule(c)
            })
        }
    }
}

@Composable
private fun StoryCard(card: WrappedCard, onTap: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clip(styleShape(28))
            .background(Brush.verticalGradient(listOf(Color(card.top), Color(card.bottom))))
            .clickable { onTap() }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(card.kicker, color = Color.White.copy(alpha = .8f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Text(card.emoji, fontSize = 64.sp)
            Spacer(Modifier.height(14.dp))
            Text(card.big, color = Color.White, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(card.sub, color = Color.White.copy(alpha = .92f), fontSize = 16.sp, textAlign = TextAlign.Center)
            if (card.lines.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                card.lines.forEach { Text(it, color = Color.White.copy(alpha = .85f), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            Spacer(Modifier.height(26.dp))
            Text("timevora", color = Color.White.copy(alpha = .6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sharing: each card becomes a 1080 x 1920 PNG (story size) and goes to the Android share sheet.
// ---------------------------------------------------------------------------------------------

private fun fitText(p: Paint, text: String, maxWidth: Float, minSize: Float) {
    while (p.measureText(text) > maxWidth && p.textSize > minSize) p.textSize -= 4f
}

internal fun renderWrappedCard(card: WrappedCard): Bitmap {
    val w = 1080
    val h = 1920
    val maxW = w - 160f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), card.top.toInt(), card.bottom.toInt(), Shader.TileMode.CLAMP) }
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

    fun paint(size: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    val cx = w / 2f
    var y = 520f
    val kicker = paint(40f, 0xCCFFFFFF.toInt(), true).apply { letterSpacing = 0.12f }
    fitText(kicker, card.kicker, maxW, 24f)
    cv.drawText(card.kicker, cx, y, kicker)

    y += 260f
    cv.drawText(card.emoji, cx, y, paint(220f, android.graphics.Color.WHITE, false))

    y += 170f
    card.big.split("\n").forEach { line ->
        val p = paint(130f, android.graphics.Color.WHITE, true)
        fitText(p, line, maxW, 48f)
        y += 30f
        cv.drawText(line, cx, y, p)
        y += 120f
    }

    y += 10f
    val sub = paint(48f, 0xEBFFFFFF.toInt(), false)
    // Long sub-lines are wrapped on word boundaries instead of being shrunk to nothing.
    wrapWords(card.sub, sub, maxW).forEach { cv.drawText(it, cx, y, sub); y += 64f }

    if (card.lines.isNotEmpty()) {
        y += 50f
        card.lines.forEach { line ->
            val p = paint(44f, 0xDDFFFFFF.toInt(), false)
            fitText(p, line, maxW, 28f)
            cv.drawText(line, cx, y, p)
            y += 70f
        }
    }

    val foot = paint(38f, 0x99FFFFFF.toInt(), true).apply { letterSpacing = 0.15f }
    cv.drawText("timevora  •  Your Time, Your Control", cx, h - 120f, foot)
    return bmp
}

private fun wrapWords(text: String, p: Paint, maxWidth: Float): List<String> {
    val out = mutableListOf<String>()
    var line = ""
    text.split(" ").forEach { word ->
        val next = if (line.isEmpty()) word else "$line $word"
        if (p.measureText(next) <= maxWidth || line.isEmpty()) line = next else { out += line; line = word }
    }
    if (line.isNotEmpty()) out += line
    return out
}

internal suspend fun shareWrapped(c: Context, cards: List<WrappedCard>) {
    try {
        val uris = withContext(Dispatchers.Default) {
            val dir = File(c.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("timevora_wrapped_") }?.forEach { it.delete() }
            cards.mapIndexed { i, card ->
                val bmp = renderWrappedCard(card)
                val file = File(dir, "timevora_wrapped_${i + 1}.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                FileProvider.getUriForFile(c, c.packageName + ".fileprovider", file)
            }
        }
        val text = "My week on timevora"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/png"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        send.putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(send, "Share your Wrapped").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(c, "Could not create the Wrapped image", Toast.LENGTH_SHORT).show()
    }
}
