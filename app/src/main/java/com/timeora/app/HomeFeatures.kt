package com.timevora.app

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import com.timevora.app.data.BannerInfo
import com.timevora.app.data.Catalog
import com.timevora.app.data.CatalogStore
import com.timevora.app.data.FeatureInfo
import com.timevora.app.data.Remote
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/** Goal filters shown as chips on Home, like category filters in a shopping app. */
internal val GOALS = listOf(
    "" to "✨ All",
    "productive" to "⚡ Get more done",
    "study" to "🎓 Study",
    "screen" to "📵 Less screen time",
    "sleep" to "🌙 Sleep better",
    "social" to "🤝 With friends"
)

private val GOAL_HINT = mapOf(
    "productive" to "Best picks to plan your day and finish what matters.",
    "study" to "Best picks for exams and daily study hours.",
    "screen" to "Best picks to see and reduce phone time.",
    "sleep" to "Best picks for a calmer night and a fresher morning.",
    "social" to "Best picks to stay motivated with friends."
)

/** Catalog = features, banners and messages. Starts from the saved copy (or built-in defaults) and quietly updates from the server. */
@Composable
internal fun rememberCatalog(c: Context, refresh: Int): Catalog {
    var catalog by remember { mutableStateOf(CatalogStore.current(c)) }
    LaunchedEffect(refresh) {
        Remote.refreshCatalog(c)
        catalog = CatalogStore.current(c)
    }
    return catalog
}

@Composable
internal fun HomeGreeting() {
    val hour = LocalTime.now().hour
    val greet = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$greet 👋", color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("What do you want to do with your time today?", color = Color.Gray, fontSize = 13.sp)
    }
}

@Composable
internal fun FeatureSearchBar(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = styleShape(14),
        placeholder = { Text("Search a feature: focus, sleep, exam…", fontSize = 13.sp) },
        leadingIcon = { Text("🔍", fontSize = 16.sp) },
        trailingIcon = { if (query.isNotEmpty()) Text("✕", Modifier.clickable { onChange("") }.padding(12.dp), color = Color.Gray) },
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CardBg, unfocusedContainerColor = CardBg, focusedTextColor = Navy, unfocusedTextColor = Navy)
    )
}

@Composable
internal fun AnnouncementBar(text: String) {
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth().styled(SoftOrange, styleShape(12), lite = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("📣", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BannerCarousel(banners: List<BannerInfo>, onClick: (BannerInfo) -> Unit) {
    if (banners.isEmpty()) return
    val pager = rememberPagerState(pageCount = { banners.size })
    LaunchedEffect(banners.size) {
        while (banners.size > 1) {
            delay(4500)
            pager.animateScrollToPage((pager.currentPage + 1) % banners.size)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), pageSpacing = 10.dp) { i ->
            val b = banners[i]
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(b.color)), shape = styleShape(20),
                modifier = Modifier.fillMaxWidth().clickable(enabled = b.target.isNotBlank()) { onClick(b) }
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(b.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 25.sp)
                        Text(b.subtitle, color = Color.White.copy(alpha = .92f), fontSize = 13.sp)
                        if (b.target.isNotBlank()) {
                            Text("Explore  →", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(Color.White.copy(alpha = .22f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                    }
                    Text(b.emoji, fontSize = 46.sp)
                }
            }
        }
        if (banners.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                banners.indices.forEach { i ->
                    Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 16.dp else 6.dp, 6.dp).clip(RoundedCornerShape(50))
                        .background(if (i == pager.currentPage) AccentPink else Color(0xFFCCCCD2)))
                }
            }
        }
    }
}

