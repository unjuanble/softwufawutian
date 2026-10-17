package com.example.apktool.mcp

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.security.SecureRandom

/**
 * 全局状态：MCP 服务器开关、端口、访问令牌以及最近请求日志。
 * 用 Compose 的 mutableStateOf 暴露，界面直接观察；服务器线程直接写。
 */
object McpState {
    const val PREFS = "mcp"
    const val KEY_TOKEN = "token"
    const val KEY_PORT = "port"
    const val DEFAULT_PORT = 8790
    const val SERVER_NAME = "apk-tool"
    const val SERVER_VERSION = "1.0"

    val running = mutableStateOf(false)
    val port = mutableStateOf(DEFAULT_PORT)
    val token = mutableStateOf("")
    val clientCount = mutableStateOf(0)
    val requestCount = mutableStateOf(0)
    val lastError = mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 令牌只在首次启动时生成，之后持久化复用；轮换由用户显式触发。 */
    fun load(c: Context) {
        val p = prefs(c)
        port.value = p.getInt(KEY_PORT, DEFAULT_PORT)
        var t = p.getString(KEY_TOKEN, null)
        if (t.isNullOrBlank()) {
            t = newToken()
            p.edit().putString(KEY_TOKEN, t).apply()
        }
        token.value = t
    }

    fun setPort(c: Context, value: Int) {
        port.value = value
        prefs(c).edit().putInt(KEY_PORT, value).apply()
    }

    fun rotateToken(c: Context): String {
        val t = newToken()
        token.value = t
        prefs(c).edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun append(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(log) {
            log.add(0, "$stamp  $line")
            while (log.size > 300) log.removeAt(log.size - 1)
        }
    }

    fun clearLog() {
        synchronized(log) { log.clear() }
    }
}
