package com.timevora.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.timevora.app.data.FocusSession
import com.timevora.app.data.Forest
import com.timevora.app.data.ForestSnapshot
import com.timevora.app.data.ForestTiers
import com.timevora.app.data.ForestTree
import com.timevora.app.data.TreeKind
import com.timevora.app.data.treeKindFor
import com.timevora.app.util.Fmt
import java.io.File
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Drawing. Everything is plain Canvas code so the same scene can be shown in the app and rendered
// into a PNG for sharing. No image assets are needed.
// ---------------------------------------------------------------------------------------------

private val SoilColor = Color(0xFF8B6B4A)
private val TrunkColor = Color(0xFF7A5230)
private val DeadTrunkColor = Color(0xFF5E4A3A)
private val DeadLeafColor = Color(0xFFA8844F)
private val DeadCanopyColor = Color(0xFF8A7355)
private val LeafDark = Color(0xFF2E8B3D)
private val LeafMid = Color(0xFF3FA34D)
private val LeafLight = Color(0xFF6CC070)
private val PineDark = Color(0xFF1F6B45)
private val PineMid = Color(0xFF2B8657)

/**
 * Draws one tree standing on [baseY]. [size] is the height of the fully grown tree in pixels,
 * [growth] 0..1 is how far it has grown and [dead] 0..1 how withered it is (1 = bare dead tree).
 */
