package com.example.apktool.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apktool.agent.AgentLoop
import com.example.apktool.agent.AgentMode
import com.example.apktool.agent.AgentState
import com.example.apktool.agent.LlmClient
import com.example.apktool.agent.AgentEffort
import com.example.apktool.agent.ChatMsg
import com.example.apktool.fs.Workspaces
import com.example.apktool.mcp.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 按时间变化的问候语。 */
private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h in 5..11 -> "早上好"
        h in 12..13 -> "中午好"
        h in 14..17 -> "下午好"
        else -> "晚上好"
    }
}

private val SUGGESTIONS = listOf(
    "列出当前工作区里所有 APK 的包名和版本",
    "把某个文件里的「旧名字」全部改成「新名字」",
    "找出工作区里最大的 5 个文件",
    "整理工作区：按文件类型分目录，动之前先问我",
)

/**
 * 主界面：对话即首页。
 *
 * 没有消息时显示问候语 + 建议卡；有消息时显示对话流；底部始终是输入栏
 * （工作区芯片 / 模式芯片 / 输入框 / 模型名 / 发送）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ctx: Context,
    registry: ToolRegistry,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    prefill: String?,
    onPrefillConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val msgs = AgentState.messages

    var input by remember { mutableStateOf("") }
    var wsMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelLoading by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            input = prefill
            onPrefillConsumed()
        }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) runCatching { listState.animateScrollToItem(msgs.size - 1) }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || AgentState.running) return
        if (AgentState.apiKey.isBlank()) {
            AgentState.append(
                ChatMsg(
                    "assistant",
                    "❌ 还没配置模型：请点右上角模型名或进「设置」，填好 Base URL / API Key / 模型名再试。",
                )
            )
            return
        }
        scope.launch {
            runCatching { AgentLoop(ctx, registry).run(t, Workspaces.currentOrNull()?.path) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ---------------------------------------------------------- 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 18.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).clickable { onOpenDrawer() },
                contentAlignment = Alignment.Center,
            ) { HamburgerIcon(MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    AgentState.running -> "第 ${AgentState.step} 步 · ${AgentState.status}"
                    else -> AgentState.status
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---------------------------------------------------------- 主体
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) {
                EmptyHome(onPick = { p -> send(p) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(msgs) { _, m -> MessageRow(m) }
                }
            }
        }

        // ---------------------------------------------------------- 输入栏
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 工作区芯片
                Box {
                    Chip(
                        onClick = { wsMenu = true },
                        leading = {
                            FolderIcon(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        },
                    ) {
                        Text(Workspaces.currentOrNull()?.name ?: "未选择工作区", fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Caret(down = true, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(10.dp))
                    }
                    DropdownMenu(expanded = wsMenu, onDismissRequest = { wsMenu = false }) {
                        Workspaces.items.forEach { w ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(w.name, fontSize = 14.sp)
                                        Text(w.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    Workspaces.setCurrent(ctx, w.id)
                                    wsMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("管理工作区…", fontSize = 14.sp) },
                            onClick = { wsMenu = false; onOpenSettings() },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 模式芯片
                Box {
                    Chip(onClick = { modeMenu = true }) {
                        Text(AgentState.mode.label, fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Caret(down = true, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(10.dp))
                    }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        AgentMode.values().forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.label, fontSize = 14.sp, fontWeight = if (m == AgentState.mode) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(m.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    AgentState.mode = m
                                    AgentState.save(ctx)
                                    modeMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))

            // 模型选择 + 推理等级（off / low / high / max）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Chip(onClick = {
                        modelMenu = true
                        if (AgentState.models.isEmpty() && !modelLoading) {
                            modelLoading = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { LlmClient.listModels(AgentState.baseUrl, AgentState.apiKey) } }
                                    .onSuccess { AgentState.models = it }
                                    .onFailure { modelError = it.message ?: "读取模型列表失败" }
                                modelLoading = false
                            }
                        }
                    }) {
                        Text("模型 " + AgentState.model, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                        Spacer(Modifier.width(4.dp))
                        Caret(down = true, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(10.dp))
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        if (modelLoading) {
                            DropdownMenuItem(text = { Text("正在读取模型列表…", fontSize = 13.sp) }, onClick = {})
                        }
                        if (modelError != null) {
                            DropdownMenuItem(
                                text = { Text("读取失败：$modelError", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) },
                                onClick = { modelError = null },
                            )
                        }
                        AgentState.models.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        m,
                                        fontSize = 14.sp,
                                        fontWeight = if (m == AgentState.model) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    AgentState.model = m
                                    AgentState.save(ctx)
                                    modelMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("在设置里手填模型名…", fontSize = 13.sp) },
                            onClick = { modelMenu = false; onOpenSettings() },
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("推理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 拖动时只更新本地值（不写盘、不重排），松手才吸附到档位 —— 这样滑起来才跟手
                var sliderVal by remember { mutableStateOf(AgentState.effort.ordinal.toFloat()) }
                LaunchedEffect(AgentState.effort) {
                    sliderVal = AgentState.effort.ordinal.toFloat()
                }
                val shown = AgentEffort.values()[
                    sliderVal.roundToInt().coerceIn(0, AgentEffort.values().size - 1)
                ]
                Slider(
                    value = sliderVal,
                    onValueChange = { sliderVal = it },
                    onValueChangeFinished = {
                        val e = AgentEffort.values()[
                            sliderVal.roundToInt().coerceIn(0, AgentEffort.values().size - 1)
                        ]
                        if (e != AgentState.effort) { AgentState.effort = e; AgentState.save(ctx) }
                        sliderVal = e.ordinal.toFloat()
                    },
                    valueRange = 0f..(AgentEffort.values().size - 1).toFloat(),
                    steps = 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    thumb = {
                        // 灰色小圆点
                        Box(
                            Modifier
                                .size(18.dp)
                                .shadow(2.dp, CircleShape)
                                .background(Color(0xFF9A9A9A), CircleShape)
                        )
                    },
                    track = { sliderState ->
                        val frac = ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                            )
                        }
                    },
                )
                Text(shown.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("描述你要做的事，@ 引用文件路径", fontSize = 13.5.sp) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFE9E9EC),
                        unfocusedContainerColor = Color(0xFFE9E9EC),
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
                )
                Spacer(Modifier.width(8.dp))
                SendButton(
                    running = AgentState.running,
                    enabled = AgentState.running || input.isNotBlank(),
                    onClick = {
                        if (AgentState.running) {
                            AgentState.cancelRequested = true
                        } else {
                            val t = input
                            input = ""
                            send(t)
                        }
                    },
                )
            }
        }
    }
}

/** 小圆角芯片。 */
@Composable
private fun Chip(
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            content()
        }
    }
}

/** 圆形发送 / 停止按钮。 */
@Composable
private fun SendButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            // 停止：小方块
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(fg))
        } else {
            ArrowUpIcon(color = fg, modifier = Modifier.size(20.dp))
        }
    }
}

/** 没有对话时的首页：问候语 + 四张建议卡。 */
@Composable
private fun EmptyHome(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        Text(greeting(), fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        @Suppress("UNUSED_EXPRESSION")
        val rows = emptyList<List<String>>()
        rows.forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { s ->
                    Card(
                        onClick = { onPick(s) },
                        modifier = Modifier.weight(1f).heightIn(min = 104.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            s,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 一条消息：用户气泡 / 助手文字 / 工具卡片。 */
@Composable
private fun MessageRow(m: ChatMsg) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                Text(m.text, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
            }
        }
        "tool" -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.toolName ?: "工具",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (m.toolOk) "已完成" else "未执行",
                        fontSize = 11.5.sp,
                        color = if (m.toolOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                if (!m.toolArgs.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        m.toolArgs,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (m.text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(m.text, fontSize = 12.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        else -> Surface(
            color = Color(0xFFE7F1FE),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Text(m.text, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
        }
    }
}
