package com.timevora.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.timevora.app.QuickCaptureActivity
import com.timevora.app.data.PlannerItem
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.widget.timevoraWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Quick Capture: add a task or a diary note in one step, without opening the Planner tab.
 * Entry points: the pinned notification (inline typing), the home-screen widget, app shortcuts and the capture screen (with voice).
 */
object QuickCapture {
    const val PREF_NOTIFICATION = "quick_capture_notification"
    private const val NOTIF_ID = 88
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val lock = Any()

    /** Adds a task for today (or [plusDays] later), with no time and no reminder. Returns false for blank text. */
    suspend fun saveTask(c: Context, text: String, plusDays: Long = 0L): Boolean {
        val title = text.trim().replace(Regex("\\s+"), " ").take(120)
        if (title.isEmpty()) return false
        val item = PlannerItem(
            title = title, notes = "", kind = "task",
            startDay = LocalDate.now().plusDays(plusDays).toString(),
            minuteOfDay = -1, repeatMode = "none", remindBefore = -1,
            createdAt = System.currentTimeMillis()
        )
        timevoraDatabase.get(c).usageDao().savePlanner(item)
        timevoraWidgetProvider.requestRefresh(c)
        return true
    }

    /** Adds a line to today's diary note, stamped with the time. Earlier text in the note is kept. */
    fun saveNote(c: Context, text: String): Boolean {
        val line = text.trim()
        if (line.isEmpty()) return false
        synchronized(lock) {
            val notes = c.getSharedPreferences("timevora_notes", Context.MODE_PRIVATE)
            val key = LocalDate.now().toString()
            val old = notes.getString(key, "")?.trimEnd() ?: ""
            val entry = "${LocalTime.now().format(timeFmt)}  $line"
            notes.edit().putString(key, if (old.isEmpty()) entry else "$old\n$entry").commit()
        }
        return true
    }

    fun isNotificationOn(c: Context): Boolean = c.getSharedPreferences("timevora", Context.MODE_PRIVATE).getBoolean(PREF_NOTIFICATION, false)

    fun setNotificationOn(c: Context, on: Boolean) {
        c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit().putBoolean(PREF_NOTIFICATION, on).apply()
        if (on) showNotification(c) else hideNotification(c)
    }

    /** Re-posts the pinned notification if the person turned it on (after reboot, update, or if it was swiped away). */
    fun refresh(c: Context) { if (isNotificationOn(c)) showNotification(c) }

    fun hideNotification(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun capturePending(c: Context, code: Int, mode: String, voice: Boolean): PendingIntent =
        PendingIntent.getActivity(
            c, code,
            QuickCaptureActivity.intent(c, mode, voice),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun replyAction(c: Context, code: Int, action: String, label: String, hint: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(QuickCaptureReceiver.KEY_TEXT).setLabel(hint).build()
        val pi = PendingIntent.getBroadcast(
            c, code,
            Intent(c, QuickCaptureReceiver::class.java).setAction(action),
            // Inline reply needs a mutable PendingIntent so Android can add the typed text.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Action.Builder(0, label, pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    /** [status] is a short confirmation such as "Task added: Buy milk"; null shows the normal text. */
    fun showNotification(c: Context, status: String? = null) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        Channels.ensure(c)
        val n = NotificationCompat.Builder(c, Channels.QUICK)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("⚡ Quick capture")
            .setContentText(status ?: "Add a task or note without opening timevora")
            .setContentIntent(capturePending(c, 21, "task", false))
            .addAction(replyAction(c, 22, QuickCaptureReceiver.ACTION_TASK, "＋ Task", "Type a task"))
            .addAction(replyAction(c, 23, QuickCaptureReceiver.ACTION_NOTE, "📝 Note", "Type a note"))
            .addAction(0, "🎤 Voice", capturePending(c, 24, "task", true))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
}

/** Receives the text typed into the notification's inline reply box and saves it right away. */
class QuickCaptureReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TASK = "com.timevora.app.QC_TASK"
        const val ACTION_NOTE = "com.timevora.app.QC_NOTE"
        const val KEY_TEXT = "qc_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString().orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val shortText = if (text.trim().length > 40) text.trim().take(40) + "…" else text.trim()
                val status = when {
                    text.isBlank() -> null
                    intent.action == ACTION_NOTE -> if (QuickCapture.saveNote(context, text)) "Note saved: $shortText" else null
                    else -> if (QuickCapture.saveTask(context, text)) "Task added: $shortText" else null
                }
                // Posting again also stops the "sending" spinner on the reply box.
                QuickCapture.showNotification(context, status)
            } finally {
                pending.finish()
            }
        }
    }
}
