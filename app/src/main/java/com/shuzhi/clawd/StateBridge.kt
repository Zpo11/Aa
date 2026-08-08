package com.shuzhi.clawd

import android.content.Context
import java.io.File

/**
 * 本地通信桥。
 *
 * 状态不再走共享存储（那需要「所有文件访问」权限，部分 ROM 直接锁死），
 * 改为：
 *   - 内存中的 currentState —— HTTP 服务写入，悬浮窗消费
 *   - 应用私有目录的 events.jsonl —— 桌宠写，外部通过 HTTP 取
 *
 * 私有目录不需要任何运行时权限。
 */
object StateBridge {

    private var appContext: Context? = null

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    private val eventFile: File?
        get() = appContext?.let { File(it.filesDir, "events.jsonl") }

    /** 最新状态，由 HTTP PUT /state 写入 */
    @Volatile
    private var currentState: String? = null

    /** 上次交给 WebView 的内容，用来跳过没变化的轮询 */
    private var lastDelivered: String? = null

    /** 外部写入新状态。返回 false 表示内容为空被忽略。 */
    fun writeState(raw: String): Boolean {
        if (raw.isBlank()) return false
        currentState = raw
        return true
    }

    /** 当前状态原文，供 HTTP GET /state 回显 */
    fun peekState(): String? = currentState

    /**
     * 状态有变化时返回新内容，否则返回 null，避免无意义地打扰 WebView。
     */
    fun readStateIfChanged(): String? {
        val raw = currentState ?: return null
        if (raw == lastDelivered) return null
        lastDelivered = raw
        return raw
    }

    /**
     * 追加一条事件。字段固定：ts / type / data。
     * 单行 JSON，追加写，不用担心并发覆盖。
     */
    fun appendEvent(type: String, data: Map<String, Any?> = emptyMap()) {
        val f = eventFile ?: return
        val sb = StringBuilder()
        sb.append("{\"ts\":").append(System.currentTimeMillis())
        sb.append(",\"type\":\"").append(escape(type)).append('"')
        if (data.isNotEmpty()) {
            sb.append(",\"data\":{")
            data.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                sb.append('"').append(escape(k)).append("\":")
                when (v) {
                    null -> sb.append("null")
                    is Number, is Boolean -> sb.append(v.toString())
                    else -> sb.append('"').append(escape(v.toString())).append('"')
                }
            }
            sb.append('}')
        }
        sb.append("}\n")
        try {
            f.appendText(sb.toString())
            trimIfTooLarge()
        } catch (e: Exception) {
            // 写不进去就算了，桌宠不能因为记日志而崩
        }
    }

    /** 读出全部事件文本，供 HTTP GET /events */
    fun dumpEvents(): String {
        val f = eventFile ?: return ""
        return try {
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 读完后清空，避免重复消费 */
    fun clearEvents() {
        val f = eventFile ?: return
        try {
            if (f.exists()) f.writeText("")
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 事件文件超过 256KB 时只留最后 300 行，防止无限膨胀 */
    private fun trimIfTooLarge() {
        val f = eventFile ?: return
        if (!f.exists() || f.length() < 256 * 1024) return
        try {
            val kept = f.readLines().takeLast(300)
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", "\\t")
}