package com.example.apktool.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apktool.agent.AgentState
import com.example.apktool.agent.LlmClient
import com.example.apktool.fs.Fs
import com.example.apktool.fs.Workspaces
import com.example.apktool.mcp.McpService
import com.example.apktool.mcp.McpState
import com.example.apktool.mcp.ToolRegistry
import kotlinx.coroutines.launch

/** 设置页：模型配置 / 工作区（目录）/ 文件访问 / MCP 服务器 / 关于。 */
@Composable
fun SettingsScreen(
    ctx: Context,
    registry: ToolRegistry,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showTools by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var newPath by remember { mutableStateOf("") }

    // 目录授权（SAF）：拿到 tree uri 后取真实路径，能取到就加成工作区
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            runCatching { Fs.addTree(ctx, uri) }
            val p = Fs.realPathOfTree(uri)
            if (p != null) Workspaces.add(ctx, p, saf = true) else testResult = "已授权该目录（SAF），但它不是可映射到真实路径的卷"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(24.dp)).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackArrow(MaterialTheme.colorScheme.onSurface, Modifier.size(20.dp)) }
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        // ---------------------------------------------------------- 模型配置
        SettingCard("模型配置（OpenAI 兼容）") {
            OutlinedTextField(
                value = AgentState.baseUrl,
                onValueChange = { AgentState.baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = AgentState.apiKey,
                onValueChange = { AgentState.apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = AgentState.model,
                onValueChange = { AgentState.model = it },
                label = { Text("模型名") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text("温度 ${"%.1f".format(AgentState.temperature)}（越低越稳定）", fontSize = 13.sp)
            Slider(
                value = AgentState.temperature,
                onValueChange = { AgentState.temperature = it },
                valueRange = 0f..1f,
            )
            Text("单次任务最大步数 ${AgentState.maxSteps}", fontSize = 13.sp)
            Slider(
                value = AgentState.maxSteps.toFloat(),
                onValueChange = { AgentState.maxSteps = it.toInt() },
                valueRange = 4f..60f,
                steps = 27,
            )
            OutlinedTextField(
                value = AgentState.systemExtra,
                onValueChange = { AgentState.systemExtra = it },
                label = { Text("附加要求（可选，会拼进系统提示词）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = {
                    AgentState.save(ctx)
                    testResult = "已保存"
                }) { Text("保存") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = {
                    AgentState.save(ctx)
                    testResult = "测试中…"
                    scope.launch {
                        testResult = runCatching {
                            LlmClient.test(AgentState.baseUrl, AgentState.apiKey, AgentState.model)
                        }.getOrElse { "测试失败：${it.javaClass.simpleName}: ${it.message}" }
                    }
                }) { Text("测试连接") }
            }
            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------------------------------------------------------- 工作区
        SettingCard("工作区（目录）") {
            if (Workspaces.items.isEmpty()) {
                Text("还没有工作区，下面添加一个。", fontSize = 13.sp)
            }
            Workspaces.items.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            w.name + if (w.id == Workspaces.currentId) "（当前）" else "",
                            fontSize = 14.sp,
                            fontWeight = if (w.id == Workspaces.currentId) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            w.path + if (w.saf) " · SAF" else "",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { Workspaces.setCurrent(ctx, w.id) }) { Text("设为当前") }
                    TextButton(onClick = { Workspaces.remove(ctx, w.id) }) { Text("删除") }
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newPath,
                onValueChange = { newPath = it },
                label = { Text("手动添加：输入绝对路径") },
                placeholder = { Text("/sdcard/Pictures") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    val p = newPath.trim()
                    if (p.isNotEmpty()) {
                        Workspaces.add(ctx, p)
                        newPath = ""
                    }
                }) { Text("添加路径") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { pickTree.launch(null) }) { Text("选目录并授权") }
            }
        }

        // ---------------------------------------------------------- 文件访问
        SettingCard("文件访问") {
            val allFiles = Fs.hasAllFiles(ctx)
            Text(
                if (allFiles) "模式：真实路径（已获得「所有文件访问权限」）\n可访问：/sdcard、/storage/…、App 专属目录"
                else "模式：受限制（未获得「所有文件访问权限」）\n目前只能访问 App 目录与 SAF 授权目录",
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Fs.allFilesSettingsIntent()) }
                }) { Text("打开系统授权页") }
            }
            val roots = Fs.savedRoots(ctx)
            if (roots.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("已授权的 SAF 目录：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                roots.take(6).forEach { r ->
                    Text("· ${r.realPath}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "提示：/Android/data/<其他应用>、/system、/data 需要 Shizuku（免 root）才能访问，暂未接入。",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------------------------------------------------- MCP
        SettingCard("MCP 服务器（让 PC 端的 AI 也能调用）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (McpState.running.value) "状态：运行中  http://127.0.0.1:${McpState.port.value}/mcp" else "状态：已停止",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "工具 ${registry.all().size} 个 · 客户端 ${McpState.clientCount.value} · 请求 ${McpState.requestCount.value}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = McpState.port.value.toString(),
                    onValueChange = { s -> s.toIntOrNull()?.let { McpState.setPort(ctx, it) } },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.width(130.dp),
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = {
                    if (McpState.running.value) McpService.stop(ctx) else McpService.start(ctx)
                }) { Text(if (McpState.running.value) "停止服务" else "启动服务") }
            }
            Spacer(Modifier.height(10.dp))
            Text("访问令牌（PC 端用 Authorization: Bearer <token>）", fontSize = 12.sp)
            Text(
                McpState.token.value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { /* 令牌可长按复制 */ }
                    .padding(8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { McpState.rotateToken(ctx) }) { Text("轮换令牌") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { showTools = !showTools }) {
                    Text(if (showTools) "隐藏工具清单" else "查看工具清单")
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { McpState.clearLog() }) { Text("清空日志") }
            }
            McpState.lastError.value?.let {
                Spacer(Modifier.height(6.dp))
                Text("最近错误：$it", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
            }
            if (showTools) {
                Spacer(Modifier.height(8.dp))
                registry.all().forEach { t ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(t.name, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            t.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(8.dp))
            if (McpState.log.isNotEmpty()) {
                Text("调用日志（最近 ${minOf(McpState.log.size, 12)} 条）", fontSize = 12.sp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(8.dp),
                ) {
                    LazyColumn {
                        items(McpState.log.take(80).size) { i ->
                            Text(
                                McpState.log[i],
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        SettingCard("关于") {
            Text("1Z-Agent v1.0（手机端 Agent）", fontSize = 13.sp)
            Text(
                "Android 17 · ${registry.all().size} 个工具 · 零第三方依赖 · 对话与配置只存本机",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    LaunchedEffect(Workspaces.items.size) { /* 让工作区变化后重组 */ }
}

/** 统一卡片样式。 */
@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
