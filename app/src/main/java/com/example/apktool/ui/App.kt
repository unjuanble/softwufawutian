package com.example.apktool.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apktool.agent.AgentState
import com.example.apktool.agent.SessionStore
import com.example.apktool.fs.Workspaces
import com.example.apktool.tools.McpTools
import kotlinx.coroutines.launch

private const val SCREEN_HOME = "home"
private const val SCREEN_SETTINGS = "settings"

private fun loadScreen(ctx: Context): String =
    ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lastScreen", SCREEN_HOME)
        ?: SCREEN_HOME

private fun saveScreen(ctx: Context, s: String) {
    ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("lastScreen", s).apply()
}

/**
 * 应用外壳：左侧 80% 抽屉（工作区 / 会话 / 设置）+ 主界面（Agent 对话）。
 *
 * 全应用只有两个页面：对话（主界面）与设置；破坏性操作的确认框挂在最外层，
 * 无论当前在哪个页面都能看到。
 */
@Composable
fun ApkToolApp() {
    val ctx = LocalContext.current
    val registry = remember { McpTools.build(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var screen by remember { mutableStateOf(loadScreen(ctx)) }
    var prefill by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        AgentState.load(ctx)
        Workspaces.load(ctx)
        SessionStore.ensure(ctx)
        if (AgentState.messages.isEmpty()) runCatching { AgentState.restore(ctx) }
    }
    LaunchedEffect(screen) { saveScreen(ctx, screen) }

    BackHandler(enabled = drawerState.isOpen || screen == SCREEN_SETTINGS) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            else -> screen = SCREEN_HOME
        }
    }

    MaterialTheme {
        DrawerShell(
            drawerState = drawerState,
            drawer = {
                AppDrawer(
                    ctx = ctx,
                    onClose = { scope.launch { drawerState.close() } },
                    onOpenSettings = { screen = SCREEN_SETTINGS },
                    onReference = { path -> prefill = "@$path " },
                )
            },
        ) {
            Column {
                when (screen) {
                    SCREEN_SETTINGS -> SettingsScreen(
                        ctx = ctx,
                        registry = registry,
                        onBack = { screen = SCREEN_HOME },
                    )
                    else -> HomeScreen(
                        ctx = ctx,
                        registry = registry,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = { screen = SCREEN_SETTINGS },
                        prefill = prefill,
                        onPrefillConsumed = { prefill = null },
                    )
                }
                ApprovalHost()
            }
        }
    }
}

/** 破坏性操作的确认框（全局唯一，跟着 [AgentState.pending] 走）。 */
@Composable
private fun ApprovalHost() {
    val req = AgentState.pending ?: return
    var silent by remember(req) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* 必须显式选择，避免误点遮罩放行 */ },
        title = { Text("需要你确认") },
        text = {
            Column {
                Text(req.reason, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "工具：${req.toolName}",
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    req.argsText,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = silent, onCheckedChange = { silent = it })
                    Text("本次任务内不再询问", fontSize = 12.5.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (silent) AgentState.sessionAutoApprove = true
                AgentState.pending = null
                req.deferred.complete(true)
            }) { Text("执行") }
        },
        dismissButton = {
            TextButton(onClick = {
                AgentState.pending = null
                req.deferred.complete(false)
            }) { Text("拒绝") }
        },
    )
}