internal fun DrawScope.drawTree(kind: TreeKind, cx: Float, baseY: Float, size: Float, growth: Float, dead: Float) {
    val g = growth.coerceIn(0f, 1f)
    val d = dead.coerceIn(0f, 1f)
    drawOval(Color(0x26000000), topLeft = Offset(cx - size * 0.26f, baseY - size * 0.03f), size = Size(size * 0.52f, size * 0.07f))

    // Seed stage: a mound of soil with a tiny sprout.
    if (g < 0.12f) {
        val t = g / 0.12f
        drawOval(lerp(SoilColor, DeadTrunkColor, d), topLeft = Offset(cx - size * 0.11f, baseY - size * 0.05f), size = Size(size * 0.22f, size * 0.07f))
        if (t > 0.15f) {
            val h = size * 0.14f * t
            val c = lerp(LeafLight, DeadLeafColor, d)
            val y0 = baseY - size * 0.03f
            drawLine(c, Offset(cx, y0), Offset(cx, y0 - h), strokeWidth = size * 0.025f, cap = StrokeCap.Round)
            drawCircle(c, size * 0.035f * t, Offset(cx - size * 0.03f, y0 - h))
            drawCircle(c, size * 0.035f * t, Offset(cx + size * 0.03f, y0 - h - size * 0.01f))
        }
        return
    }

    val s = size * (0.3f + 0.7f * ((g - 0.12f) / 0.88f))
    val trunkH = s * when (kind) { TreeKind.SAPLING -> 0.35f; TreeKind.OAK -> 0.38f; TreeKind.PINE -> 0.30f; TreeKind.BANYAN -> 0.34f }
    val trunkW = s * when (kind) { TreeKind.SAPLING -> 0.05f; TreeKind.OAK -> 0.08f; TreeKind.PINE -> 0.07f; TreeKind.BANYAN -> 0.13f }
    val top = baseY - trunkH
    drawRoundRect(
        lerp(TrunkColor, DeadTrunkColor, d),
        topLeft = Offset(cx - trunkW / 2f, top),
        size = Size(trunkW, trunkH),
        cornerRadius = CornerRadius(trunkW * 0.3f)
    )

    val alpha = 1f - d                 // the canopy fades away as the tree withers
    val cs = 1f - 0.35f * d            // and shrinks a little
    val sag = d * s * 0.08f            // and droops
    if (alpha > 0.02f) {
        fun leaf(c: Color) = lerp(c, DeadCanopyColor, d).copy(alpha = alpha)
        when (kind) {
            TreeKind.SAPLING -> {
                val r = s * 0.2f * cs
                drawCircle(leaf(LeafDark), r * 0.75f, Offset(cx - r * 0.8f, top - r * 0.1f + sag))
                drawCircle(leaf(LeafDark), r * 0.75f, Offset(cx + r * 0.8f, top - r * 0.1f + sag))
                drawCircle(leaf(LeafMid), r, Offset(cx, top - r * 0.6f + sag))
            }
            TreeKind.OAK -> {
                val r = s * 0.3f * cs
                drawCircle(leaf(LeafDark), r * 0.8f, Offset(cx - r * 0.75f, top - r * 0.1f + sag))
                drawCircle(leaf(LeafDark), r * 0.8f, Offset(cx + r * 0.75f, top - r * 0.1f + sag))
                drawCircle(leaf(LeafMid), r, Offset(cx, top - r * 0.55f + sag))
                drawCircle(leaf(LeafLight), r * 0.35f, Offset(cx - r * 0.25f, top - r * 0.85f + sag))
            }
            TreeKind.PINE -> {
                for (i in 0 until 3) {
                    val w = s * (0.46f - 0.09f * i) * cs
                    val bottom = top + s * 0.03f - i * s * 0.19f + sag
                    val apex = bottom - s * 0.3f * cs
                    val path = Path().apply {
                        moveTo(cx, apex)
                        lineTo(cx + w / 2f, bottom)
                        lineTo(cx - w / 2f, bottom)
                        close()
                    }
                    drawPath(path, color = leaf(if (i % 2 == 0) PineDark else PineMid))
                }
            }
            TreeKind.BANYAN -> {
                val r = s * 0.22f * cs
                val xs = floatArrayOf(-1.6f, -0.8f, 0f, 0.8f, 1.6f)
                val ys = floatArrayOf(0.25f, -0.1f, -0.4f, -0.1f, 0.25f)
                for (i in xs.indices) {
                    drawCircle(leaf(if (i % 2 == 0) LeafDark else LeafMid), r, Offset(cx + xs[i] * r, top + ys[i] * r + sag))
                }
                drawCircle(leaf(LeafLight), r * 0.4f, Offset(cx - r * 0.3f, top - r * 0.6f + sag))
                // aerial roots
                val root = TrunkColor.copy(alpha = alpha)
                drawLine(root, Offset(cx - r * 1.2f, top + r * 0.3f), Offset(cx - r * 1.2f, baseY), strokeWidth = s * 0.018f)
                drawLine(root, Offset(cx + r * 1.2f, top + r * 0.3f), Offset(cx + r * 1.2f, baseY), strokeWidth = s * 0.018f)
            }
        }
    }

    // Bare branches and fallen leaves appear as the tree dies.
    if (d > 0.25f) {
        val a = ((d - 0.25f) / 0.75f).coerceIn(0f, 1f)
        val bc = DeadTrunkColor.copy(alpha = a)
        val sw = (trunkW * 0.45f).coerceAtLeast(2f)
        val reach = if (kind == TreeKind.BANYAN) 1.5f else 1f
        drawLine(bc, Offset(cx, top + trunkH * 0.2f), Offset(cx - s * 0.2f * reach, top - s * 0.16f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(bc, Offset(cx, top + trunkH * 0.1f), Offset(cx + s * 0.18f * reach, top - s * 0.2f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(bc, Offset(cx, top), Offset(cx + s * 0.03f, top - s * 0.3f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(bc, Offset(cx - s * 0.1f * reach, top - s * 0.04f), Offset(cx - s * 0.2f * reach, top - s * 0.02f), strokeWidth = sw * 0.7f, cap = StrokeCap.Round)
    }
    if (d > 0.5f) {
        for (k in 0 until 4) {
            val x = cx + (k - 1.5f) * s * 0.12f
            val y = baseY - s * 0.01f + (k % 2) * s * 0.015f
            drawCircle(DeadLeafColor, s * 0.02f, Offset(x, y))
        }
    }
}

/** Sky, hills, ground, sun, clouds and flowers. The look depends on the land tier (streak). */
internal fun DrawScope.drawBackdrop(tier: Int, groundFrac: Float) {
    val w = size.width
    val h = size.height
    val skyTop: Color
    val skyBottom: Color
    when (tier) {
        0 -> { skyTop = Color(0xFFB8BECB); skyBottom = Color(0xFFE6E2DA) }
        1 -> { skyTop = Color(0xFF8FD0F4); skyBottom = Color(0xFFE6F6FD) }
        2 -> { skyTop = Color(0xFF86CDF3); skyBottom = Color(0xFFE9F8FE) }
        3 -> { skyTop = Color(0xFF74C4F0); skyBottom = Color(0xFFE2F5FF) }
        4 -> { skyTop = Color(0xFF63B8EC); skyBottom = Color(0xFFD9F1FF) }
        else -> { skyTop = Color(0xFF2F8F7C); skyBottom = Color(0xFFF7D774) }
    }
    drawRect(Brush.verticalGradient(listOf(skyTop, skyBottom)), topLeft = Offset.Zero, size = Size(w, h))

    val groundTop = h * groundFrac
    val groundA: Color
    val groundB: Color
    when (tier) {
        0 -> { groundA = Color(0xFFB59A74); groundB = Color(0xFF9C8460) }
        1 -> { groundA = Color(0xFF8FCB6B); groundB = Color(0xFF6DB04F) }
        5 -> { groundA = Color(0xFF3F9142); groundB = Color(0xFF2A6E33) }
        else -> { groundA = Color(0xFF7BC25A); groundB = Color(0xFF58A340) }
    }

    // Sun and clouds
    if (tier in 1..4) {
        drawCircle(Color(0x33FFD54F), w * 0.09f, Offset(w * 0.85f, h * 0.13f))
        drawCircle(Color(0xFFFFD54F), w * 0.055f, Offset(w * 0.85f, h * 0.13f))
    } else if (tier == 5) {
        drawCircle(Color(0x55FFF3B0), w * 0.22f, Offset(w * 0.5f, groundTop))
        drawCircle(Color(0xFFFFF3B0), w * 0.07f, Offset(w * 0.5f, groundTop - h * 0.02f))
    }
    if (tier != 5) {
        val cloud = if (tier == 0) Color(0xCC9AA0AD) else Color(0xE6FFFFFF)
        fun cloudAt(cx: Float, cy: Float, r: Float) {
            drawCircle(cloud, r, Offset(cx, cy))
            drawCircle(cloud, r * 0.8f, Offset(cx - r * 1.1f, cy + r * 0.2f))
            drawCircle(cloud, r * 0.9f, Offset(cx + r * 1.1f, cy + r * 0.15f))
        }
        cloudAt(w * 0.2f, h * 0.13f, w * 0.04f)
        cloudAt(w * 0.55f, h * 0.2f, w * 0.032f)
    }

    // Far hill for depth
    if (tier >= 1) {
        val hill = Path().apply {
            moveTo(0f, groundTop)
            quadraticBezierTo(w * 0.25f, groundTop - h * 0.12f, w * 0.55f, groundTop - h * 0.02f)
            quadraticBezierTo(w * 0.8f, groundTop - h * 0.09f, w, groundTop)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(hill, color = lerp(groundA, skyBottom, 0.4f))
    }
    val ground = Path().apply {
        moveTo(0f, groundTop + h * 0.02f)
        quadraticBezierTo(w * 0.5f, groundTop - h * 0.04f, w, groundTop + h * 0.02f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(ground, brush = Brush.verticalGradient(listOf(groundA, groundB), startY = groundTop, endY = h))

    // Flowers from the Garden tier up
    if (tier >= 2) {
        val rnd = Random(11)
        val colors = listOf(Color(0xFFFF6F91), Color(0xFFFFC857), Color(0xFFFFFFFF), Color(0xFFFF9F68), Color(0xFFB39DDB))
        repeat(10 + tier * 8) {
            val x = rnd.nextFloat() * w
            val y = groundTop + h * 0.06f + rnd.nextFloat() * (h - groundTop - h * 0.08f)
            drawCircle(colors[rnd.nextInt(colors.size)], w * 0.009f, Offset(x, y))
            drawCircle(Color(0xFFFFF59D), w * 0.0035f, Offset(x, y))
        }
    }
}

/** Birds, jungle vines and fireflies, drawn on top of the trees. */
internal fun DrawScope.drawOverlay(tier: Int) {
    val w = size.width
    val h = size.height
    if (tier >= 4) {
        val ink = Color(0xFF3A3F55)
        for (i in 0 until 3) {
            val cx = w * (0.14f + 0.12f * i)
            val cy = h * (0.1f + 0.04f * (i % 2))
            val sp = w * 0.02f
            drawLine(ink, Offset(cx - sp, cy), Offset(cx, cy + sp * 0.5f), strokeWidth = w * 0.004f, cap = StrokeCap.Round)
            drawLine(ink, Offset(cx, cy + sp * 0.5f), Offset(cx + sp, cy), strokeWidth = w * 0.004f, cap = StrokeCap.Round)
        }
    }
    if (tier >= 5) {
        val rnd = Random(3)
        repeat(7) { i ->
            val x = (i + 0.5f) / 7f * w + rnd.nextFloat() * w * 0.04f
            val len = h * (0.08f + 0.12f * rnd.nextFloat())
            drawLine(Color(0xFF1F6B3A), Offset(x, 0f), Offset(x + w * 0.01f, len), strokeWidth = w * 0.006f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF2E8B3D), w * 0.012f, Offset(x + w * 0.01f, len))
        }
        repeat(9) {
            val p = Offset(rnd.nextFloat() * w, h * (0.3f + 0.6f * rnd.nextFloat()))
            drawCircle(Color(0x44FFF59D), w * 0.02f, p)
            drawCircle(Color(0xFFFFF59D), w * 0.005f, p)
        }
    }
}

/** The whole forest: oldest trees at the back, newest in front. Withered trees stay as bare, dead trees. */
internal fun DrawScope.drawForestScene(trees: List<ForestTree>, streak: Int) {
    val tier = ForestTiers.forStreak(streak).level
    val groundFrac = 0.4f
    drawBackdrop(tier, groundFrac)

    val w = size.width
    val h = size.height
    val perRow = 8
    val n = trees.size
    if (n > 0) {
        val rows = (n + perRow - 1) / perRow
        val unit = w / perRow
        val groundTop = h * groundFrac
        val usable = h - groundTop
        val rnd = Random(5)
        var idx = 0
        for (r in 0 until rows) {
            val inRow = minOf(perRow, n - idx)
            val t = if (rows == 1) 0.45f else r.toFloat() / (rows - 1)
            val baseY = groundTop + usable * (0.22f + 0.70f * t)
            val scale = 0.6f + 0.4f * t
            val treeSize = unit * 1.7f * scale
            val stagger = if (inRow == perRow) (if (r % 2 == 0) -0.15f else 0.15f) * unit else 0f
            for (k in 0 until inRow) {
                val tree = trees[idx]
                val x = w / 2f + (k - (inRow - 1) / 2f) * unit + stagger + (rnd.nextFloat() - 0.5f) * unit * 0.25f
                val y = baseY + (rnd.nextFloat() - 0.5f) * usable * 0.03f
                drawTree(tree.kind, x, y, treeSize, 1f, if (tree.withered) 1f else 0f)
                idx++
            }
        }
    }
    drawOverlay(tier)
}

// ---------------------------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------------------------

/** The tree that is growing right now. It grows with the timer and withers as soon as [withered] turns true. */
@Composable
internal fun LiveTree(kind: TreeKind, growth: Float, withered: Boolean, tier: Int) {
    val g by animateFloatAsState(growth, tween<Float>(900), label = "treeGrowth")
    val d by animateFloatAsState(if (withered) 1f else 0f, tween<Float>(1400), label = "treeDeath")
    Canvas(Modifier.fillMaxWidth().height(190.dp).clip(styleShape(20))) {
        drawBackdrop(tier, 0.74f)
        drawTree(kind, size.width / 2f, size.height * 0.9f, size.height * 0.8f, g, d)
        drawOverlay(tier)
    }
}

/** Latest result, the forest picture, streak progress, stats and a share button. */
@Composable
internal fun ForestSection(c: Context, snap: ForestSnapshot, recent: List<FocusSession>, now: Long) {
    val tier = ForestTiers.forStreak(snap.streak)
    val next = ForestTiers.next(snap.streak)

    // Result of the session that just ended (shown for 10 minutes).
    val last = recent.firstOrNull()
    if (last != null && last.plannedSeconds >= Forest.MIN_TREE_SECONDS) {
        val endedAt = last.startMillis + last.actualSeconds * 1000L
        if (now - endedAt in 0L..10 * 60_000L) {
            val kind = treeKindFor(last.plannedSeconds).label.lowercase()
            val healthy = !last.withered && last.actualSeconds >= last.plannedSeconds
            val msg = when {
                healthy -> "🌳 Your $kind is fully grown! Forest streak: ${snap.streak} day(s)."
                last.actualSeconds < last.plannedSeconds -> "🥀 You stopped early, so your $kind withered."
                else -> "🥀 Your $kind withered. You spent ${Fmt.hms(last.leakedSeconds * 1000L)} in other apps."
            }
            Text(
                msg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Navy,
                modifier = Modifier.fillMaxWidth().styled(if (healthy) Color(0xFFE4F5E0) else SoftOrange, styleShape(14), lite = true).padding(12.dp)
            )
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("YOUR FOREST", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { shareForest(c, snap) }) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(6.dp))
            Text("Share")
        }
    }
    Canvas(Modifier.fillMaxWidth().height(260.dp).clip(styleShape(20))) {
        drawForestScene(snap.trees, snap.streak)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${tier.name} • ${snap.streak} day streak (best ${snap.bestStreak})", fontWeight = FontWeight.Bold, color = Navy)
        Text(tier.blurb, fontSize = 12.sp, color = Color.Gray)
        if (next != null) {
            val span = (next.minStreak - tier.minStreak).coerceAtLeast(1)
            LinearProgressIndicator(progress = { ((snap.streak - tier.minStreak).toFloat() / span).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            val left = next.minStreak - snap.streak
            Text("$left more day(s) with a healthy tree to reach ${next.name}", fontSize = 11.sp, color = Color.Gray)
        } else {
            Text("Top level reached. Keep the streak alive!", fontSize = 11.sp, color = Color.Gray)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniStat("Trees grown", snap.healthyTotal.toString(), Modifier.weight(1f))
        MiniStat("Withered", snap.witheredTotal.toString(), Modifier.weight(1f))
        MiniStat("Today", snap.healthyToday.toString(), Modifier.weight(1f))
    }
    Text(
        "How it works: a focus session of 5+ minutes plants a tree. Longer sessions grow bigger trees. " +
            "Stay out of other apps (a total of 15 seconds is forgiven) and stay until the timer ends, or the tree withers. " +
            "One healthy tree a day builds your streak: Garden at 3 days, Forest at 7, Woodland at 14, Jungle at 30. " +
            "Tracking must be running so timevora can see other apps.",
        fontSize = 11.sp, color = Color.Gray, lineHeight = 15.sp
    )
}

// ---------------------------------------------------------------------------------------------
// Sharing: render the forest into a PNG and hand it to the Android share sheet.
// ---------------------------------------------------------------------------------------------

internal fun shareForest(c: Context, snap: ForestSnapshot) {
    try {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val image = bmp.asImageBitmap()
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
            drawForestScene(snap.trees, snap.streak)
        }

        // Header and footer text, drawn with the platform canvas on the same bitmap.
        val tier = ForestTiers.forStreak(snap.streak)
        val out = android.graphics.Canvas(bmp)
        val band = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
        out.drawRoundRect(android.graphics.RectF(40f, 50f, w - 40f, 250f), 36f, 36f, band)
        val title = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 68f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val sub = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFE7ED.toInt()
            textSize = 40f
        }
        out.drawText("My timevora Forest", 80f, 140f, title)
        out.drawText("${tier.name} • ${snap.streak} day streak • ${snap.healthyTotal} trees", 80f, 205f, sub)
        out.drawRoundRect(android.graphics.RectF(40f, h - 150f, w - 40f, h - 50f), 36f, 36f, band)
        val foot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        out.drawText("Grown with timevora • Your Time, Your Control", w / 2f, h - 85f, foot)

        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "timevora_forest.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "My focus forest on timevora: ${snap.streak} day streak, ${snap.healthyTotal} trees grown.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share your forest").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(c, "Could not create the forest image", Toast.LENGTH_SHORT).show()
    }
}
