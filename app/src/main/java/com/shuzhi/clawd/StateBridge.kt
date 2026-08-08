package com.shuzhi.clawd

import android.os.Environment
import java.io.File

/**
 * 本地文件通信桥。
 * 读：state.json —— AI 写，桌宠消费。
 * 写：events.jsonl —— 桌宠写，AI 下次说话时读。
 * 没有云端，没有网络依赖。
 */
object StateBridge {

    private val baseDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), "Operit/clawd").apply { mkdirs() }
    }

    private val stateFile: File get() = File(baseDir, "state.json")
    private val eventFile: File get() = File(baseDir, "events.jsonl")

    /** 上次读到的内容，用来跳过没变化的轮询 */
    private var lastRaw: String? = null

    /**
     * 读状态文件。没变化或读不到时返回 null，避免无意义地打扰 WebView。
     */
    fun readStateIfChanged(): String? {
        val f = stateFile
        if (!f.exists() || !f.canRead()) return null
        val raw = try {
            f.readText()
        } catch (e: Exception) {
            return null
        }
        if (raw.isBlank() || raw == lastRaw) return null
        lastRaw = raw
        return raw
    }

    /**
     * 追加一条事件。字段固定：ts / type / data。
     * 单行 JSON，追加写，不用担心并发覆盖。
     */
    fun appendEvent(type: String, data: Map<String, Any?> = emptyMap()) {
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
            eventFile.appendText(sb.toString())
            trimIfTooLarge()
        } catch (e: Exception) {
            // 写不进去就算了，桌宠不能因为记日志而崩
        }
    }

    /** 事件文件超过 256KB 时只留最后 300 行，防止无限膨胀 */
    private fun trimIfTooLarge() {
        val f = eventFile
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