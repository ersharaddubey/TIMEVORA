package com.timevora.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.Sync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Opt-in "Back up my data" switch with a clear consent dialog, Back up now, and Delete my data from the server. */
@Composable
internal fun BackupSection(c: Context) {
    var on by remember { mutableStateOf(Sync.enabled(c)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var askOn by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(Sync.lastSync(c)) }
    val scope = rememberCoroutineScope()

    fun backupNow() {
        scope.launch {
            busy = true; status = "Backing up…"
            val ok = Sync.run(c, force = true)
            last = Sync.lastSync(c)
            status = if (ok) "✓ Backed up." else "Backup failed: ${Sync.lastError(c).ifEmpty { "please check your internet and try again" }}"
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Back up my data to the server", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 13.sp)
                Text("Off by default. Uploads your usage history, habits, planner, focus, exams and settings.", color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = on, onCheckedChange = { want ->
                if (want) askOn = true else { on = false; Sync.setEnabled(c, false); status = "Backup is off. Data already on the server stays until you delete it." }
            })
        }
        if (on) {
            val lastText = if (last > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)) else "not yet"
            Text("Last backup: $lastText", color = Color.Gray, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { backupNow() }) { Text("Back up now") }
                OutlinedButton(enabled = !busy, onClick = { askDelete = true }) { Text("Delete my server data") }
            }
        }
        if (status.isNotEmpty()) Text(status, color = if (status.startsWith("✓")) Color(0xFF1B8E5A) else Navy, fontSize = 12.sp)
    }

    if (askOn) AlertDialog(
        onDismissRequest = { askOn = false },
        title = { Text("Back up your data?") },
        text = {
            Text(
                "This sends a copy of your personal data to the timevora server: app usage history (app names and times), habits, planner, focus sessions, exams and study logs, notes, and your settings.\n\n" +
                    "It is stored under a random device ID, protected by a secret key kept on this phone. You can turn it off and delete everything from the server at any time."
            )
        },
        confirmButton = { TextButton(onClick = { askOn = false; on = true; Sync.setEnabled(c, true); backupNow() }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { askOn = false }) { Text("Cancel") } }
    )

    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("Delete your server data?") },
        text = { Text("Everything backed up from this phone is removed from the server and backup is turned off. Your data on this phone is not touched.") },
        confirmButton = {
            TextButton(onClick = {
                askDelete = false
                scope.launch {
                    busy = true
                    try {
                        Sync.deleteRemote(c)
                        Sync.setEnabled(c, false); on = false; last = 0L
                        status = "✓ Your data was deleted from the server."
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        status = "Could not delete: ${e.message ?: "unknown error"}"
                    }
                    busy = false
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } }
    )
}
