package com.timevora.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timevora.app.data.Remote
import com.timevora.app.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class RoomRef(val code: String, val title: String, val type: String)

private fun roomEmoji(type: String) = when (type) { "work" -> "💼"; "focus" -> "🎯"; else -> "📚" }

private fun readRooms(c: Context): List<RoomRef> {
    val raw = c.getSharedPreferences("timevora_rooms", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    return runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { val o = a.getJSONObject(it); RoomRef(o.getString("code"), o.optString("title"), o.optString("type", "study")) }
    }.getOrDefault(emptyList())
}

private fun writeRooms(c: Context, items: List<RoomRef>) {
    val a = JSONArray()
    items.forEach { a.put(JSONObject().put("code", it.code).put("title", it.title).put("type", it.type)) }
    c.getSharedPreferences("timevora_rooms", Context.MODE_PRIVATE).edit().putString("items", a.toString()).apply()
}

private fun rememberRoom(c: Context, r: RoomRef) = writeRooms(c, listOf(r) + readRooms(c).filterNot { it.code == r.code })
private fun forgetRoom(c: Context, code: String) = writeRooms(c, readRooms(c).filterNot { it.code == code })

/** Friends tab: live rooms (real time, needs the timevora server) and offline scorecards (manual). */
@Composable
internal fun FriendsChallengesScreen(c: Context, refresh: Int) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = CardBg, contentColor = AccentPink) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("● Live rooms", fontWeight = FontWeight.Bold) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Scorecards", fontWeight = FontWeight.Bold) })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) LiveRoomsScreen(c) else OfflineScorecards(c, refresh)
        }
    }
}

