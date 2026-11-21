package com.example.studyunlock

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

internal fun hasUsageAccess(context: Context): Boolean =
    context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
        AppOpsManager.MODE_ALLOWED

internal fun monitorEnabled(context: Context): Boolean {
    val name = ComponentName(context, StudyMonitor::class.java)
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == name }
}

class StudyMonitor : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { StudyStore.get(this) }
    private val preferences by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: View? = null
    private var overlayKey = ""
    private var previousWall = 0L
    private var previousElapsed = 0L
    private var lastHeartbeat = 0L
    private var lastPolicy = 0L
    private var usageGranted = false
    private var lastPackage: String? = null
    private val protected by lazy { protectedApps(this) }
    private var segmentDay: LocalDate? = null
    private var segmentTask = ""
    private var segmentApp: String? = null
    private var segmentStudy = false
    private var segmentMs = 0L
    private var focusSeconds = 0
    private var nextCheck = Random.nextInt(20, 41) * 60
    private var verificationPending = false
    private var verificationSince = 0L
    private var verificationFailed = false
    private var verificationDay: LocalDate? = null
    private var verificationTask = ""
    private var splitActive = false
    private var allowedApp: String? = null
    private var allowance = 0L

    private val ticker = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 450)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(ticker)
        val previous = preferences.getLong("last", 0L)
        val now = System.currentTimeMillis()
        store.markMonitoringGap(previous, now)
        connected.value = true
        handler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) sample()
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        if (previousElapsed > 0 && elapsed - previousElapsed < 100) return
        val oldWall = previousWall
        val gap = elapsed - previousElapsed
        val clockJump = oldWall > 0 && abs(now - oldWall - gap) > 5_000
        previousWall = now
        previousElapsed = elapsed
        val key = store.date(now)
        val day = store.today()
        val task = day?.taskId().orEmpty()
        if (clockJump) {
            store.event("系统时间变更")
            store.incomplete("日期记录不完整", store.date(oldWall))
            store.incomplete("日期记录不完整", key)
        }
        if (elapsed - lastPolicy >= 1_000 || lastPolicy == 0L) {
            usageGranted = hasUsageAccess(this)
            lastPolicy = elapsed
        }
        if (!usageGranted) store.incomplete("使用情况权限未开启", key)
        if (elapsed - lastHeartbeat >= 10_000 || lastHeartbeat == 0L) {
            preferences.edit().putLong("last", now).apply()
            lastHeartbeat = elapsed
        }

        val unlockedScreen = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val appWindows = if (unlockedScreen) windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } else emptyList()
        val primary = appWindows.firstOrNull { it.isActive } ?: appWindows.firstOrNull { it.isFocused }
            ?: appWindows.maxByOrNull { it.layer }
        val split = appWindows.size > 1
        if (split && !splitActive && day != null) store.event("分屏或画中画，暂停计时")
        splitActive = split
        val foreground = if (split || !unlockedScreen) null else
            (primary?.root ?: rootInActiveWindow)?.packageName?.toString()
        if (foreground != lastPackage && lastPackage != null && day?.packages("study")?.contains(lastPackage) == true) {
            store.event("离开学习应用")
        }

        if (verificationDay != key || verificationTask != task) {
            verificationDay = key
            verificationTask = task
            verificationPending = false
            focusSeconds = 0
            nextCheck = Random.nextInt(20, 41) * 60
        }
        val study = usageGranted && foreground != null && day?.packages("study")?.contains(foreground) == true &&
            !verificationPending && foreground !in protected && day.isLearning() &&
            store.remainingPass(day) == 0L
        val entertainment = foreground != null && day?.packages("entertainment")?.contains(foreground) == true &&
            foreground !in protected
        val passEnd = day?.takeIf { store.remainingPass(it) > 0 }?.optLong("pauseUntil") ?: 0L
        if (allowance != passEnd || (foreground != null && foreground != allowedApp) || split) allowedApp = null
        allowance = passEnd
        val blocked = if (entertainment && day?.isLearning() == true) {
            if (passEnd == 0L) true
            else if (allowedApp == foreground) false
            else if (store.usePassOpening(day)) {
                allowedApp = foreground
                false
            } else true
        } else false
        val app = if (study || (entertainment && !blocked && usageGranted)) foreground else null
        val continued = app == segmentApp && key == segmentDay && task == segmentTask && study == segmentStudy
        if (segmentApp != null && oldWall > 0 && !clockJump && gap in 1..2_000) {
            val end = segmentDay?.let { store.endOfDay(it) } ?: now
            segmentMs += (minOf(now, end) - oldWall).coerceIn(0, gap)
        }
        if (!continued || segmentMs >= 3_000 || (study &&
                day.optInt("seconds") + segmentMs / 1_000 >= day.optInt("minutes") * 60)) {
            flush()
        }
        if (!continued || clockJump) segmentMs = 0
        if (day != null) store.closePass(day)
        segmentDay = key
        segmentTask = task
        segmentApp = app
        segmentStudy = study
        lastPackage = foreground
        store.settle(key)
        if (usageGranted) store.recheck()

        if (focusSeconds >= nextCheck && !verificationPending) {
            verificationPending = true
            verificationSince = elapsed
            verificationFailed = false
        }
        if (verificationPending && !verificationFailed && elapsed - verificationSince >= 60_000) {
            verificationFailed = true
            store.event("学习验证超时")
        }
        when {
            blocked && day?.optString("status") == "active" -> showBlock(day, foreground!!)
            verificationPending && foreground != null && day?.packages("study")?.contains(foreground) == true &&
                usageGranted && foreground !in protected && day.isLearning() && passEnd == 0L -> showVerification()
            else -> dismissOverlay()
        }
    }

    private fun flush() {
        val day = segmentDay ?: return
        val app = segmentApp ?: return
        val seconds = (segmentMs / 1_000).toInt()
        if (seconds == 0) return
        segmentMs %= 1000
        store.record(day, segmentTask, app, seconds, segmentStudy, usageGranted, minOf(previousWall, store.endOfDay(day)))
        if (segmentStudy) focusSeconds += seconds
    }

    private fun showBlock(day: JSONObject, foreground: String) {
        val key = "block/$foreground/${store.remainingPass(day) > 0}"
        if (overlayKey == key) return
        store.event("拦截娱乐应用：$foreground")
        val seconds = (day.getInt("minutes") * 60 - day.getInt("seconds")).coerceAtLeast(0)
        val totalUsage = day.optJSONObject("usage")?.let { usage -> usage.keys().asSequence().sumOf { usage.getInt(it) } } ?: 0
        val limits = day.passLimits()
        val passes = limits.daily - day.getInt("passes")
        val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
        val detail = if (!secure) "\n请先设置手机锁屏密码" else if (store.remainingPass(day) > 0)
            "\n本次放行的打开次数已用完" else ""
        panel(key, "娱乐尚未解锁", "剩余学习 ${formatTime(seconds)} · 完成度 ${day.getInt("completion")}%\n今日娱乐 ${formatTime(totalUsage)}$detail",
            "返回学习") {
            dismissOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
            val target = day.packages("study").firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(target) }
        }?.let { layout ->
            if (secure && store.canPass(day)) layout.addView(button("放行 ${limits.minutes} 分钟（剩 $passes 次）") {
                startActivity(StudyExitActivity.intent(this, end = false))
            })
            if (secure) layout.addView(button("结束本次学习") {
                startActivity(StudyExitActivity.intent(this, end = true))
            })
        }
    }

    private fun showVerification() {
        panel("verify", "确认正在学习", "", "继续学习") {
            verificationPending = false
            focusSeconds = 0
            nextCheck = Random.nextInt(20, 41) * 60
            dismissOverlay()
        }
    }

    private fun panel(key: String, title: String, detail: String, action: String, click: () -> Unit): LinearLayout? {
        if (overlayKey == key) return null
        dismissOverlay()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (resources.displayMetrics.density * 28).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(243, 244, 249))
            addView(TextView(this@StudyMonitor).apply {
                text = title
                textSize = 26f
                setTextColor(Color.rgb(33, 35, 52))
                gravity = Gravity.CENTER
            })
            if (detail.isNotEmpty()) addView(TextView(this@StudyMonitor).apply {
                text = detail
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, padding)
                setTextColor(Color.rgb(66, 70, 94))
            })
            addView(button(action, click))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { setTitle("StudyUnlock/$key") }
        return runCatching { wm.addView(layout, params) }.fold(
            onSuccess = { overlay = layout; overlayKey = key; layout },
            onFailure = { store.incomplete("拦截窗口无法显示"); null }
        )
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { click() }
    }

    private fun dismissOverlay() {
        overlay?.let { runCatching { wm.removeViewImmediate(it) } }
        overlay = null
        overlayKey = ""
    }

    override fun onInterrupt() {
        dismissOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        flush()
        store.incomplete("监测服务停止")
        connected.value = false
        dismissOverlay()
        super.onDestroy()
    }

    companion object {
        internal val connected = MutableStateFlow(false)
    }
}

internal fun formatTime(seconds: Int): String = when {
    seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds / 60 % 60} 分"
    seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "$seconds 秒"
}

internal fun protectedApps(context: Context): Set<String> {
    val pm = context.packageManager
    val essential = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        Intent(Settings.ACTION_SETTINGS),
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")),
        Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0"))
    )
    return buildSet {
        add(context.packageName)
        addAll(listOf("android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.eg.android.AlipayGphone", "com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap"))
        essential.forEach { intent -> pm.queryIntentActivities(intent, 0).forEach { add(it.activityInfo.packageName) } }
    }
}
