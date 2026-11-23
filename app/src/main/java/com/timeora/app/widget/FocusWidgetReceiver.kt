package com.timevora.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.timevora.app.audio.AmbientPlayer
import com.timevora.app.notifications.FocusManager
import com.timevora.app.notifications.FocusSettings
import com.timevora.app.notifications.Pomodoro

/**
 * Buttons of the Focus-timer widget. Starts a normal focus session (so it grows a tree and counts as focus time)
 * or the Pomodoro cycle last used on the Focus screen, without opening the app.
 */
class FocusWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        if (intent.action != ACTION_START && intent.action != ACTION_POMODORO) return

        if (FocusManager.isActive(c) || Pomodoro.inBreak(c)) {
            Toast.makeText(c, "A focus session is already running", Toast.LENGTH_SHORT).show()
            timevoraWidgetProvider.requestRefresh(c)
            return
        }
        val strict = FocusSettings.strict(c)
        if (intent.action == ACTION_POMODORO) {
            val work = FocusSettings.workMinutes(c)
            val rounds = FocusSettings.rounds(c)
            Pomodoro.begin(c, work, Pomodoro.breakFor(work), rounds, "", strict)
            Toast.makeText(c, "Pomodoro started: $work/${Pomodoro.breakFor(work)} min x $rounds 🍅", Toast.LENGTH_SHORT).show()
        } else {
            val minutes = intent.getIntExtra(EXTRA_MINUTES, 25).coerceIn(1, 600)
            Pomodoro.endCycle(c)
            FocusManager.start(c, minutes, "", strict)
            Toast.makeText(c, "Focus started: $minutes min 🌳", Toast.LENGTH_SHORT).show()
        }
        // A chosen ambient sound starts with the session. Android may refuse this from the background; that is fine.
        try { AmbientPlayer.savedSound(c)?.let { if (AmbientPlayer.current != it) AmbientPlayer.play(c, it) } } catch (_: Exception) {}
        timevoraWidgetProvider.requestRefresh(c)
    }

    companion object {
        const val ACTION_START = "com.timevora.app.widget.FOCUS_START"
        const val ACTION_POMODORO = "com.timevora.app.widget.FOCUS_POMODORO"
        const val EXTRA_MINUTES = "minutes"
    }
}
