package com.example.apktool.tools

import android.content.Context
import com.example.apktool.agent.AgentLoop
import com.example.apktool.agent.AgentState
import com.example.apktool.mcp.McpTool
import com.example.apktool.mcp.S
import com.example.apktool.mcp.ToolRegistry
import com.example.apktool.mcp.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把「手机上的 Agent」本身暴露成一个工具。
 *
 * 这样 PC 端 AI（或任何 MCP 客户端）不用自己编排文件操作，只要说一句话，
 * 手机上的模型就会自己调用本服务器的工具去完成；破坏性操作依然会弹确认框，
 * 由拿着手机的人点「允许」——人在环里，不会被远端悄悄改数据。
 */
object AgentTools {

    fun register(reg: ToolRegistry, ctx: Context) {
        reg.register(
            McpTool(
                "agent_run",
                "把一句话交给手机上的 Agent 去做：它自己规划并调用本服务器的文件/APK 工具，" +
                    "破坏性操作（删除/覆盖/移动等）会弹确认框等手机上的人点「允许」。" +
                    "本调用会阻塞到任务结束（常见几秒到几分钟）。",
                S.obj(
                    mapOf(
                        "prompt" to S.str("自然语言任务，例如「把 /sdcard/Download 里所有 apk 的包名和版本列出来」"),
                        "includeTranscript" to S.bool("是否附带完整步骤记录，默认 false 只回最后答复", default = false),
                    ),
                    required = listOf("prompt"),
                ),
            ) { args ->
                val prompt = args.optString("prompt", "").trim()
                if (prompt.isEmpty()) {
                    ToolResult.fail("prompt 不能为空")
                } else if (AgentState.running) {
                    ToolResult.fail("手机上已有一个 Agent 任务在跑，请等它结束或先在 App 里点「停止」。")
                } else if (AgentState.apiKey.isBlank()) {
                    ToolResult.fail("还没配置模型 API Key：请在 App 的「设置」页填写 Base URL / API Key / 模型名。")
                } else {
                    val before = AgentState.messages.size
                    runBlocking {
                        AgentLoop(ctx, reg).run(prompt)
                    }
                    val err = AgentState.lastError
                    val steps = AgentState.step
                    val answer = AgentState.messages.drop(before)
                        .lastOrNull { it.role == "assistant" && it.toolName == null }
                        ?.text.orEmpty()
                    val o = JSONObject()
                        .put("ok", err == null)
                        .put("steps", steps)
                        .put("answer", answer)
                        .put("error", err ?: JSONObject.NULL)
                    if (args.optBoolean("includeTranscript", false)) {
                        val arr = JSONArray()
                        AgentState.messages.drop(before).forEach { m ->
                            arr.put(
                                JSONObject()
                                    .put("role", m.role)
                                    .put("tool", m.toolName ?: JSONObject.NULL)
                                    .put("ok", m.toolOk)
                                    .put("text", m.text)
                            )
                        }
                        o.put("transcript", arr)
                    }
                    ToolResult.ok(o.toString(2))
                }
            }
        )
    }
}
