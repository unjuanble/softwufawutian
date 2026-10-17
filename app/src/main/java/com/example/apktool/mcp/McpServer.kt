package com.example.apktool.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 本地 MCP 服务器（MCP Streamable HTTP 传输的极简实现）。
 *
 * 设计取舍：不引入任何三方 HTTP 框架 —— 只监听 127.0.0.1，协议面窄
 * （POST /mcp 上的 JSON-RPC 2.0 + GET /health），用 ServerSocket 手写即可完全掌控，
 * 也避免为这点功能拖进 Ktor/NanoHTTPD 的依赖与包体。
 *
 * 安全：绑定回环地址 + 强制 Bearer token（常量时间比较），因此局域网/公网不可达；
 * PC 侧通过 `adb forward tcp:PORT tcp:PORT` 接入。
 */
class McpServer(
    private val port: Int,
    private val token: String,
    private val registry: ToolRegistry,
) {
    companion object {
        const val LATEST_PROTOCOL = "2025-06-18"
        val SUPPORTED_PROTOCOLS = setOf("2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_BODY = 8 * 1024 * 1024
        private const val READ_TIMEOUT_MS = 60_000
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var stopping = false
    private val pool = Executors.newFixedThreadPool(4)

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        stopping = false
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        serverSocket = ss
        acceptThread = Thread({ acceptLoop(ss) }, "mcp-accept").apply {
            isDaemon = true
            start()
        }
        McpState.append("server listening on 127.0.0.1:$boundPort")
    }

    fun stop() {
        stopping = true
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        pool.shutdownNow()
        McpState.append("server stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopping) {
            val sock = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            try {
                pool.execute { serve(sock) }
            } catch (_: Throwable) {
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val keepAlive: Boolean
            get() = (headers["connection"] ?: "").lowercase().let { it != "close" }
    }

    private fun serve(sock: Socket) {
        McpState.clientCount.value += 1
        try {
            sock.soTimeout = READ_TIMEOUT_MS
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())
            while (!stopping) {
                val req = readRequest(input) ?: break
                val sse = (req.headers["accept"] ?: "").contains("text/event-stream") &&
                    !(req.headers["accept"] ?: "").contains("application/json")
                val (status, body) = try {
                    route(req)
                } catch (e: Throwable) {
                    McpState.append("500 ${req.method} ${req.path} ${e.javaClass.simpleName}: ${e.message}")
                    500 to """{"error":"internal error: ${e.message}"}"""
                }
                val close = writeResponse(output, status, body, req.keepAlive && !sse, sse)
                if (close) break
            }
        } catch (_: SocketException) {
            // 客户端断开是常态
        } catch (_: Throwable) {
        } finally {
            McpState.clientCount.value = (McpState.clientCount.value - 1).coerceAtLeast(0)
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** 读一个完整的 HTTP/1.1 请求；返回 null 表示对端已关闭。 */
    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return readRequest(input)
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) throw IllegalStateException("body too large: $len")
        val body = if (len > 0) readFully(input, len) else ByteArray(0)
        return HttpRequest(method, path, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            if (buf.length > 65536) return null
            buf.append(b.toChar())
        }
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n < 0) throw IllegalStateException("unexpected EOF in body")
            off += n
        }
        return out
    }

    /** 返回 (HTTP 状态码, 响应体)。 */
    private fun route(req: HttpRequest): Pair<Int, String> {
        val path = req.path.substringBefore('?')
        if (path == "/health") {
            return 200 to JSONObject()
                .put("status", "ok")
                .put("server", McpState.SERVER_NAME)
                .put("version", McpState.SERVER_VERSION)
                .put("protocol", LATEST_PROTOCOL)
                .put("tools", registry.names().size)
                .put("requests", McpState.requestCount.value)
                .toString()
        }
        if (path != "/mcp") {
            return 404 to """{"error":"not found","hint":"MCP 端点是 POST /mcp，另有 GET /health"}"""
        }
        if (req.method != "POST") {
            return 405 to """{"error":"method not allowed","hint":"MCP 需要 POST /mcp（JSON-RPC 2.0）"}"""
        }
        if (!authorized(req.headers)) {
            McpState.append("401 unauthorized ${req.headers["user-agent"] ?: ""}")
            return 401 to """{"error":"unauthorized","hint":"需要 Authorization: Bearer <App 里显示的访问令牌>"}"""
        }

        val text = String(req.body, StandardCharsets.UTF_8)
        val response = dispatch(text) ?: return 202 to ""
        return 200 to response
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val auth = headers["authorization"] ?: ""
        val presented = when {
            auth.startsWith("Bearer ", ignoreCase = true) -> auth.substring(7).trim()
            else -> headers["x-mcp-token"]?.trim() ?: ""
        }
        return constantTimeEquals(presented, token)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(StandardCharsets.UTF_8)
        val y = b.toByteArray(StandardCharsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        body: String,
        keepAlive: Boolean,
        sse: Boolean,
    ): Boolean {
        val payload = if (sse && body.isNotEmpty()) "event: message\ndata: $body\n\n" else body
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val contentType = if (sse) "text/event-stream; charset=utf-8" else "application/json; charset=utf-8"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
        return !keepAlive
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    // ---------------------------------------------------------------- JSON-RPC

    /** 处理一个 JSON-RPC 消息；返回 null 表示这是通知（不需要响应体）。 */
    private fun dispatch(text: String): String? {
        val root = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return errorResponse(null, -32700, "Parse error")
        }
        McpState.requestCount.value += 1

        val hasId = root.has("id") && !root.isNull("id")
        val id = if (hasId) root.get("id") else null
        val method = root.optString("method", "")
        val params = root.optJSONObject("params") ?: JSONObject()
        if (method.isEmpty()) return if (hasId) errorResponse(id, -32600, "Invalid Request: missing method") else null

        when (method) {
            "initialize" -> {
                val requested = params.optString("protocolVersion", "")
                val version = if (requested in SUPPORTED_PROTOCOLS) requested else LATEST_PROTOCOL
                val client = params.optJSONObject("clientInfo")?.optString("name") ?: "unknown"
                McpState.append("initialize ← $client (protocol $version)")
                return result(
                    id,
                    JSONObject()
                        .put("protocolVersion", version)
                        .put(
                            "capabilities",
                            JSONObject().put("tools", JSONObject().put("listChanged", false))
                        )
                        .put(
                            "serverInfo",
                            JSONObject()
                                .put("name", McpState.SERVER_NAME)
                                .put("version", McpState.SERVER_VERSION)
                        )
                        .put(
                            "instructions",
                            "本机 APK/文件工具服务器。所有路径都是设备上的真实路径（如 /sdcard/…），" +
                                "需要先在 App 里授予「所有文件访问权限」；未授权时只能访问 App 自己的目录。" +
                                "apk_* 系列工具是只读的。"
                        )
                )
            }

            "notifications/initialized", "notifications/cancelled",
            "notifications/roots/list_changed", "notifications/progress" ->
                return null

            "ping" -> return result(id, JSONObject())

            "tools/list" -> {
                McpState.append("tools/list → ${registry.names().size} 个工具")
                return result(id, JSONObject().put("tools", registry.toJson()))
            }

            "tools/call" -> {
                val name = params.optString("name", "")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val started = System.currentTimeMillis()
                val r = registry.call(name, args)
                val cost = System.currentTimeMillis() - started
                McpState.append("tools/call $name (${cost}ms)${if (r.isError) " ✗" else ""}")
                return result(id, r.toJson())
            }

            "resources/list" -> return result(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> return result(id, JSONObject().put("prompts", JSONArray()))
            "logging/setLevel" -> return result(id, JSONObject())
        }

        return if (hasId) errorResponse(id, -32601, "Method not found: $method") else null
    }

    private fun result(id: Any?, payload: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", payload).toString()

    private fun errorResponse(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
}
