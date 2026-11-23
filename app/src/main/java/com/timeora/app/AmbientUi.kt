package com.timevora.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.timevora.app.audio.AmbientPlayer
import com.timevora.app.audio.AmbientSound
import com.timevora.app.data.CatalogStore
import com.timevora.app.data.Remote
import com.timevora.app.util.Fmt

/**
 * Ambient sounds card for the Focus screen: rain, café, white noise and lo-fi (generated offline), plus any MP3 sounds the admin
 * uploaded (marked with a cloud, downloaded once), volume and a sleep timer. A chosen sound starts together with the focus session.
 */
@Composable
internal fun AmbientSection(c: Context, now: Long, sessionActive: Boolean) {
    var selected by remember { mutableStateOf(AmbientPlayer.savedSound(c)?.takeIf { it.isCloud }) }
    var sleepMin by remember { mutableIntStateOf(AmbientPlayer.savedSleepMinutes(c)) }
    // Extra sounds from the admin panel. Shown from the saved catalog right away, refreshed from the server in the background.
    var cloud by remember { mutableStateOf(CatalogStore.current(c).sounds) }
    LaunchedEffect(Unit) {
        AmbientPlayer.loadVolume(c)
        Remote.refreshCatalog(c, force = true)?.let { cloud = it.sounds }
        // If the admin removed or hid the sound this person had picked, drop the pick so a focus session never starts a dead link.
        val sel = AmbientPlayer.savedSound(c)
        if (sel != null && (!sel.isCloud || cloud.none { it.id == sel.id }) && AmbientPlayer.current?.id != sel.id) {
            AmbientPlayer.saveSound(c, null)
            selected = null
        }
    }
    val playing = AmbientPlayer.current
    val sleepEnd = AmbientPlayer.sleepEndMillis

    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AMBIENT SOUND", fontWeight = FontWeight.Bold, color = Navy, fontSize = 13.sp)
            Text(
                when {
                    playing != null -> "${playing.emoji} Now playing: ${playing.label}" + if (sleepEnd > now) "  •  stops in ${Fmt.clock(sleepEnd - now)}" else ""
                    selected != null && !sessionActive -> "${selected!!.emoji} ${selected!!.label} will start with your focus session."
                    else -> "Pick a background sound. Sounds download once, then work offline too."
                },
                fontSize = 12.sp, color = Color.Gray
            )
            val pick: (String) -> Unit = { id ->
                val sound = cloud.firstOrNull { it.id == id }
                selected = sound
                AmbientPlayer.saveSound(c, sound)
                when {
                    sound == null -> AmbientPlayer.stop(c)
                    sessionActive || playing != null -> AmbientPlayer.play(c, sound)
                }
            }
            // Only the sounds uploaded in the admin panel are shown (no built-in sounds).
            ChipRow(listOf("off" to "Off") + cloud.map { it.id to "${it.emoji} ${it.label}" }, selected?.id ?: "off", pick)
            if (cloud.isEmpty()) {
                Text(
                    if (Remote.isConfigured(c)) "No sounds added yet. Add one in the admin panel." else "Connect to your server in Settings to load sounds.",
                    fontSize = 11.sp, color = Color.Gray
                )
            }

            if (playing != null) {
                OutlinedButton(onClick = { AmbientPlayer.stop(c) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop sound")
                }
            } else if (selected != null) {
                OutlinedButton(onClick = { selected?.let { AmbientPlayer.play(c, it) } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (sessionActive) "Play now" else "Preview")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = Navy)
                Spacer(Modifier.width(8.dp))
                Slider(value = AmbientPlayer.volume, onValueChange = { AmbientPlayer.setVolume(c, it) }, modifier = Modifier.weight(1f))
            }

            Text("Sleep timer", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 13.sp)
            ChipRow(listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "60 min", 90 to "90 min"), sleepMin) {
                sleepMin = it
                AmbientPlayer.setSleepMinutes(c, it)
            }
            Text(
                if (sleepMin == 0) "Timer off: the sound ends when your focus session ends. The last 10 seconds fade out softly."
                else "The sound fades out after $sleepMin min, even if your focus session is still running.",
                fontSize = 11.sp, color = Color.Gray
            )
        }
    }
}
