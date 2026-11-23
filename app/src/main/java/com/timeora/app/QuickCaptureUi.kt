package com.timevora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.timevora.app.notifications.QuickCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Small capture sheet that floats over whatever you were doing (home screen, another app).
 * Opened from the pinned notification, the widget buttons and the app-icon shortcuts.
 * Type or speak, press Save: a task goes to today's (or tomorrow's) plan, a note is added to today's diary note.
 */
class QuickCaptureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VOICE = "voice"

        fun intent(c: Context, mode: String, voice: Boolean): Intent =
            Intent(c, QuickCaptureActivity::class.java)
                .setAction("com.timevora.app.QUICK_CAPTURE")
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_VOICE, voice)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private var mode by mutableStateOf("task")
    private var heard by mutableStateOf<String?>(null)
    private var openKeyboard by mutableStateOf(true)
    private var voiceTick by mutableIntStateOf(0)

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeState.reload(this)
        readIntent(intent)
        setContent {
            MaterialTheme(colorScheme = ThemeState.colorScheme()) {
                CaptureSheet()
            }
        }
        if (savedInstanceState == null && wantsVoice(intent)) window.decorView.post { startVoice() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        if (wantsVoice(intent)) window.decorView.post { startVoice() }
    }

    private fun wantsVoice(i: Intent?): Boolean = i?.getBooleanExtra(EXTRA_VOICE, false) == true || i?.getStringExtra(EXTRA_VOICE) == "true"

    private fun readIntent(i: Intent?) {
        mode = if (i?.getStringExtra(EXTRA_MODE) == "note") "note" else "task"
        openKeyboard = !wantsVoice(i)
    }

    /** Opens Android's own speech input. No microphone permission is needed in timevora, and nothing is recorded by the app. */
    private fun startVoice() {
        val lang = getSharedPreferences("timevora", MODE_PRIVATE).getString("qc_lang", "en-IN") ?: "en-IN"
        val speak = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (mode == "note") "Speak your note" else "Speak your task")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            voiceLauncher.launch(speak)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice input is not available on this phone. You can still type.", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun CaptureSheet() {
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf("") }
        var day by remember { mutableIntStateOf(0) } // 0 = today, 1 = tomorrow
        var lang by remember { mutableStateOf(getSharedPreferences("timevora", MODE_PRIVATE).getString("qc_lang", "en-IN") ?: "en-IN") }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(heard) {
            heard?.let { h -> text = (text.trim() + " " + h).trim(); heard = null }
        }
        LaunchedEffect(openKeyboard) {
            if (openKeyboard) { delay(150); focus.requestFocus(); keyboard?.show() }
        }

        fun save() {
            val t = text
            if (t.isBlank()) return
            scope.launch {
                val ok = if (mode == "note") QuickCapture.saveNote(this@QuickCaptureActivity, t) else QuickCapture.saveTask(this@QuickCaptureActivity, t, day.toLong())
                if (ok) {
                    com.timevora.app.widget.timevoraWidgetProvider.requestRefresh(this@QuickCaptureActivity)
                    Toast.makeText(this@QuickCaptureActivity, if (mode == "note") "Note saved ✓" else if (day == 1) "Task added for tomorrow ✓" else "Task added ✓", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        Box(
            Modifier.fillMaxSize().background(Color(0x66000000)).windowInsetsPadding(WindowInsets.safeDrawing)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = styleShape(22),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡ Quick capture", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                    ChipRow(listOf("task" to "＋ Task", "note" to "📝 Note"), mode) { mode = it }
                    if (mode == "task") ChipRow(listOf(0 to "Today", 1 to "Tomorrow"), day) { day = it }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(if (mode == "note") 600 else 120) },
                        label = { Text(if (mode == "note") "Note for today's diary" else "What needs doing?") },
                        minLines = if (mode == "note") 3 else 1,
                        maxLines = 6,
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(imeAction = if (mode == "note") ImeAction.Default else ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (mode == "task") save() }),
                        trailingIcon = { IconButton(onClick = { startVoice() }) { Icon(Icons.Default.Mic, "Speak", tint = AccentPink) } },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                    Text("🎤 Voice language", fontSize = 12.sp, color = Color.Gray)
                    ChipRow(listOf("en-IN" to "English", "hi-IN" to "हिन्दी"), lang) {
                        lang = it
                        getSharedPreferences("timevora", MODE_PRIVATE).edit().putString("qc_lang", it).apply()
                    }
                    Button(onClick = { save() }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (mode == "note") "Save note" else "Add task")
                    }
                }
            }
        }
    }
}
