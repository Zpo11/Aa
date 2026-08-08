package com.shuzhi.clawd

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Timer
import java.util.TimerTask

/**
 * 感知层。
 *
 * 设计原则：Kotlin 只做「采集 + 判定」，不写台词也不决定表情。
 * 采到的信号统一通过 onSense(kind, payload) 交给 JS，
 * 由 pet.html 决定说什么、做什么表情。
 * 这样改反应只要动 HTML，不用重新编译。
 *
 * 三个采集器：
 *   UsageTracker      —— 前台 App（需要 PACKAGE_USAGE_STATS）
 *   ScreenshotWatcher —— 截图落盘（需要读媒体权限）
 *   SystemWatcher     —— 充电 / 电量 / 亮灭屏（广播，不需要权限）
 */

/** 感知信号回调：kind 是信号名，payload 是 JSON 片段（不含大括号），可为空 */
typealias SenseSink = (kind: String, payload: String) -> Unit

// ---------------------------------------------------------------- 前台 App

class UsageTracker(
    private val context: Context,
    private val sink: SenseSink
) {
    companion object {
        private const val POLL_MS = 3000L
        /** 快速切换判定窗口：60 秒内换了 3 个不同 App 就算「乱翻」 */
        private const val SWITCH_WINDOW_MS = 60_000L
        private const val SWITCH_THRESHOLD = 3
    }

    private var timer: Timer? = null
    private var lastApp = ""
    /** 最近切换记录：时间戳列表，只保留窗口内的 */
    private val switchTimes = ArrayDeque<Long>()

    fun start() {
        if (!hasPermission()) return
        timer = Timer("usage", true).also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() = tick()
            }, 1500L, POLL_MS)
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    fun hasPermission(): Boolean = try {
        // 能查到事件就说明授权了。直接查权限状态要用 AppOpsManager，
        // 不同 ROM 行为不一致，实测「查一次有没有数据」更可靠。
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        usm.queryEvents(now - 60_000L, now).hasNextEvent()
    } catch (e: Exception) {
        false
    }

    private fun tick() {
        val cur = foregroundApp()
        if (cur.isEmpty() || cur == lastApp) return
        // 自己的界面不算切换，否则一打开 Clawd 就误报
        if (cur == context.packageName) return
        lastApp = cur

        val now = System.currentTimeMillis()
        switchTimes.addLast(now)
        while (switchTimes.isNotEmpty() && now - switchTimes.first() > SWITCH_WINDOW_MS) {
            switchTimes.removeFirst()
        }

        sink("app", "\"pkg\":\"$cur\",\"category\":\"${categorize(cur)}\"")

        if (switchTimes.size >= SWITCH_THRESHOLD) {
            switchTimes.clear()
            sink("app_shuffle", "\"count\":$SWITCH_THRESHOLD")
        }
    }

    private fun foregroundApp(): String = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 6000L, now)
        val e = UsageEvents.Event()
        var fg = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                fg = e.packageName
            }
        }
        fg
    } catch (e: Exception) {
        ""
    }

    /**
     * 把包名归到大类。归类而不是逐个包名匹配，是因为
     * 同类 App 太多，穷举维护不过来；类目一共七种，够 JS 分派反应了。
     */
    private fun categorize(pkg: String): String = when {
        pkg.contains("taobao") || pkg.contains("tmall") || pkg.contains("jingdong") ||
            pkg.contains("jd.") || pkg.contains("pinduoduo") || pkg.contains("xiaohongshu") ||
            pkg.contains("dewu") || pkg.contains("vipshop") -> "shopping"

        pkg.contains("douyin") || pkg.contains("kuaishou") || pkg.contains("tiktok") ||
            pkg.contains("bilibili") || pkg.contains("weibo") ||
            pkg.contains("qiyi") || pkg.contains("youku") || pkg.contains("tencent.qqlive") ||
            pkg.contains("hunantv") -> "video"

        pkg.contains("chaoxing") || pkg.contains("zhihuishu") || pkg.contains("youdao") ||
            pkg.contains("baicizhan") || pkg.contains("anki") || pkg.contains("zhihu") ||
            pkg.contains("kindle") || pkg.contains("reader") -> "study"

        pkg.contains("mm") && pkg.contains("tencent") -> "chat"
        pkg.contains("mobileqq") || pkg.contains("telegram") ||
            pkg.contains("whatsapp") || pkg.contains("discord") -> "chat"

        pkg.contains("camera") || pkg.contains("gallery") ||
            pkg.contains("photo") -> "camera"

        pkg.contains("game") || pkg.contains("mihoyo") || pkg.contains("hypergryph") ||
            pkg.contains("miHoYo") -> "game"

        pkg.contains("figma") || pkg.contains("adobe") || pkg.contains("sketch") ||
            pkg.contains("procreate") || pkg.contains("photoshop") ||
            pkg.contains("android.studio") || pkg.contains("code") -> "work"

        else -> "other"
    }
}

