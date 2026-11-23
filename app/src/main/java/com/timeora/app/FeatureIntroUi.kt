package com.timevora.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.FeatureInfo

/** Slim bar shown above every feature screen: back to Home, the feature name, and a one-tap "Why use?" explanation. */
@Composable
internal fun FeatureTopBar(feature: FeatureInfo, onBack: () -> Unit, onWhy: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(tintOf(feature.color)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = AccentPink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp))
        Text(feature.emoji, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(feature.title, color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("Why use? ⓘ", color = Navy, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(CardBg).clickable(onClick = onWhy).padding(horizontal = 11.dp, vertical = 6.dp))
    }
}

@Composable
internal fun FeatureIntroScreen(feature: FeatureInfo, onBack: () -> Unit, onOpen: () -> Unit) {
    val isInfoOnly = feature.route == "Info"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("‹  Back", color = AccentPink, fontWeight = FontWeight.Bold) }

        Card(colors = CardDefaults.cardColors(containerColor = AccentPink), shape = styleShape(26), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(feature.emoji, fontSize = 42.sp)
                if (feature.live) Text("● LIVE FEATURE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(feature.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
                Text(feature.tagline, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (feature.why.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WHY USE THIS?", color = AccentPink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(feature.why, color = Navy, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                    if (feature.whyHi.isNotBlank()) Text(feature.whyHi, color = Navy.copy(alpha = .65f), fontSize = 13.sp, fontStyle = FontStyle.Italic)
                }
            }
        }
        if (feature.useWhen.isNotBlank()) {
            Row(Modifier.fillMaxWidth().styled(SoftOrange, styleShape(18), lite = true).padding(16.dp), verticalAlignment = Alignment.Top) {
                Text("🕒", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("BEST TIME TO USE IT", color = Navy, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text(feature.useWhen, color = Navy, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        if (feature.points.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("WHAT YOU CAN DO", color = AccentPink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                    feature.points.forEach { point ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("✓", color = Color(0xFF1B8E5A), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Text(point, color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
        if (feature.steps.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(22), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("HOW TO START", color = AccentPink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                    feature.steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(AccentPink), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                            Text(step, color = Navy, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
        if (feature.promise.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = SoftPink), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("THE timevora DIFFERENCE", color = Navy, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text(feature.promise, color = Navy, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 23.sp)
                }
            }
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(if (isInfoOnly) "Got it" else "Open ${feature.title}  →", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
    }
}
