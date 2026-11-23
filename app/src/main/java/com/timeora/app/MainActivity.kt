package com.timevora.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.timevora.app.data.AppCategorizer
import com.timevora.app.data.AppUsageTotal
import com.timevora.app.data.FeatureInfo
import com.timevora.app.data.Remote
import com.timevora.app.data.timevoraDatabase
import com.timevora.app.data.todayKey
import com.timevora.app.notifications.FocusManager
import com.timevora.app.notifications.PlannerAlarms
import com.timevora.app.notifications.ReportScheduler
import com.timevora.app.notifications.WeeklyWrapped
import com.timevora.app.tracking.UsageTrackingService
import com.timevora.app.widget.timevoraWidgetProvider
import com.timevora.app.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) exportUsageCsv(uri) }

    /** Set when a notification asks to open a specific screen (e.g. "Wrapped"). Consumed once by timevoraApp. */
    private var launchTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchTab = intent?.getStringExtra(WeeklyWrapped.EXTRA_TAB)
        setContent {
            timevoraApp(this, launchTab = launchTab, onLaunchTabHandled = { launchTab = null },
                onExport = { exportDocument.launch("timevora-usage-${LocalDate.now()}.csv") })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(WeeklyWrapped.EXTRA_TAB)?.let { launchTab = it }
    }

    override fun onResume() {
        super.onResume()
        timevoraWidgetProvider.requestRefresh(this)
        com.timevora.app.notifications.QuickCapture.refresh(this)
        // Keep diary reminders and the focus alarm armed (alarms can be dropped by the system or a permission change).
        lifecycleScope.launch {
            PlannerAlarms.rescheduleAll(this@MainActivity)
            FocusManager.restore(this@MainActivity)
            com.timevora.app.notifications.SleepRoutine.schedule(this@MainActivity)
            WeeklyWrapped.schedule(this@MainActivity)
            // Applies a Streak Freeze for a missed day and unlocks badges earned while the app was closed.
            com.timevora.app.data.RewardEngine.evaluateAchievements(this@MainActivity)
            // Opt-in backup of the user's data to the timevora server (does nothing unless switched on in Settings; throttled to once per 15 min).
            if (com.timevora.app.data.Sync.enabled(this@MainActivity)) {
                com.timevora.app.data.Sync.schedule(this@MainActivity)
                com.timevora.app.data.Sync.run(this@MainActivity)
            }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openNotificationSettings()
    }

    fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    private fun exportUsageCsv(uri: Uri) {
        lifecycleScope.launch {
            val dao = timevoraDatabase.get(this@MainActivity).usageDao()
            val start = LocalDate.now().minusDays(89)
            val end = LocalDate.now()
            AppCategorizer.ensureLoaded(this@MainActivity)
            val csv = buildString {
                append("Date,App,Package,DurationSeconds,Duration,Sessions,Category\n")
                var day = start
                while (!day.isAfter(end)) {
                    dao.daily(day.toString()).forEach { row ->
                        val safe = row.appLabel.replace("\"", "\"\"")
                        append("${day},\"${safe}\",${row.packageName},${row.durationMillis / 1000},${Fmt.hms(row.durationMillis)},${row.sessions},${AppCategorizer.of(this@MainActivity, row.packageName, row.appLabel).label}\n")
                    }
                    day = day.plusDays(1)
                }
            }
            runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) } }
        }
    }
}

