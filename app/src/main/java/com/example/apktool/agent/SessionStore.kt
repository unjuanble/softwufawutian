package com.example.apktool.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 一个已保存的会话（抽屉「会话」分组里的一行）。 */
data class SessionInfo(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val count: Int,
)

/**
 * 会话仓库：把对话按会话存成 `filesDir/sessions/<id>.json`，
 * 侧边栏「今天的对话 / 历史对话」就是它的两个时间分组。
 *
 * 只做本地文件读写，不涉及网络；每次 [AgentState.persist] 后都会顺手落盘当前会话。
 */
object SessionStore {

    private const val DIR = "sessions"
    private const val PREFS = "agent"
    private const val KEY_CURRENT = "currentSession"

    /** 当前会话 id（空串表示尚未建立）。 */
    var currentId by mutableStateOf("")

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun fileOf(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    private fun stamp(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun stampOf(ms: Long): String = stamp(ms)

    /** 首次启动或会话被删后，确保有一个可写的当前会话。 */
    fun ensure(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (currentId.isBlank()) currentId = sp.getString(KEY_CURRENT, "") ?: ""
        if (currentId.isBlank() || !fileOf(ctx, currentId).exists()) {
            // 已有会话就接着最近一个，否则新建
            val latest = list(ctx).maxByOrNull { it.updatedAt }
            currentId = latest?.id ?: newId()
            sp.edit().putString(KEY_CURRENT, currentId).apply()
        }
        return currentId
    }

    private fun newId(): String =
        "s" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())

    /** 开一个新会话：当前会话已经落盘，直接换 id 并清空对话。 */
    fun newSession(ctx: Context): String {
        saveCurrent(ctx)
        currentId = newId()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT, currentId).apply()
        AgentState.messages.clear()
        AgentState.persist(ctx)
        return currentId
    }

    /** 把当前 [AgentState.messages] 写进当前会话文件。 */
    fun saveCurrent(ctx: Context) {
        val id = currentId.ifBlank { ensure(ctx) }
        val msgs = AgentState.messages
        if (msgs.isEmpty()) return
        val arr = JSONArray()
        msgs.takeLast(400).forEach { m -> arr.put(toJson(m)) }
        val firstUser = msgs.firstOrNull { it.role == "user" }?.text?.lineSequence()?.firstOrNull()?.take(40)
        val o = JSONObject()
            .put("id", id)
            .put("title", firstUser?.ifBlank { null } ?: "新对话")
            .put("updatedAt", msgs.last().ts)
            .put("createdAt", msgs.first().ts)
            .put("messages", arr)
        runCatching { fileOf(ctx, id).writeText(o.toString()) }
    }

    private fun toJson(m: ChatMsg): JSONObject = JSONObject()
        .put("role", m.role)
        .put("text", m.text)
        .put("toolName", m.toolName ?: JSONObject.NULL)
        .put("toolArgs", m.toolArgs ?: JSONObject.NULL)
        .put("toolOk", m.toolOk)
        .put("ts", m.ts)

    private fun fromJson(o: JSONObject): ChatMsg = ChatMsg(
        role = o.optString("role", "assistant"),
        text = o.optString("text", ""),
        toolName = if (o.isNull("toolName")) null else o.optString("toolName"),
        toolArgs = if (o.isNull("toolArgs")) null else o.optString("toolArgs"),
        toolOk = o.optBoolean("toolOk", true),
        ts = o.optLong("ts", System.currentTimeMillis()),
    )

    /** 全部会话的元信息，按更新时间倒序。 */
    fun list(ctx: Context): List<SessionInfo> {
        val out = ArrayList<SessionInfo>()
        dir(ctx).listFiles()?.forEach { f ->
            if (!f.name.endsWith(".json")) return@forEach
            runCatching {
                val o = JSONObject(f.readText())
                val id = o.optString("id", f.nameWithoutExtension)
                val arr = o.optJSONArray("messages")
                out.add(
                    SessionInfo(
                        id = id,
                        title = o.optString("title", "新对话").ifBlank { "新对话" },
                        updatedAt = o.optLong("updatedAt", f.lastModified()),
                        count = arr?.length() ?: 0,
                    )
                )
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    private fun isToday(ms: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = ms }
        val b = Calendar.getInstance()
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    fun today(ctx: Context): List<SessionInfo> = list(ctx).filter { isToday(it.updatedAt) }

    fun earlier(ctx: Context): List<SessionInfo> = list(ctx).filterNot { isToday(it.updatedAt) }

    /** 载入某个会话的对话（覆盖当前对话）。 */
    fun load(ctx: Context, id: String): Boolean {
        val f = fileOf(ctx, id)
        if (!f.exists()) return false
        return runCatching {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("messages") ?: JSONArray()
            AgentState.messages.clear()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                AgentState.messages.add(fromJson(m))
            }
            currentId = id
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CURRENT, id).apply()
            AgentState.persist(ctx)
            true
        }.getOrDefault(false)
    }

    fun delete(ctx: Context, id: String) {
        runCatching { fileOf(ctx, id).delete() }
        if (currentId == id) {
            currentId = ""
            AgentState.messages.clear()
            ensure(ctx)
        }
    }
}
