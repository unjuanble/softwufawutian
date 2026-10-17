package com.example.apktool.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条聊天记录（用户 / 助手 / 工具调用结果）。 */
data class ChatMsg(
    val role: String,
    val text: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolOk: Boolean = true,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * Agent 的运行模式（主界面输入栏上那颗芯片）。
 *
 * - [STANDARD]：破坏性操作逐次弹窗确认（默认）。
 * - [AUTO]：本次任务内不再询问，直接执行改写操作。
 * - [READONLY]：禁止一切改写操作，只允许查看/读取（工具照旧可调用，改写类直接拒绝）。
 */
enum class AgentMode(val label: String, val hint: String) {
    STANDARD("标准模式", "改动数据前弹窗确认"),
    AUTO("自动执行", "本次任务内不再询问"),
    READONLY("只读模式", "禁止一切改写操作"),
}

/** 推理等级（对应 OpenAI 兼容端点的 reasoning_effort；off 表示不传该字段）。 */
enum class AgentEffort(val label: String, val hint: String, val wire: String?) {
    OFF("off", "不额外思考，最快", null),
    LOW("low", "轻量思考", "low"),
    HIGH("high", "深度思考", "high"),
    MAX("max", "最深，要求多轮自检", "high"),
}

/** Agent 需要用户确认才能执行的破坏性操作。 */
class ApprovalRequest(
    val toolName: String,
    val argsText: String,
    val reason: String,
) {
    val deferred = CompletableDeferred<Boolean>()
}

/**
 * Agent 的配置与运行状态。
 *
 * 配置（Base URL / API Key / 模型名 / 温度 / 最大步数）与对话记录都只存在本机：
 * 配置进 SharedPreferences("agent")，对话进 filesDir/agent-history.json。
 */
object AgentState {

    private const val PREFS = "agent"
    private const val KEY_BASE = "baseUrl"
    private const val KEY_API = "apiKey"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMP = "temperature"
    private const val KEY_STEPS = "maxSteps"
    private const val KEY_SYS = "systemExtra"
    private const val KEY_AUTO = "autoApprove"
    private const val KEY_MODE = "mode"
    private const val KEY_EFFORT = "effort"
    private const val HISTORY = "agent-history.json"

    // ---------------------------------------------------------------- 配置
    var baseUrl by mutableStateOf("https://api.deepseek.com/v1")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("deepseek-chat")
    var temperature by mutableStateOf(0.2f)
    var maxSteps by mutableStateOf(20)
    var systemExtra by mutableStateOf("")

    /** 当前运行模式（标准 / 自动 / 只读），持久化。 */
    var mode by mutableStateOf(AgentMode.STANDARD)

    /** 推理等级；默认 off（不往请求里加 reasoning_effort，兼容性最好）。 */
    var effort by mutableStateOf(AgentEffort.OFF)

    /** 从服务端 /models 拉到的可选模型（不持久化）。 */
    var models by mutableStateOf<List<String>>(emptyList())

    /** 用户勾选「本任务内不再问」后置位；新任务开始时重置。 */
    var sessionAutoApprove by mutableStateOf(false)

    // ---------------------------------------------------------------- 运行状态
    var running by mutableStateOf(false)
    var status by mutableStateOf("就绪")
    var step by mutableStateOf(0)
    var lastError by mutableStateOf<String?>(null)
    val messages = mutableStateListOf<ChatMsg>()
    var pending by mutableStateOf<ApprovalRequest?>(null)

    @Volatile
    var cancelRequested: Boolean = false

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl = sp.getString(KEY_BASE, baseUrl) ?: baseUrl
        apiKey = sp.getString(KEY_API, "") ?: ""
        model = sp.getString(KEY_MODEL, model) ?: model
        effort = runCatching {
            AgentEffort.valueOf(sp.getString(KEY_EFFORT, AgentEffort.OFF.name) ?: AgentEffort.OFF.name)
        }.getOrDefault(AgentEffort.OFF)
        temperature = sp.getFloat(KEY_TEMP, temperature)
        maxSteps = sp.getInt(KEY_STEPS, maxSteps)
        systemExtra = sp.getString(KEY_SYS, "") ?: ""
        mode = runCatching {
            AgentMode.valueOf(sp.getString(KEY_MODE, AgentMode.STANDARD.name) ?: AgentMode.STANDARD.name)
        }.getOrDefault(AgentMode.STANDARD)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, baseUrl.trim())
            .putString(KEY_API, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .putFloat(KEY_TEMP, temperature)
            .putInt(KEY_STEPS, maxSteps)
            .putString(KEY_SYS, systemExtra)
            .putString(KEY_MODE, mode.name)
            .putString(KEY_EFFORT, effort.name)
            .apply()
    }

    /** 是否已具备与模型对话的最小配置。 */
    fun configured(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()

    // ---------------------------------------------------------------- 对话记录
    fun append(m: ChatMsg) {
        messages.add(m)
        if (messages.size > 400) {
            repeat(messages.size - 400) { messages.removeAt(0) }
        }
    }

    fun clearHistory(ctx: Context) {
        messages.clear()
        runCatching { historyFile(ctx).delete() }
    }

    private fun historyFile(ctx: Context) = File(ctx.filesDir, HISTORY)

    fun persist(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            messages.takeLast(200).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("text", m.text)
                        .put("toolName", m.toolName ?: JSONObject.NULL)
                        .put("toolArgs", m.toolArgs ?: JSONObject.NULL)
                        .put("toolOk", m.toolOk)
                        .put("ts", m.ts)
                )
            }
            historyFile(ctx).writeText(arr.toString())
        }
        runCatching { SessionStore.saveCurrent(ctx) }
    }

    fun restore(ctx: Context) {
        val f = historyFile(ctx)
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            messages.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                messages.add(
                    ChatMsg(
                        role = o.optString("role", "assistant"),
                        text = o.optString("text", ""),
                        toolName = if (o.isNull("toolName")) null else o.optString("toolName"),
                        toolArgs = if (o.isNull("toolArgs")) null else o.optString("toolArgs"),
                        toolOk = o.optBoolean("toolOk", true),
                        ts = o.optLong("ts", System.currentTimeMillis()),
                    )
                )
            }
        }
    }
}