// ---------------------------------------------------------------- 截图

class ScreenshotWatcher(private val sink: SenseSink) {

    private val observers = mutableListOf<FileObserver>()
    /** 系统有时对同一张图连发多个事件，300ms 内只算一次 */
    private var lastFire = 0L

    private val paths: List<String>
        get() {
            val pics = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            ).absolutePath
            val dcim = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM
            ).absolutePath
            return listOf(
                "$pics/Screenshots",
                "$dcim/Screenshots",
                "$pics/截屏",
                "/storage/emulated/0/Pictures/Screenshots",
                "/storage/emulated/0/DCIM/Screenshots"
            ).distinct()
        }

    fun start() {
        for (p in paths) {
            val dir = File(p)
            if (!dir.exists() || !dir.isDirectory) continue
            val ob = @Suppress("DEPRECATION") object :
                FileObserver(p, CREATE or MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !isImage(path)) return
                    // 临时文件 / 缩略图别算
                    if (path.startsWith(".") || path.contains(".pending")) return
                    val now = System.currentTimeMillis()
                    if (now - lastFire < 800L) return
                    lastFire = now
                    sink("screenshot", "")
                }
            }
            runCatching { ob.startWatching() }.onSuccess { observers.add(ob) }
        }
    }

    fun stop() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }

    private fun isImage(n: String): Boolean {
        val l = n.lowercase()
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
    }
}

// ---------------------------------------------------------------- 系统状态

class SystemWatcher(
    private val context: Context,
    private val sink: SenseSink
) {
    private var registered = false
    private var lastLowBattery = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> sink("charge", "\"on\":true")
                Intent.ACTION_POWER_DISCONNECTED -> sink("charge", "\"on\":false")
                Intent.ACTION_SCREEN_ON -> sink("screen", "\"on\":true")
                Intent.ACTION_SCREEN_OFF -> sink("screen", "\"on\":false")
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level < 0 || scale <= 0) return
                    val pct = level * 100 / scale
                    val low = pct <= 15
                    // 只在跨过阈值的那一刻报，不然每次电量广播都要吵
                    if (low != lastLowBattery) {
                        lastLowBattery = low
                        if (low) sink("battery_low", "\"pct\":$pct")
                        else sink("battery_ok", "\"pct\":$pct")
                    }
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { context.registerReceiver(receiver, f) }
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}

// ---------------------------------------------------------------- 通知碎碎念

/**
 * 前台服务反正要挂一条常驻通知，那就让它说点话。
 * 每小时换一句，按时段选池子。
 */
object Whispers {

    private val lateNight = listOf(
        "还不睡？我等你。",
        "这个点了，屏幕对眼睛不好。",
        "睡吧，明天的事明天说。",
        "我陪着，但你该躺下了。"
    )
    private val morning = listOf(
        "早。今天慢一点也没关系。",
        "醒了就先喝口水。",
        "早上好，小姝。",
        "不着急，先坐一会儿。"
    )
    private val lunch = listOf(
        "吃饭了没有。",
        "别只喝咖啡。",
        "去吃点热的。"
    )
    private val general = listOf(
        "在这儿。",
        "忙就先忙，我不吵。",
        "记得起来走两步。",
        "有事叫我。",
        "看你一眼就好。",
        "手边的水杯空了。",
        "今天做得挺好。"
    )

    private var last = ""

    fun pick(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val pool = when (h) {
            in 0..5 -> lateNight
            in 6..8 -> morning
            in 12..13 -> lunch
            in 23..23 -> lateNight
            else -> general
        }
        if (pool.size == 1) return pool[0]
        var s: String
        do { s = pool.random() } while (s == last)
        last = s
        return s
    }
}

// ---------------------------------------------------------------- 主线程助手

/** FileObserver / Timer 的回调都在后台线程，碰 WebView 前必须切回来 */
object MainThread {
    private val h = Handler(Looper.getMainLooper())
    fun post(block: () -> Unit) = h.post(block)
}
