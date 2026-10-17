package com.example.apktool.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.apktool.agent.AgentLoop
import com.example.apktool.agent.AgentState
import com.example.apktool.agent.ApprovalRequest
import com.example.apktool.agent.ChatMsg
import com.example.apktool.agent.LlmClient
import com.example.apktool.fs.Fs
import com.example.apktool.mcp.ToolRegistry
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Agent 对话

@Composable
fun AgentTab(ctx: Context, registry: ToolRegistry) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val msgs = AgentState.messages

    LaunchedEffect(Unit) {
        if (msgs.isEmpty()) AgentState.restore(ctx)
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) runCatching { list.animateScrollToItem(msgs.size - 1) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖 Agent", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (AgentState.running) "第 ${AgentState.step} 步" else "空闲",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${AgentState.model.ifBlank { "未设置模型" }}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(AgentState.status, style = MaterialTheme.typography.bodySmall)
                if (!AgentState.configured()) {
                    Text(
                        "还没配置模型：请到「设置」页填写 Base URL / API Key / 模型名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3261E),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) {
                Text(
                    "用一句话交代你要做的事，例如：\n" +
                        "• 把 /sdcard/Download 里所有 .apk 的包名和版本列出来\n" +
                        "• 把 /sdcard/test.txt 里所有的「旧名字」改成「新名字」，改完告诉我改了几处\n" +
                        "• 找出 /sdcard/Download 里最大的 5 个文件\n\n" +
                        "删除/覆盖这类操作会先弹窗征求你同意。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(4.dp),
                )
            } else {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(msgs) { m -> MessageBox(m) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("告诉 Agent 要做什么") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            if (AgentState.running) {
                Button(onClick = {
                    AgentState.cancelRequested = true
                    LlmClient.abort()
                }) { Text("停止") }
            } else {
                Button(onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty()) {
                        input = ""
                        scope.launch { AgentLoop(ctx.applicationContext, registry).run(t) }
                    }
                }) { Text("发送") }
            }
        }
    }

}

/**
 * 确认框的「全局宿主」：不管用户当前在哪个页签都要能弹出来，
 * 由 App 顶层调用一次（见 ui/App.kt）。
 */
@Composable
fun AgentApprovalHost() {
    AgentState.pending?.let { req -> ApprovalDialog(req) }
}

@Composable
private fun MessageBox(m: ChatMsg) {
    val isUser = m.role == "user"
    val isTool = m.role == "tool"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            Modifier.fillMaxWidth(0.94f),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    isTool -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                when {
                    isUser -> Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    isTool -> {
                        val name = m.toolName ?: "tool"
                        val ok = if (m.toolOk) "✅" else "❌"
                        Text(
                            "$ok 工具：$name",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (m.toolOk) Color(0xFF1B5E20) else Color(0xFFB3261E),
                        )
                        if (!m.toolArgs.isNullOrBlank()) {
                            Text(
                                "参数：${m.toolArgs}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(
                            if (m.text.length > 4000) m.text.take(4000) + "\n…（界面只显示前 4000 字符）" else m.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    else -> Text(m.text.ifBlank { "（空回复）" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(req: ApprovalRequest) {
    var neverAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { req.deferred.complete(false) },
        title = { Text("需要你确认") },
        text = {
            Column {
                Text("Agent 要执行会改动数据的操作：${req.reason}")
                Spacer(Modifier.height(6.dp))
                Text("工具：${req.toolName}", style = MaterialTheme.typography.labelLarge)
                Text(
                    req.argsText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverAsk, onCheckedChange = { neverAsk = it })
                    Text("本次任务内不再询问")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (neverAsk) AgentState.sessionAutoApprove = true
                req.deferred.complete(true)
            }) { Text("执行") }
        },
        dismissButton = {
            TextButton(onClick = { req.deferred.complete(false) }) { Text("拒绝") }
        },
    )
}

// ------------------------------------------------------------------ 设置

@Composable
fun SettingsTab(ctx: Context) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(AgentState.baseUrl) }
    var key by remember { mutableStateOf(AgentState.apiKey) }
    var model by remember { mutableStateOf(AgentState.model) }
    var temp by remember { mutableStateOf(AgentState.temperature) }
    var steps by remember { mutableStateOf(AgentState.maxSteps.toString()) }
    var extra by remember { mutableStateOf(AgentState.systemExtra) }
    var toast by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val allFiles = Fs.hasAllFiles(ctx)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text("模型设置（OpenAI 兼容）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = base,
            onValueChange = { base = it },
            label = { Text("Base URL（例：https://api.deepseek.com/v1）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名（例：deepseek-chat）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Text("温度：${"%.1f".format(temp)}（越低越稳定）", style = MaterialTheme.typography.bodySmall)
        Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1.5f)
        OutlinedTextField(
            value = steps,
            onValueChange = { steps = it.filter { c -> c.isDigit() } },
            label = { Text("单次任务最大步数（默认 20）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = extra,
            onValueChange = { extra = it },
            label = { Text("附加系统提示（可选，例：所有回答用中文并列出改动）") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Button(onClick = {
                AgentState.baseUrl = base
                AgentState.apiKey = key
                AgentState.model = model
                AgentState.temperature = temp
                AgentState.maxSteps = steps.toIntOrNull() ?: 20
                AgentState.systemExtra = extra
                AgentState.save(ctx)
                toast = "已保存到本机（SharedPreferences: agent）"
            }) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    toast = "正在测试 $base …"
                    scope.launch {
                        toast = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { LlmClient.test(base, key, model) }
                                .getOrElse { "测试失败：${it.message}" }
                        }
                        testing = false
                    }
                },
            ) { Text(if (testing) "测试中…" else "测试连接") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                AgentState.clearHistory(ctx)
                toast = "已清空对话记录"
            }) { Text("清空对话") }
        }
        if (toast.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(toast, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("手机权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (allFiles) "✅ 已授予「所有文件访问权限」：可直接读写 /sdcard 下的真实路径。"
            else "⚠️ 未授予「所有文件访问权限」：目前只能访问 App 目录与 SAF 授权目录。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Fs.allFilesSettingsIntent()) }
            }) { Text("打开「所有文件访问权限」设置") }
        }
        Text(
            "提示：/Android/data/<其他应用>、/system、/data 需要 Shizuku（免 root）支持，下一版接入。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