@Composable
fun timevoraApp(activity: MainActivity, launchTab: String? = null, onLaunchTabHandled: () -> Unit = {}, onExport: () -> Unit) {
    val context = activity
    val prefs = remember { context.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(if (prefs.getBoolean("privacy_ack", false)) "Home" else "Setup") }
    var featureReturnTab by remember { mutableStateOf("Home") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var activeFeatureId by remember { mutableStateOf<String?>(null) }
    var timeSavingTool by remember { mutableStateOf<String?>(null) }
    var introBackTab by remember { mutableStateOf("Home") }
    val catalog = rememberCatalog(context, refresh)
    val featureTabs = remember { setOf("Planner", "Focus", "Habits", "Study", "Insights", "Friends", "Notifications", "Sleep", "Pause", "Wrapped", "TimeSaving") }
    fun featureRoute(route: String): String = when (route) {
        "Planner", "Focus", "Habits", "Study", "Friends", "Insights", "Notifications", "Sleep", "Pause", "Wrapped", "TimeSaving" -> route
        else -> "Profile"
    }
    fun openFeature(f: FeatureInfo) {
        featureReturnTab = if (tab == "More") "More" else "Home"
        activeFeatureId = f.id
        introBackTab = featureReturnTab
        if (f.id.startsWith("ts_") && f.route == "TimeSaving") {
            // Individual time-saving tool from More list → open tool directly
            timeSavingTool = f.id.removePrefix("ts_")
            tab = "TimeSaving"
            prefs.edit().putBoolean("intro_seen_${f.id}", true).apply()
        } else if (f.route == "TimeSaving" || f.id == "timesaving") {
            // Hub entry from Home shortcut — list of tools, not a single tool
            timeSavingTool = null
            tab = "TimeSaving"
            prefs.edit().putBoolean("intro_seen_timesaving", true).apply()
        } else {
            timeSavingTool = null
            tab = if (prefs.getBoolean("intro_seen_${f.id}", false) && f.route != "Info") featureRoute(f.route) else "Intro:${f.id}"
        }
        refresh++
        FeatureRank.recordOpen(context, f.id)
        scope.launch { Remote.trackOpen(context, f.id) }
    }
    fun startFocusWithLabel(taskLabel: String) {
        prefs.edit().putString("pending_focus_label", taskLabel).apply()
        featureReturnTab = "Home"
        tab = "Focus"
        refresh++
    }
    fun showWhy(f: FeatureInfo) {
        introBackTab = if (tab in featureTabs) tab else (if (tab == "More") "More" else "Home")
        if (tab !in featureTabs) featureReturnTab = introBackTab
        activeFeatureId = f.id
        tab = "Intro:${f.id}"
    }
    var showPrivacy by remember { mutableStateOf(!prefs.getBoolean("privacy_ack", false)) }
    var showOnboarding by remember { mutableStateOf(prefs.getBoolean("privacy_ack", false) && !prefs.getBoolean("onboarding_v1_done", false)) }
    var onboardingStep by remember { mutableIntStateOf(0) }
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var floating by remember { mutableStateOf(prefs.getBoolean("floating", true)) }
    // Colours (light / dark): loaded before the first frame so there is no colour flash.
    remember { ThemeState.reload(context); 0 }
    val pal = ThemeState.palette
    val darkMode = pal.dark
    var rows by remember { mutableStateOf(emptyList<AppUsageTotal>()) }
    var total by remember { mutableLongStateOf(0L) }

    // Opened from the Sunday notification: jump straight to the Wrapped cards (once the privacy/setup step is done).
    LaunchedEffect(launchTab) {
        if (launchTab == "Wrapped" && prefs.getBoolean("privacy_ack", false)) {
            featureReturnTab = "Home"
            activeFeatureId = "wrapped"
            tab = "Wrapped"
            refresh++
        }
        if (launchTab == "Focus" && prefs.getBoolean("privacy_ack", false)) {
            featureReturnTab = "Home"
            tab = "Focus"
            refresh++
        }
        if (launchTab != null) onLaunchTabHandled()
    }

    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageAccess(context)
                overlayGranted = Settings.canDrawOverlays(context)
                refresh++
                if (prefs.getBoolean("tracking_enabled", false)) startTracking(context, floating && Settings.canDrawOverlays(context))
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }
    // Refresh only the Home usage summary while Home is visible. Other tabs no longer
    // trigger a global database refresh every few seconds.
    LaunchedEffect(tab, usageGranted) {
        if (tab == "Home" && usageGranted) {
            while (true) {
                delay(30_000)
                val dao = timevoraDatabase.get(context).usageDao()
                rows = dao.daily(todayKey())
                total = dao.total(todayKey())
            }
        }
    }
    LaunchedEffect(refresh, usageGranted) {
        usageGranted = hasUsageAccess(context)
        overlayGranted = Settings.canDrawOverlays(context)
        if (usageGranted) {
            val dao = timevoraDatabase.get(context).usageDao()
            rows = dao.daily(todayKey())
            total = dao.total(todayKey())
        }
    }

    MaterialTheme(colorScheme = ThemeState.colorScheme()) {
        if (showPrivacy) PrivacyDialog {
            prefs.edit().putBoolean("privacy_ack", true).apply()
            showPrivacy = false
            if (!prefs.getBoolean("onboarding_v1_done", false)) showOnboarding = true
        }
        if (showOnboarding && !showPrivacy) {
            FirstRunTipsDialog(
                step = onboardingStep,
                onNext = {
                    if (onboardingStep < 2) onboardingStep++
                    else {
                        prefs.edit().putBoolean("onboarding_v1_done", true).apply()
                        showOnboarding = false
                        // Step 3 complete → open Smart Task Dump as the natural first action
                        timeSavingTool = "dump"
                        tab = "TimeSaving"
                        refresh++
                    }
                },
                onSkip = {
                    prefs.edit().putBoolean("onboarding_v1_done", true).apply()
                    showOnboarding = false
                }
            )
        }
        Scaffold(
            containerColor = SurfaceBg,
            bottomBar = {
                NavigationBar(containerColor = CardBg) {
                    listOf("Home" to Icons.Default.Home, "Planner" to Icons.Default.Book, "Focus" to Icons.Default.Timer, "More" to Icons.Default.MoreHoriz)
                        .forEach { (name, icon) ->
                            NavigationBarItem(
                                selected = if (name == "More") tab !in listOf("Home", "Planner", "Focus", "Setup") else tab == name,
                                onClick = { featureReturnTab = "Home"; tab = name; refresh++ },
                                icon = { Icon(icon, null) }, label = { Text(name) }
                            )
                        }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().background(CardBg).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("timevora", color = AccentPink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                        Text("YOUR TIME. YOUR CONTROL.", color = Navy.copy(alpha = .62f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Text(if (darkMode) "☾" else "☀", color = AccentPink, fontSize = 23.sp, modifier = Modifier.clickable {
                        ThemeStore.setDark(context, !ThemeStore.darkMode(context)); ThemeState.reload(context)
                        timevoraWidgetProvider.requestRefresh(context)
                    }.padding(6.dp))
                }
                if (tab in featureTabs) {
                    val bar = catalog.features.firstOrNull { it.id == activeFeatureId && it.route == tab } ?: catalog.features.firstOrNull { it.route == tab }
                    if (bar != null) FeatureTopBar(bar, onBack = { tab = featureReturnTab; refresh++ }, onWhy = { showWhy(bar) })
                }
                when (tab) {
                    "Setup" -> SetupScreen(activity) { startTracking(context, floating && Settings.canDrawOverlays(context)); tab = "Home"; refresh++ }
                    "Home" -> HomeScreen(
                        context, usageGranted, overlayGranted, total, rows, statusText(context), refresh, catalog,
                        onGrantUsage = { tab = "Setup" },
                        onGrantOverlay = { tab = "Setup" },
                        onStart = { startTracking(context, floating && overlayGranted); refresh++ },
                        onOpenDiary = { tab = "Planner" },
                        onOpenFeature = { feature -> openFeature(feature) },
                        onShowWhy = { feature -> showWhy(feature) },
                        onStartFocusWithLabel = { label -> startFocusWithLabel(label) }
                    )
                    "Planner" -> DiaryScreen(context, refresh, onChanged = { refresh++ })
                    "Habits" -> HabitsScreen(context, refresh, onChanged = { refresh++ })
                    "Study" -> ExamModeScreen(context, refresh, onChanged = { refresh++ })
                    "Focus" -> FocusScreen(context, refresh, onChanged = { refresh++ })
                    "TimeSaving" -> TimeSavingScreen(context, refresh, onBack = { tab = featureReturnTab; timeSavingTool = null; refresh++ }, onChanged = { refresh++ }, initialTool = timeSavingTool)
                    "Insights" -> ReportsScreen(context, onExport, refresh)
                    "Wrapped" -> WrappedScreen(context, refresh)
                    "Friends" -> FriendsChallengesScreen(context, refresh)
                    "More" -> FeatureHubScreen(catalog) { feature -> openFeature(feature) }
                    "Notifications" -> NotificationCenterScreen(context, refresh, onRequestPermission = { activity.requestNotificationPermission() }, onOpenSettings = { activity.openNotificationSettings() })
                    else -> if (tab.startsWith("Intro:")) {
                        val featureId = tab.removePrefix("Intro:")
                        val info = catalog.features.firstOrNull { it.id == featureId }
                        if (info == null) { tab = "Profile" } else FeatureIntroScreen(info, onBack = { tab = introBackTab }, onOpen = {
                            prefs.edit().putBoolean("intro_seen_${info.id}", true).apply()
                            tab = if (info.route == "Info") featureReturnTab else featureRoute(info.route)
                            refresh++
                        })
                    } else SettingsScreen(
                        context, refresh,
                        scrollTo = when (tab) { "Sleep" -> "sleep"; "Pause" -> "pause"; else -> null },
                        floating = floating,
                        setFloating = {
                            floating = it
                            prefs.edit().putBoolean("floating", it).apply()
                            if (it && usageGranted) startTracking(context, overlayGranted)
                            else if (!it) context.startService(Intent(context, UsageTrackingService::class.java).setAction("HIDE_BUBBLE"))
                        },
                        overlay = overlayGranted,
                        grantOverlay = { openOverlaySettings(context) },
                        onClear = { scope.launch { timevoraDatabase.get(context).usageDao().clearAll(); refresh++ } },
                        onOpenNotifications = { tab = "Notifications"; refresh++ },
                        onExport = onExport,
                        onChanged = { refresh++ },
                        onOpenSetup = { tab = "Setup" }
                    )
                }
            }
        }
    }
}

private fun hasUsageAccess(c: Context): Boolean {
    val appOps = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29)
        appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.packageName)
    else
        appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.packageName)
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

private fun startTracking(c: Context, showBubble: Boolean) {
    if (!hasUsageAccess(c)) return
    c.getSharedPreferences("timevora", Context.MODE_PRIVATE).edit().putBoolean("tracking_enabled", true).apply()
    ReportScheduler.schedule(c)
    val i = Intent(c, UsageTrackingService::class.java)
    if (showBubble) i.action = "SHOW_BUBBLE"
    ContextCompat.startForegroundService(c, i)
}

private fun openOverlaySettings(c: Context) {
    try { c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))) }
    catch (_: Exception) {
        try { c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        catch (_: Exception) { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))) }
    }
}

private fun statusText(c: Context): String {
    val p = c.getSharedPreferences("timevora_status", Context.MODE_PRIVATE)
    val beat = p.getLong("beat", 0L)
    val alive = beat > 0L && System.currentTimeMillis() - beat < 8000L
    val now = p.getString("pkg", "") ?: ""
    val err = p.getString("error", "") ?: ""
    val bubble = p.getString("bubble", "not started") ?: "not started"
    return "Tracking service: " + (if (alive) "running" else "NOT running") +
        "\nCurrent app: " + (if (now.isEmpty()) "none" else now) +
        "\nFloating timer: " + bubble +
        (if (err.isEmpty()) "" else "\nError: $err")
}
