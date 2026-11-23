package com.timevora.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private val SetupNavy = Color(0xFF0B1530)
private val SetupGreen = Color(0xFF176B42)

/** Permission checks and "open the right settings page" helpers, with fallbacks for many phone brands. */
object timevoraPerms {
    fun usage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.packageName)
        else
            ops.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun overlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun notifications(c: Context): Boolean {
        val runtimeOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimeOk && NotificationManagerCompat.from(c).areNotificationsEnabled()
    }

    fun battery(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    fun missing(c: Context): Int = listOf(usage(c), overlay(c), notifications(c), battery(c)).count { !it }

    private fun pkgUri(c: Context): Uri = Uri.parse("package:" + c.packageName)

    private fun appDetails(c: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))

    private fun tryStart(c: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                c.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun openUsage(c: Context) {
        tryStart(
            c,
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri(c)),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            appDetails(c)
        )
    }

    fun openOverlay(c: Context) {
        tryStart(
            c,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c)),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            appDetails(c)
        )
    }

    fun openNotificationSettings(c: Context) {
        tryStart(
            c,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName),
            appDetails(c)
        )
    }

    fun openBattery(c: Context) {
        tryStart(
            c,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(c)
        )
    }

    // brand keywords, settings package, settings screen (known "autostart / background" pages)
    private val oemScreens = listOf(
        Triple("xiaomi|redmi|poco", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Triple("oppo|realme", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Triple("oppo|realme", "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Triple("oppo|realme", "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        Triple("vivo|iqoo", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Triple("vivo|iqoo", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        Triple("huawei|honor", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        Triple("huawei|honor", "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        Triple("samsung", "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        Triple("oneplus", "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        Triple("asus", "com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        Triple("tecno|infinix|itel", "com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
    )

    fun openAutostart(c: Context) {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        for ((keys, pkg, cls) in oemScreens) {
            if (keys.split("|").any { brand.contains(it) }) {
                val i = Intent().setComponent(ComponentName(pkg, cls))
                if (tryStart(c, i)) return
            }
        }
        tryStart(c, appDetails(c))
    }
}

/** One screen for every permission. "Grant all" walks through each Android settings page automatically. */
@Composable
fun SetupScreen(activity: ComponentActivity, onDone: () -> Unit) {
    val c: Context = activity
    val prefs = remember { c.getSharedPreferences("timevora", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    var flow by remember { mutableStateOf(false) }
    var lastMissing by remember { mutableIntStateOf(0) }
    var autostartDone by remember { mutableStateOf(prefs.getBoolean("autostart_done", false)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun askNotifications() {
        val runtimeOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!runtimeOk && !prefs.getBoolean("notif_asked", false)) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            timevoraPerms.openNotificationSettings(c)
        }
    }

    fun openAutostartOnce() {
        autostartDone = true
        prefs.edit().putBoolean("autostart_done", true).apply()
        timevoraPerms.openAutostart(c)
    }

    fun launchNext() {
        lastMissing = timevoraPerms.missing(c)
        if (!timevoraPerms.usage(c)) timevoraPerms.openUsage(c)
        else if (!timevoraPerms.overlay(c)) timevoraPerms.openOverlay(c)
        else if (!timevoraPerms.notifications(c)) askNotifications()
        else if (!timevoraPerms.battery(c)) timevoraPerms.openBattery(c)
        else if (!autostartDone) openAutostartOnce()
        else flow = false
    }

    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                tick++
                if (flow) {
                    val m = timevoraPerms.missing(c)
                    if (m < lastMissing || (m == 0 && !autostartDone)) launchNext() else flow = false
                }
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    val u = remember(tick) { timevoraPerms.usage(c) }
    val o = remember(tick) { timevoraPerms.overlay(c) }
    val n = remember(tick) { timevoraPerms.notifications(c) }
    val b = remember(tick) { timevoraPerms.battery(c) }
    val enabled = listOf(u, o, n, b).count { it }
    val allDone = enabled == 4 && autostartDone

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setup", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Turn everything on from this one screen. Android shows each permission on its own page, so tap the button below and timevora will take you through them one by one.",
            color = Color.Gray,
            fontSize = 13.sp
        )
        Card(colors = CardDefaults.cardColors(containerColor = SetupNavy), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("$enabled of 4 permissions enabled", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    if (allDone) "Everything is ready." else "Tap Grant all, switch timevora ON on each page, then press Back.",
                    color = Color(0xFFD9E4FF),
                    fontSize = 12.sp
                )
            }
        }
        Button(
            onClick = { if (allDone) onDone() else { flow = true; launchNext() } },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (allDone) "All set. Start tracking" else "Grant all permissions") }

        PermRow("Usage access", "Needed to see which app is open and count your screen time.", u) { timevoraPerms.openUsage(c) }
        PermRow("Display over other apps", "Shows the floating mini timer on top of YouTube, Instagram and other apps.", o) { timevoraPerms.openOverlay(c) }
        PermRow("Notifications", "Daily report, diary and meeting reminders, focus alerts, time-limit alerts and the live tracking notification.", n) { askNotifications() }
        PermRow("Unrestricted battery", "Stops the phone from closing timevora in the background. On the page that opens, choose All apps, then timevora, then Don't optimize.", b) { timevoraPerms.openBattery(c) }
        PermRow(
            "Autostart / background activity",
            "Lets tracking restart after reboot (Xiaomi, Realme, Oppo, Vivo, Samsung, OnePlus and others). Android cannot tell us if this is on, so switch timevora ON on the page that opens.",
            autostartDone,
            okLabel = "Opened"
        ) { openAutostartOnce() }
    }
}

@Composable
private fun PermRow(title: String, body: String, ok: Boolean, okLabel: String = "On", onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Navy)
                Text(body, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(10.dp))
            if (ok) {
                Text("✓ $okLabel", color = SetupGreen, fontWeight = FontWeight.Bold)
            } else {
                OutlinedButton(onClick = onClick) { Text("Enable") }
            }
        }
    }
}

@Composable
fun PrivacyDialog(onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your data stays on your phone", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "timevora records which apps you use, plus your diary notes and reports, only on this phone. Your screen-time and diary data is never uploaded.\n\nOptional online features (live study rooms, updated feature text) connect to a timevora server only if a server address is set. Then only your chosen display name, room activity and anonymous feature-open counts are sent. You can switch the counts off or disconnect any time in Settings.\n\nAapka app usage sirf is phone mein save hota hai. Live rooms ke liye sirf aapka naam aur room ki activity server par jaati hai."
            )
        },
        confirmButton = { Button(onClick = onOk) { Text("I understand") } }
    )
}
