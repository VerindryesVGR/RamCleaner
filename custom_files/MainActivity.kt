package com.example.ram_cleaner

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "ram_cleaner/native"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getAvailableRamMb" -> result.success(getAvailableRam())
                    "getTotalRamMb" -> result.success(getTotalRam())
                    "cleanAll" -> result.success(cleanAll())
                    "clearZram" -> result.success(clearZram())
                    "toggleAutoClean" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val threshold = call.argument<Int>("threshold") ?: 80
                        toggleAutoClean(enabled, threshold)
                        result.success(true)
                    }
                    "updateThreshold" -> {
                        val t = call.argument<Int>("threshold") ?: 80
                        AutoCleanService.updateThreshold(t)
                        getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE).edit().putInt("threshold", t).apply()
                        result.success(true)
                    }
                    "updateInterval" -> {
                        val v = call.argument<Int>("interval") ?: 0
                        AutoCleanService.updateInterval(v)
                        getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE).edit().putInt("interval_min", v).apply()
                        if (v > 0) {
                            val intent = Intent(this, AutoCleanService::class.java)
                            intent.putExtra("interval", v)
                            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                        }
                        result.success(true)
                    }
                    "isServiceRunning" -> result.success(isServiceRunning())
                    "hasUsagePermission" -> result.success(hasUsagePermission())
                    "requestUsagePermission" -> {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        result.success(false)
                    }
                    "isIgnoringBattery" -> result.success(isIgnoringBattery())
                    "requestIgnoreBattery" -> {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                        result.success(true)
                    }
                    "getInstalledApps" -> result.success(getInstalledApps())
                    "setFocusMode" -> {
                        setFocusMode(call.argument<Boolean>("enabled") ?: false, call.argument<String>("package"), call.argument<String>("label"))
                        result.success(true)
                    }
                    "getFocusMode" -> result.success(getFocusMode())
                    else -> result.notImplemented()
                }
            }
    }

    private fun getTotalRam(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.totalMem / 1024 / 1024).toInt()
    }

    private fun getAvailableRam(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.availMem / 1024 / 1024).toInt()
    }

    private fun isIgnoringBattery(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (AutoCleanService::class.java.name == service.service.className) return true
        }
        return false
    }

    private fun hasUsagePermission(): Boolean = try {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun clearZram(): Map<String, Any> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val miBefore = ActivityManager.MemoryInfo()
        am.getMemoryInfo(miBefore)
        val availBefore = miBefore.availMem / 1024 / 1024

        cleanAll()
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo 3 > /proc/sys/vm/drop_caches")).waitFor() } catch (_: Exception) {}
        try { Thread.sleep(500) } catch (_: Exception) {}

        val miAfter = ActivityManager.MemoryInfo()
        am.getMemoryInfo(miAfter)
        val availAfter = miAfter.availMem / 1024 / 1024
        return mapOf("availBefore" to availBefore, "availAfter" to availAfter, "freed" to (availAfter - availBefore))
    }

    private fun cleanAll(): Int {
        val pm = packageManager
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val vpn = getVpnPackages(pm)
        val launcher = getLauncherPackage(pm)
        val ime = getCurrentImePackage()
        var count = 0
        for (pi in pm.getInstalledPackages(0)) {
            val pkg = pi.packageName
            if (pkg == packageName || pkg in vpn || pkg == launcher || pkg == ime) continue
            if (pkg in AutoCleanService.PROTECTED) continue
            try { am.killBackgroundProcesses(pkg); count++ } catch (_: Exception) {}
        }
        return count
    }

    private fun getVpnPackages(pm: PackageManager): Set<String> = try {
        pm.getPackagesHoldingPermissions(arrayOf("android.permission.BIND_VPN_SERVICE"), PackageManager.GET_PERMISSIONS)
            .map { it.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun getLauncherPackage(pm: PackageManager): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    private fun getCurrentImePackage(): String? = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore("/")
    } catch (_: Exception) { null }

    private fun getInstalledApps(): List<Map<String, String>> {
        val pm = packageManager
        val result = mutableListOf<Map<String, String>>()
        for (pi in pm.getInstalledPackages(0)) {
            if (pi.packageName == packageName) continue
            try {
                val label = pm.getApplicationLabel(pi.applicationInfo).toString()
                result.add(mapOf("package" to pi.packageName, "label" to label))
            } catch (_: Exception) {}
        }
        result.sortBy { it["label"] }
        return result
    }

    private fun setFocusMode(enabled: Boolean, pkg: String?, label: String?) {
        getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE).edit().apply {
            putBoolean("focus_enabled", enabled)
            if (pkg != null) putString("focus_package", pkg)
            if (label != null) putString("focus_label", label)
            apply()
        }
    }

    private fun getFocusMode(): Map<String, Any?> {
        val prefs = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE)
        return mapOf(
            "enabled" to prefs.getBoolean("focus_enabled", false),
            "package" to prefs.getString("focus_package", null),
            "label" to prefs.getString("focus_label", null)
        )
    }

    private fun toggleAutoClean(enabled: Boolean, threshold: Int) {
        getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE).edit().putBoolean("auto_clean", enabled).putInt("threshold", threshold).apply()
        AutoCleanService.updateThreshold(threshold)
        val intent = Intent(this, AutoCleanService::class.java)
        if (enabled) {
            intent.putExtra("threshold", threshold)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } else {
            val interval = getSharedPreferences("ram_cleaner", Context.MODE_PRIVATE).getInt("interval_min", 0)
            if (interval == 0) stopService(intent)
        }
    }
}