/** One-tap Connect to the timevora server (address is built into the app via Remote.DEFAULT_SERVER), plus privacy switch. Used in Live rooms (when not connected) and in Settings. */
@Composable
internal fun ServerCard(c: Context, onChanged: () -> Unit) {
    var url by remember { mutableStateOf(Remote.baseUrl(c)) }
    var connected by remember { mutableStateOf(Remote.isConfigured(c)) }
    var share by remember { mutableStateOf(Remote.shareCounts(c)) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showOther by remember { mutableStateOf(!Remote.hasDefault) }
    val scope = rememberCoroutineScope()

    fun connectTo(target: String) {
        Remote.setBaseUrl(c, target)
        url = Remote.baseUrl(c)
        scope.launch {
            busy = true; msg = "Connecting…"
            try {
                Remote.call(c, "GET", "/api/health")
                val cat = Remote.refreshCatalog(c, force = true)
                msg = if (cat != null) "✓ Connected. ${cat.sounds.size} uploaded sound(s) found." else "Connected, but the feature list could not be loaded. Try again."
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (Remote.hasDefault && target == Remote.DEFAULT_SERVER) { Remote.setBaseUrl(c, ""); url = "" }
                msg = "Could not connect: ${e.message ?: "unknown error"}. Check that the server is running and your internet / Wi-Fi is on."
            }
            busy = false
            connected = Remote.isConfigured(c)
            onChanged()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ONLINE FEATURES", fontWeight = FontWeight.ExtraBold, color = Navy, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Text(
                    if (connected) "● Connected" else "○ Not connected",
                    color = if (connected) Color(0xFF1B8E5A) else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(if (connected) Color(0xFFE6F6EE) else Color(0xFFF1F1F4), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text("Connect to get live study rooms and the latest features, banners and tips from timevora. Only your chosen display name, room activity and anonymous feature-open counts are sent. Your screen-time and other personal data stay on this phone unless you turn on backup below.", color = Color.Gray, fontSize = 12.sp, lineHeight = 16.sp)

            if (Remote.hasDefault) {
                Button(
                    enabled = !busy,
                    onClick = { connectTo(Remote.DEFAULT_SERVER) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                    shape = styleShape(14),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (busy) "Please wait…" else if (connected) "⟳  Reconnect & refresh" else "⚡  Connect", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                if (connected) TextButton(onClick = { Remote.setBaseUrl(c, ""); url = ""; connected = false; msg = "Disconnected."; onChanged() }) { Text("Disconnect") }
                TextButton(onClick = { showOther = !showOther }) { Text(if (showOther) "Hide other server" else "Use a different server") }
            }

            if (showOther) {
                OutlinedTextField(url, { url = it }, label = { Text("Server address") }, placeholder = { Text("https://your-server.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy && url.isNotBlank(), onClick = { connectTo(url) }) { Text(if (busy) "Please wait…" else "Save & connect") }
                    if (!Remote.hasDefault && connected) TextButton(onClick = { Remote.setBaseUrl(c, ""); url = ""; connected = false; msg = "Disconnected."; onChanged() }) { Text("Disconnect") }
                }
            }

            if (connected) BackupSection(c)
            if (msg.isNotEmpty()) Text(msg, color = if (msg.startsWith("✓")) Color(0xFF1B8E5A) else Navy, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share anonymous feature counts", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 13.sp)
                    Text("Only which feature was opened. Helps improve timevora.", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(checked = share, onCheckedChange = { share = it; Remote.setShareCounts(c, it) })
            }
        }
    }
}

@Composable
internal fun LiveRoomsScreen(c: Context) {
    var configured by remember { mutableStateOf(Remote.isConfigured(c)) }
    var active by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf(readRooms(c)) }
    var name by remember { mutableStateOf(Remote.displayName(c)) }
    var joinCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val current = active
    if (current != null) {
        RoomScreen(c, current) { active = null; joined = readRooms(c) }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("● LIVE", color = Color(0xFFFF6B6B), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text("Study or work together", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("Create a room, share the code, press Start focusing. Everyone in the room sees who is focusing right now and who has the most focus time.", color = Color(0xFFD9E4FF), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }

        if (!configured) {
            Text("To use live rooms, tap Connect once:", color = Navy, fontSize = 13.sp)
            ServerCard(c) { configured = Remote.isConfigured(c) }
        } else {
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Your display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && name.isNotBlank(), onClick = { Remote.setDisplayName(c, name); showCreate = true }, modifier = Modifier.fillMaxWidth()) { Text("＋  Create a room") }

            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(16), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Join a friend's room", fontWeight = FontWeight.Bold, color = Navy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(joinCode, { joinCode = it.filter { ch -> ch.isLetterOrDigit() }.take(6).uppercase() }, label = { Text("6-letter code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.weight(1f))
                        Button(enabled = !busy && joinCode.length == 6 && name.isNotBlank(), onClick = {
                            Remote.setDisplayName(c, name)
                            scope.launch {
                                busy = true; error = ""
                                try {
                                    val r = Remote.call(c, "POST", "/api/rooms/$joinCode/join", JSONObject().put("deviceId", Remote.deviceId(c)).put("name", name.trim()))
                                    rememberRoom(c, RoomRef(r.getString("code"), r.optString("title"), r.optString("type", "study")))
                                    active = r.getString("code"); joinCode = ""
                                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not join" }
                                busy = false
                            }
                        }) { Text("Join") }
                    }
                }
            }
            if (error.isNotEmpty()) Text(error, color = Color(0xFFC62828), fontSize = 12.sp)

            if (joined.isNotEmpty()) {
                Text("MY ROOMS", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
                joined.forEach { r ->
                    Row(Modifier.fillMaxWidth().styled(CardBg, styleShape(14), lite = true).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(roomEmoji(r.type), fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title.ifBlank { "Room" }, fontWeight = FontWeight.Bold, color = Navy)
                            Text("Code ${r.code}", color = Color.Gray, fontSize = 12.sp)
                        }
                        Button(onClick = {
                            scope.launch {
                                // Re-join silently so the room appears even after a server restart or a long break.
                                runCatching { Remote.call(c, "POST", "/api/rooms/${r.code}/join", JSONObject().put("deviceId", Remote.deviceId(c)).put("name", name.ifBlank { "Friend" })) }
                                active = r.code
                            }
                        }) { Text("Open") }
                    }
                }
            }
            ServerCard(c) { configured = Remote.isConfigured(c) }
        }
    }

    if (showCreate) CreateRoomDialog(onDismiss = { showCreate = false }) { title, type ->
        showCreate = false
        scope.launch {
            busy = true; error = ""
            try {
                val r = Remote.call(c, "POST", "/api/rooms", JSONObject().put("deviceId", Remote.deviceId(c)).put("name", name.trim()).put("type", type).put("title", title))
                rememberRoom(c, RoomRef(r.getString("code"), r.optString("title"), r.optString("type", type)))
                active = r.getString("code")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not create the room" }
            busy = false
        }
    }
}

@Composable
private fun CreateRoomDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("study") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create a live room") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipRow(listOf("study" to "📚 Study", "work" to "💼 Work", "focus" to "🎯 Focus sprint"), type) { type = it }
            OutlinedTextField(title, { title = it.take(40) }, label = { Text("Room name (optional)") }, placeholder = { Text("Physics revision") }, singleLine = true)
        }
    }, confirmButton = { Button(onClick = { onCreate(title.trim(), type) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RoomScreen(c: Context, code: String, onExit: () -> Unit) {
    val prefs = remember { c.getSharedPreferences("timevora_rooms", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var room by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("idle") }
    var note by remember { mutableStateOf(prefs.getString("note_$code", "") ?: "") }
    var base by remember { mutableLongStateOf(prefs.getLong("focus_$code", 0L)) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var err by remember { mutableStateOf("") }
    val now = rememberNow()

    fun mySeconds(): Long = base + if (startedAt > 0L) (System.currentTimeMillis() - startedAt) / 1000L else 0L
    fun commit() {
        if (startedAt > 0L) { base += (System.currentTimeMillis() - startedAt) / 1000L; startedAt = 0L }
        prefs.edit().putLong("focus_$code", base).apply()
    }

    val doPing: suspend () -> Unit = {
        try {
            room = Remote.call(c, "POST", "/api/rooms/$code/ping", JSONObject()
                .put("deviceId", Remote.deviceId(c)).put("name", Remote.displayName(c).ifBlank { "Friend" })
                .put("status", status).put("note", note).put("focusSeconds", mySeconds()))
            err = ""
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            err = if ((e.message ?: "").contains("not found", true)) "This room was closed. Go back and create or join another." else "Offline. Retrying…"
        }
    }
    LaunchedEffect(code) { while (true) { doPing(); delay(5000) } }
    LaunchedEffect(status) { doPing() }
    DisposableEffect(code) { onDispose { commit() } }

    val r = room
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = HeroBg), shape = styleShape(20), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${roomEmoji(r?.optString("type") ?: "study")}  ${r?.optString("title").takeUnless { it.isNullOrBlank() } ?: "Live room"}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("CODE", color = Color(0xFFB9C8E8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(code, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp)
                Text("${r?.optInt("onlineCount", 1) ?: 1} online now", color = Color(0xFF7CE0B0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val msg = "Join my timevora live ${r?.optString("type") ?: "study"} room!\nRoom code: $code\n\n1) Install timevora\n2) Open Friends > Live rooms\n3) Enter the code and tap Join" +
                            (if (Remote.baseUrl(c).isNotEmpty() && Remote.DEFAULT_SERVER.isEmpty()) "\n\nServer address to paste in Settings: ${Remote.baseUrl(c)}" else "")
                        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Share room code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, colors = ButtonDefaults.buttonColors(containerColor = AccentPink)) { Text("Share code") }
                    OutlinedButton(onClick = {
                        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("timevora room", code))
                    }) { Text("Copy", color = Color.White) }
                }
            }
        }

        if (err.isNotEmpty()) Text(err, color = Color(0xFFC62828), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = styleShape(18), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YOUR FOCUS IN THIS ROOM", color = AccentPink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(Fmt.hms((if (now > 0) mySeconds() else 0L) * 1000L), color = Navy, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text(when (status) { "focusing" -> "🟢 Focusing. Friends can see you."; "break" -> "🟡 On a break"; else -> "⚪ Not started" }, color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(note, { note = it.take(60); prefs.edit().putString("note_$code", note).apply() }, label = { Text("What are you working on?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status != "focusing") Button(onClick = { startedAt = System.currentTimeMillis(); status = "focusing" }, modifier = Modifier.weight(1f)) { Text("▶  Start focusing") }
                    else {
                        Button(onClick = { commit(); status = "break" }, modifier = Modifier.weight(1f)) { Text("⏸  Take a break") }
                        OutlinedButton(onClick = { commit(); status = "idle" }, modifier = Modifier.weight(1f)) { Text("■  Stop") }
                    }
                }
                Text("Time counts only while you press Start focusing. Keep this screen open so friends see you live.", color = Color.Gray, fontSize = 10.sp)
            }
        }

        Text("WHO IS IN THE ROOM", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
        val members = r?.optJSONArray("members")
        if (members == null) Text("Loading room…", color = Color.Gray)
        else for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            val st = m.optString("status")
            val dot = when (st) { "focusing" -> "🟢"; "break" -> "🟡"; "idle" -> "⚪"; else -> "⚫" }
            val label = when (st) { "focusing" -> "Focusing"; "break" -> "On a break"; "idle" -> "Online"; else -> "Offline" }
            val secs = if (m.optBoolean("me")) mySeconds() else m.optLong("focusSeconds")
            Row(Modifier.fillMaxWidth().styled(if (m.optBoolean("me")) SoftPink else CardBg, styleShape(14), lite = true).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Navy.copy(alpha = .4f), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.optString("name") + (if (m.optBoolean("me")) " (You)" else "") + (if (m.optBoolean("owner")) " 👑" else ""), fontWeight = FontWeight.Bold, color = Navy)
                    Text("$dot $label" + (m.optString("note").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = Color.Gray, fontSize = 12.sp)
                }
                Text(Fmt.hms(secs * 1000L), color = AccentPink, fontWeight = FontWeight.ExtraBold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { commit(); onExit() }, modifier = Modifier.weight(1f)) { Text("‹ All rooms") }
            TextButton(onClick = {
                scope.launch {
                    commit()
                    runCatching { Remote.call(c, "POST", "/api/rooms/$code/leave", JSONObject().put("deviceId", Remote.deviceId(c))) }
                    forgetRoom(c, code)
                    prefs.edit().remove("focus_$code").remove("note_$code").apply()
                    onExit()
                }
            }) { Text("Leave room", color = Color(0xFFC62828)) }
        }
    }
}
