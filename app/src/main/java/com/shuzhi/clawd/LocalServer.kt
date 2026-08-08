package com.shuzhi.clawd

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset

/**
 * 极简 HTTP 服务，只监听 127.0.0.1:8791。
 *
 * 接口：
 *   PUT  /state   —— body 是状态 JSON，写入 StateBridge
 *   GET  /state   —— 回显当前状态
 *   GET  /events  —— 取出全部事件（jsonl），并清空
 *   GET  /ping    —— 存活探测
 *
 * 只绑回环地址，手机之外访问不到。没有鉴权：能做的事仅限于改桌宠表情、
 * 读交互事件，没有敏感数据流经这里。
 */
class LocalServer(private val port: Int = 8791) {

    @Volatile
    private var running = false
    private var socket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val loopback = InetAddress.getByName("127.0.0.1")
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(loopback, port))
                socket = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!running) break else continue
                    }
                    // 每个连接都很短，直接在同一个线程里处理完
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // 单个请求出错不影响服务
                    } finally {
                        try { client.close() } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                running = false
            }
        }.apply {
            isDaemon = true
            name = "clawd-local-server"
            start()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        thread = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val input = BufferedReader(InputStreamReader(client.getInputStream(), UTF8))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respond(client.getOutputStream(), 400, "bad request")
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        // 读 headers，主要为了拿 Content-Length
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "content-length") {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""

        val out = client.getOutputStream()
        when {
            method == "GET" && path == "/ping" ->
                respond(out, 200, "{\"ok\":true}")

            method == "PUT" && path == "/state" -> {
                val ok = StateBridge.writeState(body)
                respond(out, if (ok) 200 else 400, "{\"ok\":$ok}")
            }

            method == "POST" && path == "/state" -> {
                val ok = StateBridge.writeState(body)
                respond(out, if (ok) 200 else 400, "{\"ok\":$ok}")
            }

            method == "GET" && path == "/state" ->
                respond(out, 200, StateBridge.peekState() ?: "{}")

            method == "GET" && path == "/events" -> {
                val dump = StateBridge.dumpEvents()
                StateBridge.clearEvents()
                respond(out, 200, dump)
            }

            else -> respond(out, 404, "{\"error\":\"not found\"}")
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(UTF8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Connection: close\r\n\r\n")
            .toString()
        out.write(header.toByteArray(UTF8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val UTF8: Charset = Charset.forName("UTF-8")
    }
}
