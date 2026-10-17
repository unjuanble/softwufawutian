package com.example.apktool.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

class LlmException(message: String, val httpCode: Int = -1) : Exception(message)

/** 模型要求调用一个工具。 */
class ToolCall(val id: String, val name: String, val argumentsRaw: String)

/** 一次模型回复。 */
class LlmReply(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String,
    val usage: JSONObject?,
)

/**
 * 极简 OpenAI 兼容客户端（/chat/completions）。
 *
 * 只用 HttpURLConnection，不引三方库；支持 function calling（tools/tool_calls）。
 * 适用于 DeepSeek / OpenAI / 智谱 / 阿里 / llama.cpp / vLLM / LM Studio 等兼容端点。
 */
object LlmClient {

    @Volatile
    private var active: HttpURLConnection? = null

    /** 用户点「停止」时调用：断开正在进行的请求。 */
    fun abort() {
        runCatching { active?.disconnect() }
    }

    /**
     * 把用户填的 Base URL 规范化成完整端点。
     * - `https://api.deepseek.com`        → `https://api.deepseek.com/v1/chat/completions`
     * - `https://api.deepseek.com/v1`     → `…/v1/chat/completions`
     * - `http://192.168.1.9:8000`         → `http://192.168.1.9:8000/v1/chat/completions`
     * - 已带 `/chat/completions` 的原样使用
     */
    fun endpoint(base: String, path: String): String {
        var b = base.trim().trimEnd('/')
        if (b.isEmpty()) throw LlmException("Base URL 为空，请到「设置」里填写")
        for (suffix in listOf("/chat/completions", "/completions")) {
            if (b.endsWith(suffix)) b = b.removeSuffix(suffix).trimEnd('/')
        }
        val hostPath = runCatching { URI(b).path.orEmpty().trim('/') }.getOrDefault("")
        if (hostPath.isEmpty()) b = "$b/v1"
        return b + path
    }

    /** 读取服务端模型列表（GET {base}/models）。 */
    fun listModels(baseUrl: String, apiKey: String): List<String> {
        val conn = URL(endpoint(baseUrl, "/models")).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw LlmException("HTTP $code：${text.take(200)}")
            val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 对话
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray?,
        temperature: Float,
        reasoningEffort: String? = null,
    ): LlmReply {
        val url = endpoint(baseUrl, "/chat/completions")
        val body = JSONObject()
            .put("model", model.trim())
            .put("messages", messages)
            .put("temperature", temperature.toDouble())
        if (!reasoningEffort.isNullOrBlank()) body.put("reasoning_effort", reasoningEffort)
            .put("stream", false)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        val text = post(url, apiKey, body.toString())
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw LlmException("模型返回的不是 JSON：${text.take(400)}")
        }
        val choices = root.optJSONArray("choices")
            ?: throw LlmException("响应里没有 choices 字段：${text.take(400)}")
        if (choices.length() == 0) throw LlmException("响应 choices 为空：${text.take(400)}")
        val choice = choices.getJSONObject(0)
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val calls = ArrayList<ToolCall>()
        val tcArr = msg.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                calls.add(
                    ToolCall(
                        id = tc.optString("id", "call_$i"),
                        name = fn.optString("name", ""),
                        argumentsRaw = fn.optString("arguments", "{}"),
                    )
                )
            }
        }
        return LlmReply(
            // 注意：服务端把 content 写成 JSON null 时，org.json 的 optString 会返回字符串 "null"，
            // 必须显式判空，否则界面上会出现一行 "null"。
            content = if (msg.isNull("content")) "" else msg.optString("content", "").let {
                if (it == "null") "" else it
            },
            toolCalls = calls,
            finishReason = choice.optString("finish_reason", ""),
            usage = root.optJSONObject("usage"),
        )
    }

    /** 连接自测：拉一次 /models；不支持时退化为一次最小对话。 */
    suspend fun test(
        baseUrl: String,
        apiKey: String,
        model: String,
    ): String {
        val url = endpoint(baseUrl, "/models")
        val raw = try {
            get(url, apiKey)
        } catch (e: LlmException) {
            return "端点 $url 不可用（${e.message}）"
        }
        val names = ArrayList<String>()
        runCatching {
            val arr = JSONObject(raw).optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
                    if (id.isNotBlank()) names.add(id)
                }
            }
        }
        val hit = names.any { it == model.trim() }
        val head = names.take(10).joinToString(", ")
        return buildString {
            append("GET $url 成功，返回 ${names.size} 个模型")
            if (names.isNotEmpty()) append("（前几个：$head）")
            append("\n")
            append(if (hit) "✅ 目标模型「$model」在列表里" else "⚠️ 列表里没看到「$model」，但有些端点不列出全部模型，可直接试一次对话")
        }
    }

    // ------------------------------------------------------------------ HTTP
    private suspend fun post(url: String, apiKey: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = open(url, apiKey, "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readAll(conn)
        } finally {
            active = null
            runCatching { conn.disconnect() }
        }
    }

    private suspend fun get(url: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val conn = open(url, apiKey, "GET")
        try {
            readAll(conn)
        } finally {
            active = null
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String, apiKey: String, method: String): HttpURLConnection {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("URL 不合法：$url（${e.message}）")
        }
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        active = conn
        return conn
    }

    private fun readAll(conn: HttpURLConnection): String {
        val code = try {
            conn.responseCode
        } catch (e: SocketTimeoutException) {
            throw LlmException("请求超时（模型侧 ${conn.readTimeout / 1000} 秒无响应）：$conn")
        } catch (e: UnknownHostException) {
            throw LlmException("域名解析失败：${e.message}")
        } catch (e: Exception) {
            throw LlmException("连接失败：${e.javaClass.simpleName}: ${e.message}")
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
                .getOrNull().orEmpty().ifBlank { text.take(400) }
            val hint = when (code) {
                401, 403 -> "API Key 无效或无权访问"
                404 -> "Base URL 路径或模型名不对"
                429 -> "被限流/额度不足"
                else -> "服务端拒绝"
            }
            throw LlmException("HTTP $code（$hint）：$detail", code)
        }
        return text
    }
}
