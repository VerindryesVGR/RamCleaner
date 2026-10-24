package com.example.ram_cleaner

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

class AutoCleanService : Service() {
    companion object {
        @Volatile var isRunning = false; private set
        @Volatile var thresholdPercent = 80
        @Volatile var intervalMinutes = 0
        const val ACTION_CLEAN_NOW = "com.example.ram_cleaner.CLEAN_NOW"
        const val ACTION_CLEAR_ZRAM = "com.example.ram_cleaner.CLEAR_ZRAM"
        fun updateThreshold(v: Int) { thresholdPercent = v }
        fun updateInterval(v: Int) { intervalMinutes = v }
        val PROTECTED = setOf("android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell", "com.android.server.telecom", "com.google.android.gms", "com.google.android.gsf", "com.google.android.gms.persistent", "com.google.android.gms.ui")
    }

    private var ramTimer: Timer? = null
    private var focusTimer: Timer? = null
    private var intervalTimer: Timer? = null
    private var lastFocusKill = 0L
    private val FOCUS_DEBOUNCE = 20_000L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
        thresholdPercent = prefs.getInt("threshold", 80)
        intervalMinutes = prefs.getInt("interval_min", 0)
        startForeground(1, buildNotification())

        ramTimer = Timer()
        ramTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { try { checkRamAndClean() } catch (_: Exception) {} }
        }, 5000L, 10000L)

        focusTimer = Timer()
        focusTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { try { checkFocusMode() } catch (_: Exception) {} }
        }, 3000L, 3000L)
        setupIntervalTimer()
    }

    private fun setupIntervalTimer() {
        intervalTimer?.cancel()
        if (intervalMinutes > 0) {
            intervalTimer = Timer()
            intervalTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
                        val focusEnabled = prefs.getBoolean("focus_enabled", false)
                        val focusPkg = prefs.getString("focus_package", null)
                        val fg = getForegroundApp()
                        val except = if (focusEnabled && fg == focusPkg) focusPkg else null
                        clean(except, force = false)
                    } catch (_: Exception) {}
                }
            }, intervalMinutes * 60_000L, intervalMinutes * 60_000L)
        }
    }

    private fun getForegroundApp(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 15_000L, now)
            val e = UsageEvents.Event()
            var lastPkg: String? = null
            var lastTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp > lastTime) {
                    lastTime = e.timeStamp; lastPkg = e.packageName
                }
            }
            lastPkg
        } catch (_: Exception) { null }
    }

    private fun getRamRatio(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return 1.0 - (mi.availMem.toDouble() / mi.totalMem.toDouble())
    }

    private fun checkFocusMode() {
        val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("focus_enabled", false)) return
        val focusPkg = prefs.getString("focus_package", null) ?: return
        val fg = getForegroundApp() ?: return
        if (fg != focusPkg) return
        val now = System.currentTimeMillis()
        if (now - lastFocusKill < FOCUS_DEBOUNCE) return
        if (getRamRatio() * 100 < thresholdPercent) return
        lastFocusKill = now
        clean(exceptPkg = focusPkg, force = false)
    }

    private fun checkRamAndClean() {
        val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_clean", false)) return
        if (getRamRatio() * 100 < thresholdPercent) return
        val focusEnabled = prefs.getBoolean("focus_enabled", false)
        val focusPkg = prefs.getString("focus_package", null)
        val fg = getForegroundApp()
        val except = if (focusEnabled && fg == focusPkg) focusPkg else null
        clean(exceptPkg = except, force = false)
    }

    private fun clean(exceptPkg: String?, force: Boolean) {
        val pm = packageManager
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val vpn = getVpnPackages(pm)
        val launcher = getLauncherPackage(pm)
        val ime = getImePackage()
        val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
        for (pi in pm.getInstalledPackages(0)) {
            val pkg = pi.packageName
            if (pkg == packageName || pkg == exceptPkg) continue
            if (pkg in vpn || pkg == launcher || pkg == ime || pkg in PROTECTED) continue
            val lastKilled = prefs.getLong("killed_$pkg", 0L)
            if (!force && System.currentTimeMillis() - lastKilled < 5 * 60 * 1000L) continue
            try {
                am.killBackgroundProcesses(pkg)
                prefs.edit().putLong("killed_$pkg", System.currentTimeMillis()).apply()
            } catch (_: Exception) {}
        }
    }

    private fun getVpnPackages(pm: PackageManager): Set<String> = try {
        pm.getPackagesHoldingPermissions(arrayOf("android.permission.BIND_VPN_SERVICE"), PackageManager.GET_PERMISSIONS).map { it.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun getLauncherPackage(pm: PackageManager): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    private fun getImePackage(): String? = try {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore("/")
    } catch (_: Exception) { null }

    private fun buildNotification(): Notification {
        val channelId = "ram_cleaner_auto"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "Auto Clean", NotificationManager.IMPORTANCE_LOW))
        }
        val cleanIntent = Intent(this, AutoCleanService::class.java).apply { action = ACTION_CLEAN_NOW }
        val pendingIntent = PendingIntent.getService(this, 0, cleanIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val zramIntent = Intent(this, AutoCleanService::class.java).apply { action = ACTION_CLEAR_ZRAM }
        val zramPending = PendingIntent.getService(this, 1, zramIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("RAM Force Cleaner aktif")
            .setContentText("Memantau RAM & Timer...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .addAction(android.R.drawable.ic_menu_delete, "Bersihkan RAM", pendingIntent)
            .addAction(android.R.drawable.ic_menu_agenda, "Clear ZRAM", zramPending)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAN_NOW) {
            val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
            val except = if (prefs.getBoolean("focus_enabled", false) && getForegroundApp() == prefs.getString("focus_package", null)) prefs.getString("focus_package", null) else null
            clean(exceptPkg = except, force = true)
        } else if (intent?.action == ACTION_CLEAR_ZRAM) {
            val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
            val except = if (prefs.getBoolean("focus_enabled", false) && getForegroundApp() == prefs.getString("focus_package", null)) prefs.getString("focus_package", null) else null
            clean(exceptPkg = except, force = true)
            try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo 3 > /proc/sys/vm/drop_caches")).waitFor() } catch (_: Exception) {}
        }
        intent?.getIntExtra("threshold", 80)?.let { thresholdPercent = it }
        intent?.getIntExtra("interval", 0)?.let { intervalMinutes = it; setupIntervalTimer() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        ramTimer?.cancel(); focusTimer?.cancel(); intervalTimer?.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
