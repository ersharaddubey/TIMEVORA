package com.timevora.app.notifications

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.timevora.app.MainActivity
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.loadPlan
import com.timevora.app.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.*
import java.util.concurrent.TimeUnit

class DailyReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("timevora", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("morning_report", true)) return Result.success()
        val yesterday = LocalDate.now().minusDays(1).toString()
        val dao = timevoraDatabase.get(applicationContext).usageDao()
        val rows = dao.daily(yesterday)
        val total = rows.sumOf { it.durationMillis }
        val summary = if (rows.isEmpty()) "No usage records were available for yesterday." else rows.take(3).joinToString("  ") { "${it.appLabel}: ${Fmt.hms(it.durationMillis)}" }
        val most = rows.firstOrNull()?.appLabel ?: "No app data"
        val planCount = loadPlan(dao, LocalDate.now()).size
        val planText = if (planCount > 0) " Today you have $planCount item(s) in your diary plan." else ""
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("daily_report", "Daily reports", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(applicationContext, 2, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "daily_report").setSmallIcon(android.R.drawable.ic_menu_report_image).setContentTitle("Good Morning! Your timevora Daily Report").setContentText("Yesterday: ${Fmt.hms(total)}. Most-used: $most.").setStyle(NotificationCompat.BigTextStyle().bigText("Yesterday you spent ${Fmt.hms(total)} on your phone. $summary Your most-used app was $most.$planText Make today more productive!")).setContentIntent(intent).setAutoCancel(true).build()
        manager.notify(yesterday.hashCode(), notification)
        ReportScheduler.schedule(applicationContext)
        return Result.success()
    }
}

object ReportScheduler {
    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences("timevora", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("morning_report", true)) { WorkManager.getInstance(context).cancelUniqueWork("timevora_daily_report"); return }
        val hour = prefs.getInt("report_hour", 8); val minute = prefs.getInt("report_minute", 0)
        val now = ZonedDateTime.now(); var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val request = OneTimeWorkRequestBuilder<DailyReportWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("timevora_daily_report", ExistingWorkPolicy.REPLACE, request)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ReportScheduler.schedule(context)
        SleepRoutine.schedule(context)
        WeeklyWrapped.schedule(context)
        QuickCapture.refresh(context)
        val prefs = context.getSharedPreferences("timevora", Context.MODE_PRIVATE)
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && prefs.getBoolean("tracking_enabled", false)) {
            val service = Intent(context, com.timevora.app.tracking.UsageTrackingService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, service)
        }
        // Diary reminders and a running focus timer are lost on reboot / update / clock change, so set them up again.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PlannerAlarms.rescheduleAll(context)
                FocusManager.restore(context)
            } finally {
                pending.finish()
            }
        }
    }
}