/** Round shortcut icons for every feature, like the category strip in a shopping app. */
@Composable
internal fun QuickIconsRow(features: List<FeatureInfo>, onOpen: (FeatureInfo) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        features.forEach { f ->
            Column(Modifier.width(70.dp).clickable { onOpen(f) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(tintOf(f.color)).border(1.dp, CardBg, CircleShape), contentAlignment = Alignment.Center) {
                    Text(f.emoji, fontSize = 26.sp)
                }
                Text(f.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        if (subtitle != null) Text(subtitle, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
internal fun GoalFilter(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GOALS.forEach { (id, label) ->
                FilterChip(
                    selected = selected == id, onClick = { onSelect(id) }, label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentPink, selectedLabelColor = Color.White, containerColor = CardBg, labelColor = Navy)
                )
            }
        }
        GOAL_HINT[selected]?.let { Text(it, color = Color.Gray, fontSize = 11.sp) }
    }
}

/** One feature card: what it is, when to use it, and two clear actions (open, or learn why). */
@Composable
internal fun FeatureCard(index: Int, f: FeatureInfo, onOpen: () -> Unit, onWhy: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = onOpen), colors = CardDefaults.cardColors(containerColor = tintOf(f.color)), shape = styleShape(18)) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(CardBg), contentAlignment = Alignment.Center) { Text(f.emoji, fontSize = 21.sp) }
                Spacer(Modifier.weight(1f))
                if (f.live) Text("● LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.background(Color(0xFFE53935), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp))
                else Text("%02d".format(index), color = Navy.copy(alpha = .35f), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(f.title, color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 18.sp)
            Text(f.tagline, color = Navy.copy(alpha = .75f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (f.useWhen.isNotBlank()) {
                Column(Modifier.fillMaxWidth().styled(CardBg.copy(alpha = .65f), styleShape(10), lite = true).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("USE IT WHEN", color = AccentPink, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp)
                    Text(f.useWhen, color = Navy, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f, fill = true))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open  ›", color = AccentPink, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Why use? ⓘ", color = Navy, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(CardBg).clickable(onClick = onWhy).padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
internal fun FeatureGrid(features: List<FeatureInfo>, onOpen: (FeatureInfo) -> Unit, onWhy: (FeatureInfo) -> Unit) {
    features.chunked(2).forEachIndexed { rowIndex, pair ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEachIndexed { i, f ->
                FeatureCard(rowIndex * 2 + i + 1, f, { onOpen(f) }, { onWhy(f) }, Modifier.weight(1f).fillMaxHeight())
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
internal fun LiveRoomsPromo(feature: FeatureInfo?, onOpen: (FeatureInfo) -> Unit) {
    if (feature == null) return
    Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth().clickable { onOpen(feature) }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("● LIVE", color = Color(0xFFFF6B6B), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text("Study or work together with friends", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
            Text("Create a room, share the 6-letter code and see who is focusing right now. Leaderboard updates live.", color = Color(0xFFD9E4FF), fontSize = 12.sp, lineHeight = 16.sp)
            Text("Create or join a room  →", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(AccentPink, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

@Composable
internal fun DailyTipCard(tips: List<String>) {
    if (tips.isEmpty()) return
    val tip = tips[LocalDate.now().dayOfYear % tips.size]
    Row(Modifier.fillMaxWidth().styled(SoftPink, styleShape(14), lite = true).padding(14.dp), verticalAlignment = Alignment.Top) {
        Text("💡", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("TIP OF THE DAY", color = AccentPink, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text(tip, color = Navy, fontSize = 13.sp, fontStyle = FontStyle.Italic, lineHeight = 17.sp)
        }
    }
}

@Composable
internal fun SetupNudge(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).border(1.dp, AccentPink.copy(alpha = .5f), styleShape(14)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Finish setup (1 minute)", color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Allow Usage access so screen time and reports work.", color = Color.Gray, fontSize = 11.sp)
        }
        Text("Setup ›", color = AccentPink, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * 3-step first-run tips (≈60 sec): Goal → Dump → Focus.
 * Shown once after privacy ack until onboarding_v1_done is set.
 */
@Composable
internal fun FirstRunTipsDialog(step: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val steps = listOf(
        Triple("🎯", "Choose your focus", "What matters most today — productivity, study, less screen time, or sleep? Use the chips on Home anytime."),
        Triple("🧠", "Dump your tasks", "Write everything on your mind once. Smart Task Dump splits it into Planner items in seconds."),
        Triple("▶️", "Start a Focus block", "Pick one task and start Focus. timevora tracks the session so your day stays intentional.")
    )
    val (emoji, title, body) = steps.getOrElse(step) { steps.last() }
    AlertDialog(
        onDismissRequest = onSkip,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Step ${step + 1} of 3", color = AccentPink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("$emoji  $title", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }
        },
        text = { Text(body, color = Navy.copy(alpha = 0.8f), fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onNext) {
                Text(if (step < 2) "Next" else "Start with Task Dump", fontWeight = FontWeight.Bold, color = AccentPink)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip", color = Color.Gray) }
        },
        containerColor = CardBg
    )
}
