package com.example.apktool.agent

import android.content.Context
import com.example.apktool.fs.Fs
import com.example.apktool.mcp.ToolRegistry
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 主循环：把用户的一句话交给自己规划，模型通过 function calling 挑选工具，
 * 循环「模型 → 工具 → 模型」直到模型给出最终答复或达到步数上限。
 *
 * 破坏性工具（[com.example.apktool.mcp.McpTool.destructive]）执行前必须经过用户确认。
 */
class AgentLoop(
    private val ctx: Context,
    private val registry: ToolRegistry,
) {

    private companion object {
        const val MAX_TOOL_RESULT = 16_000        // 单个工具结果进入上下文的最大字符数
        const val CONTEXT_BUDGET = 160_000        // messages 总体字符预算，超过就折叠旧结果
        const val KEEP_RECENT_TOOL = 8            // 折叠时保留最近几条工具结果
        const val APPROVAL_TIMEOUT_MS = 300_000L  // 破坏性操作等用户确认的最长时间（5 分钟）
    }

    suspend fun run(userText: String, workspace: String? = null) {
        AgentState.running = true
        AgentState.cancelRequested = false
        AgentState.sessionAutoApprove = false
        AgentState.step = 0
        AgentState.lastError = null
        AgentState.append(ChatMsg("user", userText))
        AgentState.persist(ctx)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(workspace)))
        historyMessages().forEach { messages.put(it) }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val tools = registry.toOpenAiTools()

        try {
            var n = 0
            while (true) {
                if (AgentState.cancelRequested) {
                    AgentState.status = "已停止"
                    AgentState.append(ChatMsg("assistant", "（已按你的要求停止）"))
                    break
                }
                if (n >= AgentState.maxSteps) {
                    AgentState.status = "已达最大步数 ${AgentState.maxSteps}"
                    AgentState.append(ChatMsg("assistant", "（已达步数上限，任务可能未完成。可以再说一句「继续」）"))
                    break
                }
                n++
                AgentState.step = n
                AgentState.status = "第 $n 步：正在思考…"

                val reply = LlmClient.chat(
                    baseUrl = AgentState.baseUrl,
                    apiKey = AgentState.apiKey,
                    model = AgentState.model,
                    messages = messages,
                    tools = tools,
                    temperature = AgentState.temperature,
                    reasoningEffort = AgentState.effort.wire,
                )

                val content = reply.content.trim()
                if (content.isNotBlank() && content != "null") {
                    AgentState.append(ChatMsg("assistant", content))
                }

                if (reply.toolCalls.isEmpty()) {
                    AgentState.status = when (reply.finishReason) {
                        "length" -> "模型输出被截断（finish_reason=length）"
                        else -> "完成（$n 步）"
                    }
                    break
                }

                // 助手这一轮的 tool_calls 必须原样回填进上下文
                val asst = JSONObject().put("role", "assistant")
                asst.put("content", if (reply.content.isBlank()) JSONObject.NULL else reply.content)
                val tcArr = JSONArray()
                reply.toolCalls.forEach { c ->
                    tcArr.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", c.name).put("arguments", c.argumentsRaw)
                            )
                    )
                }
                asst.put("tool_calls", tcArr)
                messages.put(asst)

                for (call in reply.toolCalls) {
                    val argsText = call.argumentsRaw.ifBlank { "{}" }
                    val args = runCatching { JSONObject(argsText) }.getOrElse { JSONObject() }
                    val tool = registry.find(call.name)

                    val resultText: String
                    val ok: Boolean
                    if (tool == null) {
                        ok = false
                        resultText = "未知工具「${call.name}」。可用工具：${registry.names().joinToString(", ")}"
                    } else {
                        AgentState.status = "第 $n 步：执行 ${call.name}"
                        val approved = if (tool.destructive) approve(tool.name, argsText) else true
                        if (!approved) {
                            ok = false
                            resultText = "用户拒绝执行该操作（${call.name}），请换一种方式或询问用户。"
                        } else {
                            val r = registry.call(call.name, args)
                            ok = !r.isError
                            resultText = r.text
                        }
                    }

                    AgentState.append(
                        ChatMsg(
                            role = "tool",
                            text = resultText,
                            toolName = call.name,
                            toolArgs = argsText,
                            toolOk = ok,
                        )
                    )
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.id)
                            .put("content", clip(resultText, MAX_TOOL_RESULT))
                    )
                }
                collapseIfNeeded(messages)
            }
        } catch (e: LlmException) {
            AgentState.lastError = e.message
            AgentState.status = "出错：${e.message}"
            AgentState.append(ChatMsg("assistant", "❌ ${e.message}"))
        } catch (e: Throwable) {
            AgentState.lastError = "${e.javaClass.simpleName}: ${e.message}"
            AgentState.status = "出错：${AgentState.lastError}"
            AgentState.append(ChatMsg("assistant", "❌ 运行失败：${AgentState.lastError}"))
        } finally {
            AgentState.running = false
            AgentState.pending = null
            AgentState.persist(ctx)
        }
    }

    // ------------------------------------------------------------ 用户确认
    private suspend fun approve(toolName: String, argsText: String): Boolean {
        when (AgentState.mode) {
            AgentMode.AUTO -> return true
            AgentMode.READONLY -> {
                AgentState.append(
                    ChatMsg(
                        "tool",
                        "（只读模式：已阻止改写操作 $toolName。如需执行，请在输入栏把模式切到「标准模式」或「自动执行」。）",
                        toolName = toolName,
                        toolArgs = argsText,
                        toolOk = false,
                    )
                )
                return false
            }
            AgentMode.STANDARD -> Unit
        }
        if (AgentState.sessionAutoApprove) return true
        val req = ApprovalRequest(
            toolName = toolName,
            argsText = argsText,
            reason = "该操作会改动或删除数据，且不可撤销",
        )
        AgentState.pending = req
        AgentState.status = "等待你确认：$toolName"
        // 手机上没人应时不要无限期卡住：5 分钟未确认按「拒绝」处理
        val ok = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { req.deferred.await() }
        AgentState.pending = null
        if (ok == null) AgentState.append(ChatMsg("tool", "（等待确认超时 5 分钟，已按拒绝处理）", toolName = toolName, toolOk = false))
        return ok ?: false
    }

    // ------------------------------------------------------------ 上下文维护
    private fun clip(s: String, n: Int): String =
        if (s.length <= n) s else s.take(n) + "\n…（结果过长，已截断，共 ${s.length} 字符）"

    /** 上下文太长时，把较早的工具结果折叠成占位串，保住最近几次。 */
    private fun collapseIfNeeded(messages: JSONArray) {
        var total = 0
        for (i in 0 until messages.length()) total += messages.optJSONObject(i)?.toString()?.length ?: 0
        if (total <= CONTEXT_BUDGET) return
        val toolIdx = (0 until messages.length()).filter {
            messages.optJSONObject(it)?.optString("role") == "tool"
        }
        val drop = toolIdx.dropLast(KEEP_RECENT_TOOL)
        for (i in drop) {
            val o = messages.optJSONObject(i) ?: continue
            val c = o.optString("content", "")
            if (c.startsWith("（已折叠")) continue
            o.put("content", "（已折叠：原始结果 ${c.length} 字符，如需可重新调用工具）")
        }
    }

    /** 把之前的对话（纯文本部分）带进新任务，让「继续」这类指代有上下文。 */
    private fun historyMessages(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        AgentState.messages.takeLast(30).forEach { m ->
            when (m.role) {
                "user" -> out.add(JSONObject().put("role", "user").put("content", clip(m.text, 4_000)))
                "assistant" -> if (m.text.isNotBlank()) {
                    out.add(JSONObject().put("role", "assistant").put("content", clip(m.text, 4_000)))
                }
                "tool" -> out.add(
                    JSONObject()
                        .put("role", "user")   // 工具结果不进新历史，只留一行痕迹
                        .put("content", "[上次调用 ${m.toolName} 的结果] " + clip(m.text, 600))
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ 系统提示词
    private fun systemPrompt(workspace: String? = null): String {
        val allFiles = Fs.hasAllFiles(ctx)
        val roots = runCatching { Fs.knownRoots(ctx) }.getOrDefault(emptyList()).take(8)
        return buildString {
            appendLine("你是「1Z-Agent」，一个跑在用户 Android 手机上的本地 Agent：可以调用工具直接读写这台手机的文件、解析安装包。")
            appendLine()
            appendLine("环境：")
            if (!workspace.isNullOrBlank()) {
                appendLine("- 当前工作区：$workspace（用户默认想操作的就是这个目录，涉及相对的说法都按它理解；确有需要可用绝对路径访问别处）")
            }
            appendLine("- 可访问根目录：${roots.joinToString(", ")}")
            appendLine("- 「所有文件访问权限」：${if (allFiles) "已授予（可直接用 /sdcard 等真实路径读写）" else "未授予（只有 SAF 授权目录与 App 自身目录可用，遇到权限错误要提示用户去授权）"}")
            appendLine("- 当前模式：${AgentState.mode.label}（${AgentState.mode.hint}）")
            appendLine("- 路径风格：绝对路径，例如 /sdcard/Download/app.apk")
            appendLine()
            appendLine("工作方式：")
            appendLine("1. 先想清楚要达成什么，再选工具；不确定就先列表/读取确认，不要凭空猜测路径或内容。")
            appendLine("2. 每次改动后要真实校验（重新读回文件、比对大小或 hash），再向用户汇报。")
            appendLine("3. 破坏性操作（删除、覆盖、改名、安装）会自动弹窗让用户确认；如果被拒绝，换方案或问用户。")
            appendLine("4. 大文件不要整份读入；用 read_bytes 的 offset/length 分段，或用搜索工具定位。")
            appendLine("5. 完成或无法完成时，用简洁中文说明：做了什么、结果如何、产物路径。")
            if (AgentState.systemExtra.isNotBlank()) {
                appendLine()
                appendLine("用户附加要求：" + AgentState.systemExtra.trim())
            }
        }
    }
}
