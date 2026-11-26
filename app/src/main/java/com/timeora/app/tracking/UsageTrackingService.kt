package com.timevora.app.tracking

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.timevora.app.data.todayKey
import com.timevora.app.MainActivity
import com.timevora.app.data.AppCategorizer
import com.timevora.app.data.AppCategory
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.UsageSession
import com.timevora.app.notifications.Channels
import com.timevora.app.util.Fmt
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Calendar

class UsageTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null
    private var currentPackage: String? = null
    private var currentLabel = ""
    private var sessionStart = 0L
    private var lastEventTime = 0L
    private var lastLimitCheck = 0L
    private var lastNotifUpdate = 0L
    private var breakNotifiedFor = 0L
    private var lastFocusNudge = 0L
    private var bubble: View? = null
    private var windowManager: WindowManager? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var pauseView: View? = null
    private var pauseTick: Runnable? = null
    private val pauseUntilByPackage = mutableMapOf<String, Long>()
    private val trackingState by lazy { getSharedPreferences("timevora_tracking_state", MODE_PRIVATE) }
    @Volatile private var screenOn = true
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    // Save the active session immediately; do not keep polling while display is off.
                    scope.launch { runCatching { closeSession(System.currentTimeMillis()) } }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // Reconstruct the foreground app when the display becomes interactive again.
                    scope.launch { runCatching { initCurrentApp() } }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Channels.ensure(this)
        startForeground(41, buildNotification("Usage tracking is active"))
        screenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, screenFilter)
        }
        if (screenOn) initCurrentApp()
        showBubbleIfAllowed()
        trackingJob = scope.launch {
            while (isActive) {
                if (!screenOn) {
                    // Low-frequency wait only; UsageStats polling and UI/notification work stay paused.
                    delay(5000)
                    continue
                }
                try {
                    pollUsageEvents()
                    updateBubble()
                    val now = System.currentTimeMillis()
                    if (currentPackage != null && sessionStart > 0L && now - sessionStart >= 30_000L) checkpointSession(now)
                    if (now - lastLimitCheck >= 30_000L) { lastLimitCheck = now; checkTimeLimits(); checkDailyGoal() }
                    if (now - lastNotifUpdate >= 5_000L) { lastNotifUpdate = now; refreshOngoingNotification() }
                    checkBreak(now)
                    statusError = ""
                } catch (e: Exception) {
                    statusError = e.javaClass.simpleName + ": " + (e.message ?: "")
                }
                writeStatus()
                delay(1000)
            }
        }
    }

    private var bubbleState = "not started"
    private var statusError = ""
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun writeStatus() {
        getSharedPreferences("timevora_status", MODE_PRIVATE).edit()
            .putLong("beat", System.currentTimeMillis())
            .putString("pkg", currentLabel)
            .putString("bubble", bubbleState)
            .putString("error", statusError)
            .putLong("live_start", if (currentPackage != null) sessionStart else 0L)
            .putString("live_pkg", currentPackage ?: "")
            .putString("live_label", currentLabel)
            .apply()
    }

    /** Whether time spent inside timevora itself is counted (Settings, default on). */
    private fun countSelf(): Boolean = getSharedPreferences("timevora", MODE_PRIVATE).getBoolean("count_self", true)

    private fun labelFor(pkg: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }

    // Reconstruct the foreground app on service restart. Resume from the last
    // durable checkpoint when possible so a process restart does not count time twice.
    private fun initCurrentApp() {
        try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = now - 24 * 60 * 60 * 1000L
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            var activePackage: String? = null
            var activeSince = 0L
            var maxTs = now - 8_000L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.timeStamp + 1 > maxTs) maxTs = e.timeStamp + 1
                val eventPkg = e.packageName ?: continue
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> {
                        activePackage = eventPkg
                        activeSince = e.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (eventPkg == activePackage) {
                            activePackage = null
                            activeSince = 0L
                        }
                    }
                }
            }
            lastEventTime = maxTs
            val found = activePackage
            if (found != null && (found != packageName || countSelf())) {
                currentPackage = found
                currentLabel = labelFor(found)
                val savedPackage = trackingState.getString("package", null)
                val savedAt = trackingState.getLong("checkpoint", 0L)
                val resumeAt = if (savedPackage == found && savedAt in from..now) savedAt else activeSince
                sessionStart = maxOf(resumeAt, startOfToday()).coerceAtMost(now)
            } else {
                trackingState.edit().clear().commit()
            }
        } catch (_: Exception) {
            lastEventTime = System.currentTimeMillis() - 8_000L
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "HIDE_BUBBLE") { removeBubble() }
        if (intent?.action == "SHOW_BUBBLE") showBubbleIfAllowed()
        if (intent?.action == "REFRESH_BUBBLE") { removeBubble(); showBubbleIfAllowed() }
        if (intent?.action == null) showBubbleIfAllowed()
        return START_STICKY
    }

    private suspend fun pollUsageEvents() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastEventTime == 0L) now - 8000 else lastEventTime
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        var maxTs = from
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp + 1 > maxTs) maxTs = event.timeStamp + 1
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg != packageName || countSelf()) {
                        if (pkg != currentPackage) {
                            closeSession(event.timeStamp)
                            currentPackage = pkg
                            currentLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
                            sessionStart = event.timeStamp
                            maybeFocusNudge()
                            maybeShowOpeningPause(pkg)
                        }
                    } else {
                        closeSession(event.timeStamp)
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (pkg == currentPackage) closeSession(event.timeStamp)
            }
        }
        lastEventTime = maxTs
    }

    /**
     * A brief, dismissible overlay adds friction after an app has opened.
     * Android does not allow ordinary apps to veto another app's launch, so this
     * is a mindful interruption rather than a hard app lock.
     */
    private fun maybeShowOpeningPause(pkg: String) {
        val prefs = getSharedPreferences("timevora", MODE_PRIVATE)
        if (pkg == packageName) return
        val focusPrefs = getSharedPreferences("timevora_focus", MODE_PRIVATE)
        val focusEnd = focusPrefs.getLong("end", 0L)
        val shieldOn = prefs.getBoolean("focus_shield_enabled", false) && focusEnd > 0L
        val targets = prefs.getString("focus_shield_packages", "")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val whitelist = prefs.getString("focus_shield_whitelist", "")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val shieldTarget = shieldOn && targets.isNotEmpty() && pkg in targets && pkg !in whitelist
        if (!prefs.getBoolean("opening_pause", false) && !shieldTarget) return
        if (shieldTarget) {
            val logPrefs = getSharedPreferences("timevora_distraction_log", MODE_PRIVATE)
            val previous = logPrefs.getString("events", "") ?: ""
            val entry = "${System.currentTimeMillis()}|${pkg}"
            logPrefs.edit().putString("events", (previous.lines().filter { it.isNotBlank() } + entry).takeLast(100).joinToString("\n")).apply()
        }
        if (!Settings.canDrawOverlays(this) || pauseView != null) return
        val now = System.currentTimeMillis()
        val cooldownMinutes = prefs.getInt("opening_pause_cooldown_minutes", 1).coerceIn(0, 60)
        if ((pauseUntilByPackage[pkg] ?: 0L) > now) return
        pauseUntilByPackage[pkg] = now + cooldownMinutes * 60_000L
        val seconds = prefs.getInt("opening_pause_seconds", 5).coerceIn(3, 15)
        val app = currentLabel.ifBlank { labelFor(pkg) }
        mainHandler.post {
            if (pauseView != null || !Settings.canDrawOverlays(this)) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val panel = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(34, 30, 34, 30)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(18, 28, 52))
                    cornerRadius = 34f
                    setStroke(2, Color.rgb(90, 120, 180))
                }
                elevation = 18f
            }
            val title = TextView(this).apply {
                text = if (shieldTarget) "Focus Shield" else "A small pause"
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val message = TextView(this).apply {
                text = if (shieldTarget) "You opened $app during your focus session. Want to return to your mission?" else "You opened $app. Is this what you intended to do?"
                textSize = 16f
                setTextColor(Color.rgb(220, 228, 245))
                gravity = Gravity.CENTER
                setPadding(0, 18, 0, 18)
            }
            val timer = TextView(this).apply {
                text = "Take a breath · $seconds"
                textSize = 17f
                setTextColor(Color.rgb(160, 190, 255))
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 18)
            }
            val continueButton = android.widget.Button(this).apply {
                text = "Continue"
                isEnabled = false
                setOnClickListener { removePauseOverlay() }
            }
            val homeButton = android.widget.Button(this).apply {
                text = "Go back"
                setOnClickListener {
                    removePauseOverlay()
                    try {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
            }
            panel.addView(title)
            panel.addView(message)
            panel.addView(timer)
            panel.addView(continueButton)
            panel.addView(homeButton)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                dimAmount = 0.72f
            }
            try {
                wm.addView(panel, params)
                pauseView = panel
                var left = seconds
                val tick = object : Runnable {
                    override fun run() {
                        if (pauseView !== panel) return
                        left--
                        if (left <= 0) {
                            timer.text = "Ready when you are"
                            continueButton.isEnabled = true
                        } else {
                            timer.text = "Take a breath · $left"
                            mainHandler.postDelayed(this, 1000)
                        }
                    }
                }
                pauseTick = tick
                mainHandler.postDelayed(tick, 1000)
            } catch (_: Exception) {
                pauseView = null
            }
        }
    }

    private fun removePauseOverlay() {
        val cleanup = Runnable {
            pauseTick?.let { mainHandler.removeCallbacks(it) }
            pauseTick = null
            val view = pauseView
            if (view != null) {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) { }
                pauseView = null
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) cleanup.run()
        else mainHandler.post(cleanup)
    }

    private suspend fun persistSessionRange(pkg: String, label: String, start: Long, end: Long) {
        if (end <= start) return
        var cursor = start
        while (cursor < end) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = cursor
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val segmentEnd = minOf(end, cal.timeInMillis)
            withContext(NonCancellable) {
                timevoraDatabase.get(this@UsageTrackingService).usageDao().insert(
                    UsageSession(
                        packageName = pkg,
                        appLabel = label,
                        day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(cursor)),
                        startMillis = cursor,
                        endMillis = segmentEnd,
                        durationMillis = segmentEnd - cursor
                    )
                )
            }
            cursor = segmentEnd
        }
    }

    // Persist a bounded chunk every 30 seconds. If Android kills the process,
    // the uncheckpointed gap is small and the next service instance resumes here.
    private suspend fun checkpointSession(now: Long) {
        val pkg = currentPackage ?: return
        val start = sessionStart
        if (start <= 0L || now <= start) return
        persistSessionRange(pkg, currentLabel, start, now)
        sessionStart = now
        trackingState.edit()
            .putString("package", pkg)
            .putLong("checkpoint", now)
            .commit()
        writeStatus()
    }

    private suspend fun closeSession(end: Long) {
        val pkg = currentPackage ?: return
        val start = sessionStart
        persistSessionRange(pkg, currentLabel, start, end)
        currentPackage = null
        currentLabel = ""
        sessionStart = 0L
        trackingState.edit().clear().commit()
        writeStatus()
    }


    private suspend fun checkTimeLimits() {
        checkCategoryLimits()
        val dao = timevoraDatabase.get(this).usageDao()
        val prefs = getSharedPreferences("timevora_limit_alerts", MODE_PRIVATE)
        val today = todayKey()
        for (limit in dao.limits()) {
            if (!limit.enabled || limit.limitMinutes <= 0) continue
            val stored = dao.appTotal(today, limit.packageName)
            val live = if (currentPackage == limit.packageName && sessionStart > 0) (System.currentTimeMillis() - maxOf(sessionStart, startOfToday())).coerceAtLeast(0) else 0L
            val used = stored + live
            val percent = (used / 60000L * 100L / limit.limitMinutes).toInt()
            val thresholds = listOf(50, 80, 100)
            for (threshold in thresholds) {
                val enabled = when (threshold) { 50 -> limit.alert50; 80 -> limit.alert80; else -> limit.alert100 }
                val key = "$today:${limit.packageName}:$threshold"
                if (enabled && percent >= threshold && !prefs.getBoolean(key, false)) {
                    prefs.edit().putBoolean(key, true).apply()
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("limit_alerts", "App time-limit alerts", NotificationManager.IMPORTANCE_DEFAULT))
                    val notification = NotificationCompat.Builder(this, "limit_alerts")
                        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                        .setContentTitle(if (threshold == 100) "${limit.appLabel} daily limit reached" else "${limit.appLabel} usage alert")
                        .setContentText("${limit.appLabel}: ${formatDuration(used)} of ${limit.limitMinutes} minutes (${threshold}%).")
                        .setAutoCancel(true).build()
                    nm.notify((limit.packageName + threshold + today).hashCode(), notification)
                }
            }
        }
    }

    /** Category limits: all apps of a category share one daily budget (e.g. Social total 60 min). */
    private suspend fun checkCategoryLimits() {
        val dao = timevoraDatabase.get(this).usageDao()
        val limits = dao.categoryLimits().filter { it.enabled && it.limitMinutes > 0 }
        if (limits.isEmpty()) return
        AppCategorizer.ensureLoaded(this)
        val prefs = getSharedPreferences("timevora_limit_alerts", MODE_PRIVATE)
        val today = todayKey()
        val totals = HashMap<String, Long>()
        dao.rangeApps(today, today).forEach { row ->
            val cat = AppCategorizer.of(this, row.packageName, row.appLabel).name
            totals[cat] = (totals[cat] ?: 0L) + row.durationMillis
        }
        val livePkg = currentPackage
        if (livePkg != null && sessionStart > 0L) {
            val cat = AppCategorizer.of(this, livePkg, currentLabel).name
            val live = (System.currentTimeMillis() - maxOf(sessionStart, startOfToday())).coerceAtLeast(0L)
            totals[cat] = (totals[cat] ?: 0L) + live
        }
        for (limit in limits) {
            val label = AppCategory.fromName(limit.category)?.label ?: limit.category
            val used = totals[limit.category] ?: 0L
            val percent = (used / 60000L * 100L / limit.limitMinutes).toInt()
            for (threshold in listOf(50, 80, 100)) {
                val enabled = when (threshold) { 50 -> limit.alert50; 80 -> limit.alert80; else -> limit.alert100 }
                val key = "$today:cat:${limit.category}:$threshold"
                if (enabled && percent >= threshold && !prefs.getBoolean(key, false)) {
                    prefs.edit().putBoolean(key, true).apply()
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("limit_alerts", "App time-limit alerts", NotificationManager.IMPORTANCE_DEFAULT))
                    val notification = NotificationCompat.Builder(this, "limit_alerts")
                        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                        .setContentTitle(if (threshold == 100) "$label daily limit reached" else "$label usage alert")
                        .setContentText("All $label apps together: ${formatDuration(used)} of ${limit.limitMinutes} minutes (${threshold}%).")
                        .setAutoCancel(true).build()
                    nm.notify(("cat" + limit.category + threshold + today).hashCode(), notification)
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String = Fmt.hms(ms)

    private fun liveToday(): Long =
        if (currentPackage != null && sessionStart > 0L) (System.currentTimeMillis() - maxOf(sessionStart, startOfToday())).coerceAtLeast(0L) else 0L

    private suspend fun checkDailyGoal() {
        val goal = getSharedPreferences("timevora", MODE_PRIVATE).getInt("goal_minutes", 0)
        if (goal <= 0) return
        val total = timevoraDatabase.get(this).usageDao().total(todayKey()) + liveToday()
        val alerts = getSharedPreferences("timevora_limit_alerts", MODE_PRIVATE)
        val today = todayKey()
        val goalMs = goal * 60_000L
        if (total >= goalMs) {
            val key = "$today:goal100"
            if (!alerts.getBoolean(key, false)) {
                alerts.edit().putBoolean(key, true).apply()
                Channels.post(this, Channels.GOAL, 44, "Daily screen-time goal reached", "You have used ${Fmt.hms(total)} today. Your goal was ${Fmt.hms(goalMs)}.")
            }
        } else if (total >= goalMs * 8 / 10) {
            val key = "$today:goal80"
            if (!alerts.getBoolean(key, false)) {
                alerts.edit().putBoolean(key, true).apply()
                Channels.post(this, Channels.GOAL, 44, "80% of your daily goal used", "${Fmt.hms(goalMs - total)} left before your ${Fmt.hms(goalMs)} goal.")
            }
        }
    }

    private fun checkBreak(now: Long) {
        val minutes = getSharedPreferences("timevora", MODE_PRIVATE).getInt("break_minutes", 30)
        val pkg = currentPackage ?: return
        if (minutes <= 0 || sessionStart <= 0L) return
        if (now - sessionStart < minutes * 60_000L) return
        if (breakNotifiedFor == sessionStart) return
        breakNotifiedFor = sessionStart
        Channels.post(this, Channels.BREAKS, 45, "Time for a short break", "You have been in $currentLabel for ${Fmt.hms(now - sessionStart)} without a break. Rest your eyes for a minute.")
    }

    private fun maybeFocusNudge() {
        if (currentPackage == packageName) return
        val now = System.currentTimeMillis()
        val end = getSharedPreferences("timevora_focus", MODE_PRIVATE).getLong("end", 0L)
        if (end <= now) return
        if (!getSharedPreferences("timevora", MODE_PRIVATE).getBoolean("focus_nudge", true)) return
        if (now - lastFocusNudge < 30_000L) return
        lastFocusNudge = now
        val left = ((end - now) / 60_000L).toInt() + 1
        Channels.post(this, Channels.FOCUS, 43, "Your focus tree is in danger", "$left min left. You just opened $currentLabel. Your tree withers if you spend more than 15 seconds in other apps. Head back?")
    }

    /** Keeps the status-bar notification live: today's total plus the app in use right now. */
    private suspend fun refreshOngoingNotification() {
        val total = timevoraDatabase.get(this).usageDao().total(todayKey()) + liveToday()
        val text = "Today ${Fmt.hms(total)}" + (if (currentPackage != null) "  |  Now: $currentLabel" else "")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(41, buildNotification(text))
    }
    private fun startOfToday(): Long { val c = java.util.Calendar.getInstance(); c.set(java.util.Calendar.HOUR_OF_DAY,0); c.set(java.util.Calendar.MINUTE,0); c.set(java.util.Calendar.SECOND,0); c.set(java.util.Calendar.MILLISECOND,0); return c.timeInMillis }

    private fun updateBubble() {
        val elapsed = if (sessionStart > 0) (System.currentTimeMillis() - sessionStart).coerceAtLeast(0L) else 0L
        val text = "◷ " + Fmt.clock(elapsed)
        mainHandler.post { (bubble as? TextView)?.text = text }
    }

    private fun showBubbleIfAllowed() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) { bubbleState = "overlay permission missing"; return }
        if (!getSharedPreferences("timevora", MODE_PRIVATE).getBoolean("floating", true)) { bubbleState = "turned off in Settings"; return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("timevora", MODE_PRIVATE)
        val scale = prefs.getFloat("bubble_scale", 1f).coerceIn(0.75f, 1.5f)
        val alpha = prefs.getFloat("bubble_alpha", 0.86f).coerceIn(0.35f, 1f)
        val side = prefs.getString("bubble_side", "right") ?: "right"
        val bg = GradientDrawable().apply { setColor(Color.argb(220, 16, 27, 54)); cornerRadius = 40f }
        val tv = TextView(this).apply { id = 1001; text = "◷ 00:00"; textSize = 12f * scale; setTextColor(Color.WHITE); setPadding((18*scale).toInt(), (10*scale).toInt(), (18*scale).toInt(), (10*scale).toInt()); background = bg; this.alpha = alpha; elevation = 10f }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val gravitySide = if (side == "left") Gravity.LEFT else Gravity.RIGHT
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or gravitySide; x = prefs.getInt("bubble_x", 12); y = prefs.getInt("bubble_y", 220) }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        tv.setOnTouchListener { _, e -> when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; startX=params.x; startY=params.y; true }
            MotionEvent.ACTION_MOVE -> { params.x=(startX + (if(side=="left") e.rawX-downX else downX-e.rawX).toInt()).coerceAtLeast(0); params.y=(startY + e.rawY-downY).toInt().coerceAtLeast(0); windowManager?.updateViewLayout(tv, params); true }
            MotionEvent.ACTION_UP -> { if (kotlin.math.abs(e.rawX-downX)<12 && kotlin.math.abs(e.rawY-downY)<12) { val i=Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) } else {
                val width = resources.displayMetrics.widthPixels
                val newSide = if (e.rawX < width/2f) "left" else "right"
                params.gravity = Gravity.TOP or (if (newSide=="left") Gravity.LEFT else Gravity.RIGHT)
                params.x = kotlin.math.abs(params.x); prefs.edit().putString("bubble_side",newSide).putInt("bubble_x",params.x).putInt("bubble_y",params.y).apply()
                windowManager?.updateViewLayout(tv,params)
            }; true }
            else -> true
        } }
        bubble = tv; bubbleParams = params
        try { windowManager?.addView(tv, params); bubbleState = "shown" } catch (e: Exception) { bubble = null; bubbleState = "error: " + e.javaClass.simpleName }
    }

    private fun removeBubble() { try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}; bubble = null }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tracking", "timevora tracking", NotificationManager.IMPORTANCE_LOW)) }
    private fun buildNotification(text: String) = NotificationCompat.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("timevora is tracking").setContentText(text).setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).setOnlyAlertOnce(true).build()
    override fun onDestroy() {
        removePauseOverlay()
        runCatching {
            runBlocking(Dispatchers.IO) {
                trackingJob?.cancelAndJoin()
                closeSession(System.currentTimeMillis())
            }
        }
        runCatching { unregisterReceiver(screenStateReceiver) }
        mainHandler.removeCallbacksAndMessages(null)
        removeBubble()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
